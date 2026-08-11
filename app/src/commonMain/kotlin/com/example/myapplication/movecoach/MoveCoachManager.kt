package com.example.myapplication.movecoach

import co.touchlab.kermit.Logger
import com.example.myapplication.GameUiState
import com.example.myapplication.GameViewModel
import com.example.myapplication.MoveClass
import com.example.myapplication.ui.CitationSanitizer
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.ondeviceai.AiCoachOrchestrator
import com.example.ondeviceai.MoveCoachEvent
import com.example.ondeviceai.MoveCoachResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Manages the AI Move Coach state and orchestration, decoupled from the pure chess logic
 * in GameViewModel.
 */
class MoveCoachManager(
    private val gameViewModel: GameViewModel,
    private val engineDifficultyName: String = "MEDIUM"
) {
    private val logger = Logger.withTag("MoveCoachManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _coachUiState = MutableStateFlow<MoveCoachUiState>(MoveCoachUiState.Hidden)
    val coachUiState: StateFlow<MoveCoachUiState> = _coachUiState

    private var coachJob: Job? = null
    private var orchestrator: AiCoachOrchestrator? = null
    /** The last request [launchCoach] actually ran, replayed by [retry]. Never set by
     *  [explainSquare], which computes its answer outright and has nothing to re-run. */
    private var lastRequest: com.example.ondeviceai.MoveCoachRequest? = null

    /**
     * Whether [attachCoachOrchestrator] has already run with a non-null orchestrator. Entry points
     * use this to decide whether a re-entry into their setup needs to redo the (expensive) warmup:
     * on Android the holder survives a configuration change but *not* process death, and the
     * Activity cannot tell those apart from `savedInstanceState` alone — a restored bundle means
     * both. Asking the manager is the only signal that distinguishes them.
     */
    val hasOrchestrator: Boolean get() = orchestrator != null

    /**
     * The first few words of the last [MAX_REMEMBERED_FRAMES] coach lines, fed back into the prompt
     * as phrases to avoid (B15) so a game's worth of moves doesn't all open the same way.
     */
    private val recentOpeningFrames = ArrayDeque<String>(MAX_REMEMBERED_FRAMES)

    /**
     * Whether the model-phrased coach is unlocked (§0.4). Bridged from `Entitlements.isProUnlocked`
     * by `AppRoot`, mirroring how `AppSettings.aiCoachEnabled` reaches `GameViewModel`.
     *
     * Defaults to `true` so every existing caller — desktop/wasm entry points, Compose UI tests,
     * and `MoveCoachManagerTest` — keeps its current behaviour; only a real locked `Entitlements`
     * flips it. When `false` the free tier still gets a **complete** coach line, built from the
     * same `DeterministicCoach` text the orchestrator would fall back to; the model is simply never
     * invoked, so a locked user costs no inference and sees no upsell mid-game.
     */
    @Volatile var proUnlocked: Boolean = true

    init {
        // Register the callback to automatically trigger the coach on engine moves
        gameViewModel.onMoveCoached = { fenBefore, moveRecord ->
            if (gameViewModel.aiCoachEnabled && orchestrator != null) {
                triggerCoach(fenBefore, moveRecord)
            }
        }
    }

    fun attachCoachOrchestrator(orchestrator: AiCoachOrchestrator?) {
        coachJob?.cancel()
        this.orchestrator = orchestrator
        // Attaching is the "coach settled" signal: the entry point calls this only after warmup /
        // availability resolves (Android: model downloaded + loaded; iOS: Foundation Models checked).
        // Clear the transient LoadingModel placeholder ("Downloading…" / "Starting Gemma engine…") so a
        // ready coach doesn't sit behind a stale loading message. The panel stays Hidden until the
        // first coached move drives it via triggerCoach(). (This is safe now that entry points attach
        // post-warmup — the earlier bug was attaching *during* load, which wiped the loading state.)
        _coachUiState.value = MoveCoachUiState.Hidden
    }

    /**
     * Platform glue helper: set the coach panel state directly. Used while the
     * local model is being unpacked / initialized (when there's no orchestrator
     * yet to drive the state via events).
     */
    fun setCoachModelState(state: MoveCoachUiState) {
        coachJob?.cancel()
        _coachUiState.value = state
    }
    
    fun hideWindow() {
        coachJob?.cancel()
        _coachUiState.value = MoveCoachUiState.Hidden
    }

    private fun triggerCoach(fenBefore: String, moveRecord: com.example.myapplication.MoveRecord) {
        val request = com.example.ondeviceai.MoveCoachRequest(
            moveUci = moveRecord.uci,
            moveDisplay = moveRecord.san,
            deterministicHeadline = DeterministicCoach.buildHeadline(moveRecord),
            deterministicExplanation = DeterministicCoach.buildExplanation(moveRecord),
            engineDifficultyName = engineDifficultyName,
            bannedOpeningFrames = recentOpeningFrames.toList(),
            // The code-detected facts, so the on-device model can reason about this ply instead of
            // rewording one sentence. Null/empty when the move has no assessment yet (no engine
            // attached), which degrades the prompt to the baseline explanation alone.
            moveClassName = moveRecord.assessment?.moveClass?.name,
            motifs = moveRecord.assessment?.motifs.orEmpty(),
            centipawnLoss = moveRecord.assessment?.cpLoss,
        )
        launchCoach(request)
    }

    /**
     * Re-run the most recent request. Only the [FallbackPresentation.Retryable] state (a timeout)
     * surfaces this — every other fallback is either permanent for this device or already showing
     * the text the user wanted, so a retry button would be noise. No-ops before the first coached
     * move, or when no orchestrator is attached.
     */
    fun retry() {
        launchCoach(lastRequest ?: return)
    }

    /**
     * Ask the coach about a square the user tapped (B16's Explain mode), rather than about a move
     * that was just played.
     *
     * The request is built here rather than by the screen for the same reason [triggerCoach]'s is:
     * `launchCoach` is private, the free-tier and cancellation rules live behind it, and two call
     * sites (the 2D board and the 3D board) would otherwise assemble the same `MoveCoachRequest`
     * by hand and drift apart.
     *
     * [square] is algebraic (`"e4"`). [headline] and [explanation] come from `SquareInsight`, which
     * needs the board state the screen holds.
     *
     * **No model runs here**, unlike [triggerCoach]. The analysis is exact attacker counts and a
     * verdict derived from them, and a small on-device rewriter paraphrases numbers into false ones
     * — "You cover it once, Black twice" came back from gemma3-270m as "with Black twice covering it
     * once". `MoveCoachResponseValidator` cannot catch that: the sentence is still short, grounded
     * and fluent, it is merely wrong. A move needs narration because its assessment is a centipawn
     * score; a square's answer *is* the detection, so there is nothing left to narrate.
     */
    fun explainSquare(square: String, headline: String, explanation: String) {
        coachJob?.cancel()
        _coachUiState.value = MoveCoachUiState.Ready(
            com.example.ondeviceai.MoveCoachExplanation(
                headline = headline,
                explanation = explanation,
                confidence = com.example.ondeviceai.ExplanationConfidence.HIGH,
                route = SQUARE_INSIGHT_ROUTE,
                metrics = com.example.ondeviceai.AiInferenceMetrics(
                    firstTokenMs = null,
                    completeMs = 0L,
                    tokenCount = 0,
                    route = SQUARE_INSIGHT_ROUTE,
                ),
            ),
            squares = listOf(square),
        )
    }

    private fun launchCoach(request: com.example.ondeviceai.MoveCoachRequest) {
        val orchestrator = this.orchestrator ?: return
        // Recorded here rather than in triggerCoach, and after the orchestrator check rather than
        // before it, so lastRequest only ever holds a request that actually ran. Recording one that
        // returned at the line above would give retry() something to replay that never produced a
        // state to retry from.
        lastRequest = request
        coachJob?.cancel()

        // The verdict rides on every state this request produces, including the free-tier and
        // fallback ones: the colour is a property of the *move*, not of which text path answered,
        // so a deterministic line about a blunder must still paint the board red.
        val tone = MoveClass.entries.firstOrNull { it.name == request.moveClassName }.toHighlightTone()
        // From/to, straight off the UCI, so the board tints the move whatever the sentence says.
        val squares = squaresOf(request.moveUci)

        if (!proUnlocked) {
            // Free tier: render the deterministic line as a finished answer, not a Fallback. It is
            // a complete, correct explanation — labelling it a fallback would tell the user their
            // own product tier is a degraded state.
            // B11: the route still has to say Fallback, because no model wrote this text — the
            // badge would otherwise credit a model for DeterministicCoach's output. The reason is
            // FREE_TIER_ROUTE's and not NoLocalModel: a local model may well exist and be warm,
            // the user simply hasn't unlocked it, and a wrong reason is a wrong log line and a
            // wrong FallbackPresentation decision the moment either starts reading it.
            _coachUiState.value = MoveCoachUiState.Ready(
                com.example.ondeviceai.MoveCoachExplanation(
                    headline = request.deterministicHeadline,
                    explanation = request.deterministicExplanation,
                    confidence = com.example.ondeviceai.ExplanationConfidence.HIGH,
                    route = FREE_TIER_ROUTE,
                    metrics = com.example.ondeviceai.AiInferenceMetrics(
                        firstTokenMs = null,
                        completeMs = 0L,
                        tokenCount = 0,
                        route = FREE_TIER_ROUTE,
                    ),
                ),
                tone = tone,
                squares = squares,
            )
            return
        }

        _coachUiState.value =
            MoveCoachUiState.Loading(
                request.deterministicHeadline, request.deterministicExplanation, tone, squares,
            )
        coachJob = scope.launch {
            try {
                orchestrator.explainMoveStreaming(request).collect { event ->
                    when (event) {
                        is MoveCoachEvent.Streaming ->
                            _coachUiState.value = MoveCoachUiState.Streaming(
                                headline = request.deterministicHeadline,
                                explanation = request.deterministicExplanation,
                                text = CitationSanitizer.sanitizeStreaming(event.partialText),
                                tone = tone,
                                squares = squares,
                            )
                        is MoveCoachEvent.Complete -> when (val result = event.result) {
                            is MoveCoachResult.Success -> {
                                logger.d { "coach ok: ${result.explanation.explanation.take(LOG_EXCERPT_CHARS)}" }
                                val shown = CitationSanitizer.sanitize(result.explanation.explanation)
                                _coachUiState.value = MoveCoachUiState.Ready(
                                    explanation = result.explanation.copy(
                                        headline = CitationSanitizer.sanitize(result.explanation.headline),
                                        explanation = shown,
                                    ),
                                    tone = tone,
                                    squares = squares,
                                )
                                rememberOpeningFrame(shown)
                            }
                            is MoveCoachResult.FellBack -> {
                                logger.d { "coach fell back: ${result.reason}" }
                                _coachUiState.value = MoveCoachUiState.Fallback(
                                    CitationSanitizer.sanitize(result.text),
                                    result.reason,
                                    tone = tone,
                                    squares = squares,
                                )
                            }
                            is MoveCoachResult.Failed -> {
                                logger.d { "coach failed: ${result.message}" }
                                _coachUiState.value = MoveCoachUiState.Error(result.message)
                            }
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.w(t) { "Coach orchestrator failed" }
                _coachUiState.value = MoveCoachUiState.Error(t.message ?: "coach failed")
            }
        }
    }

    /**
     * Records how the line the user just read *started*, so the next prompt can ban it.
     *
     * Taken from the **sanitized** text, not the raw model output: a retrieval id left in the frame
     * would be handed back to the model as a phrase to avoid, teaching it the one shape
     * `CitationSanitizer` exists to remove. Only called from the single coach job, so the deque
     * needs no synchronization.
     */
    private fun rememberOpeningFrame(explanation: String) {
        val words = explanation.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < FRAME_WORDS) return
        val frame = words.take(FRAME_WORDS).joinToString(" ")
        if (frame in recentOpeningFrames) return
        if (recentOpeningFrames.size >= MAX_REMEMBERED_FRAMES) recentOpeningFrames.removeFirst()
        recentOpeningFrames.addLast(frame)
    }

    fun close() {
        coachJob?.cancel()
        gameViewModel.onMoveCoached = null
        scope.cancel()
    }

    // internal, not private: [squaresNamedIn] is the one member the module reaches for — a private
    // companion hides even its internal members, so `MoveCoachSquareParsingTest` could not see it.
    // Everything else here stays private.
    internal companion object {
        /**
         * The from/to squares of a UCI move (`"d2d4"` -> `["d2", "d4"]`), or empty if it isn't one.
         * Promotions carry a fifth character, which is not part of either square.
         */
        private fun squaresOf(uci: String): List<String> =
            if (uci.length >= 4) listOf(uci.substring(0, 2), uci.substring(2, 4)) else emptyList()

        /** Enough to break a rut, short enough that the ban list stays a hint and not a paragraph. */
        private const val MAX_REMEMBERED_FRAMES = 3
        private const val FRAME_WORDS = 3

        /** Keeps the per-move debug line to an identifying excerpt rather than the whole answer. */
        private const val LOG_EXCERPT_CHARS = 120

        /** Provenance of the free tier's deterministic line: engine-derived, no model involved. */
        private val FREE_TIER_ROUTE = com.example.ondeviceai.AiRoute.Fallback(
            com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason.Other(
                "free tier: deterministic coach",
            ),
        )

        /** Provenance of an Explain-mode answer: read off the board, on every tier. */
        private val SQUARE_INSIGHT_ROUTE = com.example.ondeviceai.AiRoute.Fallback(
            com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason.Other(
                "square insight: computed from the position",
            ),
        )

        /**
         * A board square, in plain algebraic ("e4") or SAN with a piece letter ("Nf3", "Bxc4").
         * The capture marker is optional; the file/rank pair is what gets kept.
         */
        private val SQUARE_REFERENCE = Regex("[KQRBN]?x?[a-h][1-8]")

        /**
         * Board squares named in a coach line, for B16's board highlighting.
         *
         * `\\b[a-h][1-8]\\b` does not work here: a word boundary cannot occur between `N` and `f3`,
         * so it matched plain algebraic and silently skipped SAN — which is how the coach writes
         * nearly every square it mentions ("Nf3", "Bxc4+", "Qd1"), so the highlight almost never
         * fired. Matching an optional piece letter and keeping the file/rank pair catches both.
         *
         * Call it with sanitized text: a citation id containing a square-shaped substring is
         * already gone by then.
         */
        internal fun squaresNamedIn(text: String): List<String> =
            SQUARE_REFERENCE.findAll(text)
                .map { it.value.takeLast(2).lowercase() }
                .distinct()
                .toList()
    }
}
