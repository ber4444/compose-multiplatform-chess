package com.example.myapplication

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import game.app.generated.resources.autoplay_label
import game.app.generated.resources.cancel_button
import game.app.generated.resources.game_end_message_no_winner
import game.app.generated.resources.game_end_message_winner
import game.app.generated.resources.king_dark
import game.app.generated.resources.king_light
import game.app.generated.resources.no_winner
import game.app.generated.resources.play_again_button
import game.app.generated.resources.promotion_prompt
import game.app.generated.resources.reset_button
import game.app.generated.resources.stockfish_disabled
import game.app.generated.resources.stockfish_enabled
import game.app.generated.resources.accept_button
import game.app.generated.resources.decline_button
import game.app.generated.resources.draw_offer_declined
import game.app.generated.resources.draw_offer_prompt
import game.app.generated.resources.offer_draw_button
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun GameScreen(
    windowSize: WindowWidthSizeClass,
    viewModel: GameViewModel
) {
    val gameState by viewModel.gameState.collectAsState()
    val animState by viewModel.animState.collectAsState()
    val viewState by viewModel.viewState.collectAsState()
    val stockfishEnabled by viewModel.stockfishEnabled.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
    ) {
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

        Board(
            gameState = gameState,
            animState = animState,
            windowSize = windowSize,
            updateSelected = viewModel::updateSelected,
            playerMove = viewModel::playerMove,
            animationEnd = viewModel::animationEnd
        )

        Spacer(modifier = Modifier.padding(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = gameState.autoPlay,
                    onCheckedChange = viewModel::setAutoPlay,
                    enabled = !viewState.buttonLock
                )
                Text(text = stringResource(Res.string.autoplay_label))
            }

            Text(
                text = stringResource(
                    if (stockfishEnabled) {
                        Res.string.stockfish_enabled
                    } else {
                        Res.string.stockfish_disabled
                    }
                ),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Row {
            Button(onClick = viewModel::resetGame) {
                Text(stringResource(Res.string.reset_button))
            }
            Button(
                onClick = viewModel::requestDrawOffer,
                enabled = canOfferDraw(gameState) && animState.pieceToAnimate == null,
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

    LaunchedEffect(gameState.autoPlay, gameState.winState, animState.pieceToAnimate, viewState.moveButtonLock) {
        if (gameState.autoPlay && gameState.winState == WinState.NONE && animState.pieceToAnimate == null && !viewState.moveButtonLock) {
            viewModel.startUserTurn()
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
    animationEnd: () -> Unit
) {
    val squareSizePx = remember { mutableStateOf(IntSize.Zero) }
    val squareAvgSizePx = remember { mutableStateOf(IntSize.Zero) }
    val selectedPossibleMoves = remember { mutableStateOf(emptyList<Pair<Int, Int>>()) }

    if (!gameState.autoPlay) {
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
    } else if (selectedPossibleMoves.value.isNotEmpty()) {
        selectedPossibleMoves.value = emptyList()
    }

    val boxModifier = Modifier
        .fillMaxWidth()
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

                        val clickable = !gameState.autoPlay && (
                            squareType == SquareType.PossibleMove ||
                                squareType == SquareType.PossibleCapture ||
                                squareType == SquareType.WhitePiece
                            )

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
                                        playerMove(
                                            gameState.positionsWhite.indexOf(moveIndex),
                                            currentSquare
                                        )
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
                                    Piece(pieceModel = gameState.piecesWhite[gameState.positionsWhite.indexOf(currentSquare)])
                                }

                                if (squareType == SquareType.BlackPiece || squareType == SquareType.PossibleCapture) {
                                    Piece(pieceModel = gameState.piecesBlack[gameState.positionsBlack.indexOf(currentSquare)])
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
