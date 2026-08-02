package com.example.myapplication.monetization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Reads [LocalEntitlements] and reports whether Pro content should render.
 *
 * `null` entitlements means **unrestricted** — that's the composition default, so Compose UI tests
 * and previews that never install a provider see the full app rather than a wall of upsells. Only a
 * real, locked [Entitlements] gates anything.
 */
@Composable
fun isProUnlocked(): Boolean {
    val entitlements = LocalEntitlements.current ?: return true
    val unlocked by entitlements.isProUnlocked.collectAsState(entitlements.isProUnlocked.value)
    return unlocked
}

/**
 * Renders [content] when Pro is unlocked, and an upsell card otherwise.
 *
 * [featureName] and [pitch] describe the specific surface, because a generic "upgrade" card next to
 * five different features tells the user nothing about what they'd actually get.
 */
@Composable
fun ProGate(
    featureName: String,
    pitch: String,
    onOpenPaywall: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (isProUnlocked()) {
        content()
        return
    }
    ProUpsellCard(featureName, pitch, onOpenPaywall, modifier)
}

/**
 * The locked half of [ProGate], usable on its own.
 *
 * Needed because some Pro surfaces own their whole screen — `RulesQaScreen` supplies its own
 * `SubScreenScaffold`, so wrapping it in [ProGate] would render two title bars when unlocked. Those
 * call sites branch on [isProUnlocked] themselves and drop this card into their own scaffold.
 */
@Composable
fun ProUpsellCard(
    featureName: String,
    pitch: String,
    onOpenPaywall: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth().testTag("pro_gate")) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("$featureName is part of Pro", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(pitch, style = MaterialTheme.typography.bodySmall)
            if (onOpenPaywall != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenPaywall,
                    modifier = Modifier.testTag("pro_gate_cta"),
                ) {
                    Text("See Pro")
                }
            }
        }
    }
}
