package com.example.myapplication.explorer

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Desktop's Maia checks: the bundled ONNX file is the pinned one, it is what runs, and it matches PyTorch. */
class OnnxMaiaRuntimeTest {
    @Test
    fun bundledModelIsWhatTheProvenanceDocumentPins() {
        val bytes = BundledMaiaModel.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(BundledMaiaModel.SHA256, sha, "re-export with tools/maia3 and update docs/maia3-model-provenance.md")
    }

    @Test
    fun desktopRunsTheOnnxExport(): Unit = runBlocking {
        val open = assertNotNull(maiaRuntimeFactory(), "desktop must find the bundled model")
        open().use { runtime -> assertTrue(runtime is OrtMaiaRuntime) }
    }

    @Test
    fun topMovesMatchReferencePyTorch(): Unit = runBlocking {
        BundledMaiaParity.assertMatchesPyTorch(assertNotNull(maiaRuntimeFactory()), BundledMaiaParity.FLOAT_SHARE_TOLERANCE)
    }
}
