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
