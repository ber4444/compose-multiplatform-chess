package com.example.myapplication.board3d

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

internal object DesktopFilamentNative {
    private var loaded = false

    fun load() {
        if (loaded) return
        tryLoadForTest(defaultLibraryCandidates()).getOrThrow()
        loaded = true
    }

    internal fun tryLoadForTest(candidates: List<Path>): Result<Unit> = runCatching {
        val lib = candidates.firstOrNull { it.exists() }
            ?: error(
                "Desktop Filament native bridge was not found. Run tools/fetch_filament_desktop.sh " +
                    "and build the native bridge before launching desktop 3D."
            )
        System.load(lib.absolutePathString())
    }

    private fun defaultLibraryCandidates(): List<Path> {
        val mapped = System.mapLibraryName("desktop_filament_bridge")
        val cwd = Path.of(System.getProperty("user.dir"))
        val packagedResources = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).resolve(mapped) }
        return listOfNotNull(
            cwd.resolve("app/build/desktop-filament-native/cmake").resolve(mapped),
            cwd.resolve("app/build/desktop-filament-native/cmake/Release").resolve(mapped),
            cwd.resolve("build/desktop-filament-native/cmake").resolve(mapped),
            cwd.resolve("build/desktop-filament-native/cmake/Release").resolve(mapped),
            packagedResources,
        )
    }

    external fun nativeCreate(glb: ByteArray, ibl: ByteArray, skybox: ByteArray): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeResize(handle: Long, width: Int, height: Int)
    external fun nativeSetScene(handle: Long, encoded: String)
    external fun nativeSetCamera(handle: Long, encoded: String)
    external fun nativeRenderRgba(handle: Long): ByteArray?
    external fun nativeLastError(handle: Long): String?

    fun lastError(handle: Long): String? = nativeLastError(handle)
}
