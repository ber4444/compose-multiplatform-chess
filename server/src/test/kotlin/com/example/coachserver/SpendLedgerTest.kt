package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import com.example.coachapi.PositionChatRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The aggregate spend rail.
 *
 * [ProviderCostBudget] bounds one request and [FixedWindowRateLimiter] bounds one caller; before
 * this ledger nothing bounded the sum, so an unauthenticated endpoint could be driven at
 * 30 req/min per IP x the per-request cap, across unlimited IPs, until the provider bill said stop.
 *
 * Following [ProviderCostBudgetTest]'s rule — *a criterion never observed passing is as untested as
 * one never observed failing* — every case here pins both sides: the ledger admits ordinary traffic
 * and refuses the overflow, and the composers keep calling the provider until it says otherwise.
 */
class SpendLedgerTest {

    private var clock = 1_000_000L

    private fun ledger(capUsdCents: Double) = SpendLedger(
        maxUsdCentsPerWindow = capUsdCents,
        nowMillis = { clock },
    )

    @Test
    fun `spend accumulates until the window cap is reached`() {
        val ledger = ledger(10.0)

        repeat(10) { assertTrue(ledger.tryReserve(1.0) is SpendOutcome.Reserved, "call $it must be admitted") }

        val refused = ledger.tryReserve(1.0)
        assertTrue(refused is SpendOutcome.Refused)
        assertEquals(10.0, refused.spentUsdCents)
        assertEquals(10.0, refused.capUsdCents)
        assertEquals(0.0, ledger.remainingUsdCents())
    }

    @Test
    fun `a refusal charges nothing, so a cheaper call still fits`() {
        val ledger = ledger(10.0)
        ledger.tryReserve(9.5)

        assertTrue(ledger.tryReserve(1.0) is SpendOutcome.Refused)
        // If the refusal had charged, the 0.5c that is genuinely left would be gone.
        assertTrue(ledger.tryReserve(0.5) is SpendOutcome.Reserved)
    }

    @Test
    fun `the window rolls over and re-admits`() {
        val ledger = ledger(10.0)
        ledger.tryReserve(10.0)
        assertTrue(ledger.tryReserve(1.0) is SpendOutcome.Refused)

        clock += SpendLedger.DEFAULT_WINDOW_MILLIS

        assertTrue(ledger.tryReserve(1.0) is SpendOutcome.Reserved)
        assertEquals(1.0, ledger.spentUsdCents())
    }

    @Test
    fun `settling below the estimate returns the difference to the window`() {
        // The reservation prices ProviderCostBudget.expectedOutputTokens; a call that came in under
        // it must not keep charging the day for tokens nobody was billed for.
        val ledger = ledger(10.0)
        val reserved = ledger.tryReserve(4.0) as SpendOutcome.Reserved

        reserved.reservation.settle(1.0)

        assertEquals(1.0, ledger.spentUsdCents())
        assertEquals(9.0, ledger.remainingUsdCents())
    }

    @Test
    fun `settling above the estimate charges the real cost`() {
        val ledger = ledger(10.0)
        val reserved = ledger.tryReserve(4.0) as SpendOutcome.Reserved

        reserved.reservation.settle(6.0)

        assertEquals(6.0, ledger.spentUsdCents())
    }

    @Test
    fun `settling a reservation from an expired window is ignored`() {
        // Its spend rolled away with the window it belonged to; applying the delta anyway would
        // credit (or charge) a window that never saw the call.
        val ledger = ledger(10.0)
        val reserved = ledger.tryReserve(4.0) as SpendOutcome.Reserved
        clock += SpendLedger.DEFAULT_WINDOW_MILLIS
        ledger.tryReserve(2.0)

        reserved.reservation.settle(0.0)

        assertEquals(2.0, ledger.spentUsdCents())
    }

    @Test
    fun `concurrent callers cannot overspend the window`() {
        // The whole point of the rail is bulk traffic, which arrives in parallel. A read-then-write
        // ledger passes every test above and still lets 64 simultaneous calls through a 10c cap.
        val ledger = SpendLedger(maxUsdCentsPerWindow = 10.0)
        val threads = 64
        val granted = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val done = CountDownLatch(threads)
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (ledger.tryReserve(1.0) is SpendOutcome.Reserved) granted.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(10, granted.get())
        assertEquals(10.0, ledger.spentUsdCents())
    }

    @Test
    fun `refusal logging is rate limited to the first and every hundredth`() {
        // Under the traffic that exhausts a daily cap, a line per refused request makes the log the
        // incident. The first refusal is the transition an operator needs and always logs.
        assertTrue(SpendLedger.isLoggableRefusal(1))
        assertFalse(SpendLedger.isLoggableRefusal(2))
        assertFalse(SpendLedger.isLoggableRefusal(99))
        assertTrue(SpendLedger.isLoggableRefusal(100))
    }

    @Test
    fun `the environment default admits a full eval run`() {
        // DEFAULT_MAX_USD_CENTS_PER_DAY is sized against the measured 2026-08-05 run: 100 opening
        // calls against gemini-3.6-flash at ~1.15c each. A default that cannot afford the repo's own
        // eval harness would turn every scorecard into a measurement of this gate.
        val default = SpendLedger.fromEnvironment(emptyMap())

        repeat(100) {
            assertTrue(default.tryReserve(1.15) is SpendOutcome.Reserved, "eval call $it must be admitted")
        }
    }

    @Test
    fun `the environment cap is read, and an unparseable one falls back to the default`() {
        assertEquals(50.0, SpendLedger.fromEnvironment(mapOf(CAP to "50")).maxUsdCentsPerWindow)
        assertEquals(
            SpendLedger.DEFAULT_MAX_USD_CENTS_PER_DAY,
            SpendLedger.fromEnvironment(mapOf(CAP to "not-a-number")).maxUsdCentsPerWindow,
        )
        assertEquals(
            SpendLedger.DEFAULT_MAX_USD_CENTS_PER_DAY,
            SpendLedger.fromEnvironment(mapOf(CAP to "-1")).maxUsdCentsPerWindow,
        )
    }

    @Test
    fun `a zero cap is the kill switch, not a disabled ledger`() {
        // Documented behaviour: 0 means spend nothing, so both routes go deterministic without a
        // redeploy or a key rotation. "No ceiling" has no environment spelling on purpose.
        val off = SpendLedger.fromEnvironment(mapOf(CAP to "0"))

        assertTrue(off.tryReserve(0.01) is SpendOutcome.Refused)
    }

    @Test
    fun `the opening composer stops calling the provider once the day is spent`() {
        var transportCalls = 0
        val ledger = ledger(3.0)
        val composer = LlmComposer(
            client = OpenAiCompatibleLlmClient.forTesting(
                transport = {
                    transportCalls++
                    ACCEPTABLE_ANSWER
                },
            ),
            fallback = TemplateComposer(),
            budget = budget,
            ledger = ledger,
        )

        val accepted = generateSequence { composer.compose(request, passages) }
            .take(20)
            .takeWhile { it.composerId == LlmComposer.ID }
            .count()

        assertTrue(accepted > 0, "the ledger must admit ordinary traffic before it refuses any")
        val exhausted = composer.compose(request, passages)
        assertEquals(TemplateComposer.ID, exhausted.composerId)
        assertEquals("budget_exhausted", exhausted.finishReason, "the downgrade must name its own cause")
        val callsAtExhaustion = transportCalls
        composer.compose(request, passages)
        assertEquals(callsAtExhaustion, transportCalls, "an exhausted ledger must not reach the provider")
    }

    @Test
    fun `one ledger covers both surfaces`() = runBlocking {
        // One provider bill, one cap. Two ledgers would each admit the whole day's spend, which is
        // why main builds exactly one and hands it to both factories.
        val shared = ledger(2.0)
        val opening = LlmComposer(
            client = OpenAiCompatibleLlmClient.forTesting(
                transport = { ACCEPTABLE_ANSWER },
            ),
            fallback = TemplateComposer(),
            budget = budget,
            ledger = shared,
        )
        val chat = LlmChatComposer(
            client = { _, _, _, _ -> error("the provider must not be reached") },
            fallback = TemplateChatComposer(),
            budget = budget,
            ledger = shared,
        )

        // One call at ~1.15c against a 2c cap leaves too little for a second, on either surface.
        assertEquals(LlmComposer.ID, opening.compose(request, passages).composerId)

        val chunks = chat.streamCompose(chatRequest, passages).toList()

        val fallback = chunks.filterIsInstance<ChatChunk.Fallback>().singleOrNull()
        assertNotNull(fallback, "chat must downgrade on a ledger the opening route exhausted: $chunks")
        assertEquals("budget_exhausted", fallback.finishReason)
    }

    private val budget = ProviderCostBudget(
        maxUsdCents = 1.5,
        inputUsdPerMillionTokens = 1.50,
        outputUsdPerMillionTokens = 7.50,
    )

    private val passages = listOf(
        Passage(SOURCE_A, "Sicilian Defense", "Black answers 1.e4 with 1...c5, fighting for the center."),
        Passage(SOURCE_B, "Smith-Morra Gambit", "White offers a pawn for rapid development and open lines."),
    )

    private val request = OpeningExplainRequest(
        fen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        movesSan = listOf("e4", "c5", "Nf3", "d6"),
        eco = "B90",
        locale = "en-US",
    )

    private val chatRequest = PositionChatRequest(
        fen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        movesSan = listOf("e4", "c5"),
        userMessage = "Why is this move played?",
        eco = "B90",
        locale = "en-US",
    )

    private companion object {
        const val CAP = "COACH_LLM_MAX_USD_CENTS_PER_DAY"
        /**
         * A provider response the real [OpeningExplanationValidator] accepts, with usage reported
         * so the settle-up runs. Both composers must be observed *succeeding* before the ledger
         * refuses, or the test proves only that something went wrong.
         */
        val ACCEPTABLE_ANSWER = """{"choices":[{"message":{"role":"assistant","content":""" +
            """"Black fights for the center asymmetrically with counterplay [lichess-b-373-b20]. """ +
            """White offers rapid development and open lines toward the center [lichess-b-374-b21]."}}],""" +
            """"usage":{"completion_tokens":1400}}"""
        const val SOURCE_A = "lichess-b-373-b20"
        const val SOURCE_B = "lichess-b-374-b21"
    }
}
