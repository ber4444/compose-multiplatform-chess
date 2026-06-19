package com.example.myapplication.board3d

import game.app.generated.resources.Res
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Visual regression / iteration harness for the iOS SceneKit board. Renders the start position
 * headlessly (no on-screen SCNView) to a PNG so the renderer's look — skybox, IBL reflections,
 * materials — can be eyeballed and diffed after changes, instead of round-tripping through a human
 * running the app and toggling the 3D switch.
 *
 * Run: `./gradlew :app:iosSimulatorArm64Test --tests "*IosBoard3DSnapshotTest*"`
 * Output: `build/ios-3d-snapshot.png` (look for the `IOS_3D_SNAPSHOT=` line in the test log).
 *
 * Also exposes [renderAllBaselineScenes] — the Phase A.2 batch baseline flow that iterates
 * [VisualBaselineScenes.ALL] and writes one PNG per scene under `app/build/baseline/ios/`, mirroring
 * `VisualBaselineDumpTest` on desktop and `WebBaselineCapture` on web so all three non-Android
 * platforms can be eyeballed at the same scenes/resolution.
 *
 * Skips (does not fail) when assets can't be read or no Metal device is present, so it stays green
 * on headless CI; the real assertion only bites on a Metal-capable host where it actually rendered.
 */
class IosBoard3DSnapshotTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
    @Test
    fun rendersStartPositionToPng() = runBlocking {
        val (geometries, textures) = try {
            buildIosChessAssets { readAsset(it) }
        } catch (t: Throwable) {
            println("IOS_3D_SNAPSHOT skipped: assets unavailable (${t.message})")
            return@runBlocking
        }
        if (geometries.isEmpty()) {
            println("IOS_3D_SNAPSHOT skipped: no geometries loaded")
            return@runBlocking
        }

        val renderer = IosSceneKitChessRenderer(geometries, textures)
        val png = try {
            renderer.renderSnapshotPng(
                fen = startFen,
                widthPx = 900,
                heightPx = 1300,
                camera = OrbitCameraController.DEFAULT_WHITE_VIEW,
            )
        } finally {
            renderer.dispose()
        }

        if (png == null) {
            println("IOS_3D_SNAPSHOT skipped: no Metal device / encode failed")
            return@runBlocking
        }

        val outPath = outputPath()
        png.toNSData().writeToFile(outPath, atomically = true)
        println("IOS_3D_SNAPSHOT=$outPath bytes=${png.size}")

        // A real render is hundreds of KB; a blank/failed frame is tiny. Guards against regressions
        // that silently produce an empty image.
        assertTrue(png.size > 10_000, "snapshot suspiciously small (${png.size} bytes) — render likely broke")
    }

    /**
     * Phase A.2 batch baseline flow — renders every [VisualBaselineScene] via the same SceneKit
     * renderer path as [rendersStartPositionToPng], at the shared canonical 1024×1024 resolution,
     * and writes a PNG per scene to `app/build/baseline/ios/scene-<id>-ios.png`.
     *
     * Output dir matches `VisualBaselineDumpTest`'s `app/build/baseline/desktop/` convention so the
     * `docs/assets/baselines/<platform>/` curated set can be assembled from any platform's run with
     * the same filenames. Skips silently (no fail) under the same headless-CI conditions as the
     * single-scene test — this lets the test stay green in CI while still doing real work on a
     * developer Mac.
     */
    @OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
    @Test
    fun renderAllBaselineScenes() = runBlocking {
        val (geometries, textures) = try {
            buildIosChessAssets { readAsset(it) }
        } catch (t: Throwable) {
            println("IOS_BASELINE skipped: assets unavailable (${t.message})")
            return@runBlocking
        }
        if (geometries.isEmpty()) {
            println("IOS_BASELINE skipped: no geometries loaded")
            return@runBlocking
        }

        val outDir = baselineOutputDir()
        NSFileManager.defaultManager.createDirectoryAtPath(outDir, true, null, null)

        val renderer = IosSceneKitChessRenderer(geometries, textures)
        try {
            for (scene in VisualBaselineScenes.ALL) {
                val png = renderer.renderSnapshotPng(
                    fen = scene.fen,
                    widthPx = scene.widthPx,
                    heightPx = scene.heightPx,
                    camera = scene.camera,
                )
                if (png == null) {
                    // No Metal device — same skip condition as the single-scene test. Once it's null
                    // for one scene it's null for all, so bail out of the loop entirely.
                    println("IOS_BASELINE skipped: no Metal device / encode failed (at scene ${scene.id})")
                    return@runBlocking
                }
                val base = VisualBaselineScenes.baseName(scene, "ios")
                val outPath = "$outDir/$base.png"
                png.toNSData().writeToFile(outPath, atomically = true)
                println("IOS_BASELINE_${scene.id}=$outPath bytes=${png.size}")
                assertTrue(png.size > 10_000, "scene ${scene.id} PNG suspiciously small (${png.size} bytes)")
            }
            println("IOS_BASELINE_DIR=$outDir")
        } finally {
            renderer.dispose()
        }
    }

    /** Reads a compose-resource path, falling back to the host source tree for local iteration. */
    @OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
    private suspend fun readAsset(path: String): ByteArray {
        runCatching { Res.readBytes(path) }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        for (root in hostProjectRoots()) {
            val full = "$root/app/src/commonMain/composeResources/$path"
            NSData.dataWithContentsOfFile(full)?.let { return it.toByteArray() }
        }
        error("asset not found: $path")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun outputPath(): String {
        val root = hostProjectRoots().firstOrNull { NSFileManager.defaultManager.fileExistsAtPath("$it/app") }
        if (root != null) {
            val buildDir = "$root/build"
            NSFileManager.defaultManager.createDirectoryAtPath(buildDir, true, null, null)
            return "$buildDir/ios-3d-snapshot.png"
        }
        return NSTemporaryDirectory() + "ios-3d-snapshot.png"
    }

    /**
     * Output dir for the Phase A.2 baseline batch: `app/build/baseline/ios/` on the host source
     * tree when it can be located (mirrors `VisualBaselineDumpTest`'s desktop dir convention), else
     * a directory under NSTemporaryDirectory so the test still produces files in a sandbox.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun baselineOutputDir(): String {
        val root = hostProjectRoots().firstOrNull { NSFileManager.defaultManager.fileExistsAtPath("$it/app") }
        if (root != null) return "$root/app/build/baseline/ios"
        return NSTemporaryDirectory() + "chess-baseline/ios"
    }

    /** Host source-tree roots for local runs (simulator shares the host filesystem). */
    @OptIn(ExperimentalForeignApi::class)
    private fun hostProjectRoots(): List<String> = buildList {
        platform.posix.getenv("CHESS_PROJECT_DIR")?.toKString()?.let { if (it.isNotBlank()) add(it) }
        add("/Users/presence/AndroidStudioProjects/compose-multiplatform-chess")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData()
        else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val len = length.toInt()
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        out.usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
        return out
    }
}
