package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class WgpuShaderRegressionTest {
    @Test
    fun `environment direction keeps world up after face upload conversion`() {
        assertFalse(WGPU_SHADER.contains("envN.y = -envN.y"))
        assertFalse(WGPU_SHADER.contains("envR.y = -envR.y"))
        assertFalse(SKY_SHADER.contains("sampleDir.y = -sampleDir.y"))
    }

    @Test
    fun `stone rim uses the shared direct and environment lighting path`() {
        assertTrue(WGPU_SHADER.contains("let Lo ="))
        assertTrue(WGPU_SHADER.contains("let ambient ="))
        assertFalse(WGPU_SHADER.contains("unlit"))
        assertEquals(0.68f, wgpuMaterialRoughness(ChessTexture.FRAME))
        assertTrue(ChessSceneGeometry.FRAME_TINT_VALUE <= 0.3f)
    }
}
