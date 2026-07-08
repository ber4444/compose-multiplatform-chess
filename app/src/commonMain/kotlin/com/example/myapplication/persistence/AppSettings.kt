package com.example.myapplication.persistence

import com.example.myapplication.EngineDifficulty
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed, observable view over a russhwolf [Settings] instance. Plain class (mirrors
 * [com.example.myapplication.GameViewModel] — not an androidx ViewModel), constructed at the
 * platform entry point and threaded into `AppRoot`. Holds [MutableStateFlow]s seeded from settings
 * and writes through on every setter.
 *
 * Surface: 3D-board-enabled toggle + engine difficulty. The persisted theme override was removed
 * (theme now always follows the system dark-mode setting).
 */
class AppSettings(private val settings: Settings) {

    /**
     * Whether the 3D board is shown (vs the 2D board). Defaults to `true` on first install. Read by
     * `SettingsScreen`'s Switch and observed by `GameScreen` to drive the mount/teardown of the 3D
     * surface. The runtime `viewState.show3D` in [GameViewModel] is seeded from this value at
     * construction; toggling it re-runs GameScreen's entry/teardown frame choreography.
     */
    private val _board3DEnabled: MutableStateFlow<Boolean> =
        MutableStateFlow(readBoard3DEnabled())
    val board3DEnabled: StateFlow<Boolean> get() = _board3DEnabled

    fun setBoard3DEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_BOARD_3D, enabled)
        _board3DEnabled.value = enabled
    }

    private fun readBoard3DEnabled(): Boolean = settings.getBoolean(KEY_BOARD_3D, true)

    /**
     * Engine play-strength (issue #39 Phase 4). Defaults to [EngineDifficulty.MEDIUM]. Read by
     * `SettingsScreen`'s radio group; applied to the attached engine via
     * [com.example.myapplication.GameViewModel.attachEngine]/`setEngineDifficulty`.
     */
    private val _engineDifficulty: MutableStateFlow<EngineDifficulty> =
        MutableStateFlow(readEngineDifficulty())
    val engineDifficulty: StateFlow<EngineDifficulty> get() = _engineDifficulty

    fun setEngineDifficulty(difficulty: EngineDifficulty) {
        settings.putString(KEY_ENGINE_DIFFICULTY, difficulty.name)
        _engineDifficulty.value = difficulty
    }

    private fun readEngineDifficulty(): EngineDifficulty =
        settings.getStringOrNull(KEY_ENGINE_DIFFICULTY)
            ?.let { runCatching { EngineDifficulty.valueOf(it) }.getOrNull() }
            ?: EngineDifficulty.MEDIUM

    companion object {
        const val KEY_BOARD_3D = "settings.board_3d_enabled"
        const val KEY_ENGINE_DIFFICULTY = "settings.engine_difficulty"
    }
}

