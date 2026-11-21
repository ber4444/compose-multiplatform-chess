package com.example.myapplication.explorer

// The web build has no Maia runtime yet: onnxruntime-web (WASM backend) in a worker, as LiteRT-LM for Web already is, is the likely route.
internal actual fun maiaRuntimeFactory(): (() -> MaiaRuntime)? = null
