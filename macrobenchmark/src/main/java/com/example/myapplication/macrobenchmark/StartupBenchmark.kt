package com.example.myapplication.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// TraceSectionMetric is experimental; the opt-in is scoped to the one class that measures sections.
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldNoCompilation() = coldStartup(CompilationMode.None())

    // Require fails the test when the APK lacks a usable bundled Baseline Profile.
    @Test fun coldWithBaselineProfile() = coldStartup(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0)
    )

    private fun coldStartup(compilation: CompilationMode) = rule.measureRepeated(
        packageName = CHESS_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            // Mode.First: chessStartupTrace emits every section twice (AndroidX Tracer + atrace),
            // and the default Mode.Sum reported each one at double its real length.
            TraceSectionMetric("Chess.startStockfish", TraceSectionMetric.Mode.First),
            TraceSectionMetric("Chess.restoreGame", TraceSectionMetric.Mode.First),
            TraceSectionMetric("Chess.createGameViewModel", TraceSectionMetric.Mode.First),
        ),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        launchChess()
    }
}
