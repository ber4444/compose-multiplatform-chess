package com.example.myapplication.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.SavedGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/** Holds only the most recently fetched page for this signed-in account. */
internal class LichessHistoryCache {
    private var owner: String? = null
    private var rows: List<LichessArchiveEntry> = emptyList()

    fun read(accountId: String): List<LichessArchiveEntry> {
        if (owner != accountId) {
            owner = accountId
            rows = emptyList()
        }
        return rows
    }

    fun write(accountId: String, entries: List<LichessArchiveEntry>) {
        if (owner == accountId) rows = entries.distinctBy(LichessArchiveEntry::id)
    }
}

@Composable
internal fun LichessHistory(
    profile: LichessProfile,
    client: LichessArchiveClient,
    cache: LichessHistoryCache,
    localCopies: List<SavedGame>,
    repository: GameHistoryRepository,
    onOpen: (SavedGame) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scope = androidx.compose.runtime.key(profile.id) { rememberCoroutineScope() }
    var entries by remember(profile.id) { mutableStateOf(cache.read(profile.id)) }
    var loading by remember(profile.id) { mutableStateOf(false) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    var openJob by remember(profile.id) { mutableStateOf<Job?>(null) }
    var refresh by remember(profile.id) { mutableStateOf(0) }
    LaunchedEffect(profile.id, refresh) {
        loading = true
        try {
            cache.write(profile.id, client.recentFinished(profile.username).toList())
            entries = cache.read(profile.id)
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Could not refresh Lichess games."
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Lichess games", style = MaterialTheme.typography.titleMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
            TextButton(onClick = { uriHandler.openUri("$LICHESS_BASE_URL/@/${profile.username}/all") }) {
                Text("Full history on Lichess")
            }
        }
        val downloaded = localCopies.mapNotNull { game -> game.lichessGameId?.let { it to game } }.toMap()
        entries.filterNot { it.id in downloaded }.forEach { entry ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    openJob?.cancel()
                    openJob = scope.launch {
                        try {
                            onOpen(entry.asSavedGame(client.exportPgn(entry.id)))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Could not load this PGN from Lichess."
                        }
                    }
                }) { Text("${entry.white.displayName} vs ${entry.black.displayName}") }
                TextButton(onClick = {
                    openJob?.cancel()
                    openJob = scope.launch {
                        try {
                            repository.add(entry.asSavedGame(client.exportPgn(entry.id)))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Could not save an offline copy."
                        }
                    }
                }) { Text("Save offline") }
            }
        }
    }
}

private fun LichessArchiveEntry.asSavedGame(pgn: String): SavedGame {
    val result = when (winner) {
        "white" -> "1-0"
        "black" -> "0-1"
        else -> "1/2-1/2"
    }
    return SavedGame("lichess-$id", createdAt, result, white.displayName, black.displayName, moveCount, pgn, lichessGameId = id)
}
