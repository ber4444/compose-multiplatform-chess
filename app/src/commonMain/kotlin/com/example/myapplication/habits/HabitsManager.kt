package com.example.myapplication.habits

import com.example.myapplication.persistence.GameHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Cross-game habit aggregation (B6/RAG-5) — the Android Pro story now that no on-device model
 * exists there (`android-model-latency-2026-08.md`). Recomputes [summaries] from
 * [GameHistoryRepository.games] on every change.
 *
 * Unlike [com.example.myapplication.movecoach.MoveCoachManager]/
 * [com.example.myapplication.movecoach.GameSummaryManager], this needs no platform runtime, model,
 * or engine — [HabitAggregator] is a pure function over data already persisted — so there is nothing
 * to attach and no `Unavailable` state: the manager is either constructed (there's a game history to
 * read) or not constructed at all, mirroring how [AppRoot][com.example.myapplication.AppRoot]
 * already treats `gameHistory: GameHistoryRepository?` as nullable per entry point.
 *
 * A plain class with its own [CoroutineScope], not an androidx `ViewModel` — same pattern as
 * [com.example.myapplication.GameViewModel] and every other manager in this app. Callers must call
 * [close].
 */
class HabitsManager(
    gameHistory: GameHistoryRepository,
    window: Int = HabitAggregator.DEFAULT_WINDOW,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _summaries = MutableStateFlow<List<HabitSummary>>(emptyList())
    val summaries: StateFlow<List<HabitSummary>> = _summaries

    init {
        gameHistory.games
            .onEach { games -> _summaries.value = HabitAggregator.aggregate(games, window) }
            .launchIn(scope)
    }

    fun close() {
        scope.cancel()
    }
}
