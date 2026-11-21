package com.example.myapplication.explorer

// Android has no Maia runtime yet: onnxruntime-android (or a LiteRT conversion) is the next step, and the 3 ms figure has not been measured on a phone.
internal actual fun createMaiaRuntime(): MaiaRuntime? = null
