package com.example.myapplication.movecoach

import co.touchlab.kermit.Logger
import com.example.myapplication.GameUiState
import com.example.myapplication.GameViewModel
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
    /** The last request built by [triggerCoach], replayed by [retry]. */
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

    private fun launchCoach(request: com.example.ondeviceai.MoveCoachRequest) {
        lastRequest = request
        val orchestrator = this.orchestrator ?: return
        coachJob?.cancel()

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
                )
            )
            return
        }

        _coachUiState.value = MoveCoachUiState.Loading(request.moveDisplay)
        coachJob = scope.launch {
            try {
                orchestrator.explainMoveStreaming(request).collect { event ->
                    when (event) {
                        is MoveCoachEvent.Streaming ->
                            _coachUiState.value = MoveCoachUiState.Streaming(
                                move = request.moveDisplay,
                                text = CitationSanitizer.sanitizeStreaming(event.partialText),
                            )
                        is MoveCoachEvent.Complete -> when (val result = event.result) {
                            is MoveCoachResult.Success -> {
                                val shown = CitationSanitizer.sanitize(result.explanation.explanation)
                                _coachUiState.value = MoveCoachUiState.Ready(
                                    explanation = result.explanation.copy(
                                        headline = CitationSanitizer.sanitize(result.explanation.headline),
                                        explanation = shown,
                                    )
                                )
                                rememberOpeningFrame(shown)
                            }
                            is MoveCoachResult.FellBack ->
                                _coachUiState.value = MoveCoachUiState.Fallback(
                                    CitationSanitizer.sanitize(result.text),
                                    result.reason,
                                )
                            is MoveCoachResult.Failed ->
                                _coachUiState.value = MoveCoachUiState.Error(result.message)
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

    private companion object {
        /** Enough to break a rut, short enough that the ban list stays a hint and not a paragraph. */
        const val MAX_REMEMBERED_FRAMES = 3
        const val FRAME_WORDS = 3

        /** Provenance of the free tier's deterministic line: engine-derived, no model involved. */
        private val FREE_TIER_ROUTE = com.example.ondeviceai.AiRoute.Fallback(
            com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason.Other(
                "free tier: deterministic coach",
            ),
        )
    }
}
