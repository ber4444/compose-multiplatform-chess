package com.example.myapplication.board3d

sealed interface Board3DInput {
    /** Normalized [0,1] surface coords, origin top-left. M1 renderers may ignore; M5 consumes via ray pick. */
    data class Tap(val xNorm: Float, val yNorm: Float) : Board3DInput
    /** Camera orbit — purely visual state, owned by the 3D layer per the issue. */
    data class Drag(val deltaXNorm: Float, val deltaYNorm: Float) : Board3DInput
    data class Zoom(val factor: Float) : Board3DInput
    data class Resize(val widthPx: Int, val heightPx: Int) : Board3DInput
    /** Host-computed camera (from OrbitCameraController); renderers just render it. */
    data class SetCamera(val camera: CameraParams) : Board3DInput
}
