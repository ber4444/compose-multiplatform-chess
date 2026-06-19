package com.example.myapplication

import com.example.myapplication.board3d.Board3D
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.board3d.BoardSquare
import com.example.myapplication.board3d.Board3DSessionState
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import game.app.generated.resources.board_3d_toggle_label
import game.app.generated.resources.board_3d_unavailable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun GameScreen(
    windowSize: WindowWidthSizeClass,
    viewModel: GameViewModel,
    board3D: Board3DSupport? = null,
    switchTopPadding: Dp = 8.dp
) {
    val gameState by viewModel.gameState.collectAsState()
    val animState by viewModel.animState.collectAsState()
    val viewState by viewModel.viewState.collectAsState()
    val scrollState = rememberScrollState()
    val show3D = viewState.show3D && board3D != null
    val board3DCameraSession = remember { Board3DSessionState() }
    var isEntering3D by remember { mutableStateOf(false) }
    var isTearingDown3D by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        if (gameState.winState != WinState.NONE && !viewState.hideWindow) {
            val resetGame = { reset: Boolean ->
                if (reset) {
                    viewModel.resetGame()
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
            Box(modifier = Modifier.fillMaxSize()) {
                Board3D(
                    support = board3D,
                    fen = fen,
                    modifier = Modifier.fillMaxSize(),
                    onUnavailable = {
                        isEntering3D = false
                        viewModel.markBoard3DUnavailable()
                    },
                    cameraSession = board3DCameraSession,
                    onRendererReady = { isEntering3D = false },
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

            GameControls(
                gameState = gameState,
                animState = animState,
                viewState = viewState,
                viewModel = viewModel,
                transparentButtons = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(16.dp)
            )
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
                        viewModel = viewModel
                    )
                }
            }
        }

        if (board3D != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(1f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .offset(y = switchTopPadding)
                    .padding(end = 12.dp)
                    .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = stringResource(Res.string.board_3d_toggle_label),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = viewState.show3D,
                    onCheckedChange = { checked ->
                        if (!checked && viewState.show3D) {
                            isTearingDown3D = true
                            coroutineScope.launch {
                                // Keep the existing surface mounted while Compose presents the
                                // loader. Frame boundaries guarantee visible progress without a
                                // timing guess; the second frame lets its animation advance before
                                // SceneView/Filament teardown can occupy the UI thread.
                                withFrameNanos { }
                                withFrameNanos { }
                                viewModel.setShow3D(false)
                                // Disposal/recomposition has completed before controls re-enable.
                                withFrameNanos { }
                                isTearingDown3D = false
                            }
                        } else {
                            isEntering3D = checked
                            coroutineScope.launch {
                                withFrameNanos { }
                                viewModel.setShow3D(checked)
                            }
                        }
                    },
                    enabled = !viewState.buttonLock && !isEntering3D && !isTearingDown3D,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.Gray.copy(alpha = 0.48f),
                        checkedBorderColor = Color.White.copy(alpha = 0.22f),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.28f),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.32f),
                        disabledCheckedThumbColor = Color.White.copy(alpha = 0.42f),
                        disabledCheckedTrackColor = Color.Gray.copy(alpha = 0.22f),
                        disabledCheckedBorderColor = Color.White.copy(alpha = 0.16f),
                        disabledUncheckedThumbColor = Color.White.copy(alpha = 0.32f),
                        disabledUncheckedTrackColor = Color.Gray.copy(alpha = 0.14f),
                        disabledUncheckedBorderColor = Color.White.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier.testTag("board_3d_toggle")
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
            val buttonColors = if (transparentButtons) {
                ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Color.White.copy(alpha = 0.38f)
                )
            } else {
                ButtonDefaults.buttonColors()
            }

            Button(
                onClick = viewModel::resetGame,
                colors = buttonColors
            ) {
                Text(stringResource(Res.string.reset_button))
            }
            Button(
                onClick = viewModel::requestDrawOffer,
                enabled = canOfferDraw(gameState) && animState.pieceToAnimate == null,
                colors = buttonColors,
                modifier = Modifier.testTag("offer_draw_button")
            ) { Text(stringResource(Res.string.offer_draw_button)) }
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

        if (animState.pieceToAnimate != null) {
            if (animState.moveIsValid()) {
                AnimatedChessPiece(
                    piece = animState.pieceToAnimate,
                    squareSizePx = squareSizePx.value,
                    from = animState.animatePositionStart,
                    to = animState.animatePositionEnd,
                    animationEnd = animationEnd
                )
                if (animState.secondaryPiece != null) {
                    val fallbackSize = if (squareSizePx.value == IntSize.Zero) squareAvgSizePx.value else squareSizePx.value
                    AnimatedChessPiece(
                        piece = animState.secondaryPiece,
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
        painter = painterResource(pieceModel.asset),
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
    val height = 200.dp
    val cornerRoundness = 25.dp
    val contentPadding = 15.dp

    Dialog(onDismissRequest = { onDismiss(false) }) {
        Card(
            modifier = Modifier.fillMaxWidth().height(height),
            shape = RoundedCornerShape(cornerRoundness),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .wrapContentHeight(),
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
