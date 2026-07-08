package com.example.myapplication.persistence

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Process-wide app context, populated by [ChessApplication.onCreate] before any activity runs.
 * Accessed only by [createSettings] (which is invoked from `MainActivity.onCreate` and onward).
 */
internal lateinit var appContext: Context

actual fun createSettings(name: String): Settings {
    return SharedPreferencesSettings(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))
}
