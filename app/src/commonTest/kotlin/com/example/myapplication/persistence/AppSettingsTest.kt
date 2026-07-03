package com.example.myapplication.persistence

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
}
