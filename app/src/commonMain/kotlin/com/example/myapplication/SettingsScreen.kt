package com.example.myapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import com.example.myapplication.monetization.LocalEntitlements
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.persistence.LocalAppSettings
import game.app.generated.resources.Res
import game.app.generated.resources.board_3d_toggle_label
import org.jetbrains.compose.resources.stringResource

/**
 * Settings screen. Holds the engine-difficulty selector and the 3D-board on/off toggle, both reading
 * and writing [com.example.myapplication.persistence.AppSettings] via [LocalAppSettings]. The
 * persisted theme selector was removed (theme now always follows the system dark-mode setting).
 *
 * The 3D toggle is only shown when a [Board3DSupport] backend is available (`board3D != null`);
 * platforms without one never show a dead control. Toggling it writes to `AppSettings.board3DEnabled`,
 * which `GameScreen` observes and translates into the 3D surface mount/teardown choreography.
 *
 * Difficulty is always visible — it applies to the engine on every platform (the CPU fallback ignores
 * it, but the setting persists for when an engine is attached). `AppRoot` bridges the setting to the
 * VM's `setEngineDifficulty`, which applies it to the attached engine.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    board3D: Board3DSupport? = null,
    /**
     * The **only** route to the paywall that isn't an upsell card.
     *
     * Every other entry point is a `ProGate` CTA, and those hide themselves when the feature behind
     * them is unavailable on this build (`ProGate(available = false)` renders nothing — selling a
     * surface that would stay dead after payment is the one paywall bug that earns a refund). The
     * consequence is that on a build where no gated surface is available, the paywall has no route
     * at all and a willing buyer cannot reach it. This row is that route.
     *
     * Null hides it, which is what previews and Compose UI tests get.
     */
    onOpenPaywall: (() -> Unit)? = null,
) {
    val settings = LocalAppSettings.current
        ?: error("SettingsScreen requires AppSettings. Render it under AppRoot.")
    val board3DEnabled by settings.board3DEnabled.collectAsState()
    val engineDifficulty by settings.engineDifficulty.collectAsState()
    val aiCoachEnabled by settings.aiCoachEnabled.collectAsState()

    SubScreenScaffold(title = "Settings", onBack = onBack, showBackButton = false) {
        Text(
            text = "Engine difficulty",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            EngineDifficulty.entries.forEach { level ->
                DifficultyOption(
                    label = level.name.lowercase().replaceFirstChar { it.titlecase() },
                    level = level,
                    current = engineDifficulty,
                    onSelect = settings::setEngineDifficulty,
                    testTag = "settings_difficulty_${level.name}",
                )
            }
        }

        Text(
            text = "Player Side",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        val playerSide by settings.playerSide.collectAsState()
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            listOf("WHITE", "BLACK").forEach { side ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = playerSide == side,
                            role = Role.RadioButton,
                            onClick = { settings.setPlayerSide(side) },
                        )
                        .padding(vertical = 4.dp)
                        .testTag("settings_player_side_${side.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = playerSide == side,
                        onClick = null,
                    )
                    Text(side.lowercase().replaceFirstChar { it.titlecase() })
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp)
                .testTag("settings_ai_coach"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(
                checked = aiCoachEnabled,
                onCheckedChange = settings::setAiCoachEnabled,
            )
            Text("Enable AI Move Coach")
        }

        if (board3D != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 4.dp)
                    .testTag("settings_board_3d"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Switch(
                    checked = board3DEnabled,
                    onCheckedChange = settings::setBoard3DEnabled,
                )
                Text(stringResource(Res.string.board_3d_toggle_label))
            }
        }

        if (onOpenPaywall != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            // Reads LocalEntitlements directly rather than through isProUnlocked(), for the same
            // reason PaywallScreen does: that helper treats a null Entitlements as unrestricted,
            // which is right for previews and would make this row claim Pro is already active.
            val entitlements = LocalEntitlements.current
            val unlocked by (entitlements?.isProUnlocked ?: MutableStateFlow(false)).collectAsState()
            TextButton(
                onClick = onOpenPaywall,
                modifier = Modifier.fillMaxWidth().testTag("settings_upgrade"),
            ) {
                Text(if (unlocked) "Chess Coach Pro — active" else "Upgrade to Chess Coach Pro")
            }
        }
    }
}

@Composable
private fun DifficultyOption(
    label: String,
    level: EngineDifficulty,
    current: EngineDifficulty,
    onSelect: (EngineDifficulty) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = current == level,
                role = Role.RadioButton,
                onClick = { onSelect(level) },
            )
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = current == level,
            onClick = null,
        )
        Text(label)
    }
}
