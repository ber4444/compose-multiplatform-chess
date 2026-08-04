package com.example.myapplication.monetization

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The Pro paywall. Reads whatever [Entitlements] `AppRoot` published, so on an unkeyed Android/iOS
 * build ([UnconfiguredEntitlements]) it renders the "nothing to sell" state rather than a dead
 * purchase button. On desktop/wasm ([NoOpEntitlements]) it renders a full plan row priced "Free",
 * so the whole purchase path stays exercisable at window sizes no phone build covers.
 *
 * Deliberately hand-rolled rather than RevenueCat's remote paywall: the remote one pulls in the
 * `purchases-kmp-ui` artifact, which — like the core — has no desktop or wasm variant, so it could
 * not live in `commonMain` alongside the rest of the UI.
 */
@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entitlements = LocalEntitlements.current
    // Not isProUnlocked(): that treats a null Entitlements as unrestricted, which is right for
    // gating content but wrong here — the paywall would claim Pro is active in a preview.
    val unlocked by (entitlements?.isProUnlocked ?: MutableStateFlow(false))
        .collectAsState(entitlements?.isProUnlocked?.value ?: false)
    val scope = rememberCoroutineScope()

    var plans by remember { mutableStateOf<List<ProPlan>?>(null) }
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entitlements) {
        val loaded = entitlements?.availablePlans().orEmpty()
        plans = loaded
        selectedPlanId = loaded.firstOrNull { it.isBestValue }?.id ?: loaded.firstOrNull()?.id
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("paywall"),
    ) {
        Text("Chess Coach Pro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Unlimited play, both boards, every engine level and the quick coach stay free. " +
                "Pro adds the AI surfaces:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        PRO_FEATURES.forEach { feature ->
            Text("•  $feature", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(16.dp))

        when {
            unlocked -> Text(
                "Pro is active. Thank you!",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("paywall_active"),
            )

            plans == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Loading plans…", style = MaterialTheme.typography.bodyMedium)
            }

            plans!!.isEmpty() -> Text(
                // Covers storeless targets, a missing API key, and a missing/empty offering — the
                // user can't act on any of them, so don't offer a button that will fail.
                "Purchases aren't available on this device right now.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("paywall_unavailable"),
            )

            else -> {
                plans!!.forEach { plan ->
                    PlanRow(
                        plan = plan,
                        selected = plan.id == selectedPlanId,
                        onSelect = { selectedPlanId = plan.id },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val planId = selectedPlanId ?: return@Button
                        busy = true
                        message = null
                        scope.launch {
                            when (val outcome = entitlements?.purchase(planId)) {
                                PurchaseOutcome.Purchased -> onClose()
                                // Backing out is a correct action, not an error: say nothing.
                                PurchaseOutcome.Cancelled -> Unit
                                PurchaseOutcome.Unavailable, null ->
                                    message = "Purchases aren't available right now."
                                is PurchaseOutcome.Failed ->
                                    message = outcome.message ?: "That purchase didn't go through."
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && selectedPlanId != null,
                    modifier = Modifier.fillMaxWidth().testTag("paywall_purchase"),
                ) {
                    Text(if (busy) "Working…" else "Unlock Pro")
                }
            }
        }

        if (!unlocked) {
            TextButton(
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val restored = entitlements?.restorePurchases() ?: false
                        message = if (restored) null else "No previous purchase found."
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("paywall_restore"),
            ) {
                Text("Restore purchases")
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("paywall_message"),
            )
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(if (unlocked) "Done" else "Not now")
        }
    }
}

@Composable
private fun PlanRow(plan: ProPlan, selected: Boolean, onSelect: () -> Unit) {
    OutlinedCard(
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(Modifier.size(8.dp))
                Column {
                    Text(plan.title, style = MaterialTheme.typography.titleSmall)
                    plan.detail?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                    if (plan.isBestValue) {
                        Text("Best value", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(plan.priceLabel, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private val PRO_FEATURES = listOf(
    "Move Coach explanations written by the on-device model",
    "Game Summary after every finished game",
    "Position Chat — ask about the board as you play",
    "Opening Explainer",
    "Rules Q&A",
)
