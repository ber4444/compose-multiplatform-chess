package com.example.myapplication

import com.example.myapplication.board3d.Board3D
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.board3d.BoardSquare
import com.example.myapplication.board3d.Board3DSessionState
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
import com.example.myapplication.movecoach.MoveCoachPanel
import com.example.myapplication.movecoach.MoveCoachUiState
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
import game.app.generated.resources.draw_offer_declined
import game.app.generated.resources.draw_offer_prompt
import game.app.generated.resources.offer_draw_button
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
) {
    val gameState by viewModel.gameState.collectAsState()
    val animState by viewModel.animState.collectAsState()
    val viewState by viewModel.viewState.collectAsState()
    val moveCoachManager = LocalMoveCoachManager.current
    val coachState = moveCoachManager?.coachUiState?.collectAsState()?.value ?: MoveCoachUiState.Hidden
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
    val board3DCameraSession = remember { Board3DSessionState() }
    var isEntering3D by remember { mutableStateOf(false) }
    var isTearingDown3D by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

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
            val resetGame = { reset: Boolean ->
                if (reset) {
                    viewModel.resetGame(show3D = board3DEnabled)
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
                    color = Color.Red,
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
            }
        }

        if (gameState.pendingPromotion != null) {
            PromotionDialog(
                set = gameState.turn,  // always WHITE when pending
                onSelect = viewModel::promotePawn,
                onDismiss = viewModel::cancelPromotion
            )
        }

        if (gameState.drawOffer == Set.BLACK && gameState.winState == WinState.NONE) {
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
                    onSquareTapped = onSquareTapped@{ sq ->
                    // Route a 3D tap through the same selection/move logic the 2D board uses.
                    if (animState.pieceToAnimate != null || gameState.turn != Set.WHITE) return@onSquareTapped
                    val pos = Pair(sq.row, sq.col)
                    val selectedPieceIndex = gameState.positionsWhite.indexOf(gameState.selectedSquare)
                    val legalMoves = if (selectedPieceIndex != -1) {
                        getLegalMovesForPiece(
                            pieceIndex = selectedPieceIndex,
                            enemyPieces = gameState.piecesBlack,
                            enemyPositions = gameState.positionsBlack,
                            allyPositions = gameState.positionsWhite,
                            allyPieces = gameState.piecesWhite,
                            castlingRights = gameState.castlingRights,
                            enPassantTarget = gameState.enPassantTarget,
                        )
                    } else emptyList()
                    when {
                        pos in legalMoves -> viewModel.playerMove(selectedPieceIndex, pos)
                        pos in gameState.positionsWhite -> viewModel.updateSelected(pos)
                    }
                    }
                )
                }

                if (isEntering3D || !surfaceComposeReady || !rendererReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.85f))
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
                    onResetGame = { viewModel.resetGame(show3D = board3DEnabled) },
                    transparentButtons = true,
                )
                if (coachState !is MoveCoachUiState.Hidden) {
                    MoveCoachPanel(
                        state = coachState,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        } else {
            // Measure the viewport OUTSIDE the verticalScroll — the scroll gives children infinite
            // max height, so BoxWithConstraints inside it can't cap the board. Capping the board at
            // min(width, 0.85 × height) keeps it fully visible on wide/landscape windows (web/desktop)
            // while still using the full width on portrait.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val boardMaxSize = minOf(maxWidth, maxHeight * 0.85f)
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
                        updateSelected = viewModel::updateSelected,
                        playerMove = viewModel::playerMove,
                        animationEnd = viewModel::animationEnd,
                        boardMaxSize = boardMaxSize,
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    GameControls(
                        gameState = gameState,
                        animState = animState,
                        viewState = viewState,
                        viewModel = viewModel,
                        onResetGame = { viewModel.resetGame(show3D = board3DEnabled) }
                    )

                    // Move Coach panel — fills remaining space below the buttons.
                    if (coachState !is MoveCoachUiState.Hidden) {
                        MoveCoachPanel(
                            state = coachState,
                            modifier = Modifier.weight(1f, fill = false)
                        )
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
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        if (viewState.board3DUnavailable) {
            Text(
                text = stringResource(Res.string.board_3d_unavailable),
                color = Color.Red,
                modifier = Modifier.testTag("board_3d_unavailable")
            )
        }

        Row {
            if (transparentButtons) {
                TransparentUnderlineButton(onClick = onResetGame) {
                    Text(stringResource(Res.string.reset_button))
                }
                TransparentUnderlineButton(
                    onClick = viewModel::requestDrawOffer,
                    enabled = canOfferDraw(gameState) && animState.pieceToAnimate == null,
                    modifier = Modifier.testTag("offer_draw_button")
                ) { Text(stringResource(Res.string.offer_draw_button)) }
            } else {
                Button(onClick = onResetGame) {
                    Text(stringResource(Res.string.reset_button))
                }
                Button(
                    onClick = viewModel::requestDrawOffer,
                    enabled = canOfferDraw(gameState) && animState.pieceToAnimate == null,
                    modifier = Modifier.testTag("offer_draw_button")
                ) { Text(stringResource(Res.string.offer_draw_button)) }
            }
        }

        if (gameState.drawOfferDeclinedBy == Set.BLACK) {
            Text(
                text = stringResource(Res.string.draw_offer_declined),
                modifier = Modifier.testTag("draw_offer_declined_text")
            )
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
    onClick: (SquareType) -> Unit = {},
    content: @Composable () -> Unit
) {
    val (borderWidth, borderColor, shapeType) = when (squareType) {
        SquareType.CanMove -> Triple(1.dp, Color.Green, RectangleShape)
        SquareType.CannotMove -> Triple(1.dp, Color.Red, RectangleShape)
        SquareType.PossibleMove -> Triple(5.dp, Color.Yellow, CircleShape)
        SquareType.PossibleCapture -> Triple(5.dp, Color.Red, CircleShape)
        else -> Triple(0.dp, Color.Transparent, RectangleShape)
    }

    Box(
        modifier = modifier
            .weight(1f)
            .aspectRatio(1f)
            .background(
                color = if (isDarkSquare) MaterialTheme.colorScheme.secondary else Color.White
            )
            .border(borderWidth, borderColor, shapeType)
            .clickable(enabled = clickable, onClick = { onClick(squareType) })
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun Board(
    gameState: GameUiState,
    animState: PieceAnimationState,
    windowSize: WindowWidthSizeClass,
    updateSelected: (Pair<Int, Int>) -> Unit,
    playerMove: (Int, Pair<Int, Int>) -> Unit,
    animationEnd: () -> Unit,
    /** Caps the board's size so it fits within the viewport on wide/landscape windows.
     *  On portrait this equals the full width; on landscape it equals ~85% of the height. */
    boardMaxSize: Dp = Dp.Unspecified,
) {
    val squareSizePx = remember { mutableStateOf(IntSize.Zero) }
    val squareAvgSizePx = remember { mutableStateOf(IntSize.Zero) }
    val selectedPossibleMoves = remember { mutableStateOf(emptyList<Pair<Int, Int>>()) }

    if (gameState.selectedSquare != INVALID_POSITION) {
        val pieceIndex = gameState.positionsWhite.indexOf(gameState.selectedSquare)
        if (pieceIndex != -1) {
            selectedPossibleMoves.value = getLegalMovesForPiece(
                pieceIndex = pieceIndex,
                enemyPieces = gameState.piecesBlack,
                enemyPositions = gameState.positionsBlack,
                allyPositions = gameState.positionsWhite,
                allyPieces = gameState.piecesWhite,
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
                        val currentSquare = Pair(row, column)

                        val squareType = if (currentSquare == gameState.selectedSquare) {
                            if (selectedPossibleMoves.value.isEmpty()) {
                                SquareType.CannotMove
                            } else {
                                SquareType.CanMove
                            }
                        } else {
                            when {
                                currentSquare in selectedPossibleMoves.value -> {
                                    if (currentSquare in gameState.positionsBlack) {
                                        SquareType.PossibleCapture
                                    } else {
                                        SquareType.PossibleMove
                                    }
                                }
                                currentSquare in gameState.positionsWhite -> SquareType.WhitePiece
                                currentSquare in gameState.positionsBlack -> SquareType.BlackPiece
                                else -> SquareType.Empty
                            }
                        }

                        val clickable = squareType == SquareType.PossibleMove ||
                            squareType == SquareType.PossibleCapture ||
                            squareType == SquareType.WhitePiece

                        Square(
                            modifier = Modifier.onGloballyPositioned {
                                if (animState.animatePositionStart == currentSquare) {
                                    squareSizePx.value = it.size
                                }
                                if (squareAvgSizePx.value == IntSize.Zero) {
                                    squareAvgSizePx.value = it.size
                                }
                            },
                            isDarkSquare = (row + column) % 2 == 1,
                            squareType = squareType,
                            clickable = clickable,
                            testTag = squareTestTag(currentSquare, squareType),
                            onClick = { currentSquareType ->
                                when (currentSquareType) {
                                    SquareType.PossibleMove, SquareType.PossibleCapture -> {
                                        val moveIndex = gameState.selectedSquare
                                        updateSelected(INVALID_POSITION)
                                        selectedPossibleMoves.value = emptyList()
                                        val idx = gameState.positionsWhite.indexOf(moveIndex)
                                        if (idx != -1) {
                                            playerMove(idx, currentSquare)
                                        }
                                    }
                                    SquareType.WhitePiece -> if (gameState.turn == Set.WHITE) {
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
                                if (
                                    squareType == SquareType.WhitePiece ||
                                    squareType == SquareType.CannotMove ||
                                    squareType == SquareType.CanMove
                                ) {
                                    val idx = gameState.positionsWhite.indexOf(currentSquare)
                                    if (idx != -1) {
                                        Piece(pieceModel = gameState.piecesWhite[idx])
                                    }
                                }

                                if (squareType == SquareType.BlackPiece || squareType == SquareType.PossibleCapture) {
                                    val idx = gameState.positionsBlack.indexOf(currentSquare)
                                    if (idx != -1) {
                                        Piece(pieceModel = gameState.piecesBlack[idx])
                                    }
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
                AnimatedChessPiece(
                    piece = primaryPiece,
                    squareSizePx = squareSizePx.value,
                    from = animState.animatePositionStart,
                    to = animState.animatePositionEnd,
                    animationEnd = animationEnd
                )
                val secondaryPiece = animState.secondaryPiece
                if (secondaryPiece != null) {
                    val fallbackSize = if (squareSizePx.value == IntSize.Zero) squareAvgSizePx.value else squareSizePx.value
                    AnimatedChessPiece(
                        piece = secondaryPiece,
                        squareSizePx = fallbackSize,
                        from = animState.secondaryStart,
                        to = animState.secondaryEnd,
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
            .border(width = 1.dp, color = Color.Red)
    ) {
        Piece(pieceModel = piece)
    }
}

@Composable
fun PopupWindow(onDismiss: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val cornerRoundness = 25.dp
    val contentPadding = 15.dp

    Dialog(onDismissRequest = { onDismiss(false) }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(cornerRoundness),
        ) {
            Column(
                modifier = Modifier
                    .padding(contentPadding)
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
