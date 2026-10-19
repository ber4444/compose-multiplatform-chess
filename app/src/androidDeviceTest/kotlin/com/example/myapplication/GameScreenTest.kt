package com.example.myapplication

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.device.DeviceInteraction.Companion.setScreenOrientation
import androidx.test.espresso.device.EspressoDevice.Companion.onDevice
import androidx.test.espresso.device.action.ScreenOrientation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.ui.theme.MyApplicationTheme
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class GameScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testButtonClickAndPieceDisplayed() {
        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, GameViewModel())
            }
        }

        val movedWhitePieceTag = performRandomPlayerMove()
        composeTestRule.onNodeWithTag(movedWhitePieceTag).assertIsDisplayed()
    }

    @Test
    fun testGameOver() {
        val testGameState = GameUiState(winState = WinState.WHITE)

        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, GameViewModel(testGameState))
            }
        }

        composeTestRule.onNodeWithText("Game ended! WHITE wins!").assertIsDisplayed()
    }

    /** Waits for the game-over popup's result line to be laid out with real text in it. */
    private fun awaitResultLine() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val shown = composeTestRule.onAllNodesWithTag("winnerText", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .firstOrNull() ?: return@waitUntil false
            val label = shown.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            shown.layoutInfo.width > 0 && shown.layoutInfo.height > 0 && !label.isNullOrBlank()
        }
    }

    @Test
    fun stalemateIsDetectedBeforeTheEngineIsAskedForAMove() {
        // Black to move, king a8 boxed in by Ka6 and Qb5 without being in check.
        val viewModel = GameViewModel(FenConverter.fenToGameState("k7/8/K7/1Q6/8/8/8/8 b - - 0 1"))
        composeTestRule.setContent {
            MyApplicationTheme { GameScreen(WindowWidthSizeClass.Medium, viewModel) }
        }

        composeTestRule.runOnIdle {
            runBlocking {
                viewModel.moveCPU { _, _, _, _ -> error("stalemate should end the game before a move is picked") }
            }
        }

        awaitResultLine()
        composeTestRule.onNodeWithText("Game ended in a STALEMATE!").assertIsDisplayed()
    }

    @Test
    fun testDrawDialogDisplayed() {
        val testGameState = GameUiState(winState = WinState.DRAW)

        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, GameViewModel(testGameState))
            }
        }

        composeTestRule.onNodeWithText("Game ended in a DRAW!").assertIsDisplayed()
    }

    @Test
    fun testInsufficientMaterialDrawViaClicks() {
        val state = FenConverter.fenToGameState("4k3/8/8/6n1/8/8/8/2B1K3 w - - 10 40")
        val viewModel = GameViewModel(state)

        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }

        // Click bishop on c1
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.WhitePiece, 7, 2)).performClick()
        
        // Click capture square on g5 (knight)
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.PossibleCapture, 3, 6)).performClick()

        awaitResultLine()
        composeTestRule.onNodeWithText("Game ended in a DRAW!").assertIsDisplayed()
    }

    @Test
    fun testEnPassantCapture() {
        val state = FenConverter.fenToGameState("k7/8/8/3pP3/8/8/8/K7 w - d6 0 1")
        val viewModel = GameViewModel(state)
        
        composeTestRule.setContent {
            MyApplicationTheme {
                GameScreen(WindowWidthSizeClass.Medium, viewModel)
            }
        }
        
        // 1. Assert initial state: black pawn at d5 (3, 3), white pawn at e5 (3, 4)
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.BlackPiece, 3, 3)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.WhitePiece, 3, 4)).assertIsDisplayed()
        
        // 2. Click white pawn
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.WhitePiece, 3, 4)).performClick()
        
        // 3. Click the en passant target square (2, 3) which shows up as a PossibleMove
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.PossibleMove, 2, 3)).performClick()
        
        // 4. Wait for animation
        composeTestRule.waitForIdle()
        
        // 5. Assert new state: white pawn is at (2, 3), square (3, 3) is empty
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.WhitePiece, 2, 3)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.Empty, 3, 3)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.Empty, 3, 4)).assertIsDisplayed()
    }

    @Test
    fun boardClearsTheStatusBarInPortrait() = assertBoardClearsStatusBar(ScreenOrientation.PORTRAIT)

    @Test
    fun boardClearsTheStatusBarInLandscape() = assertBoardClearsStatusBar(ScreenOrientation.LANDSCAPE)

    /** Height of the system status bar on this device, or 0 if the platform doesn't report one. */
    private fun statusBarHeightPx(): Int {
        val res = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    /**
     * Rotates the device, renders a fresh game edge to edge, and checks the board's top edge is not
     * tucked under the status bar — and that a far-corner piece (Black's a8 rook) is still drawn.
     */
    private fun assertBoardClearsStatusBar(orientation: ScreenOrientation) {
        onDevice().setScreenOrientation(orientation)
        composeTestRule.waitForIdle()
        val statusBar = statusBarHeightPx()

        composeTestRule.setContent {
            MyApplicationTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    GameScreen(windowSize = WindowWidthSizeClass.Medium, viewModel = GameViewModel())
                }
            }
        }
        composeTestRule.waitForIdle()

        val boardTop = composeTestRule.onNodeWithTag("chess_board").fetchSemanticsNode().boundsInRoot.top
        assertTrue("board top $boardTop px sits under the $statusBar px status bar ($orientation)", boardTop >= statusBar)
        composeTestRule.onNodeWithTag(boardSquareTag(SquareType.BlackPiece, row = 0, column = 0)).assertIsDisplayed()
    }

    private fun performRandomPlayerMove(): String {
        val random = Random(0)
        composeTestRule.waitForIdle()
        val whitePieceTags = existingSquareTags(SquareType.WhitePiece).shuffled(random)

        for (pieceTag in whitePieceTags) {
            composeTestRule.onNodeWithTag(pieceTag).performClick()
            composeTestRule.waitForIdle()

            val moveTargetTags = (
                existingSquareTags(SquareType.PossibleMove) +
                    existingSquareTags(SquareType.PossibleCapture)
                )
                .distinct()

            if (moveTargetTags.isNotEmpty()) {
                val selectedMoveTag = moveTargetTags.random(random)
                val movedWhitePieceTag = selectedMoveTag.toWhitePieceSquareTag()

                composeTestRule.onNodeWithTag(selectedMoveTag).performClick()
                // The piece only gets its new tag once the move has animated in.
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag(movedWhitePieceTag)
                        .fetchSemanticsNodes(atLeastOneRootRequired = false)
                        .isNotEmpty()
                }

                return movedWhitePieceTag
            }
        }

        throw AssertionError("Expected to find a white piece with at least one legal move")
    }

    private fun existingSquareTags(squareType: SquareType): List<String> {
        return allBoardSquareTags(squareType).filter { tag ->
            composeTestRule
                .onAllNodesWithTag(tag)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    private fun allBoardSquareTags(squareType: SquareType): List<String> {
        return (0 until BOARD_SIZE).flatMap { row ->
            (0 until BOARD_SIZE).map { column ->
                boardSquareTag(squareType, row, column)
            }
        }
    }

    private fun boardSquareTag(squareType: SquareType, row: Int, column: Int): String {
        return "board_square_${squareType.name}_${row}_${column}"
    }

    private fun String.toWhitePieceSquareTag(): String {
        return replace(
            "board_square_${SquareType.PossibleMove.name}_",
            "board_square_${SquareType.WhitePiece.name}_"
        ).replace(
            "board_square_${SquareType.PossibleCapture.name}_",
            "board_square_${SquareType.WhitePiece.name}_"
        )
    }
}
