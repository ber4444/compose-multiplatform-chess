package com.example.myapplication.movecoach

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import game.app.generated.resources.Res
import game.app.generated.resources.move_coach_unavailable
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay

@Composable
fun MoveCoachPanel(
    state: MoveCoachUiState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    /** Whether B16's Explain mode is armed — the next board tap asks about a square. */
    explainMode: Boolean = false,
    /** Arms/disarms Explain mode. Null hides the control (no coach attached). */
    onToggleExplainMode: (() -> Unit)? = null,
) {
    if (state is MoveCoachUiState.Hidden) return

    // B17: a fallback is not one state. Silent substitutions render exactly like a normal coach
    // line (no label, no chrome) because the deterministic text is the product; only quota and
    // timeout earn an explanation, and only timeout earns a retry.
    val presentation = (state as? MoveCoachUiState.Fallback)?.let {
        FallbackPresentation.of(it.reason)
    }
    val label: String? = when (state) {
        is MoveCoachUiState.Ready -> state.explanation.headline
        is MoveCoachUiState.Streaming -> state.headline
        is MoveCoachUiState.Loading -> state.headline
        is MoveCoachUiState.Fallback -> when (presentation) {
            is FallbackPresentation.Labeled -> presentation.label
            is FallbackPresentation.Retryable -> presentation.label
            else -> null
        }
        else -> null
    }

    val text: String = when (state) {
        is MoveCoachUiState.Ready -> state.explanation.explanation
        is MoveCoachUiState.Streaming -> state.text.ifBlank { state.explanation }
        is MoveCoachUiState.Loading -> state.explanation
        is MoveCoachUiState.LoadingModel -> state.message
        is MoveCoachUiState.Fallback -> state.text
        is MoveCoachUiState.Error -> state.message
        is MoveCoachUiState.Unavailable -> state.reason
            ?: stringResource(Res.string.move_coach_unavailable)
        MoveCoachUiState.Hidden -> return
    }

    val showSpinner = state is MoveCoachUiState.Loading ||
        state is MoveCoachUiState.Streaming ||
        state is MoveCoachUiState.LoadingModel

    val scrollState = rememberScrollState()
    var autoScrolled by remember(text) { mutableStateOf(false) }

    // Auto-scroll to the bottom after 5 seconds so the user can read the
    // beginning first, then see the rest if the text overflows.
    LaunchedEffect(text) {
        autoScrolled = false
        delay(5000)
        if (scrollState.maxValue > 0 && !autoScrolled) {
            scrollState.animateScrollTo(scrollState.maxValue)
            autoScrolled = true
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag("move_coach_panel")
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp).testTag("move_coach_spinner"),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        Column {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.testTag("move_coach_fallback_label"),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
            if (onToggleExplainMode != null) {
                // Opt-in, and it says which state it is in. The alternative B16 shipped with —
                // swallowing every tap whenever the panel was visible — made the board unplayable
                // from move two, because the panel is essentially always visible after that.
                TextButton(
                    onClick = onToggleExplainMode,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.testTag("move_coach_explain_toggle"),
                ) {
                    Text(
                        text = if (explainMode) "Tap a square…" else "Explain a square",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (presentation is FallbackPresentation.Retryable && onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.testTag("move_coach_retry"),
                ) {
                    Text(text = "Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
