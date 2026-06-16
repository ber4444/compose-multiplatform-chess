package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Box

/**
 * Local copy of the commonTest fake. androidDeviceTest is an instrumented source set
 * and is not part of the unit-test hierarchy, so it cannot see commonTest. Keep this
 * in sync with app/src/commonTest/.../board3d/FakeChess3DRenderer.kt.
 */
class FakeChess3DRenderer : Chess3DBoardRenderer {
    val events = mutableListOf<String>()
    var lastFen: String? = null
    var isAttached = false
    var currentSurface: Chess3DSurface? = null

    override fun attach(surface: Chess3DSurface) {
        if (isAttached) {
            events.add("detach")
        }
        isAttached = true
        currentSurface = surface
        events.add("attach")
        lastFen?.let {
            events.add("updatePosition:$it")
        }
    }

    override fun detach() {
        if (isAttached) {
            isAttached = false
            currentSurface = null
            events.add("detach")
        }
    }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        lastFen = fen
        if (isAttached) {
            events.add("updatePosition:$fen" + if (transition != null && transition !is Board3DTransition.Reset) ":animate" else "")
        }
    }

    override fun onUserInteraction(event: Board3DInput) {
        events.add("input:${event::class.simpleName}")
    }

    override fun dispose() {
        events.add("dispose")
    }
}

class FakeChess3DSurface(override val widthPx: Int = 100, override val heightPx: Int = 100) : Chess3DSurface

/**
 * A [Board3DSupport] whose surface is a plain Compose [Box] (no SceneView). The real
 * [androidBoard3DSupport] hosts a live SceneView whose render loop never lets the Compose test
 * clock go idle, so `waitForIdle()` (and the finders that call it) hang. This fake keeps the
 * board_3d tag + GameScreen 3D wiring (toggle, selection routing, FEN updates) testable under
 * `runComposeUiTest`. It does NOT exercise actual GPU rendering — that's verified manually / by
 * the desktop+iOS render tests.
 */
fun fakeBoard3DSupport(renderer: FakeChess3DRenderer = FakeChess3DRenderer()): Board3DSupport =
    Board3DSupport(
        rendererFactory = { renderer },
        surfaceContent = { _, modifier -> Box(modifier) },
    )
