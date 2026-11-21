package com.example.myapplication.explorer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.MoveRecord
import kotlin.math.roundToInt

/**
 * "What do players at this level play here?" for a finished game, one position at a time.
 *
 * Lives in the History detail only, a review context: what humans play is assistance while a game
 * is still going.
 *
 * The caller decides availability ([HumanMoveExplorer.canAnswer]); a game without move records
 * renders nothing.
 */
@Composable
fun HumanMovesPanel(
    records: List<MoveRecord>,
    explorer: HumanMoveExplorer,
    initialBand: RatingBand,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) return
    var ply by remember(records) { mutableIntStateOf(0) }
    var band by remember(initialBand) { mutableStateOf(initialBand) }
    val position = remember(records, ply) { ExplorerPosition.beforePly(records, ply) }
    val answer by produceState<ExplorerAnswer?>(null, explorer, position, band) {
        value = null
        value = explorer.explore(position, band)
    }
    val played = records[ply]

    Card(
        modifier = modifier.fillMaxWidth().testTag("human_moves_panel"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What players at this level play", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { ply-- },
                    enabled = ply > 0,
                    modifier = Modifier.testTag("human_moves_prev"),
                ) { Text("Previous") }
                Text(
                    "${ply / 2 + 1}${if (ply % 2 == 0) "." else "…"} played ${played.san}",
                    modifier = Modifier.weight(1f).testTag("human_moves_position"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { ply++ },
                    enabled = ply < records.lastIndex,
                    modifier = Modifier.testTag("human_moves_next"),
                ) { Text("Next") }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RatingBand.entries.forEach { option ->
                    FilterChip(
                        selected = option == band,
                        onClick = { band = option },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("human_moves_band_${option.name}"),
                    )
                }
            }
            when (val current = answer) {
                null -> CircularProgressIndicator(Modifier.size(24.dp).testTag("human_moves_loading"))
                ExplorerAnswer.NoAnswer -> Text(
                    "No data for this position.",
                    modifier = Modifier.testTag("human_moves_empty"),
                    style = MaterialTheme.typography.bodySmall,
                )
                is ExplorerAnswer.Moves -> MoveList(current, played.uci)
            }
        }
    }
}

@Composable
private fun MoveList(answer: ExplorerAnswer.Moves, playedUci: String) {
    val stats = answer.stats
    Text(
        sourceText(stats),
        modifier = Modifier.testTag("human_moves_source"),
        style = MaterialTheme.typography.labelMedium,
    )
    stats.moves.forEach { move ->
        val isPlayed = move.uci == playedUci
        Row(Modifier.fillMaxWidth().testTag("human_moves_row_${move.uci}")) {
            Text(
                move.san + if (isPlayed) " (played)" else "",
                modifier = Modifier.weight(1f),
                fontWeight = if (isPlayed) FontWeight.Bold else null,
            )
            Text("${(move.share * 100).roundToInt()}%")
        }
    }
    if (stats.moves.none { it.uci == playedUci }) {
        Text(
            "The move played is not among these.",
            modifier = Modifier.testTag("human_moves_played_absent"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun sourceText(stats: HumanMoveStats): String = when (stats.source) {
    HumanMoveSource.MAIA -> "Maia-3 prediction for players rated about ${stats.band.maiaRating}"
}
