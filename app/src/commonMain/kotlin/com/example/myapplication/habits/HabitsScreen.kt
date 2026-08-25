package com.example.myapplication.habits

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.SubScreenScaffold

/**
 * Cross-game habits (B6/RAG-5). Reached from [AppRoot][com.example.myapplication.AppRoot] via
 * `Screen.HABITS`, Pro-gated the same way Rules Q&A is — this screen supplies its own
 * [SubScreenScaffold], so the gate branches in `AppRoot` rather than wrapping in `ProGate`.
 */
@Composable
fun HabitsScreen(manager: HabitsManager, onBack: () -> Unit) {
    val summaries by manager.summaries.collectAsState()
    SubScreenScaffold(title = "Habits", onBack = onBack) {
        if (summaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Not enough finished games yet to spot a pattern. Play and save a few more — " +
                        "habits need several assessed games before one repeats often enough to name.",
                    modifier = Modifier.testTag("habits_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                summaries.forEachIndexed { index, summary ->
                    HabitCard(summary, modifier = Modifier.testTag("habit_card_$index"))
                }
            }
        }
    }
}

@Composable
private fun HabitCard(summary: HabitSummary, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(HabitNarrator.headline(summary), style = MaterialTheme.typography.titleMedium)
            Text(
                HabitNarrator.explanation(summary),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            summary.occurrences.forEach { occurrence ->
                Text(
                    HabitNarrator.occurrenceLine(occurrence),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                // The exact position, so the player can set it up and try the alternative
                // themselves — the "suggest a practice position" half of B6. Plain text, matching
                // GameHistoryDetail's PGN block: this app has no FEN-import/practice-board flow to
                // hand it to yet, so displaying it is the honest scope for this feature.
                Text(
                    occurrence.fenBefore,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}
