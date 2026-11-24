package com.example.myapplication

import android.app.UiAutomation
import android.graphics.Rect
import android.view.Surface
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.board3d.fakeBoard3DSupport
import com.example.myapplication.movecoach.MoveCoachManager
import com.example.myapplication.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The landscape rails against a real window: real status bar, real navigation bar and, on a phone
 * with a punch-hole camera, a real display cutout on one long edge. `LandscapeLayoutUiTest` (desktop)
 * pins the layout itself; only a device reports cutout bounds, so this is where clearing them is checked.
 *
 * Each rail starts just under the status bar, against its side's edge, and never touches a cutout.
 * On the cutout's side, the buttons from the first one that would meet it onwards wrap into an inner
 * column beside the outer one, also starting at the top; a side without a cutout stays one column.
 * Both landscapes are covered because the camera changes sides between them.
 */
@RunWith(AndroidJUnit4::class)
class LandscapeRailsDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val uiAutomation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private val navButtons = listOf("open_chat_button", "open_rules_button", "open_settings_button", "open_history_button")
    private val gameButtons = listOf("reset_button", "hint_button", "move_coach_explain_toggle")

    @After
    fun unfreezeRotation() {
        uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test
    fun rails3DInLandscape90() = assertRails(UiAutomation.ROTATION_FREEZE_90, Surface.ROTATION_90, board3D = true)

    @Test
    fun rails3DInLandscape270() = assertRails(UiAutomation.ROTATION_FREEZE_270, Surface.ROTATION_270, board3D = true)

    @Test
    fun rails2DInLandscape90() = assertRails(UiAutomation.ROTATION_FREEZE_90, Surface.ROTATION_90, board3D = false)

    @Test
    fun rails2DInLandscape270() = assertRails(UiAutomation.ROTATION_FREEZE_270, Surface.ROTATION_270, board3D = false)

    private fun assertRails(freeze: Int, rotation: Int, board3D: Boolean) {
        uiAutomation.setRotation(freeze)
        rule.waitUntil(timeoutMillis = 10_000) { rule.activity.display.rotation == rotation }
        val viewModel = GameViewModel()
        // Hint only shows with an engine attached, Explain only with a coach: both stand-ins.
        viewModel.attachEngine(object : ChessEngine {
            override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? = null
            override fun close() {}
        })
        val coach = MoveCoachManager(viewModel)
        rule.setContent {
            CompositionLocalProvider(LocalMoveCoachManager provides coach) {
                MyApplicationTheme {
                    GameScreen(
                        WindowWidthSizeClass.Expanded,
                        viewModel,
                        board3D = if (board3D) fakeBoard3DSupport() else null,
                    )
                }
            }
        }
        rule.waitForIdle()
        try {
            assertRailPositions(rotation, board3D)
        } finally {
            coach.close()
            viewModel.close()
        }
    }

    private fun assertRailPositions(rotation: Int, board3D: Boolean) {
        val decor = rule.activity.window.decorView
        val insets = decor.rootWindowInsets
        val cutouts = insets.displayCutout?.boundingRects.orEmpty()
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        val near = 14 * decor.resources.displayMetrics.density // the rail's 6dp margin, plus slack
        val where = "rotation=$rotation 3D=$board3D cutouts=$cutouts bars=$bars"

        fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        val board = if (board3D) null else bounds("chess_board")

        listOf(navButtons to false, gameButtons to true).forEach { (tags, endRail) ->
            val buttons = tags.map { it to bounds(it) }
            val hugs = { b: Rect -> if (endRail) decor.width - bars.right - b.right <= near else b.left - bars.left <= near }
            // The outer column is the buttons against the edge; it is a prefix of the rail, and
            // whatever follows it is the inner column.
            val outer = buttons.takeWhile { (_, b) -> hugs(b) }
            val inner = buttons.drop(outer.size)
            val top = buttons.first().second.top
            assertTrue("$tags: the rail starts under the status bar ($where)", top >= bars.top && top - bars.top <= near)
            assertTrue("$tags: the outer column holds the first button ($where)", outer.isNotEmpty())

            listOf(outer, inner).filter { it.isNotEmpty() }.forEach { column ->
                assertTrue("${column.first().first} starts the column at the top ($where)", column.first().second.top == top)
                column.zipWithNext().forEach { (above, below) ->
                    assertTrue("${above.first} sits above ${below.first} ($where)", above.second.bottom <= below.second.top)
                }
            }
            if (inner.isNotEmpty()) {
                val sideCutout = cutouts.any { if (endRail) it.centerX() > decor.width / 2 else it.centerX() < decor.width / 2 }
                assertTrue("$tags wrapped with no cutout on their side ($where)", sideCutout)
                val outerSpan = outer.minOf { it.second.left }..outer.maxOf { it.second.right }
                inner.forEach { (tag, b) ->
                    val clear = if (endRail) b.right <= outerSpan.first else b.left >= outerSpan.last
                    assertTrue("$tag $b sits inside the outer column $outerSpan ($where)", clear)
                    assertTrue("$tag $b stays hugging its column, not drifting to mid-screen ($where)",
                        if (endRail) outerSpan.first - b.right <= near else b.left - outerSpan.last <= near)
                }
            }
            buttons.forEach { (tag, b) ->
                cutouts.forEach { assertTrue("$tag $b overlaps the cutout $it ($where)", !Rect.intersects(b, it)) }
                if (board != null) assertTrue("$tag $b overlaps the board $board ($where)", !Rect.intersects(b, board))
            }
        }
    }
}
