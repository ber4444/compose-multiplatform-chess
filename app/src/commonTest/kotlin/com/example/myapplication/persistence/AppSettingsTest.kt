package com.example.myapplication.persistence

import com.example.myapplication.EngineDifficulty
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {

    @Test
    fun `board3DEnabled defaults to true on first install`() {
        val settings = AppSettings(MapSettings())
        assertEquals(true, settings.board3DEnabled.value)
    }

    @Test
    fun `setBoard3DEnabled round-trips through Settings`() {
        val backing = MapSettings()
        val settings = AppSettings(backing)

        settings.setBoard3DEnabled(false)
        assertEquals(false, settings.board3DEnabled.value)
        assertEquals(false, backing.getBoolean(AppSettings.KEY_BOARD_3D, true))

        // A fresh AppSettings over the same backing reads the persisted value.
        val reloaded = AppSettings(backing)
        assertEquals(false, reloaded.board3DEnabled.value)
    }

    @Test
    fun `setBoard3DEnabled updates the StateFlow`() {
        val settings = AppSettings(MapSettings())
        settings.setBoard3DEnabled(false)
        assertEquals(false, settings.board3DEnabled.value)
        settings.setBoard3DEnabled(true)
        assertEquals(true, settings.board3DEnabled.value)
    }

    // --- engineDifficulty (issue #39 Phase 4) ---

    @Test
    fun `engineDifficulty defaults to MEDIUM`() {
        val settings = AppSettings(MapSettings())
        assertEquals(EngineDifficulty.MEDIUM, settings.engineDifficulty.value)
    }

    @Test
    fun `setEngineDifficulty round-trips through Settings`() {
        val backing = MapSettings()
        val settings = AppSettings(backing)

        settings.setEngineDifficulty(EngineDifficulty.HARD)
        assertEquals(EngineDifficulty.HARD, settings.engineDifficulty.value)
        assertEquals(EngineDifficulty.HARD.name, backing.getStringOrNull(AppSettings.KEY_ENGINE_DIFFICULTY))

        // A fresh AppSettings over the same backing reads the persisted value.
        val reloaded = AppSettings(backing)
        assertEquals(EngineDifficulty.HARD, reloaded.engineDifficulty.value)
    }

    @Test
    fun `unknown stored difficulty falls back to MEDIUM without throwing`() {
        val backing = MapSettings().apply { putString(AppSettings.KEY_ENGINE_DIFFICULTY, "NIGHTMARE") }
        val settings = AppSettings(backing)
        assertEquals(EngineDifficulty.MEDIUM, settings.engineDifficulty.value)
    }

    @Test
    fun `setEngineDifficulty updates the StateFlow`() {
        val settings = AppSettings(MapSettings())
        settings.setEngineDifficulty(EngineDifficulty.EASY)
        assertEquals(EngineDifficulty.EASY, settings.engineDifficulty.value)
        settings.setEngineDifficulty(EngineDifficulty.MAX)
        assertEquals(EngineDifficulty.MAX, settings.engineDifficulty.value)
    }
}
