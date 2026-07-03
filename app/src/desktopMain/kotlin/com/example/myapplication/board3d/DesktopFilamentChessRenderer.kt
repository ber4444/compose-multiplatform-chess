package com.example.myapplication.board3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal class DesktopFilamentPeer(
    nativeHandle: Long,
    private val onFrame: (ByteArray, Int, Int) -> Unit,
) : FilamentChessPeer {
    var nativeHandle: Long = nativeHandle
        private set

    private var width = 1
    private var height = 1
    private var attached = false
    private var hasScene = false

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
        hasScene = true
        DesktopFilamentNative.nativeSetScene(nativeHandle, encoded)
        renderIfAttached()
    }

    override fun setCamera(encoded: String) {
        if (nativeHandle == 0L) return
        DesktopFilamentNative.nativeSetCamera(nativeHandle, encoded)
        renderIfAttached()
    }

    override fun shutdown() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) DesktopFilamentNative.nativeDestroy(handle)
    }

    private fun renderIfAttached() {
        // Skip rendering until the first scene is pushed: attach() applies the camera before the
        // initial scene arrives, and rendering then would emit a one-frame empty board.
        if (!attached || !hasScene || nativeHandle == 0L) return
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
    private val renderScope = CoroutineScope(dispatcher + SupervisorJob())
    private var surface: ImageBitmapChess3DSurface? = null
    private val peer: DesktopFilamentPeer
    private val delegate: FilamentEncodedChessRenderer

    init {
        // Filament is not thread-safe: the Engine and every native call must run on a single
        // consistent thread. All renderer calls go through renderScope (the executor thread), so
        // the Engine must also be *created* there — otherwise resize/render panic with
        // utils::PreconditionPanic. Block the constructing thread until the handle is ready.
        val handle = executor.submit(Callable {
            DesktopFilamentNative.load()
            DesktopFilamentNative.nativeCreate(glb, ibl, skybox)
        }).get()
        require(handle != 0L) {
            DesktopFilamentNative.lastError(0L)
                ?: "Desktop Filament native renderer returned null handle"
        }
        peer = DesktopFilamentPeer(handle) { rgba, width, height ->
            surface?.onFrame(rgbaBytesToImageBitmap(rgba, width, height))
        }
        delegate = FilamentEncodedChessRenderer(peer, renderScope)
    }

    override fun attach(surface: Chess3DSurface) {
        this.surface = surface as? ImageBitmapChess3DSurface
        renderScope.launch { delegate.attach(surface) }
    }

    override fun detach() {
        renderScope.launch {
            delegate.detach()
            surface = null
        }
    }

    override fun updatePosition(fen: String) {
        renderScope.launch { delegate.updatePosition(fen) }
    }

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        renderScope.launch { delegate.updatePosition(fen, transition) }
    }

    override fun onUserInteraction(event: Board3DInput) {
        renderScope.launch { delegate.onUserInteraction(event) }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        renderScope.launch { delegate.setSelectedSquare(square) }
    }

    override fun dispose() {
        runBlocking {
            withContext(dispatcher) {
                delegate.dispose()
            }
        }
        renderScope.cancel()
        dispatcher.close()
        executor.shutdownNow()
    }
}
