package com.example.myapplication.board3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/** Contract (KDoc'd on the interface, enforced by FakeChess3DRenderer tests):
 *  - All methods are called from the UI thread; implementations marshal to their own render thread.
 *  - attach() while already attached detaches the previous surface first.
 *  - updatePosition() before attach() stores the FEN; it is applied on attach.
 *  - detach() is idempotent and returns quickly (async GPU teardown allowed,
 *    but the surface must not be touched after detach() returns).
 *  - dispose() releases GPU resources; the renderer is unusable afterwards. */
interface Chess3DBoardRenderer {
    fun attach(surface: Chess3DSurface)
    fun detach()
    fun updatePosition(fen: String)
    fun onUserInteraction(event: Board3DInput)
    fun dispose()
}

/** Marker for a platform drawing target. Platform impls wrap native handles
 *  (frame sink / SurfaceHolder / CAMetalLayer / HTMLCanvasElement); renderers downcast. */
interface Chess3DSurface {
    val widthPx: Int
    val heightPx: Int
}

/** Returns null when 3D is unsupported or init fails -> UI falls back to 2D.
 *  suspend so implementations can load the glTF asset via Res.readBytes(). */
fun interface Chess3DRendererFactory {
    suspend fun create(): Chess3DBoardRenderer?
}

/** Injected at platform entry points, mirroring ChessEngine injection. */
@Immutable
class Board3DSupport(
    val rendererFactory: Chess3DRendererFactory,
    val surfaceContent: @Composable (renderer: Chess3DBoardRenderer, modifier: Modifier) -> Unit,
)
