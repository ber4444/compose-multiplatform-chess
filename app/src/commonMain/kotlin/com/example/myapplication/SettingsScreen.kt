package com.example.myapplication

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings screen. Currently a placeholder — the persisted theme selector was removed (theme now
 * always follows the system dark-mode setting). Engine difficulty (Phase 4) will slot in here,
 * reading and writing [com.example.myapplication.persistence.AppSettings] via [LocalAppSettings].
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    SubScreenScaffold(title = "Settings", onBack = onBack) {
        Text(
            text = "No settings yet. Engine difficulty will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
