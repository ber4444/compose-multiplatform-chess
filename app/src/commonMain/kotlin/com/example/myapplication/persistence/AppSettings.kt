package com.example.myapplication.persistence

import com.russhwolf.settings.Settings

/**
 * Typed, observable view over a russhwolf [Settings] instance. Plain class (mirrors
 * [com.example.myapplication.GameViewModel] — not an androidx ViewModel), constructed at the
 * platform entry point and threaded into `AppRoot`. Holds [MutableStateFlow]s seeded from settings
 * and writes through on every setter.
 *
 * Currently empty — the persisted theme override was removed (theme now always follows the system
 * dark-mode setting). Engine difficulty (Phase 4) will reuse this class and its `Settings` backing
 * store. Kept as the injection seam for [LocalAppSettings] so the upcoming setting slots in without
 * rewiring the entry points.
 */
class AppSettings(private val settings: Settings)
