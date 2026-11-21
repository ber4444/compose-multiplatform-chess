package com.example.myapplication.explorer

// iOS has no Maia runtime yet: ONNX Runtime's Objective-C package or a Core ML conversion would host it, behind a Swift bridge like Foundation Models'.
internal actual fun maiaRuntimeFactory(): (() -> MaiaRuntime)? = null
