package com.example.myapplication

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawOfferScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun boardSquareTag(squareType: SquareType, row: Int, column: Int): String {
        return "board_square_${squareType.name}_${row}_${column}"
    }

    private fun mockEngine(eval: Int?): ChessEngine {
        return object : ChessEngine {
            override fun getBestMove(fen: String): String? = null
            override fun evaluate(fen: String): Int? = eval
            override fun close() {}
        }
    }

    @Test
    fun testButtonPresentAndEnabledAtStart() {
        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, GameViewModel())
            }
        }

        composeTestRule.onNodeWithTag("offer_draw_button")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun testOfferAccepted_Fallback() {
        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, GameViewModel())
            }
        }

        composeTestRule.onNodeWithTag("offer_draw_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("winnerText", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithText("Game ended in a DRAW!").assertIsDisplayed()
    }

    @Test
    fun testOfferDeclined() {
        val viewModel = GameViewModel()
        viewModel.attachEngine(mockEngine(-500))

        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }

        composeTestRule.onNodeWithTag("offer_draw_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("draw_offer_declined_text", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithTag("draw_offer_declined_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("offer_draw_button").assertIsNotEnabled()
        
        assert(composeTestRule.onAllNodesWithTag("winnerText", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun testBlackOffers_Accept() {
        val state = FenConverter.fenToGameState("r3k3/p7/8/8/8/8/P7/R3K3 w - - 10 30")
        val viewModel = GameViewModel(state)

        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }

        // Click White Piece (a1 Rook)
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.WhitePiece, 7, 0)).performClick()
        
        // Click PossibleMove (d1)
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.PossibleMove, 7, 3)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag("draw_offer_accept", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithTag("draw_offer_accept").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("winnerText", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithText("Game ended in a DRAW!").assertIsDisplayed()
    }

    @Test
    fun testBlackOffers_Decline() {
        val state = FenConverter.fenToGameState("r3k3/p7/8/8/8/8/P7/R3K3 w - - 10 30")
        val viewModel = GameViewModel(state)

        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }

        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.WhitePiece, 7, 0)).performClick()
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.PossibleMove, 7, 3)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag("draw_offer_decline", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithTag("draw_offer_decline").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.gameState.value.turn == Set.WHITE
        }

        assert(composeTestRule.onAllNodesWithTag("winnerText", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
