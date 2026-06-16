package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeChess3DRendererTest {

    @Test
    fun `updatePosition before attach buffers fen and applies on attach`() {
        val fake = FakeChess3DRenderer()
        
        // updatePosition before attach
        fake.updatePosition("some_fen")
        assertEquals(emptyList<String>(), fake.events)
        assertEquals("some_fen", fake.lastFen)
        assertFalse(fake.isAttached)
        
        // now attach
        val surface = object : Chess3DSurface {
            override val widthPx = 100
            override val heightPx = 100
        }
        fake.attach(surface)
        
        assertEquals(listOf("attach", "updatePosition:some_fen"), fake.events)
        assertTrue(fake.isAttached)
        // lastFen should still be "some_fen"
        assertEquals("some_fen", fake.lastFen)
    }

    @Test
    fun `double attach records a detach first`() {
        val fake = FakeChess3DRenderer()
        val surface1 = object : Chess3DSurface {
            override val widthPx = 100
            override val heightPx = 100
        }
        val surface2 = object : Chess3DSurface {
            override val widthPx = 200
            override val heightPx = 200
        }
        
        fake.attach(surface1)
        assertEquals(listOf("attach"), fake.events)
        assertTrue(fake.isAttached)
        
        fake.attach(surface2)
        assertEquals(listOf("attach", "detach", "attach"), fake.events)
        assertTrue(fake.isAttached)
    }
}
