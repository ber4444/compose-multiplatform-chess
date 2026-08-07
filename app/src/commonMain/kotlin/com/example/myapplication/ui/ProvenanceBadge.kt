package com.example.myapplication.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ondeviceai.AiRoute

/**
 * B11: says which route produced the text it sits under. The label is derived from an [AiRoute] and
 * nothing else, so a surface cannot claim provenance it doesn't have — every call site must hand it
 * the route recorded with the text (`explanation.route`, or `AiRoute.Fallback(reason)`), never a
 * literal guess.
 *
 * [AiRoute.Fallback] deliberately does **not** say "model": deterministic text (the free tier's
 * `DeterministicCoach` line, `MoveCoachFallback`, the server's template composers) is
 * engine-derived, and labelling it model-phrased is the misreport this badge exists to prevent.
 *
 * The glyph and the label are merged into one semantics node, so a `testTag` on the badge can be
 * asserted with `assertTextContains` without an unmerged tree.
 */
@Composable
fun ProvenanceBadge(
    route: AiRoute,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val glyph = when (route) {
        is AiRoute.Fallback -> "⚙️"
        is AiRoute.OnDevice -> "📱"
        is AiRoute.Cloud -> "☁️"
    }

    val text = when (route) {
        is AiRoute.Fallback -> "Engine-derived"
        is AiRoute.OnDevice -> "Model-phrased • Never left your device"
        is AiRoute.Cloud -> "Model-phrased"
    }

    val tint = color.copy(alpha = 0.7f)

    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
