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
 * Each rail button must start just under the status bar, keep within a few dp of its side's edge,
 * and never touch a cutout: a button in the cutout's rows steps inward past it, the rest stay put.
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
        val density = decor.resources.displayMetrics.density
        val near = 14 * density // the rail's 6dp margin, plus slack for rounding
        val where = "rotation=$rotation 3D=$board3D cutouts=$cutouts bars=$bars"

        fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        fun sharesRowsWithCutout(b: Rect) = cutouts.any { b.top < it.bottom + near && b.bottom > it.top - near }

        listOf(navButtons, gameButtons).forEach { rail ->
            val first = bounds(rail.first())
            assertTrue("${rail.first()} $first starts under the status bar ($where)", first.top >= bars.top)
            assertTrue("${rail.first()} $first starts right under the status bar ($where)", first.top - bars.top <= near)
        }
        (navButtons + gameButtons).forEach { tag ->
            val b = bounds(tag)
            cutouts.forEach { cutout ->
                assertTrue("$tag $b overlaps the cutout $cutout ($where)", !Rect.intersects(b, cutout))
            }
        }
        navButtons.map { it to bounds(it) }.filterNot { (_, b) -> sharesRowsWithCutout(b) }.forEach { (tag, b) ->
            assertTrue("$tag $b hugs the left edge ($where)", b.left - bars.left <= near)
        }
        gameButtons.map { it to bounds(it) }.filterNot { (_, b) -> sharesRowsWithCutout(b) }.forEach { (tag, b) ->
            assertTrue("$tag $b hugs the right edge ($where)", decor.width - bars.right - b.right <= near)
        }
    }
}
