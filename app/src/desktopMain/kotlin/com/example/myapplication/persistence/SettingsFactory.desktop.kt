package com.example.myapplication.persistence

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

actual fun createSettings(name: String): Settings {
    return PreferencesSettings(Preferences.userRoot().node("chess/$name"))
}
