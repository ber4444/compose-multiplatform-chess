package com.example.myapplication.movecoach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import game.app.generated.resources.Res
import game.app.generated.resources.move_coach_loading
import game.app.generated.resources.move_coach_unavailable
import game.app.generated.resources.move_coach_unavailable_hint
import org.jetbrains.compose.resources.stringResource

/**
 * Compact coach panel — no title, just the explanation text with an inline spinner
 * for loading states. Mounted directly below the control buttons so it's visible
 * without scrolling.
 */
@Composable
fun MoveCoachPanel(
    state: MoveCoachUiState,
    modifier: Modifier = Modifier,
) {
    if (state is MoveCoachUiState.Hidden) return

    val text: String = when (state) {
        is MoveCoachUiState.Ready -> state.explanation.explanation
        is MoveCoachUiState.Streaming -> state.text.ifBlank { "Generating…" }
        is MoveCoachUiState.Loading -> stringResource(Res.string.move_coach_loading, state.move)
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("move_coach_panel")
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp).testTag("move_coach_spinner"),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
