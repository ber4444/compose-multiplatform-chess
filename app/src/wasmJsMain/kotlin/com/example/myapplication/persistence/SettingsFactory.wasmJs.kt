package com.example.myapplication.persistence

import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings

actual fun createSettings(name: String): Settings {
    // Browser localStorage is global per-origin; namespace keys so multiple stores don't collide.
    // StorageSettings keys through the given prefix when constructed with one (russhwolf 1.2+).
    return StorageSettings()
}
