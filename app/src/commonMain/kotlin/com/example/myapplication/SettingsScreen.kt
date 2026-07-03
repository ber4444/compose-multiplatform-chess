package com.example.myapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.persistence.LocalAppSettings
import game.app.generated.resources.Res
import game.app.generated.resources.board_3d_toggle_label
import org.jetbrains.compose.resources.stringResource

/**
 * Settings screen. Currently a placeholder for engine difficulty (Phase 4) plus the 3D-board on/off
 * toggle, both reading and writing [com.example.myapplication.persistence.AppSettings] via
 * [LocalAppSettings]. The persisted theme selector was removed (theme now always follows the system
 * dark-mode setting).
 *
 * The 3D toggle is only shown when a [Board3DSupport] backend is available (`board3D != null`);
 * platforms without one never show a dead control. Toggling it writes to `AppSettings.board3DEnabled`,
 * which `GameScreen` observes and translates into the 3D surface mount/teardown choreography.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    board3D: Board3DSupport? = null,
) {
    val settings = LocalAppSettings.current
        ?: error("SettingsScreen requires AppSettings. Render it under AppRoot.")
    val board3DEnabled by settings.board3DEnabled.collectAsState()

    SubScreenScaffold(title = "Settings", onBack = onBack) {
        Text(
            text = "No settings yet. Engine difficulty will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

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
    }
}
