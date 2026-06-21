package com.example.myapplication.persistence

import com.russhwolf.settings.Settings

/**
 * Multiplatform `Settings` factory. Each target constructs the russhwolf `Settings` backend that
 * suits its platform storage (`SharedPreferences`, `java.util.prefs.Preferences`, JS `Storage`,
 * `NSUserDefaults`). The Android actual reads a process-wide `appContext` populated by
 * `ChessApplication` (see `androidMain`).
 *
 * Mirrors the injection pattern used for `Board3DSupport` / `ChessEngine`: constructed at each
 * platform entry point and passed into `AppRoot`/`AppSettings`.
 */
expect fun createSettings(name: String): Settings
