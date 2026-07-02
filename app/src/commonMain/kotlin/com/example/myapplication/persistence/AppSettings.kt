package com.example.myapplication.persistence

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Typed, observable view over a russhwolf [Settings] instance. Plain class (mirrors
 * [com.example.myapplication.GameViewModel] — not an androidx ViewModel), constructed at the
 * platform entry point and threaded into `AppRoot`. Holds [MutableStateFlow]s seeded from settings
 * and writes through on every setter.
 *
 * Phase 0 surface: theme mode only. Engine-difficulty (Phase 4) and time-control (Phase 5) will
 * reuse the same pattern.
 */
class AppSettings(private val settings: Settings) {

    private val _themeMode: MutableStateFlow<ThemeMode> =
        MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> get() = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME, mode.name)
        _themeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode =
        settings.getStringOrNull(KEY_THEME)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM

    companion object {
        const val KEY_THEME = "settings.theme_mode"
    }
}
