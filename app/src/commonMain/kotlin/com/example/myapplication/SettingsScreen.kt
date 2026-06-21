package com.example.myapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.ThemeMode

/**
 * Settings screen. Phase 0 surface: a single 3-way theme selector (System / Light / Dark) reading
 * and writing [com.example.myapplication.persistence.AppSettings]. Engine difficulty (Phase 4) and
 * time control (Phase 5) will slot in below using the same `SettingsRow` pattern.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = LocalAppSettings.current
    val themeMode by settings.themeMode.collectAsState()

    SubScreenScaffold(title = "Settings", onBack = onBack) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            ThemeOption(
                label = "System",
                mode = ThemeMode.SYSTEM,
                current = themeMode,
                onSelect = settings::setThemeMode,
                testTag = "settings_theme_system",
            )
            ThemeOption(
                label = "Light",
                mode = ThemeMode.LIGHT,
                current = themeMode,
                onSelect = settings::setThemeMode,
                testTag = "settings_theme_light",
            )
            ThemeOption(
                label = "Dark",
                mode = ThemeMode.DARK,
                current = themeMode,
                onSelect = settings::setThemeMode,
                testTag = "settings_theme_dark",
            )
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    mode: ThemeMode,
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = current == mode,
            onClick = { onSelect(mode) },
        )
        Text(label)
    }
}
