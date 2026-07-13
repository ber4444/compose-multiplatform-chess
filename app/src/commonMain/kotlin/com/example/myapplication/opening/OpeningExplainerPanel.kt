package com.example.myapplication.opening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun OpeningExplainerPanel(
    state: OpeningExplainerUiState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        OpeningExplainerUiState.Hidden -> Unit
        OpeningExplainerUiState.Loading -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Opening explainer", style = MaterialTheme.typography.titleMedium)
            CircularProgressIndicator()
            Text("Finding the opening ideas…")
        }
        is OpeningExplainerUiState.Ready -> OpeningExplainerReadyPanel(state, modifier)
    }
}

@Composable
private fun OpeningExplainerReadyPanel(
    state: OpeningExplainerUiState.Ready,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Opening explainer", style = MaterialTheme.typography.titleMedium)
        Text(
            text = state.text,
            modifier = Modifier.testTag("opening_explainer_text"),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.sourceTitles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Sources: ${state.sourceTitles.joinToString()}",
                modifier = Modifier.testTag("opening_explainer_sources"),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (state.isFallback) {
            Text(
                text = "Offline opening guidance",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
