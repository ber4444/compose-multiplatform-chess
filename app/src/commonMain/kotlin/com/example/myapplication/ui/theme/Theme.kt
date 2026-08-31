package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Both schemes are filled in past the template's three roles, because the app reads more than
 * three: `surfaceVariant` backs the paywall's feature card and plan rows, `primaryContainer` the
 * "Pro is active" row, `onSurfaceVariant` most secondary copy. Left at their defaults those roles
 * were derived from the template purple and pulled a cool grey-lavender through screens whose
 * neutrals are warm.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Slate80,
    onSecondary = Slate20,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate90,
    tertiary = Claret80,
    onTertiary = Claret20,
    tertiaryContainer = Claret30,
    onTertiaryContainer = Claret90,
    background = NightSurface,
    onBackground = NightOn,
    surface = NightSurface,
    onSurface = NightOn,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightOnVariant,
    outline = OutlineDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorContainerLight,
)

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Paper,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Slate40,
    onSecondary = Paper,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate20,
    tertiary = Claret40,
    onTertiary = Paper,
    tertiaryContainer = Claret90,
    onTertiaryContainer = Claret20,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkVariant,
    outline = OutlineLight,
    error = ErrorLight,
    onError = Paper,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
