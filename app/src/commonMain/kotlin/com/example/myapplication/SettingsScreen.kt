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
import com.example.myapplication.explorer.RatingBand
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.Opponent
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
 * Difficulty is always visible. For Stockfish it applies to the engine on every
 * platform (the CPU fallback ignores it, but the setting persists for when an engine is attached).
 * `AppRoot` bridges the setting to the VM's `setEngineDifficulty`, which applies it to the attached
 * engine.
 *
 * Where the build runs Maia-3 (`maiaAvailable`), an Opponent choice appears, with Maia selected unless
 * the user picked Stockfish. The same difficulty list sets Maia's strength; each level shows the
 * rating band it plays like. Without Maia there is no Opponent choice.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    board3D: Board3DSupport? = null,
    /** Opens the open-source licences screen. Null hides the row (previews, Compose UI tests). */
    onOpenLicenses: (() -> Unit)? = null,
    extraSettings: (@Composable () -> Unit)? = null,
    /**
     * Whether this build can run Maia-3 (every target with a Maia runtime). Without it the Opponent choice is not
     * shown at all, and Stockfish's difficulty is the only strength control.
     */
    maiaAvailable: Boolean = false,
) {
    val settings = LocalAppSettings.current
        ?: error("SettingsScreen requires AppSettings. Render it under AppRoot.")
    val board3DEnabled by settings.board3DEnabled.collectAsState()
    val engineDifficulty by settings.engineDifficulty.collectAsState()
    val aiCoachEnabled by settings.aiCoachEnabled.collectAsState()

    val opponent by settings.opponent.collectAsState()
    val playsMaia = maiaAvailable && opponent == Opponent.MAIA

    SubScreenScaffold(title = "Settings", onBack = onBack) {
        extraSettings?.invoke()
        if (maiaAvailable) {
            Text(
                text = "Opponent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                ChoiceRow("Maia-3: plays like a person at your level", playsMaia, "settings_opponent_maia") {
                    settings.setOpponent(Opponent.MAIA)
                }
                ChoiceRow("Stockfish", !playsMaia, "settings_opponent_stockfish") {
                    settings.setOpponent(Opponent.STOCKFISH)
                }
            }
            if (playsMaia) {
                Text(
                    "Hints and move coaching still come from Stockfish.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("settings_maia_note"),
                )
            }
        }
        Text(
            text = "Engine difficulty",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = if (maiaAvailable) 16.dp else 8.dp, bottom = 4.dp)
        )
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            EngineDifficulty.entries.forEach { level ->
                val name = level.name.lowercase().replaceFirstChar { it.titlecase() }
                ChoiceRow(
                    // With Maia, say what each level means: the rating band it plays like.
                    label = if (playsMaia) "$name (plays like ${RatingBand.forDifficulty(level).label})" else name,
                    selected = engineDifficulty == level,
                    testTag = "settings_difficulty_${level.name}",
                ) { settings.setEngineDifficulty(level) }
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

        if (onOpenLicenses != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            TextButton(
                onClick = onOpenLicenses,
                modifier = Modifier.fillMaxWidth().testTag("settings_licenses"),
            ) {
                Text("Open-source licences")
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    testTag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Text(label)
    }
}
