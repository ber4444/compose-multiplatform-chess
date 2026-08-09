package com.example.myapplication.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * Two deliberate choices in the rendering:
 *
 * - **No glyph.** An emoji (☁️/📱/⚙️) reads well on Android and iOS and renders as tofu on the
 *   desktop JVM, whose default AWT font chain carries no colour-emoji font — and this is a
 *   five-target app. The label alone says the same thing everywhere.
 * - **Plain English, not taxonomy.** "Engine-derived" is our word for it, not the user's. Each
 *   label answers the two questions a provenance badge exists to answer — who wrote this, and did
 *   it leave my device — in words that need no glossary.
 *
 * The badge is a single text node, so a `testTag` on it can be asserted with `assertTextContains`
 * without an unmerged tree.
 */
@Composable
fun ProvenanceBadge(
    route: AiRoute,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val label = when (route) {
        is AiRoute.Fallback -> "Written by the app, not a model"
        is AiRoute.OnDevice -> "Written by a model on your device"
        is AiRoute.Cloud -> "Written by a model in the cloud"
    }

    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = 0.7f),
    )
}
