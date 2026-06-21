package com.example.myapplication

import android.app.Application

/**
 * Process-wide Application entry. Exists so that [com.example.myapplication.persistence.createSettings]
 * can read a `Context` without the factory signature having to thread one through every call site.
 * Registered in `androidApp/src/main/AndroidManifest.xml` via `android:name`.
 */
class ChessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.example.myapplication.persistence.appContext = this
    }
}
