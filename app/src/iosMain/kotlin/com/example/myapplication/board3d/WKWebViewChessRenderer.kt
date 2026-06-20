package com.example.myapplication.board3d

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.CoreGraphics.CGRectMake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Production three.js chess renderer for iOS. Wraps a WKWebView that loads a bundled three.js
 * renderer (`chess3d-bundle.js`) + `chess.glb`, driven from Kotlin via `evaluateJavaScript`.
 *
 * Implements [Chess3DBoardRenderer] so it drops into the existing `iosBoard3DSupport()` factory
 * as a direct replacement for `IosSceneKitChessRenderer`. The shared `OrbitCameraController` +
 * `BoardRayPicker` on the Kotlin side compute camera + picks; the WKWebView just renders.
 *
 * The WKWebView is hosted inside a `UIKitView(interactive = false)` (same pattern as SceneKit's
 * SCNView) so Compose `pointerInput` intercepts all touches and drives the shared gesture math.
 */
@OptIn(ExperimentalForeignApi::class)
class WkWebViewChess3DSurface(
    val webView: WKWebView,
    override val widthPx: Int,
    override val heightPx: Int,
) : Chess3DSurface

@OptIn(ExperimentalForeignApi::class)
class WKWebViewChessRenderer : Chess3DBoardRenderer {

    private var webView: WKWebView? = null
    private var pendingFen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var selectedSquare: BoardSquare? = null
    private var isReady = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Move arc + selection bounce are computed in commonMain; each frame the interpolated scene is
    // pushed to three.js as the encoded wire form (chess3d.setScene).
    private val driver = Board3DAnimationDriver(scope) { scene ->
        if (isReady) evalJs("chess3d.setScene('${scene.encode()}')")
    }

    override fun attach(surface: Chess3DSurface) {
        val wkSurface = surface as? WkWebViewChess3DSurface ?: return
        webView = wkSurface.webView

        // Load the host HTML over the custom asset scheme (registered on the WKWebView config in
        // IosBoard3D). The relative paths (./chess3d-bundle.js, ./chess.glb, ./papermill_*.hdr) then
        // resolve to the same scheme and are served by BundleAssetSchemeHandler — three.js's
        // loaders can XHR them, which a file:// origin blocks.
        val url = NSURL.URLWithString(ASSET_HOST_URL) ?: return
        wkSurface.webView.loadRequest(platform.Foundation.NSURLRequest(uRL = url))

        // Apply initial state after a delay (chess.glb load + three.js init takes ~2s).
        val initialFen = pendingFen
        val initialCamera = camera
        scope.launch {
            delay(2000)
            isReady = true
            driver.setPosition(runCatching { Board3DSceneMapper.fromFen(initialFen) }.getOrNull(), null)
            applyCamera(initialCamera)
            driver.setSelected(selectedSquare)
        }
    }

    override fun detach() {
        webView = null
        isReady = false
    }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        pendingFen = fen
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(fen) }.getOrNull(), transition)
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        selectedSquare = square
        driver.setSelected(square)
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> {
                camera = event.camera
                applyCamera(event.camera)
            }
            is Board3DInput.Resize -> {
                camera = camera.copy(aspect = event.widthPx.toFloat() / event.heightPx.coerceAtLeast(1).toFloat())
                applyResize(event.widthPx, event.heightPx)
            }
            else -> {}
        }
    }

    override fun dispose() {
        driver.cancel()
        scope.cancel()
        evalJs("chess3d.dispose()")
        detach()
    }

    // --- JS bridge ---

    private fun applyCamera(cam: CameraParams) {
        if (!isReady) return
        evalJs("chess3d.setCamera(${cam.position.x},${cam.position.y},${cam.position.z}," +
            "${cam.target.x},${cam.target.y},${cam.target.z}," +
            "${cam.up.x},${cam.up.y},${cam.up.z}," +
            "${cam.fovYDegrees},${cam.aspect})")
    }

    private fun applyResize(w: Int, h: Int) {
        if (!isReady) return
        evalJs("chess3d.resize($w,$h)")
    }

    private fun evalJs(js: String) {
        webView?.evaluateJavaScript(js) { _, _ -> }
    }
}
