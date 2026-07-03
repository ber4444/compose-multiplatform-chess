package com.example.myapplication.persistence

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Provides the app-wide [AppSettings] to deeply-nested composables (e.g. [SettingsScreen],
 * [GameScreen]'s 3D-toggle observer) without prop-drilling. Installed by
 * [com.example.myapplication.AppRoot].
 *
 * Defaults to `null` rather than throwing so that composables that read it (notably `GameScreen`,
 * which observes `board3DEnabled`) can be rendered in tests without wrapping in `AppRoot` — those
 * call sites treat `null` as "no setting provided" and fall back to the built-in default (3D on).
 * Production code always runs under `AppRoot`, which provides a real instance.
 */
val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }
