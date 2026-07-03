package com.example.myapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.SavedGame
import com.example.myapplication.share.PgnSharer

/**
 * Lists saved games (newest first) from [gameHistory]. Tapping a row opens an inline detail view
 * with the full PGN (selectable) and **Share** / **Delete** actions. Empty state when no games.
 *
 * Reached from [AppRoot] via `Screen.HISTORY`. The detail view is a local `selectedGame` state
 * rather than a new [Screen] value to keep history navigation shallow.
 */
@Composable
fun GameHistoryScreen(
    gameHistory: GameHistoryRepository,
    pgnSharer: PgnSharer? = null,
    onBack: () -> Unit,
) {
    val games by gameHistory.games.collectAsState()
    var selectedGame by remember { mutableStateOf<SavedGame?>(null) }

    val current = selectedGame
    SubScreenScaffold(title = "Game History", onBack = onBack, scrollable = current != null) {
        if (current == null) {
            GameHistoryList(
                games = games,
                pgnSharer = pgnSharer,
                onOpen = { selectedGame = it },
                onDelete = { gameHistory.delete(it.id) },
            )
        } else {
            GameHistoryDetail(
                game = current,
                pgnSharer = pgnSharer,
                onBack = { selectedGame = null },
            )
        }
    }
}

@Composable
private fun GameHistoryList(
    games: List<SavedGame>,
    pgnSharer: PgnSharer?,
    onOpen: (SavedGame) -> Unit,
    onDelete: (SavedGame) -> Unit,
) {
    if (games.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No saved games yet. Finish a game and tap “Save game” to add one.",
                modifier = Modifier.testTag("history_empty"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    // Each row: date, result, players, move count. The whole card is selectable.
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(games, key = { it.id }) { game ->
            GameHistoryRow(
                game = game,
                canShare = pgnSharer != null,
                onOpen = { onOpen(game) },
                onShare = { pgnSharer?.share(game.pgn, "game-${game.result}.pgn") },
                onDelete = { onDelete(game) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun GameHistoryRow(
    game: SavedGame,
    canShare: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("history_row_${game.id}")
            .selectable(selected = false, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "${game.white} vs ${game.black}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Result: ${game.result}  •  ${game.moveCount} moves",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onOpen, modifier = Modifier.testTag("history_view_${game.id}")) {
                    Text("View")
                }
                if (canShare) {
                    Button(
                        onClick = onShare,
                        modifier = Modifier.testTag("history_share_${game.id}"),
                    ) { Text("Share") }
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.testTag("history_delete_${game.id}"),
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun GameHistoryDetail(
    game: SavedGame,
    pgnSharer: PgnSharer?,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text("Back") }
            Text(
                "${game.white} vs ${game.black}  •  ${game.result}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(modifier = Modifier.padding(6.dp))
        // The PGN text. heightIn(min) keeps it scrollable/visible on small screens; the surfaceVariant
        // card makes the selectable text block visually distinct.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = game.pgn,
                modifier = Modifier
                    .padding(12.dp)
                    .heightIn(min = 200.dp)
                    .testTag("history_detail_pgn"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.padding(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pgnSharer != null) {
                Button(
                    onClick = { pgnSharer.share(game.pgn, "game-${game.result}.pgn") },
                    modifier = Modifier.testTag("history_detail_share"),
                ) { Text("Share PGN") }
            }
        }
    }
}
