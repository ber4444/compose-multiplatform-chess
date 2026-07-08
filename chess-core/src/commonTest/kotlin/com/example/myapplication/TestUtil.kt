package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Real-time wait for a state change produced by the ViewModel's own (non-test) scope.
 *  withContext(Dispatchers.Default) escapes runTest's virtual time. */
suspend fun GameViewModel.awaitState(timeoutMs: Long = 5_000, predicate: (GameUiState) -> Boolean): GameUiState =
    withContext(Dispatchers.Default) { withTimeout(timeoutMs) { gameState.first(predicate) } }
