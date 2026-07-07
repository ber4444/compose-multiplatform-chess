package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualBaselineScenesTest {

    @Test
    fun `all scenes parse through Board3DSceneMapper`() {
        for (scene in VisualBaselineScenes.ALL) {
            val parsed = Board3DSceneMapper.fromFen(scene.fen)
            assertTrue(parsed.pieces.isNotEmpty(), "${scene.id} produced no pieces")
        }
    }

    @Test
    fun `endgame closeup frames the king and is closer than the default view`() {
        val defaultDist = OrbitCameraController.DEFAULT_WHITE_VIEW.let {
            (it.position - it.target).length()
        }
        val closeup = VisualBaselineScenes.ENDGAME_SINGLE_PIECE_CLOSEUP
        val closeupDist = (closeup.camera.position - closeup.camera.target).length()

        assertTrue(closeupDist < defaultDist, "closeup ($closeupDist) should be tighter than default ($defaultDist)")
        // e4 = (4 - 3.5, 0, 4 - 3.5) = (0.5, 0, 0.5)
        assertEquals(0.5f, closeup.camera.target.x, 0.001f)
        assertEquals(0.5f, closeup.camera.target.z, 0.001f)
    }

    @Test
    fun `ALL has exactly the three documented baseline scenes in stable order`() {
        assertEquals(
            listOf("start-high-lighting", "midgame-shadows", "endgame-single-piece-closeup"),
            VisualBaselineScenes.ALL.map { it.id },
        )
    }

    @Test
    fun `baseName produces stable cross-platform filenames`() {
        assertEquals(
            "scene-start-high-lighting-android",
            VisualBaselineScenes.baseName(VisualBaselineScenes.START_POSITION_HIGH_LIGHTING, "android"),
        )
    }
}
