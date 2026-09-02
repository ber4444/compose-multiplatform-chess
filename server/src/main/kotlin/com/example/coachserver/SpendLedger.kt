package com.example.coachserver

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide ceiling on **aggregate** provider spend over a rolling window.
 *
 * [ProviderCostBudget] bounds one request; [FixedWindowRateLimiter] bounds one caller. Neither
 * bounds the sum, and the two routes are unauthenticated: at the shipped defaults a single IP may
 * spend 30 requests/minute x 1.5c ~= $27/hour, and nothing at all caps the total across IPs. This is
 * the rail that closes that gap — the only one of the three that an attacker cannot widen by
 * changing address, and the only one that also covers a caller with no client to attest (curl, or
 * the desktop build, which has the base URL compiled into it).
 *
 * **Refusal must never be silent.** The failure this file is most likely to cause is the one
 * [ProviderCostBudget]'s KDoc records: a cost gate that quietly disables the provider and reports
 * itself as an ordinary fallback. So a refusal produces its own [ComposeAttempt.LedgerExhausted]
 * and its own `budget_exhausted` finishReason, distinct from the per-request gate's
 * `budget_rejected`, and is logged — rate-limited by [isLoggableRefusal], since the requests that
 * trip this are exactly the ones arriving in bulk.
 *
 * Spend is *reserved* before the call at the same expected cost the per-request gate prices, then
 * *settled* against reported usage where the provider reports it (the one-shot route does; the
 * streaming route does not, so its reservation stands). Reserving first is deliberate: charging only
 * on completion would let an unbounded number of concurrent calls pass a ledger that still reads
 * zero.
 *
 * In-process and non-durable, like the rate limiter beside it — a restart resets the window. That is
 * acceptable here because the machine scales to zero between requests anyway; a ledger that survived
 * restarts would need the database this service deliberately no longer has.
 */
class SpendLedger(
    val maxUsdCentsPerWindow: Double,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxUsdCentsPerWindow >= 0.0 && windowMillis > 0) {
            "maxUsdCentsPerWindow must be >= 0 and windowMillis > 0"
        }
    }

    private data class Window(val startedAt: Long, val spentUsdCents: Double, val refusals: Long)

    private val window = AtomicReference(Window(nowMillis(), 0.0, 0L))

    /** Spend charged to the current window, in US cents. Rolls to 0 when the window expires. */
    fun spentUsdCents(): Double = currentWindow().spentUsdCents

    /** What is left of [maxUsdCentsPerWindow] in the current window. */
    fun remainingUsdCents(): Double = (maxUsdCentsPerWindow - spentUsdCents()).coerceAtLeast(0.0)

    /**
     * Charges [estimateUsdCents] against the window if it fits, and returns the reservation so the
     * caller can [Reservation.settle] it against real usage. Returns [Refused] otherwise, without
     * charging anything.
     */
    fun tryReserve(estimateUsdCents: Double): SpendOutcome {
        require(estimateUsdCents >= 0.0) { "estimateUsdCents must be >= 0" }
        while (true) {
            val now = nowMillis()
            val current = window.get()
            // An expired window is rolled here rather than by a sweeper: this is the only path that
            // reads it, so a lazy roll cannot leave a stale window pinning the ledger closed.
            val base = if (now - current.startedAt >= windowMillis) Window(now, 0.0, 0L) else current
            val spent = base.spentUsdCents + estimateUsdCents
            if (spent > maxUsdCentsPerWindow) {
                val refused = base.copy(refusals = base.refusals + 1)
                if (!window.compareAndSet(current, refused)) continue
                return SpendOutcome.Refused(
                    spentUsdCents = refused.spentUsdCents,
                    capUsdCents = maxUsdCentsPerWindow,
                    refusalsInWindow = refused.refusals,
                )
            }
            val next = base.copy(spentUsdCents = spent)
            if (window.compareAndSet(current, next)) {
                return SpendOutcome.Reserved(Reservation(estimateUsdCents, next.startedAt))
            }
        }
    }

    /**
     * A granted reservation. [settle] replaces the estimate with the real cost once the provider
     * reports usage, so the window tracks what was billed rather than what was budgeted — the same
     * distinction [ProviderCostBudget.expectedOutputTokens] exists to make.
     */
    inner class Reservation internal constructor(
        private val reservedUsdCents: Double,
        private val windowStartedAt: Long,
    ) {
        fun settle(actualUsdCents: Double) {
            val delta = actualUsdCents.coerceAtLeast(0.0) - reservedUsdCents
            if (delta == 0.0) return
            while (true) {
                val current = window.get()
                // The window this reservation belongs to is gone: its spend went with it, so there
                // is nothing left to adjust. Applying the delta anyway would charge (or credit) a
                // window that never saw the call.
                if (current.startedAt != windowStartedAt) return
                val next = current.copy(
                    spentUsdCents = (current.spentUsdCents + delta).coerceAtLeast(0.0),
                )
                if (window.compareAndSet(current, next)) return
            }
        }
    }

    private fun currentWindow(): Window {
        val current = window.get()
        return if (nowMillis() - current.startedAt >= windowMillis) {
            Window(nowMillis(), 0.0, 0L)
        } else {
            current
        }
    }

    companion object {
        /** 24 hours. Daily is the granularity a provider bill is read at. */
        const val DEFAULT_WINDOW_MILLIS: Long = 24L * 60 * 60 * 1000

        /**
         * Default aggregate cap, in US cents per day.
         *
         * Sized from the two numbers this repo has actually measured, not from a comfort level:
         * a 100-case `:evals` run against gemini-3.6-flash bills about 115c end to end, and a
         * single call about 1.15c. 250c therefore admits a full eval run with the same again to
         * spare, while bounding a runaway month at roughly $75 instead of the unbounded figure the
         * open endpoint carried before. Lower it once real traffic gives a baseline; the point of
         * the rail is that it exists, not that this number is right for every deployment.
         */
        const val DEFAULT_MAX_USD_CENTS_PER_DAY = 250.0

        private const val LOG_EVERY_REFUSAL = 100L

        /**
         * Whether a refusal should be logged. The first in a window always is — that is the
         * transition an operator needs to see — and every hundredth after it, because the traffic
         * that exhausts a daily cap is by definition bulk traffic and a line per request would make
         * the log itself the incident.
         */
        fun isLoggableRefusal(refusalsInWindow: Long): Boolean =
            refusalsInWindow == 1L || refusalsInWindow % LOG_EVERY_REFUSAL == 0L

        /**
         * The no-op ledger, for callers with no aggregate cap of their own (tests, and the composer
         * constructors' defaults). Deliberately a named factory rather than a nullable parameter:
         * "no ceiling" should have to be written down.
         */
        fun unlimited(): SpendLedger = SpendLedger(Double.MAX_VALUE)

        /**
         * Reads `COACH_LLM_MAX_USD_CENTS_PER_DAY`, falling back to [DEFAULT_MAX_USD_CENTS_PER_DAY]
         * when unset or unparseable.
         *
         * `0` is a valid value and means *spend nothing* — the kill switch that downgrades both
         * routes to their deterministic composers without a redeploy or a key rotation. For no
         * ceiling at all, set an absurdly large number; there is deliberately no "unlimited"
         * spelling in the environment.
         */
        fun fromEnvironment(environment: Map<String, String>): SpendLedger {
            val cap = environment["COACH_LLM_MAX_USD_CENTS_PER_DAY"]?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: DEFAULT_MAX_USD_CENTS_PER_DAY
            return SpendLedger(cap)
        }
    }
}

/** The result of [SpendLedger.tryReserve]. */
sealed interface SpendOutcome {
    /** Granted: [reservation] carries the settle-up. */
    data class Reserved(val reservation: SpendLedger.Reservation) : SpendOutcome

    /** Refused: the window is exhausted and no provider call was made. */
    data class Refused(
        val spentUsdCents: Double,
        val capUsdCents: Double,
        val refusalsInWindow: Long,
    ) : SpendOutcome {
        val loggable: Boolean get() = SpendLedger.isLoggableRefusal(refusalsInWindow)
    }
}
