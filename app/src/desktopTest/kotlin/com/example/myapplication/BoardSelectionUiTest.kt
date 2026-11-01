package com.example.myapplication

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 2D board's move dots used to live in a MutableState that composition only wrote while a piece
 * was selected, so clearing the selection anywhere but the board's own click handler left the old
 * piece's dots on screen — after Reset, on an empty starting position.
 */
@OptIn(ExperimentalTestApi::class)
class BoardSelectionUiTest {

    private val moveDot = SemanticsMatcher("is a move dot") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("board_square_PossibleMove") == true
    }

    @Test
    fun resetClearsTheSelectedPiecesMoveDots() = runComposeUiTest {
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

            onNodeWithTag("board_square_WhitePiece_6_4").performClick() // e2
            onNodeWithTag("board_square_PossibleMove_4_4").assertExists() // e4

            viewModel.resetGame()
            waitForIdle()

            assertTrue(
                onAllNodes(moveDot).fetchSemanticsNodes().isEmpty(),
                "Reset cleared the selection but the e2 pawn's move dots are still drawn",
            )
        } finally {
            viewModel.close()
        }
    }
}
