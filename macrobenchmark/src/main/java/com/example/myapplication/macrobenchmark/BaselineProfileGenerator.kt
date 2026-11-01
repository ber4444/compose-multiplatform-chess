package com.example.myapplication.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = CHESS_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        launchChess()
    }

    @Test
    fun boardAndNavigation() = rule.collect(
        packageName = CHESS_PACKAGE,
        includeInStartupProfile = false,
    ) {
        pressHome()
        launchChess()
        device.playAndNavigate()
    }
}
