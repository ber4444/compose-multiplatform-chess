package com.example.myapplication

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class GameScreenUiTest {

    @Test
    fun boardRendersWithPiecesAndControls() = runComposeUiTest {
        val viewModel = GameViewModel()
        setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }

        onNodeWithTag("chess_board").assertExists()
        onNodeWithTag("board_square_WhitePiece_7_4", useUnmergedTree = true).assertExists() // white king e1
        onNodeWithTag("board_square_BlackPiece_0_4", useUnmergedTree = true).assertExists() // black king e8
        onNodeWithTag("offer_draw_button").assertExists() // draw button

        viewModel.close()
    }

    @Test
    fun playerPawnMoveWorks() = runComposeUiTest {
        val viewModel = GameViewModel()
        setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }

        // click e2 pawn
        onNodeWithTag("board_square_WhitePiece_6_4").performClick()
        // click e4 square
        onNodeWithTag("board_square_PossibleMove_4_4").performClick()

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("board_square_WhitePiece_4_4")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }

        onNodeWithTag("board_square_WhitePiece_4_4").assertIsDisplayed()
        
        viewModel.close()
    }

    @Test
    fun gameOverPopupIsShown() = runComposeUiTest {
        val testGameState = GameUiState(winState = WinState.WHITE)
        val viewModel = GameViewModel(testGameState)
        
        setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }

        onNodeWithTag("winnerText", useUnmergedTree = true).assertIsDisplayed()
        
        viewModel.close()
    }
}
