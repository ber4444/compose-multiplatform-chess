package com.example.myapplication.board3d

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

    override fun updatePosition(fen: String) {
        lastFen = fen
        if (isAttached) {
            events.add("updatePosition:$fen")
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
