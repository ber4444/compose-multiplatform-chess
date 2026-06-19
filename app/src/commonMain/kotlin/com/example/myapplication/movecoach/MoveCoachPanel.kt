package com.example.myapplication.movecoach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import game.app.generated.resources.Res
import game.app.generated.resources.move_coach_title
import game.app.generated.resources.move_coach_loading
import game.app.generated.resources.move_coach_loading_model
import game.app.generated.resources.move_coach_unavailable
import game.app.generated.resources.move_coach_unavailable_hint
import game.app.generated.resources.move_coach_fallback_label
import game.app.generated.resources.move_coach_error_label
import org.jetbrains.compose.resources.stringResource

/**
 * The coach panel (plan §8). Surfaces the [MoveCoachUiState] the GameViewModel
 * emits after Black's move. The panel only mounts when state != [MoveCoachUiState.Hidden].
 *
 * Per plan §1.3 the headline + 2-sentence explanation come from the on-device
 * model. When the orchestrator fell back, the panel labels the text as a rule-
 * based explanation so the user knows a model wasn't consulted.
 */
@Composable
fun MoveCoachPanel(
    state: MoveCoachUiState,
    modifier: Modifier = Modifier,
) {
    if (state is MoveCoachUiState.Hidden) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("move_coach_panel"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.move_coach_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state is MoveCoachUiState.Loading ||
                    state is MoveCoachUiState.Streaming ||
                    state is MoveCoachUiState.LoadingModel) {
                    Spacer(modifier = Modifier.size(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp).testTag("move_coach_spinner"),
                        strokeWidth = 2.dp,
                    )
                }
            }

            Spacer(modifier = Modifier.size(6.dp))

            when (state) {
                is MoveCoachUiState.Loading -> Text(
                    modifier = Modifier.testTag("move_coach_loading"),
                    text = stringResource(Res.string.move_coach_loading, state.move),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MoveCoachUiState.LoadingModel -> Column {
                    Text(
                        modifier = Modifier.testTag("move_coach_loading_model"),
                        text = stringResource(Res.string.move_coach_loading_model, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.message.isNotBlank()) {
                        Spacer(modifier = Modifier.size(2.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                is MoveCoachUiState.Streaming -> Text(
                    modifier = Modifier.testTag("move_coach_streaming"),
                    text = state.text.ifBlank { stringResource(Res.string.move_coach_loading, state.move) },
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MoveCoachUiState.Ready -> {
                    Text(
                        modifier = Modifier.testTag("move_coach_headline"),
                        text = state.explanation.headline,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.explanation.explanation != state.explanation.headline) {
                        Spacer(modifier = Modifier.size(2.dp))
                        Text(
                            modifier = Modifier.testTag("move_coach_explanation"),
                            text = state.explanation.explanation,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is MoveCoachUiState.Fallback -> Column {
                    Text(
                        modifier = Modifier.testTag("move_coach_fallback_text"),
                        text = state.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.size(2.dp))
                    Text(
                        modifier = Modifier.testTag("move_coach_fallback_reason"),
                        text = stringResource(Res.string.move_coach_fallback_label, state.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                is MoveCoachUiState.Error -> Text(
                    modifier = Modifier.testTag("move_coach_error"),
                    text = stringResource(Res.string.move_coach_error_label, state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB00020),
                )

                is MoveCoachUiState.Unavailable -> {
                    val text = if (state.reason.isNullOrBlank()) {
                        stringResource(Res.string.move_coach_unavailable)
                    } else {
                        stringResource(Res.string.move_coach_unavailable_hint, state.reason)
                    }
                    Text(
                        modifier = Modifier.testTag("move_coach_unavailable"),
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                MoveCoachUiState.Hidden -> Unit
            }
        }
    }
}
