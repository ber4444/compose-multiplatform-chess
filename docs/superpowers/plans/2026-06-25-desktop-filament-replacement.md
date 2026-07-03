# Desktop Filament Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the desktop JVM 3D chess board backend with Google Filament while reusing the iOS/web Filament scene protocol and keeping the current Compose Desktop surface contract.

**Architecture:** Add a shared Kotlin encoded-renderer lifecycle that both iOS and desktop use, then add a desktop JNI/CMake bridge to Filament C++ using a headless swap chain and `readPixels`. Web remains JavaScript Filament but shares the same `Board3DScene.encode`, camera encoding, asset names, and constants.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform desktop, Kotlin coroutines, JNI, CMake, C++17, Google Filament v1.72.0, gltfio, KTX reader, Gradle.

---

## File Structure

- Create `app/src/commonMain/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRenderer.kt`
  - Shared Kotlin lifecycle for encoded Filament peers.
- Create `app/src/commonTest/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRendererTest.kt`
  - Tests buffering, attach, resize, camera, and disposal behavior with a fake peer.
- Modify `app/src/iosMain/kotlin/com/example/myapplication/board3d/FilamentIosChessRenderer.kt`
  - Keep UIKitView hosting, replace duplicated renderer lifecycle with shared wrapper.
- Create `tools/fetch_filament_desktop.sh`
  - Fetch and stage Filament v1.72.0 desktop payloads.
- Modify `.gitignore`
  - Ignore staged desktop Filament payloads and native build outputs.
- Create `app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopFilamentNative.kt`
  - JNI loader and narrow native facade.
- Create `app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopFilamentChessRenderer.kt`
  - Desktop peer + `Chess3DBoardRenderer` implementation backed by shared lifecycle.
- Create `app/src/desktopMain/native/filament_bridge/CMakeLists.txt`
  - Native bridge build.
- Create `app/src/desktopMain/native/filament_bridge/desktop_filament_bridge.cpp`
  - JNI functions and desktop host.
- Create `app/src/desktopMain/native/filament_bridge/filament_chess_core.h`
  - Shared C++ renderer core API.
- Create `app/src/desktopMain/native/filament_bridge/filament_chess_core.cpp`
  - Filament scene setup, glTF instancing, scene reconciliation, camera, and readback.
- Modify `iosApp/iosApp/Filament/FilamentChessRenderer.mm`
  - Extract or mirror scene-core constants and parsing to match desktop; keep iOS host thin.
- Modify `app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopBoard3D.kt`
  - Switch factory from Vulkan to desktop Filament.
- Modify `app/build.gradle.kts`
  - Wire CMake native build, runtime library paths, desktop packaging, and remove Vulkan dependencies after replacement.
- Modify `gradle/libs.versions.toml`
  - Remove unused Vulkan/shaderc entries after desktop no longer compiles Vulkan.
- Modify `.github/workflows/android-tests.yml`
  - Fetch desktop Filament before desktop build/test and remove lavapipe install when Vulkan tests are gone.
- Modify docs mentioning desktop Vulkan as current behavior.
  - Update `AGENTS.md` if present in repo, `docs/plans/*` current-status notes, and README references found by `rg "desktop Vulkan|VulkanChessRenderer|lavapipe"`.

---

### Task 1: Shared Kotlin Encoded Renderer Lifecycle

**Files:**
- Create: `app/src/commonMain/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRenderer.kt`
- Create: `app/src/commonTest/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRendererTest.kt`

- [ ] **Step 1: Write the failing test**

Create `FilamentEncodedChessRendererTest.kt`:

```kotlin
package com.example.myapplication.board3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeFilamentPeer : FilamentChessPeer {
    val events = mutableListOf<String>()
    var isShutdown = false

    override fun setScene(encoded: String) { events += "scene:$encoded" }
    override fun setCamera(encoded: String) { events += "camera:$encoded" }
    override fun resize(widthPx: Int, heightPx: Int) { events += "resize:${widthPx}x$heightPx" }
    override fun attach(surface: Chess3DSurface?) { events += "attach:${surface?.widthPx ?: 0}x${surface?.heightPx ?: 0}" }
    override fun detach() { events += "detach" }
    override fun shutdown() { isShutdown = true; events += "shutdown" }
}

class FilamentEncodedChessRendererTest {
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun `position before attach is rendered when attached`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer, TestScope(StandardTestDispatcher(testScheduler)))
        renderer.updatePosition(startFen)

        assertEquals(emptyList(), peer.events)

        renderer.attach(object : Chess3DSurface {
            override val widthPx = 320
            override val heightPx = 240
        })
        testScheduler.advanceUntilIdle()

        assertTrue(peer.events.first() == "attach:320x240")
        assertTrue(peer.events.any { it.startsWith("camera:") })
        assertTrue(peer.events.any { it.startsWith("scene:") })
    }

    @Test
    fun `resize updates peer and camera aspect`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer, TestScope(StandardTestDispatcher(testScheduler)))
        renderer.attach(object : Chess3DSurface {
            override val widthPx = 100
            override val heightPx = 100
        })

        renderer.onUserInteraction(Board3DInput.Resize(400, 200))

        assertTrue(peer.events.contains("resize:400x200"))
        assertTrue(peer.events.last { it.startsWith("camera:") }.endsWith(",2.0"))
    }

    @Test
    fun `dispose shuts down peer once`() {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer)

        renderer.dispose()
        renderer.dispose()

        assertTrue(peer.isShutdown)
        assertEquals(1, peer.events.count { it == "shutdown" })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:desktopTest --tests "com.example.myapplication.board3d.FilamentEncodedChessRendererTest"`

Expected: FAIL with unresolved references to `FilamentChessPeer` and `FilamentEncodedChessRenderer`.

- [ ] **Step 3: Implement shared lifecycle**

Create `FilamentEncodedChessRenderer.kt`:

```kotlin
package com.example.myapplication.board3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

interface FilamentChessPeer {
    fun setScene(encoded: String)
    fun setCamera(encoded: String)
    fun resize(widthPx: Int, heightPx: Int)
    fun attach(surface: Chess3DSurface?)
    fun detach()
    fun shutdown()
}

class FilamentEncodedChessRenderer(
    private val peer: FilamentChessPeer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) : Chess3DBoardRenderer {
    private var pendingFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private var camera = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var selectedSquare: BoardSquare? = null
    private var isReady = false
    private var isDisposed = false

    private val driver = Board3DAnimationDriver(scope) { scene ->
        if (isReady && !isDisposed) peer.setScene(scene.encode())
    }

    override fun attach(surface: Chess3DSurface) {
        if (isDisposed) return
        isReady = true
        peer.attach(surface)
        peer.resize(surface.widthPx, surface.heightPx)
        camera = camera.copy(aspect = surface.widthPx.toFloat() / surface.heightPx.coerceAtLeast(1).toFloat())
        applyCamera()
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(pendingFen) }.getOrNull(), null)
        driver.setSelected(selectedSquare)
        driver.refresh()
    }

    override fun detach() {
        if (!isReady || isDisposed) return
        isReady = false
        peer.detach()
    }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        if (isDisposed) return
        pendingFen = fen
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(fen) }.getOrNull(), transition)
    }

    override fun onUserInteraction(event: Board3DInput) {
        if (isDisposed) return
        when (event) {
            is Board3DInput.SetCamera -> {
                camera = event.camera
                applyCamera()
            }
            is Board3DInput.Resize -> {
                if (event.widthPx > 0 && event.heightPx > 0) {
                    camera = camera.copy(aspect = event.widthPx.toFloat() / event.heightPx.toFloat())
                    if (isReady) peer.resize(event.widthPx, event.heightPx)
                    applyCamera()
                }
            }
            else -> Unit
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        if (isDisposed) return
        selectedSquare = square
        driver.setSelected(square)
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        isReady = false
        driver.cancel()
        scope.cancel()
        peer.shutdown()
    }

    private fun applyCamera() {
        if (!isReady || isDisposed) return
        val cam = camera
        peer.setCamera(
            "${cam.position.x},${cam.position.y},${cam.position.z}," +
                "${cam.target.x},${cam.target.y},${cam.target.z}," +
                "${cam.up.x},${cam.up.y},${cam.up.z}," +
                "${cam.fovYDegrees},${cam.aspect}"
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:desktopTest --tests "com.example.myapplication.board3d.FilamentEncodedChessRendererTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/commonMain/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRenderer.kt \
  app/src/commonTest/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRendererTest.kt
git commit -m "feat: share encoded filament renderer lifecycle"
```

---

### Task 2: Move iOS Kotlin Wrapper Onto Shared Lifecycle

**Files:**
- Modify: `app/src/iosMain/kotlin/com/example/myapplication/board3d/FilamentIosChessRenderer.kt`
- Test: `app/src/commonTest/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRendererTest.kt`

- [ ] **Step 1: Write the failing regression test**

Append this test to `FilamentEncodedChessRendererTest.kt`:

```kotlin
@Test
fun `set selected square before attach is applied on attach`() = runTest {
    val peer = FakeFilamentPeer()
    val renderer = FilamentEncodedChessRenderer(peer, TestScope(StandardTestDispatcher(testScheduler)))

    renderer.updatePosition(startFen)
    renderer.setSelectedSquare(BoardSquare(row = 7, col = 4))
    renderer.attach(object : Chess3DSurface {
        override val widthPx = 300
        override val heightPx = 300
    })
    testScheduler.advanceTimeBy(20)

    assertTrue(peer.events.any { it.startsWith("scene:") })
}
```

- [ ] **Step 2: Run test to verify it fails if selection buffering is broken**

Run: `./gradlew :app:desktopTest --tests "com.example.myapplication.board3d.FilamentEncodedChessRendererTest"`

Expected: PASS after Task 1 if selection buffering was included. If it fails, fix Task 1 before editing iOS.

- [ ] **Step 3: Refactor iOS wrapper**

Change `FilamentIosChessRenderer` to delegate to `FilamentEncodedChessRenderer`:

```kotlin
class FilamentIosChessRenderer(factory: FilamentChessViewFactory) : Chess3DBoardRenderer {
    val nativeView: FilamentChessNativeView = factory.create()

    private val delegate = FilamentEncodedChessRenderer(
        peer = object : FilamentChessPeer {
            override fun setScene(encoded: String) = nativeView.setScene(encoded)
            override fun setCamera(encoded: String) = nativeView.setCamera(encoded)
            override fun resize(widthPx: Int, heightPx: Int) = nativeView.resize(widthPx, heightPx)
            override fun attach(surface: Chess3DSurface?) {}
            override fun detach() {}
            override fun shutdown() = nativeView.shutdown()
        },
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    )

    override fun attach(surface: Chess3DSurface) = delegate.attach(surface)
    override fun detach() = delegate.detach()
    override fun updatePosition(fen: String) = delegate.updatePosition(fen)
    override fun updatePosition(fen: String, transition: Board3DTransition?) = delegate.updatePosition(fen, transition)
    override fun onUserInteraction(event: Board3DInput) = delegate.onUserInteraction(event)
    override fun setSelectedSquare(square: BoardSquare?) = delegate.setSelectedSquare(square)
    override fun dispose() = delegate.dispose()
}
```

Keep `FilamentIosBoard3DSurface` and protocol declarations in the same file.

- [ ] **Step 4: Run iOS Kotlin compile/test**

Run: `./gradlew :app:iosSimulatorArm64Test -PiosSimulatorDeviceId=iPhone 16`

Expected: PASS or simulator-environment failure unrelated to Kotlin compile. Kotlin compile errors must be fixed.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/iosMain/kotlin/com/example/myapplication/board3d/FilamentIosChessRenderer.kt \
  app/src/commonTest/kotlin/com/example/myapplication/board3d/FilamentEncodedChessRendererTest.kt
git commit -m "refactor: reuse encoded filament lifecycle on ios"
```

---

### Task 3: Desktop Filament Fetch Script And Ignore Rules

**Files:**
- Create: `tools/fetch_filament_desktop.sh`
- Modify: `.gitignore`

- [ ] **Step 1: Write a failing script smoke check**

Run: `test -x tools/fetch_filament_desktop.sh`

Expected: FAIL because the script does not exist.

- [ ] **Step 2: Add fetch script**

Create `tools/fetch_filament_desktop.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-1.72.0}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/app/src/desktopMain/filament/filament"

uname_s="$(uname -s)"
case "$uname_s" in
  Darwin) PLATFORM="mac" ;;
  Linux) PLATFORM="linux" ;;
  MINGW*|MSYS*|CYGWIN*) PLATFORM="windows" ;;
  *) echo "Unsupported desktop Filament host: $uname_s" >&2; exit 1 ;;
esac

TARBALL="filament-v${VERSION}-${PLATFORM}.tgz"
URL="https://github.com/google/filament/releases/download/v${VERSION}/${TARBALL}"

echo "==> Filament desktop v${VERSION} (${PLATFORM})"
echo "    from: $URL"
echo "    into: $DEST"

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fL --retry 3 -o "$TMP/$TARBALL" "$URL"
tar xzf "$TMP/$TARBALL" -C "$TMP"
rsync -a --delete "$TMP/filament/" "$DEST/"

test -d "$DEST/include"
test -d "$DEST/lib"

echo "==> Staged Filament desktop payload:"
find "$DEST" -maxdepth 2 \( -type f -o -type l \) | sed "s#^$REPO_ROOT/##" | sort | head -80
echo "==> Done."
```

- [ ] **Step 3: Make script executable**

Run: `chmod +x tools/fetch_filament_desktop.sh`

- [ ] **Step 4: Ignore downloaded payloads**

Add to `.gitignore`:

```gitignore
# Filament desktop release payload fetched by tools/fetch_filament_desktop.sh
app/src/desktopMain/filament/filament/

# Desktop native bridge build output
app/build/desktop-filament-native/
```

- [ ] **Step 5: Verify script shape without downloading**

Run: `bash -n tools/fetch_filament_desktop.sh && test -x tools/fetch_filament_desktop.sh`

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add tools/fetch_filament_desktop.sh .gitignore
git commit -m "build: add desktop filament fetch script"
```

---

### Task 4: Desktop Native Loader Facade

**Files:**
- Create: `app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopFilamentNative.kt`
- Create: `app/src/desktopTest/kotlin/com/example/myapplication/board3d/DesktopFilamentNativeTest.kt`

- [ ] **Step 1: Write failing loader test**

Create `DesktopFilamentNativeTest.kt`:

```kotlin
package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopFilamentNativeTest {
    @Test
    fun `native library path error names fetch script`() {
        val result = DesktopFilamentNative.tryLoadForTest(emptyList())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tools/fetch_filament_desktop.sh") == true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:desktopTest --tests "com.example.myapplication.board3d.DesktopFilamentNativeTest"`

Expected: FAIL with unresolved reference `DesktopFilamentNative`.

- [ ] **Step 3: Implement loader facade**

Create `DesktopFilamentNative.kt`:

```kotlin
package com.example.myapplication.board3d

import java.nio.file.Files
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
        val buildDir = cwd.resolve("app/build/desktop-filament-native")
        return listOf(
            buildDir.resolve(mapped),
            cwd.resolve("build/desktop-filament-native").resolve(mapped),
        ).filter { Files.exists(it.parent) || true }
    }

    external fun nativeCreate(glb: ByteArray, ibl: ByteArray, skybox: ByteArray): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeResize(handle: Long, width: Int, height: Int)
    external fun nativeSetScene(handle: Long, encoded: String)
    external fun nativeSetCamera(handle: Long, encoded: String)
    external fun nativeRenderRgba(handle: Long): ByteArray?
    external fun nativeLastError(handle: Long): String?
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:desktopTest --tests "com.example.myapplication.board3d.DesktopFilamentNativeTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopFilamentNative.kt \
  app/src/desktopTest/kotlin/com/example/myapplication/board3d/DesktopFilamentNativeTest.kt
git commit -m "feat: add desktop filament native loader"
```

---

### Task 5: Desktop Filament Renderer Kotlin Peer

**Files:**
- Create: `app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopFilamentChessRenderer.kt`
- Modify: `app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopBoard3D.kt`
- Test: `app/src/desktopTest/kotlin/com/example/myapplication/board3d/DesktopFilamentChessRendererTest.kt`

- [ ] **Step 1: Write failing renderer factory test**

Create `DesktopFilamentChessRendererTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:desktopTest --tests "com.example.myapplication.board3d.DesktopFilamentChessRendererTest"`

Expected: FAIL with unresolved references to `DesktopFilamentPeer`.

- [ ] **Step 3: Implement desktop peer and renderer**

Create `DesktopFilamentChessRenderer.kt`:

```kotlin
package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

internal class DesktopFilamentPeer(
    val nativeHandle: Long,
    private val onFrame: (ByteArray, Int, Int) -> Unit,
) : FilamentChessPeer {
    private var width = 1
    private var height = 1
    private var attached = false

    override fun attach(surface: Chess3DSurface?) {
        width = surface?.widthPx?.coerceAtLeast(1) ?: width
        height = surface?.heightPx?.coerceAtLeast(1) ?: height
        attached = true
        if (nativeHandle != 0L) DesktopFilamentNative.nativeResize(nativeHandle, width, height)
    }

    override fun detach() {
        attached = false
    }

    override fun resize(widthPx: Int, heightPx: Int) {
        width = widthPx.coerceAtLeast(1)
        height = heightPx.coerceAtLeast(1)
        if (nativeHandle != 0L) DesktopFilamentNative.nativeResize(nativeHandle, width, height)
    }

    override fun setScene(encoded: String) {
        if (nativeHandle == 0L) return
        DesktopFilamentNative.nativeSetScene(nativeHandle, encoded)
        renderIfAttached()
    }

    override fun setCamera(encoded: String) {
        if (nativeHandle == 0L) return
        DesktopFilamentNative.nativeSetCamera(nativeHandle, encoded)
        renderIfAttached()
    }

    override fun shutdown() {
        if (nativeHandle != 0L) DesktopFilamentNative.nativeDestroy(nativeHandle)
    }

    private fun renderIfAttached() {
        if (!attached || nativeHandle == 0L) return
        val rgba = DesktopFilamentNative.nativeRenderRgba(nativeHandle) ?: return
        onFrame(rgba, width, height)
    }
}

class DesktopFilamentChessRenderer(
    glb: ByteArray,
    ibl: ByteArray,
    skybox: ByteArray,
) : Chess3DBoardRenderer {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "desktop-filament-render").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var surface: ImageBitmapChess3DSurface? = null
    private val peer: DesktopFilamentPeer
    private val delegate: FilamentEncodedChessRenderer

    init {
        DesktopFilamentNative.load()
        val handle = DesktopFilamentNative.nativeCreate(glb, ibl, skybox)
        require(handle != 0L) { "Desktop Filament native renderer returned null handle" }
        peer = DesktopFilamentPeer(handle) { rgba, width, height ->
            surface?.onFrame(rgbaBytesToImageBitmap(rgba, width, height))
        }
        delegate = FilamentEncodedChessRenderer(peer, CoroutineScope(dispatcher + SupervisorJob()))
    }

    override fun attach(surface: Chess3DSurface) {
        this.surface = surface as? ImageBitmapChess3DSurface
        delegate.attach(surface)
    }

    override fun detach() {
        delegate.detach()
        surface = null
    }

    override fun updatePosition(fen: String) = delegate.updatePosition(fen)
    override fun updatePosition(fen: String, transition: Board3DTransition?) = delegate.updatePosition(fen, transition)
    override fun onUserInteraction(event: Board3DInput) = delegate.onUserInteraction(event)
    override fun setSelectedSquare(square: BoardSquare?) = delegate.setSelectedSquare(square)

    override fun dispose() {
        delegate.dispose()
        dispatcher.close()
        executor.shutdownNow()
    }
}
```

- [ ] **Step 4: Update factory**

Change `DesktopBoard3D.kt` to read the GLB and KTX assets and construct `DesktopFilamentChessRenderer`.

- [ ] **Step 5: Run desktop tests**

Run: `./gradlew :app:desktopTest --tests "com.example.myapplication.board3d.DesktopFilamentChessRendererTest"`

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopFilamentChessRenderer.kt \
  app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopBoard3D.kt \
  app/src/desktopTest/kotlin/com/example/myapplication/board3d/DesktopFilamentChessRendererTest.kt
git commit -m "feat: add desktop filament renderer wrapper"
```

---

### Task 6: Native C++ Filament Bridge

**Files:**
- Create: `app/src/desktopMain/native/filament_bridge/CMakeLists.txt`
- Create: `app/src/desktopMain/native/filament_bridge/desktop_filament_bridge.cpp`
- Create: `app/src/desktopMain/native/filament_bridge/filament_chess_core.h`
- Create: `app/src/desktopMain/native/filament_bridge/filament_chess_core.cpp`
- Modify: `iosApp/iosApp/Filament/FilamentChessRenderer.mm`

- [ ] **Step 1: Fetch desktop Filament**

Run: `tools/fetch_filament_desktop.sh 1.72.0`

Expected: `app/src/desktopMain/filament/filament/include` and `app/src/desktopMain/filament/filament/lib` exist.

- [ ] **Step 2: Add CMake bridge**

Create `CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(desktop_filament_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FILAMENT_DIR "${CMAKE_CURRENT_LIST_DIR}/../../filament/filament" CACHE PATH "Filament desktop release")

find_package(JNI REQUIRED)

add_library(desktop_filament_bridge SHARED
    desktop_filament_bridge.cpp
    filament_chess_core.cpp
)

target_include_directories(desktop_filament_bridge PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${FILAMENT_DIR}/include"
)

target_link_directories(desktop_filament_bridge PRIVATE
    "${FILAMENT_DIR}/lib"
)

target_link_libraries(desktop_filament_bridge PRIVATE
    filament backend filabridge filaflat geometry utils smol-v ibl image ktxreader
    gltfio_core uberarchive uberzlib dracodec basis_transcoder meshoptimizer mikktspace stb
    zstd abseil
)
```

- [ ] **Step 3: Add C++ core API**

Create `filament_chess_core.h` with:

```cpp
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace chess3d {

struct RenderResult {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> rgba;
    std::string error;
};

class FilamentChessCore {
public:
    FilamentChessCore(const uint8_t* glb, int glbSize,
                      const uint8_t* ibl, int iblSize,
                      const uint8_t* skybox, int skyboxSize);
    ~FilamentChessCore();

    bool valid() const;
    const std::string& lastError() const;
    void resize(int width, int height);
    void setScene(const std::string& encoded);
    void setCamera(const std::string& encoded);
    RenderResult render();

private:
    struct Impl;
    Impl* impl;
};

} // namespace chess3d
```

- [ ] **Step 4: Implement C++ core**

Implement `filament_chess_core.cpp` by moving the engine-independent logic from
`iosApp/iosApp/Filament/FilamentChessRenderer.mm`:

- `PieceWire`
- `split`
- scene parsing
- `kModelScale`, `kMaxPieces`, `kInstanceCount`, `kMeshForKind`
- gltfio asset loading with `createInstancedAsset`
- board entity visibility
- piece-pool reconciliation
- KTX IBL and skybox setup
- neutral main/fill lights
- camera parsing and portrait FOV boost
- headless swap chain creation via `engine->createSwapChain(width, height)`
- `Renderer::readPixels` into RGBA UBYTE

- [ ] **Step 5: Add JNI layer**

Create `desktop_filament_bridge.cpp` with JNI functions matching `DesktopFilamentNative`:

```cpp
#include "filament_chess_core.h"
#include <jni.h>
#include <memory>
#include <string>

using chess3d::FilamentChessCore;

static std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray array) {
    if (!array) return {};
    jsize n = env->GetArrayLength(array);
    std::vector<uint8_t> out(static_cast<size_t>(n));
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeCreate(
        JNIEnv* env, jobject, jbyteArray glbArray, jbyteArray iblArray, jbyteArray skyboxArray) {
    auto glb = bytes(env, glbArray);
    auto ibl = bytes(env, iblArray);
    auto skybox = bytes(env, skyboxArray);
    auto* core = new FilamentChessCore(glb.data(), (int) glb.size(), ibl.data(), (int) ibl.size(), skybox.data(), (int) skybox.size());
    if (!core->valid()) {
        delete core;
        return 0;
    }
    return reinterpret_cast<jlong>(core);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<FilamentChessCore*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeResize(JNIEnv*, jobject, jlong handle, jint width, jint height) {
    if (auto* core = reinterpret_cast<FilamentChessCore*>(handle)) core->resize(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeSetScene(JNIEnv* env, jobject, jlong handle, jstring encoded) {
    if (!handle || !encoded) return;
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    reinterpret_cast<FilamentChessCore*>(handle)->setScene(chars);
    env->ReleaseStringUTFChars(encoded, chars);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeSetCamera(JNIEnv* env, jobject, jlong handle, jstring encoded) {
    if (!handle || !encoded) return;
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    reinterpret_cast<FilamentChessCore*>(handle)->setCamera(chars);
    env->ReleaseStringUTFChars(encoded, chars);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeRenderRgba(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return nullptr;
    auto result = reinterpret_cast<FilamentChessCore*>(handle)->render();
    if (!result.error.empty() || result.rgba.empty()) return nullptr;
    auto out = env->NewByteArray((jsize) result.rgba.size());
    env->SetByteArrayRegion(out, 0, (jsize) result.rgba.size(), reinterpret_cast<const jbyte*>(result.rgba.data()));
    return out;
}
```

- [ ] **Step 6: Build bridge manually**

Run:

```bash
cmake -S app/src/desktopMain/native/filament_bridge \
  -B app/build/desktop-filament-native/cmake \
  -DCMAKE_BUILD_TYPE=Release
cmake --build app/build/desktop-filament-native/cmake --config Release
```

Expected: native library `desktop_filament_bridge` is produced.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/desktopMain/native/filament_bridge iosApp/iosApp/Filament/FilamentChessRenderer.mm
git commit -m "feat: add desktop filament native bridge"
```

---

### Task 7: Gradle Native Build And Runtime Wiring

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Write failing Gradle task check**

Run: `./gradlew :app:tasks --all | rg buildDesktopFilamentBridge`

Expected: no match.

- [ ] **Step 2: Add Gradle tasks**

In `app/build.gradle.kts`, add tasks:

```kotlin
val desktopFilamentNativeDir = layout.buildDirectory.dir("desktop-filament-native")
val desktopFilamentBridgeLibName = System.mapLibraryName("desktop_filament_bridge")

val configureDesktopFilamentBridge by tasks.registering(Exec::class) {
    val sourceDir = layout.projectDirectory.dir("src/desktopMain/native/filament_bridge")
    val buildDir = desktopFilamentNativeDir.map { it.dir("cmake") }
    inputs.dir(sourceDir)
    inputs.dir(layout.projectDirectory.dir("src/desktopMain/filament/filament"))
    outputs.dir(buildDir)
    commandLine("cmake", "-S", sourceDir.asFile.absolutePath, "-B", buildDir.get().asFile.absolutePath, "-DCMAKE_BUILD_TYPE=Release")
}

val buildDesktopFilamentBridge by tasks.registering(Exec::class) {
    dependsOn(configureDesktopFilamentBridge)
    val buildDir = desktopFilamentNativeDir.map { it.dir("cmake") }
    outputs.file(buildDir.map { it.file(desktopFilamentBridgeLibName) })
    commandLine("cmake", "--build", buildDir.get().asFile.absolutePath, "--config", "Release")
}
```

Wire `desktopTest`, `run`, `desktopJar`, and `packageDistributionForCurrentOS` to depend on `buildDesktopFilamentBridge`.

- [ ] **Step 3: Add JVM library path**

For `tasks.withType<Test>()` and `tasks.withType<JavaExec>()`, add:

```kotlin
val nativeLibDir = desktopFilamentNativeDir.get().dir("cmake").asFile.absolutePath
jvmArgs("-Djava.library.path=$nativeLibDir")
```

- [ ] **Step 4: Verify task exists**

Run: `./gradlew :app:tasks --all | rg buildDesktopFilamentBridge`

Expected: task is listed.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/build.gradle.kts
git commit -m "build: wire desktop filament native bridge"
```

---

### Task 8: Switch Desktop Product Path And Remove Vulkan

**Files:**
- Modify: `app/src/desktopMain/kotlin/com/example/myapplication/board3d/DesktopBoard3D.kt`
- Delete: `app/src/desktopMain/kotlin/com/example/myapplication/board3d/VulkanChessRenderer.kt`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify or delete Vulkan-only tests in `app/src/desktopTest/kotlin/com/example/myapplication/board3d/`

- [ ] **Step 1: Write failing smoke test name update**

Rename `DesktopRendererSmokeTest` references from Vulkan-specific setup to desktop Filament.
Run: `./gradlew :app:desktopTest --tests "*DesktopRendererSmokeTest" -Dchess3d.smoke=true`

Expected before native bridge is wired: fails or skips with clear native payload message.

- [ ] **Step 2: Remove Vulkan factory usage**

Ensure `DesktopBoard3D.kt` constructs:

```kotlin
DesktopFilamentChessRenderer(
    glb = Res.readBytes("files/models/${ChessSetConventions.GLB_ASSET}"),
    ibl = Res.readBytes("files/env/${ChessSetConventions.IBL_ASSET}"),
    skybox = Res.readBytes("files/env/${ChessSetConventions.SKYBOX_ASSET_BLURRED}"),
)
```

- [ ] **Step 3: Remove Vulkan dependencies**

In `gradle/libs.versions.toml`, remove `lwjgl-vulkan` and `lwjgl-shaderc` if no remaining code imports them.
In `app/build.gradle.kts`, remove desktop `implementation(libs.lwjgl.vulkan)`, `implementation(libs.lwjgl.shaderc)`, and Vulkan runtime classifiers.

- [ ] **Step 4: Delete Vulkan renderer**

Delete `VulkanChessRenderer.kt`. Keep shared helpers only if they are still referenced by desktop Filament or tests.

- [ ] **Step 5: Run desktop compile/test**

Run: `./gradlew :app:desktopTest`

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/desktopMain/kotlin/com/example/myapplication/board3d \
  app/src/desktopTest/kotlin/com/example/myapplication/board3d \
  app/build.gradle.kts gradle/libs.versions.toml
git commit -m "feat: switch desktop 3d to filament"
```

---

### Task 9: CI And Documentation Update

**Files:**
- Modify: `.github/workflows/android-tests.yml`
- Modify: `docs/plans/issue-32-3d-ui-overview.md`
- Modify: `docs/plans/issue-32-3d-ui-m1-foundation.md`
- Modify: any file found by `rg -n "desktop Vulkan|VulkanChessRenderer|lavapipe|LWJGL headless Vulkan" README.md docs app tools .github`

- [ ] **Step 1: Find stale references**

Run:

```bash
rg -n "desktop Vulkan|VulkanChessRenderer|lavapipe|LWJGL headless Vulkan" README.md docs app tools .github
```

Expected: list of references to update or mark historical.

- [ ] **Step 2: Update CI**

In `.github/workflows/android-tests.yml`:

- replace the lavapipe install step with `tools/fetch_filament_desktop.sh`
- keep iOS `tools/fetch_filament_ios.sh`
- leave Android emulator behavior unchanged

- [ ] **Step 3: Update docs**

Change current-behavior text to "desktop Filament via native C++ headless readback". Historical docs can retain Vulkan decisions if they are clearly labeled historical.

- [ ] **Step 4: Run reference scan**

Run:

```bash
rg -n "desktop Vulkan|VulkanChessRenderer|lavapipe|LWJGL headless Vulkan" README.md docs app tools .github
```

Expected: only historical references remain, each labeled historical.

- [ ] **Step 5: Commit**

Run:

```bash
git add .github/workflows/android-tests.yml README.md docs app tools
git commit -m "docs: document desktop filament backend"
```

---

### Task 10: Full Verification

**Files:**
- No new files.

- [ ] **Step 1: Fetch native dependencies**

Run:

```bash
tools/fetch_filament_desktop.sh 1.72.0
tools/fetch_filament_ios.sh 1.72.0
```

Expected: both scripts complete.

- [ ] **Step 2: Run desktop focused tests**

Run:

```bash
./gradlew :app:desktopTest --tests "*board3d*" -Dchess3d.smoke=true
```

Expected: PASS, smoke PNG written under `app/build/`.

- [ ] **Step 3: Run full repo build path**

Run:

```bash
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
```

Expected: PASS.

- [ ] **Step 4: Run Apple Kotlin checks**

Run:

```bash
./gradlew :app:iosSimulatorArm64Test :app:desktopTest :app:linkDebugFrameworkIosArm64 "-PiosSimulatorDeviceId=iPhone 16"
```

Expected: PASS or environment-specific simulator unavailability; Kotlin/iOS compile errors must be fixed.

- [ ] **Step 5: Record verification**

Update final response with the exact commands run and their results. Do not claim completion without fresh command output.
