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
 * Surface: the 3D-board toggle, engine difficulty, the AI Move Coach toggle and the player side —
 * all observable. Theme is not persisted: it always follows the system dark-mode setting.
 */
class AppSettings(internal val settings: Settings) {

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

    private val _aiCoachEnabled: MutableStateFlow<Boolean> =
        MutableStateFlow(readAiCoachEnabled())
    val aiCoachEnabled: StateFlow<Boolean> get() = _aiCoachEnabled

    fun setAiCoachEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AI_COACH, enabled)
        _aiCoachEnabled.value = enabled
    }

    private fun readAiCoachEnabled(): Boolean = settings.getBoolean(KEY_AI_COACH, true)

    private val _playerSide: MutableStateFlow<String> =
        MutableStateFlow(readPlayerSide())
    val playerSide: StateFlow<String> get() = _playerSide

    fun setPlayerSide(side: String) {
        settings.putString(KEY_PLAYER_SIDE, side)
        _playerSide.value = side
    }

    private fun readPlayerSide(): String = settings.getString(KEY_PLAYER_SIDE, "WHITE")

    /**
     * Who plays the engine side. **Maia-3 by default**, and Stockfish only once the user picks it:
     * nothing is written until they choose, so an untouched install follows the default. Where the
     * build cannot run Maia (iOS, web), `AppRoot` ignores [Opponent.MAIA] and Stockfish plays, and
     * Settings shows no choice. Strength is [engineDifficulty] for either opponent;
     * `RatingBand.forDifficulty` maps it to Maia's rating.
     */
    private val _opponent: MutableStateFlow<Opponent> = MutableStateFlow(readOpponent())
    val opponent: StateFlow<Opponent> get() = _opponent

    fun setOpponent(opponent: Opponent) {
        settings.putString(KEY_OPPONENT, opponent.name)
        _opponent.value = opponent
    }

    private fun readOpponent(): Opponent =
        settings.getStringOrNull(KEY_OPPONENT)?.let { runCatching { Opponent.valueOf(it) }.getOrNull() }
            ?: Opponent.MAIA

    companion object {
        const val KEY_OPPONENT = "settings.opponent"
        const val KEY_BOARD_3D = "settings.board_3d_enabled"
        const val KEY_ENGINE_DIFFICULTY = "settings.engine_difficulty"
        const val KEY_AI_COACH = "settings.ai_coach_enabled"
        const val KEY_PLAYER_SIDE = "settings.player_side"
    }
}


/** Who plays against you in solo games. */
enum class Opponent { STOCKFISH, MAIA }
