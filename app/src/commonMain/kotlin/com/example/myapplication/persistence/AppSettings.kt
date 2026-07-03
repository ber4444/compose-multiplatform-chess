package com.example.myapplication.persistence

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed, observable view over a russhwolf [Settings] instance. Plain class (mirrors
 * [com.example.myapplication.GameViewModel] — not an androidx ViewModel), constructed at the
 * platform entry point and threaded into `AppRoot`. Holds [MutableStateFlow]s seeded from settings
 * and writes through on every setter.
 *
 * Surface: 3D-board-enabled toggle. The persisted theme override was removed (theme now always
 * follows the system dark-mode setting). Engine difficulty (Phase 4) will reuse the same pattern.
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

    companion object {
        const val KEY_BOARD_3D = "settings.board_3d_enabled"
    }
}
