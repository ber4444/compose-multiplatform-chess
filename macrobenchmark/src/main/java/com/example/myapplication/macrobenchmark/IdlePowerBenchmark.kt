package com.example.myapplication.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.PowerCategory
import androidx.benchmark.macro.PowerCategoryDisplayLevel
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the 3D board keep the app rendering while nothing on screen changes?
 *
 * The defect is a **rate**: SceneView's `Scene`/`SceneView` composable drives its render loop from
 * an unconditional `withFrameNanos` await, so an untouched board still does a full frame's worth of
 * work every vsync. The metric that demonstrates that is therefore a frame *count* over a fixed
 * idle window, not a frame duration.
 *
 * `FrameTimingMetric` is deliberately **not** in the list, and the reason is specific to this app
 * rather than a matter of taste. SceneView renders into a `SurfaceView` through Filament's own
 * threads, so the app has no HWUI RenderThread frames at all while the 3D board is up;
 * `FrameTimingMetric` fails outright with "Observed no renderthread slices in trace". It also could
 * not have shown the defect even if it had worked — it reports how *long* each frame took, which
 * stays perfectly healthy precisely because the wasted frames are cheap.
 *
 * [IDLE_FRAME_LABEL] is the number to watch. Measured on a 120 Hz SM-F926U before any fix: 1202
 * frames per 10 s window, i.e. the full display refresh rate on a board nobody is touching. A board
 * that idles correctly should approach zero. Everything else here exists to keep that count honest:
 *
 *  - the measured window contains **no** app launch (see [setupBlock] below), because startup
 *    energy and startup frames dwarf anything an idle screen does;
 *  - the measured window contains no input, because the claim under test is about an untouched
 *    board.
 *
 * The benchmark assumes the 3D board is the visible one. That is the shipped default
 * (`AppSettings.board3DEnabled`, default true), so a fresh install lands on it.
 *
 * One caveat when reading the reported figure: `TraceSectionMetric` counts matching slices over the
 * **whole iteration trace**, not only over `measureBlock`, so it also picks up the frames drawn
 * during setup. On the runs above that is a ~4% overcount — reported `idleFrameCount` 1250..1269
 * against 1201..1202 actually inside the window. It does not affect the before/after comparison,
 * which is what this benchmark is for, but if you need the exact in-window number, query the trace:
 *
 * ```sql
 * SELECT count(*) FROM slice s
 *   JOIN thread_track tt ON s.track_id = tt.id
 *   JOIN thread t USING(utid) JOIN process p USING(upid)
 *  WHERE p.name = 'io.github.ber4444.chess'
 *    AND s.name GLOB 'Choreographer#doFrame*'
 *    AND s.ts >= (SELECT ts       FROM slice WHERE name = 'measureBlock' LIMIT 1)
 *    AND s.ts <  (SELECT ts + dur FROM slice WHERE name = 'measureBlock' LIMIT 1);
 * ```
 */
@RunWith(AndroidJUnit4::class)
class IdlePowerBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun idleBoard() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = idleMetrics(),
            iterations = ITERATIONS,
            // No `startupMode`. StartupMode.COLD would put a process launch, Filament/SceneView
            // init and the model load *inside* the measured window; against a 10 s idle window
            // that startup cost is the measurement. Launching in setupBlock instead means the
            // window opens on an already-warm, already-drawn board.
            setupBlock = {
                // Home first, so the following launch always resumes the activity rather than
                // finding it already foregrounded.
                pressHome()
                // Deliberately NOT MacrobenchmarkScope.startActivityAndWait(). That confirms
                // launch by polling `dumpsys gfxinfo <pkg> framestats`, which reports HWUI frames
                // — and this app has none while the 3D board is up: SceneView draws into a
                // SurfaceView through Filament's own threads, so HWUI never records a frame for
                // it. On a Pixel 7a / Android 17 that means framestats stays empty and the helper
                // fails the run outright with "Unable to confirm activity launch completion
                // [... lastFrameNs=null]". It is the same blind spot that rules out
                // FrameTimingMetric here (see the class KDoc), and it gets worse, not better,
                // once the render loop is fixed and the board genuinely stops producing frames.
                //
                // UiAutomator observes the window instead, which is true for both boards on
                // every device this has run on.
                launchTargetApp()
                device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)
                // Let the entry choreography (3D surface mount, model load, board transition)
                // finish before measuring, so its frames are not counted as idle frames.
                device.waitForIdle(UI_TIMEOUT_MS)
                Thread.sleep(SETTLE_MS)
            }
        ) {
            // Deliberately empty apart from the wait: the board is untouched for the whole window.
            Thread.sleep(IDLE_WINDOW_MS)
        }
    }

    /**
     * Cold-neutral launch of the app under test through its launcher intent, using the
     * instrumentation's own context. Returns as soon as the intent is dispatched; readiness is
     * established by the UiAutomator wait at the call site.
     */
    private fun launchTargetApp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)) {
            "$TARGET_PACKAGE has no launcher activity — is the app installed for this user?"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private companion object {
        const val TARGET_PACKAGE = "io.github.ber4444.chess"

        const val ITERATIONS = 5

        /** Length of the measured idle window. All frame counts below are "per this window". */
        const val IDLE_WINDOW_MS = 10_000L

        /** Time given to post-launch animation/asset work before the window opens. */
        const val SETTLE_MS = 2_000L

        const val UI_TIMEOUT_MS = 5_000L

        /** Main-thread frame dispatch. One slice per frame the app chose to render. */
        const val IDLE_FRAME_LABEL = "idleFrame"

        /** Buffer production. Counts frames that actually reached the compositor. */
        const val IDLE_DRAW_LABEL = "idleDraw"
    }

    /**
     * Metric set for the idle window.
     *
     * [PowerMetric] is the part that has to be chosen at runtime rather than hard-coded:
     * `PowerMetric.Type.Energy` reads Android's ODPM power rails, which in practice means Pixel 6
     * and later. On a device without them `dumpsys powerstats` reports no channels and the metric
     * throws during configuration, failing the whole benchmark — including on the Samsung this was
     * developed against. So the power metric degrades in two steps and, if neither step is
     * available, is simply left out: the trace-section counts below are what actually demonstrate
     * the bug, and losing power telemetry must not cost us them.
     */
    @OptIn(ExperimentalMetricApi::class)
    private fun idleMetrics(): List<Metric> = buildList {
        // Primary signal. Mode.Count, not Sum: the question is "how many frames", not "how long".
        // The trailing `%` is required — Choreographer tags each slice with its vsync id
        // ("Choreographer#doFrame 129943"), so an exact match finds nothing.
        add(
            TraceSectionMetric(
                sectionName = "Choreographer#doFrame%",
                mode = TraceSectionMetric.Mode.Count,
                label = IDLE_FRAME_LABEL,
                targetPackageOnly = true
            )
        )
        // Corroborates the above from the far end of the pipeline: one dequeueBuffer per buffer the
        // app actually produced for the compositor. If doFrame stays high while this collapses the
        // app is waking up without drawing, which would be a different bug.
        add(
            TraceSectionMetric(
                sectionName = "dequeueBuffer",
                mode = TraceSectionMetric.Mode.Count,
                label = IDLE_DRAW_LABEL,
                targetPackageOnly = true
            )
        )
        powerMetricOrNull()?.let(::add)
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun powerMetricOrNull(): Metric? = when {
        // ODPM rails. Note the library's own error text points at a `deviceSupportsPowerEnergy()`
        // that does not exist in benchmark-macro — the real predicate is this one, and all it does
        // is grep `dumpsys powerstats` for `ChannelId:`. It has nothing to do with being plugged in.
        PowerMetric.deviceSupportsHighPrecisionTracking() ->
            // Categories are NOT optional in practice. `Type.Energy()` defaults to an empty map,
            // and PowerMetric emits one metric per requested category — so the default reports
            // *nothing at all*, silently. That is not a device limitation and it is not visible in
            // the output; it just looks like a device with no power support. Ask for every
            // category: rails the device does not expose are simply absent from the result.
            PowerMetric(
                PowerMetric.Type.Energy(
                    PowerCategory.entries.associateWith { PowerCategoryDisplayLevel.TOTAL }
                )
            )

        // Whole-device battery delta. Coarse, and it needs enough charge that the library's own
        // guard (>= 50%) lets the run start; PowerMetric suspends USB charging itself for the
        // duration, so staying plugged in for adb is fine.
        PowerMetric.deviceBatteryHasMinimumCharge() ->
            PowerMetric(PowerMetric.Type.Battery())

        else -> null
    }
}
