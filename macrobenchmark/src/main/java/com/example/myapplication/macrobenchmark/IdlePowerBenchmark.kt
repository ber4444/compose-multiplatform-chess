package com.example.myapplication.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import androidx.benchmark.macro.FrameTimingMetric

@RunWith(AndroidJUnit4::class)
class IdlePowerBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun benchmarkIdlePower() {
        benchmarkRule.measureRepeated(
            packageName = "io.github.ber4444.chess",
            metrics = listOf(
                FrameTimingMetric(),
                PowerMetric(PowerMetric.Type.Energy())
            ),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = {
                pressHome()
            }
        ) {
            startActivityAndWait()
            
            // Wait for the main board screen to be visible.
            // Using a simple timeout or waiting for a specific node if known.
            // The default UI starts immediately on the board.
            device.wait(Until.hasObject(By.pkg("io.github.ber4444.chess").depth(0)), 5000)

            // Idle for 10 seconds to measure power usage on the static board.
            Thread.sleep(10000)
        }
    }
}
