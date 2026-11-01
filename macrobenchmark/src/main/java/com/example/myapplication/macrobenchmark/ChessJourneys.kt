package com.example.myapplication.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val CHESS_PACKAGE = "io.github.ber4444.chess"
private const val UI_TIMEOUT_MS = 15_000L

/** Avoid gfxinfo-based startActivityAndWait: Filament uses a SurfaceView, not HWUI frames. */
internal fun MacrobenchmarkScope.launchChess() {
    val context = InstrumentationRegistry.getInstrumentation().context
    val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(CHESS_PACKAGE))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    device.awaitBoard()
}

internal fun UiDevice.awaitBoard() {
    check(wait(Until.hasObject(By.res("open_settings_button")), UI_TIMEOUT_MS)) {
        "Chess navigation did not appear"
    }
    // Either board. A whole-name pattern, so the 3D loading overlay (`board_3d_entering`) does not
    // count as the board having appeared.
    check(wait(Until.hasObject(By.res(Pattern.compile("board_3d|chess_board"))), UI_TIMEOUT_MS)) {
        "Neither chess board appeared"
    }
    check(wait(Until.gone(By.res("board_3d_entering")), UI_TIMEOUT_MS)) {
        "3D board did not finish entering"
    }
    waitForIdle()
}

internal fun UiDevice.clickTag(tag: String) {
    val node = wait(Until.findObject(By.res(tag)), UI_TIMEOUT_MS)
    checkNotNull(node) { "Missing UI element: $tag" }.click()
    waitForIdle()
}

/** Reset asks first, and while its dialog is up the board under it is not in UiAutomator's window. */
internal fun UiDevice.resetGame() {
    clickTag("reset_button")
    checkNotNull(wait(Until.findObject(By.text("Yes")), UI_TIMEOUT_MS)) {
        "Reset confirmation did not appear"
    }.click()
    waitForIdle()
}

/** Repeatable offline paths. Leaves the default 3D board and a fresh game ready for the next run. */
internal fun UiDevice.playAndNavigate() {
    clickTag("open_settings_button")
    val toggle = wait(Until.findObject(By.res("settings_board_3d")), UI_TIMEOUT_MS)
    checkNotNull(toggle) { "3D board setting is missing" }
    // Settings row exposes the label; its Switch child carries the checked state.
    val switch = toggle.findObject(By.checkable(true)) ?: toggle
    if (switch.isChecked) switch.click()
    pressBack()
    check(wait(Until.hasObject(By.res("chess_board")), UI_TIMEOUT_MS))
    resetGame()
    clickTag("board_square_WhitePiece_6_4") // e2
    clickTag("board_square_PossibleMove_4_4") // e4, then the engine reply
    check(wait(Until.hasObject(By.res("board_square_WhitePiece_4_4")), UI_TIMEOUT_MS))
    clickTag("open_history_button")
    check(wait(Until.hasObject(By.text("Game History")), UI_TIMEOUT_MS))
    pressBack()
    clickTag("open_rules_button")
    check(wait(Until.hasObject(By.text("Chess rules")), UI_TIMEOUT_MS))
    pressBack()
    resetGame()
    clickTag("open_settings_button")
    val row = checkNotNull(wait(Until.findObject(By.res("settings_board_3d")), UI_TIMEOUT_MS))
    val boardSwitch = row.findObject(By.checkable(true)) ?: row
    if (!boardSwitch.isChecked) boardSwitch.click()
    pressBack()
    awaitBoard()
}
