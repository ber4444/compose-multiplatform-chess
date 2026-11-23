package com.example.myapplication

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 2D board's move dots used to live in a MutableState that composition only wrote while a piece
 * was selected, so clearing the selection anywhere but the board's own click handler left the old
 * piece's dots on screen — after Reset, on an empty starting position.
 *
 * Tapping the selected piece a second time used to crash the app with
 * `IllegalStateException: Should not be clickable`: #153 made every square holding one of your
 * pieces clickable, which includes the selected square (`CanMove`/`CannotMove`), but the click
 * handler only knew the piece and move-target types. The second-tap cases below pin the fix.
 */
@OptIn(ExperimentalTestApi::class)
class BoardSelectionUiTest {

    private val moveDot = SemanticsMatcher("is a move dot") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("board_square_PossibleMove") == true
    }

    @Test
    fun resetClearsTheSelectedPiecesMoveDots() = runComposeUiTest {
        withBoard { viewModel ->
            onNodeWithTag("board_square_WhitePiece_6_4").performClick() // e2
            onNodeWithTag("board_square_PossibleMove_4_4").assertExists() // e4

            viewModel.resetGame()
            waitForIdle()

            assertTrue(
                onAllNodes(moveDot).fetchSemanticsNodes().isEmpty(),
                "Reset cleared the selection but the e2 pawn's move dots are still drawn",
            )
        }
    }

    @Test
    fun tappingTheSelectedPieceAgainDeselectsIt() = runComposeUiTest {
        withBoard { viewModel ->
            onNodeWithTag("board_square_WhitePiece_6_4").performClick() // e2
            onNodeWithTag("board_square_CanMove_6_4").assertExists()

            onNodeWithTag("board_square_CanMove_6_4").performClick()

            onNodeWithTag("board_square_WhitePiece_6_4").assertExists()
            assertEquals(INVALID_POSITION, viewModel.gameState.value.selectedSquare)
            assertTrue(onAllNodes(moveDot).fetchSemanticsNodes().isEmpty(), "e2's move dots outlived the deselect")
        }
    }

    @Test
    fun tappingASelectedPieceThatCannotMoveAgainDeselectsIt() = runComposeUiTest {
        withBoard { viewModel ->
            onNodeWithTag("board_square_WhitePiece_7_0").performClick() // a1, boxed in at the start
            onNodeWithTag("board_square_CannotMove_7_0").assertExists()

            onNodeWithTag("board_square_CannotMove_7_0").performClick()

            onNodeWithTag("board_square_WhitePiece_7_0").assertExists()
            assertEquals(INVALID_POSITION, viewModel.gameState.value.selectedSquare)
        }
    }

    @Test
    fun repeatedTapsOnTheSamePieceToggleTheSelection() = runComposeUiTest {
        withBoard { viewModel ->
            repeat(3) {
                onNodeWithTag("board_square_WhitePiece_7_6").performClick() // g1
                assertEquals(7 to 6, viewModel.gameState.value.selectedSquare)
                onNodeWithTag("board_square_CanMove_7_6").performClick()
                assertEquals(INVALID_POSITION, viewModel.gameState.value.selectedSquare)
            }
        }
    }

    @Test
    fun choosingAnotherOwnPieceMovesTheSelection() = runComposeUiTest {
        withBoard { viewModel ->
            onNodeWithTag("board_square_WhitePiece_6_4").performClick() // e2
            onNodeWithTag("board_square_WhitePiece_6_3").performClick() // d2

            assertEquals(6 to 3, viewModel.gameState.value.selectedSquare)
            onNodeWithTag("board_square_WhitePiece_6_4").assertExists()
            onNodeWithTag("board_square_CanMove_6_3").assertExists()
        }
    }

    /**
     * Same second tap with the board flipped. A fixed Black-to-move position rather than
     * `GameViewModel.playerSide = BLACK`, which would make the engine open asynchronously; what is
     * under test is the board's click handler, not the opponent.
     */
    @Test
    fun tappingTheSelectedPieceAgainDeselectsItWhenPlayingBlack() = runComposeUiTest {
        var gameState by mutableStateOf(
            FenConverter.fenToGameState("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        )
        setContent {
            Board(
                gameState = gameState,
                animState = PieceAnimationState(),
                windowSize = WindowWidthSizeClass.Compact,
                playerSide = Set.BLACK,
                updateSelected = { gameState = gameState.copy(selectedSquare = it) },
                playerMove = { _, _ -> },
                animationEnd = {},
                boardMaxSize = 480.dp,
            )
        }

        onNodeWithTag("board_square_BlackPiece_0_1").performClick() // b8
        onNodeWithTag("board_square_CanMove_0_1").performClick()
        assertEquals(INVALID_POSITION, gameState.selectedSquare)

        onNodeWithTag("board_square_BlackPiece_0_0").performClick() // a8, boxed in
        onNodeWithTag("board_square_CannotMove_0_0").performClick()
        assertEquals(INVALID_POSITION, gameState.selectedSquare)
        onNodeWithTag("board_square_BlackPiece_0_0").assertExists()
    }

    private fun ComposeUiTest.withBoard(block: ComposeUiTest.(GameViewModel) -> Unit) {
        val viewModel = GameViewModel()
        try {
            setContent {
                val gameState by viewModel.gameState.collectAsState()
                val animState by viewModel.animState.collectAsState()
                Board(
                    gameState = gameState,
                    animState = animState,
                    windowSize = WindowWidthSizeClass.Compact,
                    updateSelected = viewModel::updateSelected,
                    playerMove = viewModel::playerMove,
                    animationEnd = viewModel::animationEnd,
                    // The default test window is 1024x768; a full-width board puts rank 2 below it.
                    boardMaxSize = 480.dp,
                )
            }
            block(viewModel)
        } finally {
            viewModel.close()
        }
    }
}
