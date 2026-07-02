package com.example.myapplication.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {

    @Test
    fun `themeMode defaults to SYSTEM`() {
        val settings = AppSettings(MapSettings())
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
    }

    @Test
    fun `setThemeMode round-trips through Settings`() {
        val backing = MapSettings()
        val settings = AppSettings(backing)

        settings.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, settings.themeMode.value)
        assertEquals(ThemeMode.DARK.name, backing.getStringOrNull(AppSettings.KEY_THEME))

        // A fresh AppSettings over the same backing Store reads the persisted value.
        val reloaded = AppSettings(backing)
        assertEquals(ThemeMode.DARK, reloaded.themeMode.value)
    }

    @Test
    fun `unknown stored values fall back to SYSTEM without throwing`() {
        val backing = MapSettings().apply { putString(AppSettings.KEY_THEME, "not-a-real-mode") }
        val settings = AppSettings(backing)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
    }

    @Test
    fun `subsequent setThemeMode updates the StateFlow`() {
        val settings = AppSettings(MapSettings())
        settings.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, settings.themeMode.value)
        settings.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
    }
}
