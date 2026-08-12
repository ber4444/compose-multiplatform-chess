package com.example.myapplication.board3d

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `chess.glb` exists three times in this repo and every backend claims to share "the same asset".
 * They did not.
 *
 * Android and desktop read the Compose Resources copy. **iOS reads its own** — the `.mm` calls
 * `[[NSBundle mainBundle] pathForResource:@"chess" ofType:@"glb"]`, which resolves to the copy
 * `iosApp/project.yml` puts in the app bundle root from `iosApp/Resources` — and **wasm reads its
 * own** from `wasmJsMain/resources`. Nothing copies between them, so an asset change lands on one
 * or two backends and silently skips the rest.
 *
 * That is not hypothetical. When this test was written the iOS and wasm copies were byte-identical
 * to each other and two changes behind: they had never received #126's Draco compression (6.0 MB vs
 * 5.2 MB) nor #128's `Highlight` node and material — so the B16 coach highlight could not have been
 * drawn from the asset on either backend, and B19's tone quads would not have been either. There is
 * no crash and no log line when a node lookup finds nothing; the quad is simply never shown.
 *
 * The right fix is one asset with a copy step at build time. Until then this fails the moment they
 * drift, which is the property that was missing.
 */
class ChessGlbCopiesInSyncTest {

    /** Tests run with `app/` as the working directory (see `DesktopRendererSmokeTest`). */
    private val canonical = File("src/commonMain/composeResources/files/models/chess.glb")

    private val copies = listOf(
        File("../iosApp/iosApp/Resources/chess.glb"),
        File("src/wasmJsMain/resources/chess.glb"),
    )

    @Test
    fun everyBackendReadsTheSameAsset() {
        assertTrue(canonical.exists(), "canonical asset missing at ${canonical.absolutePath}")
        val expected = canonical.sha256()

        for (copy in copies) {
            assertTrue(copy.exists(), "missing backend copy: ${copy.absolutePath}")
            assertEquals(
                expected,
                copy.sha256(),
                "${copy.path} has drifted from ${canonical.path}. Copy the canonical file over it — " +
                    "a backend reading a stale chess.glb silently loses every node added since.",
            )
        }
    }

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
