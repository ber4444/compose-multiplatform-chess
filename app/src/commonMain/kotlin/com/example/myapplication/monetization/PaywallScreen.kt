package com.example.myapplication.monetization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.myapplication.LocalGameSummaryManager
import com.example.myapplication.LocalMoveCoachManager
import com.example.myapplication.isAndroidPlatform
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.movecoach.GameSummaryUiState
import com.example.myapplication.opening.cloudCoachConfigured
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
 *
 * Two layout fences, both fixing what a phone screenshot showed:
 * - This screen is **its own [Surface]**. Every other destination reaches one through
 *   `SubScreenScaffold`'s `Scaffold`; this one has no scaffold, and without a Surface it inherited
 *   neither `colorScheme.background` nor a content colour — so a dark-theme device rendered black
 *   body text on the platform's white window, with the dark scheme's pale-lavender primary button
 *   on top of it. That reads as a washed-out button; the cause is the missing background.
 * - The content is inset by [WindowInsets.safeDrawing]. Nothing else here pads for the status bar
 *   or the home indicator, so the title sat under the iOS clock.
 *
 * @param rulesQaAvailable whether `defaultRulesQaAnswerer` returned an answerer on this build — the
 *   same signal `AppRoot` gates the Rules screen on. It has to be passed in for the reason
 *   `ProGate.available` does: availability is a property of the build, and this screen cannot probe
 *   it without constructing the platform answerer a second time. Defaults to `false` so a caller
 *   that doesn't know (previews, tests) under-promises rather than over-promises.
 */
@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    rulesQaAvailable: Boolean = false,
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

    val features = proFeatures(rulesQaAvailable)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .testTag("paywall"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // Capped so the desktop and browser builds — where NoOpEntitlements keeps this
                // screen reachable — don't stretch a purchase form across an 800 dp window.
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Text(
                    "Chess Coach Pro",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unlimited play, both boards, every engine level and the move coach stay free.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))
                if (features.isEmpty()) {
                    Text(
                        "None of the Pro features are available in this build, so there's nothing " +
                            "extra to unlock right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("paywall_no_features"),
                    )
                } else {
                    Text(
                        "PRO ADDS",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            features.forEach { FeatureRow(it) }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                when {
                    unlocked -> Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CheckMark()
                            Spacer(Modifier.size(12.dp))
                            Text(
                                "Pro is active. Thank you!",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.testTag("paywall_active"),
                            )
                        }
                    }

                    plans == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Loading plans…", style = MaterialTheme.typography.bodyMedium)
                    }

                    plans!!.isEmpty() -> Text(
                        // Covers storeless targets, a missing API key, and a missing/empty offering
                        // — the user can't act on any of them, so don't offer a button that fails.
                        "Purchases aren't available on this device right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("paywall_unavailable"),
                    )

                    // Nothing this build can deliver, so nothing to charge for. Same rule as
                    // `ProGate(available = false)`: a purchase that unlocks a dead feature is the
                    // paywall bug that earns a refund and a one-star review. Restore stays below —
                    // it moves no money and an existing subscriber may be on this build.
                    features.isEmpty() -> Unit

                    else -> {
                        Column(modifier = Modifier.selectableGroup()) {
                            plans!!.forEach { plan ->
                                PlanRow(
                                    plan = plan,
                                    selected = plan.id == selectedPlanId,
                                    onSelect = { selectedPlanId = plan.id },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
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
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("paywall_purchase"),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(
                                    "Unlock Pro",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                if (!unlocked) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                // Four outcomes, four sentences. Reporting them all as "No previous
                                // purchase found." hid both halves of a real support case: a store
                                // that couldn't be reached, and a purchase the store never had.
                                message = when (val outcome = entitlements?.restorePurchases()) {
                                    RestoreOutcome.Restored -> null
                                    // Name the account, because the app has none of its own: a
                                    // purchase belongs to the store account signed in on the
                                    // device, and "this store account" left the user hunting for
                                    // an app login that doesn't exist.
                                    RestoreOutcome.NothingToRestore ->
                                        "No purchase found for the $storeAccountName signed in on " +
                                            "this device. Purchases are tied to the account that " +
                                            "bought them."
                                    RestoreOutcome.Unavailable, null ->
                                        "Purchases aren't available on this device right now."
                                    is RestoreOutcome.Failed ->
                                        outcome.message ?: "Couldn't reach the store to restore."
                                }
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
                TextButton(
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (unlocked) "Done" else "Not now")
                }
            }
        }
    }
}

@Composable
private fun PlanRow(plan: ProPlan, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    plan.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                plan.detail?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (plan.isBestValue) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            "BEST VALUE",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(
                plan.priceLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FeatureRow(feature: ProFeature) {
    Row(verticalAlignment = Alignment.Top) {
        CheckMark(modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                feature.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                feature.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Drawn rather than a glyph or an icon: `material-icons-core` isn't a dependency of `:app`, and a
 * literal "✓" depends on the platform font stack — which differs across the five targets this
 * composable ships to.
 */
@Composable
private fun CheckMark(modifier: Modifier = Modifier) {
    val tick = MaterialTheme.colorScheme.primary
    val disc = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    Canvas(modifier.size(20.dp)) {
        drawCircle(disc)
        val w = size.minDimension
        drawPath(
            path = Path().apply {
                moveTo(w * 0.28f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.68f)
                lineTo(w * 0.73f, w * 0.33f)
            },
            color = tick,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
        )
    }
}

/**
 * What to call the account a purchase belongs to.
 *
 * The app has no accounts of its own — that is the point of anonymous App User IDs — so the only
 * honest answer to "which account?" is the store one the device is already signed into. Only the two
 * store platforms can reach the copy that uses this (`NothingToRestore` comes from
 * `RevenueCatEntitlements`; desktop and wasm always restore).
 */
private val storeAccountName: String
    get() = if (isAndroidPlatform) "Google Play account" else "App Store account"

private data class ProFeature(val title: String, val blurb: String)

/**
 * What Pro actually adds **on this build** — never a fixed list.
 *
 * Every line is keyed to the same signal that gates the surface itself, because the alternative was
 * measurably wrong: the list advertised Game Summary, Position Chat, Opening Explainer and Rules Q&A
 * unconditionally, while each of those `ProGate`s hides itself when `available` is false. On a
 * plain desktop run (`ProGate(available = false)` on all four) the paywall sold four features and
 * unlocked none; on an iOS build without Apple Intelligence it sold Game Summary, which
 * `GameSummaryManager` reports `Unavailable`; on any build without `coach.baseUrl` it sold the two
 * cloud surfaces, which can only emit their offline sentence.
 *
 * The signals, and where each is also read:
 * - Move Coach — `MoveCoachManager.hasOrchestrator`. No model attached means a paying user gets the
 *   identical `DeterministicCoach` line a free one does. No phone platform attaches one today.
 * - Game Summary — `GameSummaryManager.uiState !is Unavailable`, the check `GameScreen` uses both to
 *   hide the button and to set `ProGate.available`.
 * - Position Chat / Opening Explainer — `cloudCoachConfigured`, the flag their two `ProGate`s and
 *   `AppRoot`'s chat branch already read.
 * - Rules Q&A — [rulesQaAvailable], `AppRoot`'s `rulesQaAnswerer != null`.
 *
 * If a device ever gains one of these, its line returns with no code change here.
 */
@Composable
private fun proFeatures(rulesQaAvailable: Boolean): List<ProFeature> {
    val modelCoach = LocalMoveCoachManager.current?.hasOrchestrator == true
    val gameSummary = gameSummaryAvailable()
    return listOfNotNull(
        ProFeature(
            "Move Coach in the model's words",
            "The same verdict on your move, rephrased by the on-device model.",
        ).takeIf { modelCoach },
        ProFeature(
            "Game Summary",
            "A coach's read on the whole game — the turning points and what to work on.",
        ).takeIf { gameSummary },
        ProFeature(
            "Position Chat",
            "Ask about the position you're in and get grounded answers as you play.",
        ).takeIf { cloudCoachConfigured },
        ProFeature(
            "Opening Explainer",
            "See what opening you played and the ideas behind it.",
        ).takeIf { cloudCoachConfigured },
        ProFeature(
            "Rules Q&A",
            "Ask any rules question and get an answer cited to the rulebook, on your device.",
        ).takeIf { rulesQaAvailable },
    )
}

/** Whether an orchestrator (or Android's deterministic composer) is attached to Game Summary. */
@Composable
private fun gameSummaryAvailable(): Boolean {
    val manager = LocalGameSummaryManager.current ?: return false
    val state by manager.uiState.collectAsState()
    return state !is GameSummaryUiState.Unavailable
}
