package com.example.myapplication

import android.app.Application
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.createSettings
import io.github.sceneview.EngineWarmUp

/**
 * Process-wide Application entry. Exists so that [com.example.myapplication.persistence.createSettings]
 * can read a `Context` without the factory signature having to thread one through every call site.
 * Registered in `androidApp/src/main/AndroidManifest.xml` via `android:name`.
 */
class ChessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.example.myapplication.persistence.appContext = this
        // With the 3D board on (the default) it is the first screen, which is the case SceneView's
        // EngineWarmUp exists for: Filament's libraries, the EGL context and gltfio's texture tables
        // are prepared on its own thread, so the board's first composition only makes the context
        // current instead of doing that work on the main thread. Skipped when the player has 3D
        // off: a launch that never shows 3D would pay 60–80 ms of CPU and an EGL context for it.
        if (AppSettings(createSettings("chess")).board3DEnabled.value) EngineWarmUp.start()
    }
}
