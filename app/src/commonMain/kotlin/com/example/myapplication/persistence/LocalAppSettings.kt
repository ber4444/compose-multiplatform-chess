package com.example.myapplication.persistence

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Provides the app-wide [AppSettings] to deeply-nested composables (e.g. [SettingsScreen]) without
 * prop-drilling. Installed by [com.example.myapplication.AppRoot].
 */
val LocalAppSettings = staticCompositionLocalOf<AppSettings> {
    error("AppSettings not provided. Wrap content in AppRoot or CompositionLocalProvider(LocalAppSettings provides ...).")
}
