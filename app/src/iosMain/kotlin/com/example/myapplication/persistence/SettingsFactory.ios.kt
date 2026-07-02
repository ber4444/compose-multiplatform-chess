package com.example.myapplication.persistence

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults

@OptIn(ExperimentalForeignApi::class)
actual fun createSettings(name: String): Settings {
    // NSUserDefaults(suiteName:) returns non-null in the Kotlin/Native framework binding — if the
    // suite doesn't exist it's created, so the standard-defaults fallback in the russhwolf docs
    // isn't needed here.
    val defaults = NSUserDefaults(suiteName = name)
    return NSUserDefaultsSettings(defaults)
}
