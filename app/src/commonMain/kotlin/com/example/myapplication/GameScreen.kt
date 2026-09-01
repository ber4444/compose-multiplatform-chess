package com.example.myapplication

import com.example.myapplication.board3d.Board3D
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.board3d.BoardSquare
import com.example.myapplication.board3d.HighlightTone
import com.example.myapplication.board3d.HighlightedSquare
import com.example.myapplication.board3d.Board3DSessionState
import com.example.myapplication.board3d.OrbitCameraController
import com.example.myapplication.persistence.GameActions
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.share.PgnSharer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.example.myapplication.monetization.ProGate
import com.example.myapplication.ui.theme.BoardDarkSquare
import com.example.myapplication.ui.theme.BoardLightSquare
import com.example.myapplication.ui.theme.CaptureMarker
import com.example.myapplication.ui.theme.MoveMarker
import com.example.myapplication.ui.theme.SelectionBlockedRing
import com.example.myapplication.ui.theme.SelectionRing
import com.example.myapplication.movecoach.FallbackPresentation
import com.example.myapplication.movecoach.MoveCoachManager
import com.example.myapplication.movecoach.MoveCoachPanel
import com.example.myapplication.movecoach.SquareInsight
import com.example.myapplication.movecoach.MoveCoachUiState
import com.example.myapplication.movecoach.narratedText
import com.example.myapplication.movecoach.highlightTone
import com.example.myapplication.movecoach.GameSummaryUiState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import com.example.myapplication.opening.OpeningExplainerPanel
import com.example.myapplication.opening.cloudCoachConfigured
import com.example.myapplication.opening.OpeningExplainerUiState
import game.app.generated.resources.Res
import game.app.generated.resources.cancel_button
import game.app.generated.resources.game_end_message_no_winner
import game.app.generated.resources.game_end_message_winner
import game.app.generated.resources.king_dark
import game.app.generated.resources.king_light
import game.app.generated.resources.no_winner
import game.app.generated.resources.play_again_button
import game.app.generated.resources.promotion_prompt
import game.app.generated.resources.reset_button
import game.app.generated.resources.accept_button
import game.app.generated.resources.decline_button
import game.app.generated.resources.draw_offer_prompt
import game.app.generated.resources.board_3d_unavailable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal val THREE_D_CONTROL_CONTAINER_COLOR = Color.Transparent
internal val THREE_D_CONTROL_CONTENT_COLOR = Color.Black
internal val THREE_D_CONTROL_ACCENT_COLOR = Color.LightGray.copy(alpha = 0.70f)
internal val THREE_D_CONTROL_DISABLED_CONTENT_COLOR = Color.Black.copy(alpha = 0.38f)
internal val THREE_D_CONTROL_DISABLED_ACCENT_COLOR = Color.LightGray.copy(alpha = 0.38f)

@Composable
private fun TransparentUnderlineButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val underlineColor = if (enabled) THREE_D_CONTROL_ACCENT_COLOR else THREE_D_CONTROL_DISABLED_ACCENT_COLOR
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = THREE_D_CONTROL_CONTAINER_COLOR,
            contentColor = THREE_D_CONTROL_ACCENT_COLOR,
            disabledContainerColor = THREE_D_CONTROL_CONTAINER_COLOR,
            disabledContentColor = THREE_D_CONTROL_DISABLED_ACCENT_COLOR,
        ),
        modifier = modifier.drawBehind {
            val strokeWidth = 1.dp.toPx()
            drawLine(
                color = underlineColor,
                start = Offset(0f, size.height - strokeWidth / 2f),
                end = Offset(size.width, size.height - strokeWidth / 2f),
                strokeWidth = strokeWidth,
            )
        },
        content = content,
    )
}

/**
 * Parses an algebraic square ("e4") into board coordinates, or null if it isn't one.
 *
 * Explicit bounds rather than a try/catch: the input comes from model prose via
 * `MoveCoachManager.squaresNamedIn`, so "not a square" is an ordinary outcome to filter out, not
 * an exception to swallow.
 */
internal fun algebraicToSquare(algebraic: String): BoardSquare? {
    if (algebraic.length != 2) return null
    val col = algebraic[0] - 'a'
    val row = '8' - algebraic[1]
    return if (col in 0..7 && row in 0..7) BoardSquare(row, col) else null
}

/** Reads the strategic situation on [pos] and hands it to the coach. Shared by the 2D and 3D boards. */
private fun explainSquareAt(
    pos: Pair<Int, Int>,
    gameState: GameUiState,
    viewer: Set,
    moveCoachManager: com.example.myapplication.movecoach.MoveCoachManager,
) {
    moveCoachManager.explainSquare(
        square = UciMoveConverter.positionToUciSquare(pos),
        headline = SquareInsight.buildHeadline(gameState, pos, viewer),
        explanation = SquareInsight.buildExplanation(gameState, pos, viewer),
    )
}

@Composable
fun GameScreen(
    windowSize: WindowWidthSizeClass,
    viewModel: GameViewModel,
    board3D: Board3DSupport? = null,
    gameHistory: GameHistoryRepository? = null,
    pgnSharer: PgnSharer? = null,
    switchTopPadding: Dp = 8.dp,
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenPaywall: (() -> Unit)? = null,
) {
    val gameState by viewModel.gameState.collectAsState()
    val animState by viewModel.animState.collectAsState()
    val viewState by viewModel.viewState.collectAsState()
    val moveCoachManager = LocalMoveCoachManager.current
    val coachState = moveCoachManager?.coachUiState?.collectAsState()?.value ?: MoveCoachUiState.Hidden

    /**
     * B16 Explain mode: while on, the next board tap asks the coach about that square instead of
     * moving a piece.
     *
     * Opt-in and one-shot, both deliberately. Keying it off "the coach panel is visible" instead
     * makes the board unplayable: `MoveCoachManager` only returns to `Hidden` on attach or
     * `hideWindow()`, so from the second move onward the panel is always showing something and
     * every tap would be swallowed. One-shot means even a mis-tap on the toggle costs one tap, not
     * a stuck mode.
     */
    var explainMode by remember { mutableStateOf(false) }
    // Leaving the mode armed across a move would fire it on an unrelated later tap.
    LaunchedEffect(gameState.moveHistory.size) { explainMode = false }

    /**
     * Squares the coach line currently on screen names, as board coordinates.
     *
     * Parsed from the text the panel is displaying rather than from a field carried on the Ready
     * state: only a model-authored `Ready` ever populated that field, so the tint was dead on the
     * two paths that produce most of the lines a user actually sees — the deterministic fallback
     * and the free tier — and on Explain mode, which usually lands on one of them.
     */
    val coachHighlights = remember(coachState) {
        val tone = coachState.highlightTone
        // Stated squares first: the manager knows the move's from/to exactly. Prose parsing is the
        // fallback, for squares only the model brought up — it cannot be the primary source, or the
        // tint quietly depends on a template still spelling the move out.
        val stated = (coachState as? MoveCoachUiState.Toned)?.squares.orEmpty()
        val named = stated.ifEmpty { MoveCoachManager.squaresNamedIn(coachState.narratedText.orEmpty()) }
        named.mapNotNull(::algebraicToSquare).map { HighlightedSquare(it, tone) }
    }
    val hintSquares by viewModel.hintSquares.collectAsState()
    // Hints stay NEUTRAL: a hint is a suggestion, not a verdict on something the player did, and
    // painting it green would claim an assessment nobody computed.
    val allHighlights = remember(coachHighlights, hintSquares) {
        if (hintSquares.isNotEmpty()) {
            hintSquares.map { HighlightedSquare(BoardSquare(it.first, it.second)) } + coachHighlights
        } else {
            coachHighlights
        }
    }
    val openingExplainerStateHolder = LocalOpeningExplainerStateHolder.current
    val openingExplainerState = openingExplainerStateHolder?.state?.collectAsState()?.value
        ?: OpeningExplainerUiState.Hidden
    val scrollState = rememberScrollState()
    // The 3D toggle now lives in SettingsScreen and writes to AppSettings.board3DEnabled. Observe it
    // here (when a settings instance is provided via LocalAppSettings — production always provides
    // one through AppRoot; tests that render GameScreen without AppRoot see `null` and fall back to
    // the built-in default, 3D on) and re-run the entry/teardown frame choreography when it flips,
    // so the loader/teardown overlays behave exactly as they did when the Switch was inline.
    val appSettings = LocalAppSettings.current
    val board3DEnabledFlow = remember(appSettings) {
        appSettings?.board3DEnabled ?: kotlinx.coroutines.flow.MutableStateFlow(true)
    }
    val board3DEnabled by board3DEnabledFlow.collectAsState()
    val show3D = viewState.show3D && board3D != null
    // Both boards are drawn from the player's end. Collected rather than read off `viewModel`
    // directly: `playerSide` is a plain `var` there, so a Settings change would not recompose and
    // the board would keep the previous orientation until something else invalidated it.
    val playerSide by viewModel.playerSideFlow.collectAsState()
    val board3DCameraSession = remember(playerSide) {
        Board3DSessionState(
            initialYawDegrees = if (playerSide == Set.BLACK) OrbitCameraController.BLACK_YAW_DEG else 0f,
        )
    }
    var isEntering3D by remember { mutableStateOf(false) }
    var isTearingDown3D by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(gameState.winState, gameState.fullmoveNumber, openingExplainerStateHolder) {
        openingExplainerStateHolder?.explain(gameState)
    }

    // Bridge the persisted setting → the VM's runtime show3D flag, preserving the exact frame
    // choreography the old inline Switch used (teardown holds the surface for two frames while the
    // loader paints; enter gives the surface one frame before mounting). Skipped when there's no
    // 3D backend or the value didn't actually change relative to current runtime state.
    LaunchedEffect(board3DEnabled, board3D != null) {
        if (board3D == null) return@LaunchedEffect
        if (board3DEnabled && !viewState.show3D) {
            // Enter 3D.
            isEntering3D = true
            withFrameNanos { }
            viewModel.setShow3D(true)
            // isEntering3D is cleared by Board3D's onRendererReady/onUnavailable callbacks.
        } else if (!board3DEnabled && viewState.show3D) {
            // Tear down 3D. Keep the existing surface mounted while the teardown overlay paints.
            isTearingDown3D = true
            withFrameNanos { }
            withFrameNanos { }
            viewModel.setShow3D(false)
            withFrameNanos { }
            isTearingDown3D = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (gameState.winState != WinState.NONE && !viewState.hideWindow) {
            // `gameSaved` guards against double-saves of the same finished game (tapping "Save game"
            // twice). Reset whenever a new game-over is shown (winState transitions back to NONE
            // between games, or hideWindow flips). Keyed on the result token so a genuinely new
            // finished game can be saved even if the previous popup wasn't dismissed.
            var gameSaved by remember(gameState.winState, gameState.fullmoveNumber) { mutableStateOf(false) }
            val resetGame: (Boolean) -> Unit = { reset: Boolean ->
                if (reset) {
                    viewModel.resetGame(show3D = board3DEnabled)
                    moveCoachManager?.hideWindow()
                } else {
                    viewModel.hideWindow()
                }
            }
            PopupWindow(resetGame) {
                val (winIcon, gameEndMessageFormat) = when (gameState.winState) {
                    WinState.NONE -> error("Invalid Game State")
                    WinState.WHITE -> Res.drawable.king_light to Res.string.game_end_message_winner
                    WinState.BLACK -> Res.drawable.king_dark to Res.string.game_end_message_winner
                    WinState.DRAW, WinState.STALEMATE -> Res.drawable.no_winner to Res.string.game_end_message_no_winner
                }
                Icon(
                    painter = painterResource(winIcon),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(50.dp),
                    contentDescription = null
                )
                Text(
                    modifier = Modifier.testTag("winnerText"),
                    text = stringResource(gameEndMessageFormat, gameState.winState),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge
                )

                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        modifier = Modifier.padding(5.dp),
                        onClick = { resetGame(true) }
                    ) {
                        Text(stringResource(Res.string.play_again_button))
                    }
                    Button(
                        modifier = Modifier.padding(5.dp),
                        onClick = { resetGame(false) }
                    ) {
                        Text(stringResource(Res.string.cancel_button))
                    }
                }

                // Save / Share PGN (issue #39 Phase 3). Save writes to GameHistory; Share routes
                // through the platform PgnSharer. Both hidden when there's no gameHistory (Save) /
                // no pgnSharer (Share) — mirroring the board3D-null gating.
                if (gameHistory != null || pgnSharer != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (gameHistory != null) {
                            Button(
                                modifier = Modifier
                                    .padding(5.dp)
                                    .testTag("save_game_button"),
                                enabled = !gameSaved,
                                onClick = {
                                    val saved = GameActions.toSavedGame(gameState, viewModel.engineAttached)
                                    gameHistory.add(saved)
                                    gameSaved = true
                                }
                            ) {
                                Text(if (gameSaved) "Saved" else "Save game")
                            }
                        }
                        if (pgnSharer != null) {
                            Button(
                                modifier = Modifier
                                    .padding(5.dp)
                                    .testTag("share_pgn_button"),
                                onClick = {
                                    val pgn = GameActions.toPgn(gameState, viewModel.engineAttached)
                                    pgnSharer.share(pgn, "game-${PgnSerializer.resultToken(gameState.winState)}.pgn")
                                }
                            ) {
                                Text("Share PGN")
                            }
                        }
                    }
                }
                // Coach Summary (issue #39 Phase 4 / issue #33)
                val gameSummaryManager = LocalGameSummaryManager.current
                if (gameSummaryManager != null) {
                    val summaryState by gameSummaryManager.uiState.collectAsState()
                    // Unavailable (no orchestrator attached — release Android, coach-disabled
                    // desktop/web, or Foundation Models unavailable on iOS) renders nothing: the
                    // trigger button would otherwise be visible but do nothing when pressed, since
                    // GameSummaryManager.triggerSummary no-ops without an orchestrator.
                    if (summaryState !is GameSummaryUiState.Unavailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    ProGate(
                        featureName = "Game Summary",
                        pitch = "Get a coach's read on the whole game — the turning points and what to work on.",
                        onOpenPaywall = onOpenPaywall,
                        // Same Unavailable check that hides the button: with no orchestrator
                        // attached the feature would not work after a purchase either, so the
                        // upsell has to disappear alongside it.
                        available = summaryState !is GameSummaryUiState.Unavailable,
                    ) {
                    when (summaryState) {
                        GameSummaryUiState.Unavailable -> Unit
                        is GameSummaryUiState.Hidden -> {
                            Button(
                                onClick = {
                                    val pgn = GameActions.toPgn(gameState, viewModel.engineAttached)
                                    val engineDiff = appSettings?.engineDifficulty?.value?.name ?: "MEDIUM"
                                    gameSummaryManager.triggerSummary(
                                        pgn = pgn,
                                        moveHistory = gameState.moveHistory,
                                        playerSide = viewModel.playerSide,
                                        engineDifficultyName = engineDiff
                                    )
                                }
                            ) {
                                Text("Get Coach Summary")
                            }
                        }
                        is GameSummaryUiState.Loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Crunching data...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is GameSummaryUiState.Streaming -> {
                            val text = (summaryState as GameSummaryUiState.Streaming).text
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        is GameSummaryUiState.Ready -> {
                            val state = summaryState as GameSummaryUiState.Ready
                            val exp = state.explanation
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Coach Summary", fontWeight = FontWeight.Bold)
                                Text(exp.explanation, style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                com.example.myapplication.ui.ProvenanceBadge(
                                    route = exp.route,
                                    modifier = Modifier.testTag("game_summary_provenance")
                                )
                            }
                        }
                        is GameSummaryUiState.Fallback -> {
                            val fallback = (summaryState as GameSummaryUiState.Fallback)
                            // B17: same three designed states as the coach panel. A silent
                            // substitution reads as an ordinary summary — no label, no retry.
                            val presentation = FallbackPresentation.of(fallback.reason)
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Coach Summary", fontWeight = FontWeight.Bold)
                                when (presentation) {
                                    is FallbackPresentation.Labeled -> Text(
                                        presentation.label,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    is FallbackPresentation.Retryable -> Text(
                                        presentation.label,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    FallbackPresentation.Silent -> Unit
                                }
                                Text(fallback.text, style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                // Fallback text is engine-derived by construction, so the reason
                                // that produced it is also its provenance (B11).
                                com.example.myapplication.ui.ProvenanceBadge(
                                    route = com.example.ondeviceai.AiRoute.Fallback(fallback.reason),
                                    modifier = Modifier.testTag("game_summary_provenance")
                                )
                                if (presentation is FallbackPresentation.Retryable) {
                                    TextButton(onClick = { gameSummaryManager.retry() }) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                        is GameSummaryUiState.Error -> {
                            Text(
                                "Error: ${(summaryState as GameSummaryUiState.Error).message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                ProGate(
                    featureName = "Opening Explainer",
                    pitch = "See what opening you played and the ideas behind it.",
                    onOpenPaywall = onOpenPaywall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    // No base URL → the panel can only ever show its offline sentence.
                    available = cloudCoachConfigured,
                ) {
                OpeningExplainerPanel(
                    state = openingExplainerState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                }
            }
        }

        if (gameState.pendingPromotion != null) {
            PromotionDialog(
                set = gameState.turn,  // always WHITE when pending
                onSelect = viewModel::promotePawn,
                onDismiss = viewModel::cancelPromotion
            )
        }

        // The *opponent's* offer, which is White's when the player is Black — the VM already writes
        // `engineSide` here. Hard-coding BLACK didn't merely hide the dialog for a Black player: an
        // unanswered offer makes `playerMove` return early, so the board froze for the rest of the
        // game with no way to accept or decline.
        if (gameState.drawOffer == viewModel.engineSide && gameState.winState == WinState.NONE) {
            DrawOfferDialog(onAccept = viewModel::acceptDrawOffer, onDecline = viewModel::declineDrawOffer)
        }

        if (show3D) {
            LaunchedEffect(animState.pieceToAnimate) {
                if (animState.pieceToAnimate != null) {
                    kotlinx.coroutines.delay(50)
                    viewModel.animationEnd()
                }
            }
            val fen = remember(gameState) { FenConverter.gameStateToFen(gameState) }
            // Let the loading overlay paint and start animating before the 3D surface composes.
            // The platform surface (notably Android SceneView/Filament) builds its scene
            // synchronously on the UI thread during composition; composing it in the same frame as
            // the loader leaves the spinner no frame to draw. Hold it back two frames.
            var surfaceComposeReady by remember { mutableStateOf(false) }
            var rendererReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                surfaceComposeReady = true
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (surfaceComposeReady) {
                Board3D(
                    support = board3D,
                    fen = fen,
                    modifier = Modifier.fillMaxSize(),
                    onUnavailable = {
                        isEntering3D = false
                        rendererReady = true
                        viewModel.markBoard3DUnavailable()
                    },
                    cameraSession = board3DCameraSession,
                    onRendererReady = {
                        isEntering3D = false
                        rendererReady = true
                    },
                    selectedSquare = gameState.selectedSquare
                        .takeIf { it != INVALID_POSITION }
                        ?.let { BoardSquare(it.first, it.second) },
                    highlightedSquares = allHighlights,
                    onSquareTapped = onSquareTapped@{ sq ->
                    // Explain mode is opt-in and one-shot (see explainMode). Outside it, a tap is a
                    // move — the board must never stop accepting moves because a panel is open.
                    if (explainMode && moveCoachManager != null) {
                        explainSquareAt(Pair(sq.row, sq.col), gameState, viewModel.playerSide, moveCoachManager)
                        explainMode = false
                        return@onSquareTapped
                    }

                    // Route a 3D tap through the same selection/move logic the 2D board uses.
                    // Everything below is relative to the *player's* side, not White: `playerSide`
                    // is a Settings choice, and hard-coding White left the whole 3D board inert for
                    // a player who picked Black (the turn guard never opened).
                    if (animState.pieceToAnimate != null || gameState.turn != playerSide) return@onSquareTapped
                    val playingWhite = playerSide == Set.WHITE
                    val ownPositions = if (playingWhite) gameState.positionsWhite else gameState.positionsBlack
                    val ownPieces = if (playingWhite) gameState.piecesWhite else gameState.piecesBlack
                    val enemyPositions = if (playingWhite) gameState.positionsBlack else gameState.positionsWhite
                    val enemyPieces = if (playingWhite) gameState.piecesBlack else gameState.piecesWhite
                    val pos = Pair(sq.row, sq.col)
                    val selectedPieceIndex = ownPositions.indexOf(gameState.selectedSquare)
                    val legalMoves = if (selectedPieceIndex != -1) {
                        getLegalMovesForPiece(
                            pieceIndex = selectedPieceIndex,
                            enemyPieces = enemyPieces,
                            enemyPositions = enemyPositions,
                            allyPositions = ownPositions,
                            allyPieces = ownPieces,
                            castlingRights = gameState.castlingRights,
                            enPassantTarget = gameState.enPassantTarget,
                        )
                    } else emptyList()
                    when {
                        pos in legalMoves -> viewModel.playerMove(selectedPieceIndex, pos)
                        pos in ownPositions -> viewModel.updateSelected(pos)
                    }
                    }
                )
                }

                if (isEntering3D || !surfaceComposeReady || !rendererReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .zIndex(2f)
                            .testTag("board_3d_entering"),
                        contentAlignment = Alignment.Center
                    ) {
                        ChessLoader("Loading 3D Engine")
                    }
                }

                if (isTearingDown3D) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(2f)
                            .testTag("board_3d_tearing_down"),
                        contentAlignment = Alignment.Center
                    ) {
                        ChessLoader("Tearing down 3D board")
                    }
                }
            }

            // Stack controls + coach panel at the bottom. Panel fills remaining
            // vertical space below the buttons down to the bottom of the screen;
            // internally scrolls with a 5s auto-scroll delay if text overflows.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp)
            ) {
                GameControls(
                    gameState = gameState,
                    animState = animState,
                    viewState = viewState,
                    viewModel = viewModel,
                    onResetGame = { showResetConfirmation = true },
                    transparentButtons = true,
                    explainMode = explainMode,
                    onToggleExplainMode = moveCoachManager?.let { { explainMode = !explainMode } },
                )
                if (coachState !is MoveCoachUiState.Hidden) {
                    MoveCoachPanel(
                        state = coachState,
                        modifier = Modifier.weight(1f, fill = false),
                        onRetry = moveCoachManager?.let { { it.retry() } },
                    )
                }
            }
        } else {
            // Measure the viewport OUTSIDE the verticalScroll — the scroll gives children infinite
            // max height, so BoxWithConstraints inside it can't cap the board. Capping the board at
            // min(width, 0.85 × height) keeps it fully visible on wide/landscape windows (web/desktop)
            // while still using the full width on portrait.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Shrink the board cap when the coach panel is visible so it has guaranteed
                // room above the fold (otherwise the panel — which can't use `weight` inside a
                // scrollable Column — sits below the board + controls and needs scrolling).
                val coachVisible = coachState !is MoveCoachUiState.Hidden
                val boardHeightFraction = if (coachVisible) 0.65f else 0.85f
                val boardMaxSize = minOf(maxWidth, maxHeight * boardHeightFraction)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                ) {
                    Board(
                        gameState = gameState,
                        animState = animState,
                        windowSize = windowSize,
                        playerSide = playerSide,
                        updateSelected = viewModel::updateSelected,
                        playerMove = viewModel::playerMove,
                        animationEnd = viewModel::animationEnd,
                        boardMaxSize = boardMaxSize,
                        highlightedSquares = allHighlights,
                        onSquareTapped = if (explainMode && moveCoachManager != null) {
                            { pos ->
                                explainSquareAt(pos, gameState, viewModel.playerSide, moveCoachManager)
                                explainMode = false
                            }
                        } else null,
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    GameControls(
                        gameState = gameState,
                        animState = animState,
                        viewState = viewState,
                        viewModel = viewModel,
                        onResetGame = { showResetConfirmation = true },
                        explainMode = explainMode,
                        onToggleExplainMode = moveCoachManager?.let { { explainMode = !explainMode } },
                    )

                    // Move Coach panel. Unlike the 3D branch (which overlays a non-scrollable
                    // Column where `weight` works), this Column is verticalScroll, so `weight`
                    // is a no-op here — the panel renders at wrap-content with padding, and the
                    // reduced board cap above guarantees it's visible without scrolling.
                    if (coachVisible) {
                        MoveCoachPanel(
                            state = coachState,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            onRetry = moveCoachManager?.let { { it.retry() } },
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (showResetConfirmation) {
            PopupWindow(
                onDismiss = { showResetConfirmation = false }
            ) {
                Text(
                    modifier = Modifier.padding(bottom = 16.dp),
                    text = "Are you sure you want to reset the game?",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        modifier = Modifier.padding(5.dp),
                        onClick = {
                            showResetConfirmation = false
                            viewModel.resetGame(show3D = board3DEnabled)
                            moveCoachManager?.hideWindow()
                        }
                    ) {
                        Text("Yes")
                    }
                    Button(
                        modifier = Modifier.padding(5.dp),
                        onClick = { showResetConfirmation = false }
                    ) {
                        Text("No")
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(1f)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .offset(y = switchTopPadding)
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
        ) {
            TextButton(
                onClick = onOpenChat,
                modifier = Modifier.testTag("open_chat_button")
            ) {
                Text(
                    text = "Chat",
                    color = THREE_D_CONTROL_ACCENT_COLOR,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
                onClick = onOpenRules,
                modifier = Modifier.testTag("open_rules_button")
            ) {
                Text(
                    text = "Rules",
                    color = THREE_D_CONTROL_ACCENT_COLOR,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            // Settings + History entry points (issue #39). The 3D toggle moved to SettingsScreen.
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("open_settings_button")
            ) {
                Text(
                    text = "Settings",
                    color = THREE_D_CONTROL_ACCENT_COLOR,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
                onClick = onOpenHistory,
                modifier = Modifier.testTag("open_history_button")
            ) {
                Text(
                    text = "History",
                    color = THREE_D_CONTROL_ACCENT_COLOR,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun GameControls(
    gameState: GameUiState,
    animState: PieceAnimationState,
    viewState: ViewState,
    viewModel: GameViewModel,
    onResetGame: () -> Unit,
    transparentButtons: Boolean = false,
    explainMode: Boolean = false,
    onToggleExplainMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        if (viewState.board3DUnavailable) {
            Text(
                text = stringResource(Res.string.board_3d_unavailable),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("board_3d_unavailable")
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (transparentButtons) {
                TransparentUnderlineButton(
                    onClick = onResetGame,
                    modifier = Modifier.testTag("reset_button")
                ) {
                    Text(stringResource(Res.string.reset_button))
                }
                if (viewModel.engineAttached) {
                    TransparentUnderlineButton(
                        onClick = viewModel::requestHint,
                        enabled = gameState.turn == viewModel.playerSide && gameState.winState == WinState.NONE && animState.pieceToAnimate == null,
                        modifier = Modifier.testTag("hint_button")
                    ) { Text("Hint") }
                }
                if (onToggleExplainMode != null) {
                    TransparentUnderlineButton(
                        onClick = onToggleExplainMode,
                        modifier = Modifier.testTag("move_coach_explain_toggle")
                    ) {
                        Text(if (explainMode) "Cancel Explain" else "Explain")
                    }
                }
            } else {
                Button(
                    onClick = onResetGame,
                    modifier = Modifier.testTag("reset_button")
                ) {
                    Text(stringResource(Res.string.reset_button))
                }
                if (viewModel.engineAttached) {
                    Button(
                        onClick = viewModel::requestHint,
                        enabled = gameState.turn == viewModel.playerSide && gameState.winState == WinState.NONE && animState.pieceToAnimate == null,
                        modifier = Modifier.testTag("hint_button")
                    ) { Text("Hint") }
                }
                if (onToggleExplainMode != null) {
                    Button(
                        onClick = onToggleExplainMode,
                        modifier = Modifier.testTag("move_coach_explain_toggle")
                    ) {
                        Text(if (explainMode) "Cancel Explain" else "Explain")
                    }
                }
            }
        }
    }
}

enum class SquareType {
    Empty,
    WhitePiece,
    BlackPiece,
    CanMove,
    CannotMove,
    PossibleMove,
    PossibleCapture
}

/**
 * Which board square the grid cell at view position [row]/[column] shows.
 *
 * White sees the board as stored (view row 0 = rank 8, view column 0 = file a); Black sees it
 * rotated 180°, so view (0,0) is h1. The mapping is its own inverse — [boardToView] is the same
 * transform — which is why one function covers both directions.
 *
 * Test tags keep using the **board** square, so UI tests address squares by chess coordinate and are
 * unaffected by the flip.
 */
internal fun viewToBoard(row: Int, column: Int, playingWhite: Boolean): Pair<Int, Int> =
    if (playingWhite) Pair(row, column) else Pair(7 - row, 7 - column)

/** Where board square [square] is drawn. Inverse of [viewToBoard]; `INVALID_POSITION` passes through. */
internal fun boardToView(square: Pair<Int, Int>, playingWhite: Boolean): Pair<Int, Int> =
    if (playingWhite || square == INVALID_POSITION) square else Pair(7 - square.first, 7 - square.second)

private const val BOARD_SQUARE_TEST_TAG_PREFIX = "board_square"

private fun squareTestTag(position: Pair<Int, Int>, squareType: SquareType): String {
    return "${BOARD_SQUARE_TEST_TAG_PREFIX}_${squareType.name}_${position.first}_${position.second}"
}

@Composable
fun RowScope.Square(
    modifier: Modifier,
    isDarkSquare: Boolean,
    squareType: SquareType = SquareType.Empty,
    clickable: Boolean = false,
    testTag: String,
    /** B16/B19: the tone this square is named in, or `null` when the coach isn't naming it. */
    coachHighlight: HighlightTone? = null,
    onClick: (SquareType) -> Unit = {},
    content: @Composable () -> Unit
) {
    val (borderWidth, borderColor, shapeType) = when (squareType) {
        SquareType.CanMove -> Triple(1.dp, SelectionRing, RectangleShape)
        SquareType.CannotMove -> Triple(1.dp, SelectionBlockedRing, RectangleShape)
        SquareType.PossibleMove -> Triple(5.dp, MoveMarker, CircleShape)
        SquareType.PossibleCapture -> Triple(5.dp, CaptureMarker, CircleShape)
        else -> Triple(0.dp, Color.Transparent, RectangleShape)
    }

    Box(
        modifier = modifier
            .weight(1f)
            .aspectRatio(1f)
            .background(
                color = if (isDarkSquare) BoardDarkSquare else BoardLightSquare
            )
            // Tinted rather than bordered: the border slot already encodes selection and legal
            // moves, and a second border there would be read as a move hint.
            .then(
                if (coachHighlight != null) Modifier.background(coachHighlightColor(coachHighlight))
                else Modifier
            )
            .border(borderWidth, borderColor, shapeType)
            .clickable(enabled = clickable, onClick = { onClick(squareType) })
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Wash for a square the coach mentioned, tinted by its verdict. Translucent so the piece and square
 * colour show through.
 *
 * These mirror `ChessSetConventions.HIGHLIGHT_COLORS`, which the 3D backends use, but are authored
 * in sRGB rather than linear because Compose takes sRGB — converting at runtime would make the two
 * boards agree numerically and disagree visually. They are matched by eye, not by formula.
 */
private fun coachHighlightColor(tone: HighlightTone): Color = when (tone) {
    HighlightTone.NEUTRAL -> Color(0x553F51B5)
    HighlightTone.GOOD -> Color(0x552E7D32)
    HighlightTone.INACCURATE -> Color(0x55B26A00)
    HighlightTone.BAD -> Color(0x55C62828)
}

@Composable
fun Board(
    gameState: GameUiState,
    animState: PieceAnimationState,
    windowSize: WindowWidthSizeClass,
    /** The side the player is on. Drives both the flip and which pieces are selectable. */
    playerSide: Set = Set.WHITE,
    updateSelected: (Pair<Int, Int>) -> Unit,
    playerMove: (Int, Pair<Int, Int>) -> Unit,
    animationEnd: () -> Unit,
    /** Caps the board's size so it fits within the viewport on wide/landscape windows.
     *  On portrait this equals the full width; on landscape it equals ~85% of the height. */
    boardMaxSize: Dp = Dp.Unspecified,
    /** Squares named in the current coach line (B16). Rendered on the 2D board as a tint. */
    highlightedSquares: List<HighlightedSquare> = emptyList(),
    onSquareTapped: ((Pair<Int, Int>) -> Unit)? = null,
) {
    val squareSizePx = remember { mutableStateOf(IntSize.Zero) }
    val squareAvgSizePx = remember { mutableStateOf(IntSize.Zero) }
    val selectedPossibleMoves = remember { mutableStateOf(emptyList<Pair<Int, Int>>()) }
    // A set, not the list: this is tested once per square, 64 times per recomposition.
    // Last tone wins on a collision, which only happens when a hint and a coach line name the same
    // square; the coach's verdict is the more specific statement, and it is appended second.
    val highlightedPositions = remember(highlightedSquares) {
        highlightedSquares.associate { (it.square.row to it.square.col) to it.tone }
    }

    val playingWhite = playerSide == Set.WHITE
    val ownPositions = if (playingWhite) gameState.positionsWhite else gameState.positionsBlack
    val ownPieces = if (playingWhite) gameState.piecesWhite else gameState.piecesBlack
    val enemyPositions = if (playingWhite) gameState.positionsBlack else gameState.positionsWhite
    val enemyPieces = if (playingWhite) gameState.piecesBlack else gameState.piecesWhite

    if (gameState.selectedSquare != INVALID_POSITION) {
        val pieceIndex = ownPositions.indexOf(gameState.selectedSquare)
        if (pieceIndex != -1) {
            selectedPossibleMoves.value = getLegalMovesForPiece(
                pieceIndex = pieceIndex,
                enemyPieces = enemyPieces,
                enemyPositions = enemyPositions,
                allyPositions = ownPositions,
                allyPieces = ownPieces,
                castlingRights = gameState.castlingRights,
                enPassantTarget = gameState.enPassantTarget
            )
        }
    }

    val boxModifier = Modifier
        .then(if (boardMaxSize != Dp.Unspecified) Modifier.size(boardMaxSize) else Modifier.fillMaxWidth())
        .padding(
            when (windowSize) {
                WindowWidthSizeClass.Expanded -> 18.dp
                WindowWidthSizeClass.Medium -> 12.dp
                WindowWidthSizeClass.Compact -> 0.dp
            }
        )

    Box(
        modifier = boxModifier
    ) {
        Column(modifier = Modifier.testTag("chess_board")) {
            repeat(8) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(8) { column ->
                        // The grid index is a *view* position; `currentSquare` is the board square
                        // it shows. Playing Black rotates the board 180°, which is what "flip the
                        // board" means — and because the rotation preserves (row + col) parity, the
                        // light/dark pattern comes out right without a second case.
                        val currentSquare = viewToBoard(row, column, playingWhite)

                        val squareType = if (currentSquare == gameState.selectedSquare) {
                            if (selectedPossibleMoves.value.isEmpty()) {
                                SquareType.CannotMove
                            } else {
                                SquareType.CanMove
                            }
                        } else {
                            when {
                                currentSquare in selectedPossibleMoves.value -> {
                                    if (currentSquare in enemyPositions) {
                                        SquareType.PossibleCapture
                                    } else {
                                        SquareType.PossibleMove
                                    }
                                }
                                // These two stay literally white/black: they pick which drawable is
                                // rendered and they name the test tag. Whether a square is *yours*
                                // is `currentSquare in ownPositions`, asked separately below.
                                currentSquare in gameState.positionsWhite -> SquareType.WhitePiece
                                currentSquare in gameState.positionsBlack -> SquareType.BlackPiece
                                else -> SquareType.Empty
                            }
                        }

                        val isOwnPiece = currentSquare in ownPositions
                        val clickable = onSquareTapped != null || squareType == SquareType.PossibleMove ||
                            squareType == SquareType.PossibleCapture ||
                            isOwnPiece

                        Square(
                            modifier = Modifier.onGloballyPositioned {
                                if (animState.animatePositionStart == currentSquare) {
                                    squareSizePx.value = it.size
                                }
                                if (squareAvgSizePx.value == IntSize.Zero) {
                                    squareAvgSizePx.value = it.size
                                }
                            },
                            isDarkSquare = (currentSquare.first + currentSquare.second) % 2 == 1,
                            squareType = squareType,
                            clickable = clickable,
                            testTag = squareTestTag(currentSquare, squareType),
                            coachHighlight = highlightedPositions[currentSquare],
                            onClick = { currentSquareType ->
                                // `onSquareTapped` is non-null only while Explain mode is armed, and
                                // it is what made every square clickable above — so in that mode any
                                // square is a valid target, including empty ones the move logic
                                // would reject. `else` rather than an early return: two lambdas are
                                // passed to Square(), so `return@Square` is ambiguous to read even
                                // where it resolves.
                                if (onSquareTapped != null) {
                                    onSquareTapped(currentSquare)
                                } else when (currentSquareType) {
                                    SquareType.PossibleMove, SquareType.PossibleCapture -> {
                                        val moveIndex = gameState.selectedSquare
                                        updateSelected(INVALID_POSITION)
                                        selectedPossibleMoves.value = emptyList()
                                        val idx = ownPositions.indexOf(moveIndex)
                                        if (idx != -1) {
                                            playerMove(idx, currentSquare)
                                        }
                                    }
                                    // Both colours reach here now — `clickable` only lets a square
                                    // through when it holds one of *your* pieces, so the branch is
                                    // on the turn, not on the colour.
                                    SquareType.WhitePiece, SquareType.BlackPiece ->
                                        if (gameState.turn == playerSide) {
                                            updateSelected(currentSquare)
                                        }
                                    else -> error("Should not be clickable")
                                }
                            }
                        ) {
                            if (!(animState.pieceToAnimate != null &&
                                    (animState.animatePositionStart == currentSquare ||
                                        animState.animatePositionEnd == currentSquare)) &&
                                !(animState.secondaryPiece != null &&
                                    (animState.secondaryStart == currentSquare ||
                                        animState.secondaryEnd == currentSquare))) {
                                // Look the square up in both lists rather than deriving the colour
                                // from `squareType`: a square can hold only one piece, and the old
                                // form drew the white list for CanMove/CannotMove, which is the
                                // *selected* square — so a Black player's selected piece vanished.
                                val whiteIdx = gameState.positionsWhite.indexOf(currentSquare)
                                if (whiteIdx != -1) {
                                    Piece(pieceModel = gameState.piecesWhite[whiteIdx])
                                }
                                val blackIdx = gameState.positionsBlack.indexOf(currentSquare)
                                if (blackIdx != -1) {
                                    Piece(pieceModel = gameState.piecesBlack[blackIdx])
                                }
                            }
                        }
                    }
                }
            }
        }

        val primaryPiece = animState.pieceToAnimate
        if (primaryPiece != null) {
            if (animState.moveIsValid()) {
                // The animated piece is offset in grid cells from the board's top-left corner, so
                // it travels in *view* coordinates. Feeding it raw board squares on a flipped board
                // sent every piece to the mirrored square and back.
                AnimatedChessPiece(
                    piece = primaryPiece,
                    squareSizePx = squareSizePx.value,
                    from = boardToView(animState.animatePositionStart, playingWhite),
                    to = boardToView(animState.animatePositionEnd, playingWhite),
                    animationEnd = animationEnd
                )
                val secondaryPiece = animState.secondaryPiece
                if (secondaryPiece != null) {
                    val fallbackSize = if (squareSizePx.value == IntSize.Zero) squareAvgSizePx.value else squareSizePx.value
                    AnimatedChessPiece(
                        piece = secondaryPiece,
                        squareSizePx = fallbackSize,
                        from = boardToView(animState.secondaryStart, playingWhite),
                        to = boardToView(animState.secondaryEnd, playingWhite),
                        animationEnd = {}
                    )
                }
            } else {
                error("Invalid move")
            }
        }
    }
}

@Composable
fun Piece(pieceModel: Piece) {
    Icon(
        painter = painterResource(pieceModel.asset()),
        tint = Color.Unspecified,
        contentDescription = pieceModel.name
    )
}

@Composable
fun AnimatedChessPiece(
    piece: Piece,
    squareSizePx: IntSize,
    from: Pair<Int, Int>,
    to: Pair<Int, Int>,
    animationEnd: () -> Unit
) {
    val offsetY = remember(from) { Animatable(from.first.toFloat()) }
    val offsetX = remember(from) { Animatable(from.second.toFloat()) }

    val squareSizeDp = with(LocalDensity.current) {
        DpSize(width = squareSizePx.width.toDp(), height = squareSizePx.height.toDp())
    }

    LaunchedEffect(from) {
        val yAnim = launch { offsetY.animateTo(to.first.toFloat(), animationSpec = tween(500)) }
        val xAnim = launch { offsetX.animateTo(to.second.toFloat(), animationSpec = tween(500)) }
        joinAll(yAnim, xAnim)
        animationEnd()
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (offsetX.value * squareSizePx.width).roundToInt(),
                    (offsetY.value * squareSizePx.height).roundToInt()
                )
            }
            .size(squareSizeDp)
            .zIndex(1f)
    ) {
        Piece(pieceModel = piece)
    }
}

@Composable
fun PopupWindow(onDismiss: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val cornerRoundness = 25.dp
    val contentPadding = 15.dp
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = { onDismiss(false) }) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            shape = RoundedCornerShape(cornerRoundness),
        ) {
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(scrollState)
                    .wrapContentHeight(Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                content()
            }
        }
    }
}

@Composable
fun PromotionDialog(set: Set, onSelect: (PromotionType) -> Unit, onDismiss: () -> Unit) {
    PopupWindow(onDismiss = { onDismiss() }) {
        Text(stringResource(Res.string.promotion_prompt), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PromotionType.entries.forEach { type ->
                Box(
                    modifier = Modifier.size(56.dp).clickable { onSelect(type) }
                        .testTag("promotion_choice_${type.name}"),
                    contentAlignment = Alignment.Center
                ) { Piece(pieceModel = type.toPiece(set)) }
            }
        }
    }
}

@Composable
fun DrawOfferDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    PopupWindow(onDismiss = { onDecline() }) {
        Text(stringResource(Res.string.draw_offer_prompt), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAccept, modifier = Modifier.testTag("draw_offer_accept")) {
                Text(stringResource(Res.string.accept_button))
            }
            Button(onClick = onDecline, modifier = Modifier.testTag("draw_offer_decline")) {
                Text(stringResource(Res.string.decline_button))
            }
        }
    }
}
