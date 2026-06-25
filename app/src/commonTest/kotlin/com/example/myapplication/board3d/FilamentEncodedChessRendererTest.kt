package com.example.myapplication.board3d

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeFilamentPeer : FilamentChessPeer {
    val events = mutableListOf<String>()
    var isShutdown = false

    override fun setScene(encoded: String) {
        events += "scene:$encoded"
    }

    override fun setCamera(encoded: String) {
        events += "camera:$encoded"
    }

    override fun resize(widthPx: Int, heightPx: Int) {
        events += "resize:${widthPx}x$heightPx"
    }

    override fun attach(surface: Chess3DSurface?) {
        events += "attach:${surface?.widthPx ?: 0}x${surface?.heightPx ?: 0}"
    }

    override fun detach() {
        events += "detach"
    }

    override fun shutdown() {
        isShutdown = true
        events += "shutdown"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilamentEncodedChessRendererTest {
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun `position before attach is rendered when attached`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer, TestScope(StandardTestDispatcher(testScheduler)))
        renderer.updatePosition(startFen)

        assertEquals(emptyList(), peer.events)

        renderer.attach(object : Chess3DSurface {
            override val widthPx = 320
            override val heightPx = 240
        })
        advanceUntilIdle()

        assertTrue(peer.events.first() == "attach:320x240")
        assertTrue(peer.events.any { it.startsWith("camera:") })
        assertTrue(peer.events.any { it.startsWith("scene:") })
    }

    @Test
    fun `resize updates peer and camera aspect`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer, TestScope(StandardTestDispatcher(testScheduler)))
        renderer.attach(object : Chess3DSurface {
            override val widthPx = 100
            override val heightPx = 100
        })

        renderer.onUserInteraction(Board3DInput.Resize(400, 200))

        assertTrue(peer.events.contains("resize:400x200"))
        assertTrue(peer.events.last { it.startsWith("camera:") }.endsWith(",2.0"))
    }

    @Test
    fun `zero size attach does not resize peer`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer, TestScope(StandardTestDispatcher(testScheduler)))

        renderer.attach(object : Chess3DSurface {
            override val widthPx = 0
            override val heightPx = 0
        })

        assertTrue(peer.events.contains("attach:0x0"))
        assertTrue(peer.events.none { it.startsWith("resize:") })
    }

    @Test
    fun `dispose shuts down peer once`() {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer)

        renderer.dispose()
        renderer.dispose()

        assertTrue(peer.isShutdown)
        assertEquals(1, peer.events.count { it == "shutdown" })
    }
}
