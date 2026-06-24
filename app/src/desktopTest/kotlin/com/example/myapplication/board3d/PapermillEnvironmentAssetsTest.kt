package com.example.myapplication.board3d

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PapermillEnvironmentAssetsTest {

    @Test
    fun papermillSkyboxUsesVkChessScaleCubemapInEveryPackagedCopy() {
        val skyboxes = listOf(
            File("src/commonMain/composeResources/files/env/papermill_skybox.ktx"),
            File("src/desktopMain/resources/papermill_skybox.ktx"),
            File("src/wasmJsMain/resources/papermill_skybox.ktx"),
        )

        for (file in skyboxes) {
            assertTrue(file.exists(), "${file.path} must exist")
            val ktx = KtxLoader.load(file.readBytes())
            assertTrue(ktx != null, "${file.path} must be a readable KTX cubemap")
            try {
                assertEquals(256, ktx.width, "${file.path} should match vkChess skybox width")
                assertEquals(256, ktx.height, "${file.path} should match vkChess skybox height")
                assertEquals(6, ktx.faces, "${file.path} must contain all cubemap faces")
                assertEquals(0x8C3A, ktx.glInternalFormat, "${file.path} should stay in cmgen KTX format")
            } finally {
                ktx.free()
            }
        }
    }
}
