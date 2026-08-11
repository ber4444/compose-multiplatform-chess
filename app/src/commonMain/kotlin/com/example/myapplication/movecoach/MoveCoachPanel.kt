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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.myapplication.board3d.HighlightTone
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
    /**
     * Defaults to white for the 3D branch, where the panel overlays the rendered board rather than
     * a themed surface and no color-scheme role describes what is behind it. The 2D branch sits on
     * `surface` and passes `onSurface` — left at the default there, the whole line is white on a
     * near-white background.
     */
    contentColor: Color = Color.White,
) {
    if (state is MoveCoachUiState.Hidden) return

    // B17: a fallback is not one state. Silent substitutions render exactly like a normal coach
    // line (no label, no chrome) because the deterministic text is the product; only quota and
    // timeout earn an explanation, and only timeout earns a retry.
    val presentation = (state as? MoveCoachUiState.Fallback)?.let {
        FallbackPresentation.of(it.reason)
    }
    // B19: when the board is painting the verdict, the headline stops saying it. "Good — a3" beside
    // a green a3 is the same fact twice, and the text version is the one that reads like a grade.
    // Tones the colour cannot express keep their headline: Explain mode's subject square, a book
    // move, and anything with no assessment behind it are all NEUTRAL and still need their words.
    val verdictIsOnTheBoard = state.highlightTone != HighlightTone.NEUTRAL
    val label: String? = when (state) {
        is MoveCoachUiState.Ready -> state.explanation.headline.takeUnless { verdictIsOnTheBoard }
        is MoveCoachUiState.Streaming -> state.headline.takeUnless { verdictIsOnTheBoard }
        is MoveCoachUiState.Loading -> state.headline.takeUnless { verdictIsOnTheBoard }
        is MoveCoachUiState.Fallback -> when (presentation) {
            // Quota and timeout outrank the headline: they tell the user something about what to do
            // next, which the verdict does not.
            is FallbackPresentation.Labeled -> presentation.label
            is FallbackPresentation.Retryable -> presentation.label
            else -> state.headline.takeUnless { verdictIsOnTheBoard || it.isEmpty() }
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

    // B11: derived, never stored twice. Ready carries the route the orchestrator recorded; a
    // Fallback's text is engine-derived by construction, so its reason *is* its provenance. The
    // remaining states have no text a route could describe yet.
    val route: com.example.ondeviceai.AiRoute? = when (state) {
        is MoveCoachUiState.Ready -> state.explanation.route
        is MoveCoachUiState.Fallback -> com.example.ondeviceai.AiRoute.Fallback(state.reason)
        else -> null
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
            // Bounds this scroll's own height. The 2D branch of GameScreen hosts the panel inside a
            // verticalScroll Column, which hands children an infinite max height — and a scrollable
            // measured with one throws rather than degrading, so 2D plus any visible coach line
            // crashed on launch.
            .heightIn(max = 180.dp)
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
                    color = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.testTag("move_coach_fallback_label"),
                )
            }
            Text(
                // Rendered, not shown as syntax: a model that reaches for **bold** should read as
                // bold rather than as asterisks. See InlineMarkdown.
                text = com.example.myapplication.ui.InlineMarkdown.render(text),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )

            // Debug builds name the reason, mirroring RulesQaScreen. Most fallbacks present as
            // Silent — correct for a user, and it also means a device that never once reached the
            // model looks identical to one that did. Without this the only way to tell "no model"
            // from "the validator vetoed it" is a log line nobody is watching.
            if (com.example.myapplication.LocalIsDebug.current && state is MoveCoachUiState.Fallback) {
                Text(
                    text = "fallback: ${state.reason.description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier.testTag("move_coach_fallback_reason"),
                )
            }

            // Determinate only when the runtime can say how far along it is: Cactus reports a
            // fraction by watching the partial file grow, LiteRT-LM (desktop/wasm) reports none.
            // A null progress leaves the surrounding spinner as the whole indicator rather than
            // rendering a bar stuck at zero.
            val progress = (state as? MoveCoachUiState.LoadingModel)?.progress
            if (progress != null) {
                Spacer(modifier = Modifier.size(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).testTag("move_coach_progress"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            }

            if (route != null) {
                Spacer(modifier = Modifier.size(4.dp))
                com.example.myapplication.ui.ProvenanceBadge(
                    route = route,
                    // The badge's default onSurfaceVariant is a surface role; this panel may be
                    // overlaying the 3D board instead, so it follows the panel's own color.
                    color = contentColor,
                    modifier = Modifier.testTag("move_coach_provenance")
                )
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
