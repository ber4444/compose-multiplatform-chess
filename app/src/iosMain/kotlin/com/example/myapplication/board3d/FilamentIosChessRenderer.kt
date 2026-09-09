package com.example.myapplication.board3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIView

/**
 * Issue #54 — Metal-native iOS 3D board via **Filament** (UIKitView-hosted `CAMetalLayer`).
 *
 * Why a Swift-injected native view instead of pure Kotlin/Native: Filament's iOS distribution is a
 * C++ static-library xcframework with no Kotlin/Native cinterop and no SPM package. Rather than
 * cinterop the whole Filament API, the actual Metal renderer lives on the Swift/Obj-C++ side
 * (`FilamentChessView` + `FilamentChessRenderer.mm`) and is injected here through
 * [FilamentChessViewFactory] — exactly mirroring how `StockfishChessEngine` (Swift) is injected as
 * the Kotlin `ChessEngine`. The Kotlin framework therefore has **no compile-time dependency on the
 * Filament xcframework**; the Swift app target owns the native Filament dependency.
 *
 * Division of labour: all the *smart* logic — FEN→scene mapping, the move-arc hop, the selection
 * bounce, frame pacing, orbit camera, and ray picking — stays in shared commonMain. Each animation
 * frame the interpolated [Board3DScene] is handed to the Swift side as a compact wire string
 * ([Board3DScene.encode]), so the native view is a "dumb" renderer that just draws what it's told.
 */

/**
 * Injected from Swift (`FilamentChessFactory`). Produces a Metal-backed Filament chess view. Kept
 * separate from [Chess3DRendererFactory] because it is implemented in Swift and exported as an
 * Obj-C protocol; the Kotlin [FilamentIosChessRenderer] wraps whatever it produces.
 */
interface FilamentChessViewFactory {
    fun create(): FilamentChessNativeView
}

/**
 * A Metal/Filament chess surface implemented in Swift. The Kotlin [FilamentIosChessRenderer] owns
 * the shared animation/camera state and drives this with compact wire forms, so the native side never
 * reimplements game logic.
 *
 * All methods are called on the main thread (the renderer marshals nothing else here; Filament's own
 * render loop runs on its CADisplayLink).
 */
interface FilamentChessNativeView {
    /** The `CAMetalLayer`-backed `UIView` to host inside Compose's `UIKitView(interactive = false)`. */
    fun metalView(): UIView

    /** Encoded [Board3DScene] ([Board3DScene.encode]); reconciled against the piece pool each frame. */
    fun setScene(encoded: String)

    /** Camera as `"px,py,pz,tx,ty,tz,ux,uy,uz,fovYDeg,aspect"`. */
    fun setCamera(encoded: String)

    /** Drawable + viewport resize in physical pixels. */
    fun resize(width: Int, height: Int)

    /**
     * Resume (`true`) or park (`false`) the native `CADisplayLink`.
     *
     * This is iOS's counterpart to SceneView's `isRendering` argument on Android: without it an
     * idle 3D board redraws at up to 120 Hz for as long as it is on screen. The signal is
     * [Board3DAnimationDriver.isDirty] — "a frame was published recently", never "an animation is
     * running"; see [FilamentChessPeer.setRenderingActive].
     *
     * The native view keeps its own backstop on top of this (see `FrameLoopGate`), because the
     * driver's dirty window can lapse before the display link even exists: the view is created
     * before it has a size, so the renderer is built later, from `layoutSubviews`.
     */
    fun setRenderingActive(active: Boolean)

    /** Release all Filament + Metal resources; the view is unusable afterwards. */
    fun shutdown()
}

@OptIn(ExperimentalForeignApi::class)
private class FilamentChess3DSurface(
    override val widthPx: Int,
    override val heightPx: Int,
) : Chess3DSurface

@OptIn(ExperimentalForeignApi::class)
class FilamentIosChessRenderer(factory: FilamentChessViewFactory) : Chess3DBoardRenderer {

    // Created up front (on the main thread, from Board3DHost's renderer factory) so the UIKitView
    // factory below can hand its UIView straight to Compose.
    val nativeView: FilamentChessNativeView = factory.create()

    private val delegate = FilamentEncodedChessRenderer(
        peer = object : FilamentChessPeer {
            override fun setScene(encoded: String) = nativeView.setScene(encoded)
            override fun setCamera(encoded: String) = nativeView.setCamera(encoded)
            override fun resize(widthPx: Int, heightPx: Int) = nativeView.resize(widthPx, heightPx)
            override fun setRenderingActive(active: Boolean) = nativeView.setRenderingActive(active)
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
    override fun setSelectedSquare(square: BoardSquare?) = delegate.setSelectedSquare(square)
    override fun setHighlightedSquares(squares: List<HighlightedSquare>) = delegate.setHighlightedSquares(squares)
    override fun onUserInteraction(event: Board3DInput) = delegate.onUserInteraction(event)
    override fun dispose() = delegate.dispose()
}

/**
 * Hosts the Swift-owned Filament `UIView` inside `UIKitView(interactive = false)` so Compose's
 * `pointerInput` (in `Board3D`) intercepts touches and the shared `OrbitCameraController` /
 * `BoardRayPicker` pipeline drives the camera.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun FilamentIosBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier = Modifier) {
    val filamentRenderer = renderer as? FilamentIosChessRenderer ?: return
    var isAttached by remember { mutableStateOf(false) }

    DisposableEffect(renderer) {
        onDispose {
            if (isAttached) {
                renderer.detach()
                isAttached = false
            }
        }
    }

    UIKitView(
        factory = { filamentRenderer.nativeView.metalView() },
        modifier = modifier,
        interactive = false,
        update = { _ ->
            // Drawable sizing is driven by the view's own layoutSubviews (see FilamentChessView); attach
            // is size-independent (it just marks the renderer ready and pushes the initial scene/camera),
            // so do it here once. The camera aspect is supplied separately by Board3D via SetCamera.
            if (!isAttached) {
                renderer.attach(FilamentChess3DSurface(0, 0))
                isAttached = true
            }
        },
        onRelease = {
            if (isAttached) {
                renderer.detach()
                isAttached = false
            }
        }
    )
}
