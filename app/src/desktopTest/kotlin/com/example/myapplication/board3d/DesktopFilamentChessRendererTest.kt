package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFilamentChessRendererTest {
    @Test
    fun `desktop filament peer ignores frames before attach`() {
        val peer = DesktopFilamentPeer(nativeHandle = 0L) { _, _, _ -> Unit }

        peer.setScene("")
        peer.setCamera("0,0,0,0,0,0,0,1,0,45,1")
        peer.detach()

        assertEquals(0L, peer.nativeHandle)
    }
}
