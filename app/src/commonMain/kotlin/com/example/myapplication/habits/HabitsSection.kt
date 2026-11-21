package com.example.myapplication.habits

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Cross-game habits (B6/RAG-5), rendered as the top section of the History screen's game list
 * rather than as a screen of their own: habits are a reading of the saved games, so they live
 * with them, and the game screen's menu row needs no extra button.
 *
 * Emitted into the History list's `LazyColumn`, never wrapped in a scrolling container of its own.
 * Same-direction nested scrolling throws at measure time ("measured with an infinity maximum
 * height"), which is the crash the original standalone Habits screen shipped with.
 */
internal fun LazyListScope.habitsSection(summaries: List<HabitSummary>) {
    item(key = "habits_header", contentType = "section_header") {
        SectionHeader("Habits", Modifier.testTag("habits_header"))
    }
    if (summaries.isEmpty()) {
        item(key = "habits_empty", contentType = "habits_empty") {
            Text(
                "No habit yet. A habit appears once the same pattern repeats across several " +
                    "assessed games, so save a few more to see yours.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).testTag("habits_empty"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        itemsIndexed(summaries, key = { index, _ -> "habit_$index" }, contentType = { _, _ -> "habit" }) { index, summary ->
            HabitCard(summary, modifier = Modifier.testTag("habit_card_$index"))
        }
    }
    item(key = "games_header", contentType = "section_header") {
        SectionHeader("Saved games", Modifier.testTag("saved_games_header"))
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun HabitCard(summary: HabitSummary, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val categoryLabel = when (summary.category) {
                HabitCategory.TACTICAL -> "Tactical Habit"
                HabitCategory.BEHAVIORAL -> "Behavioral Habit"
            }
            Text(
                categoryLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
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
