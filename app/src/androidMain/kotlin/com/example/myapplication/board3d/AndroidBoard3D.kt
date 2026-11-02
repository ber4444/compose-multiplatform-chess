package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.android.filament.Camera
import dev.romainguy.kotlin.math.Float3
import game.app.generated.resources.Res
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberFillLightNode
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.node.CameraNode
import org.jetbrains.compose.resources.ExperimentalResourceApi

// Compose resources land at this prefix inside Android assets (set by compose.resources config
// in app/build.gradle.kts: packageOfResClass = "game.app.generated.resources").
private const val RES_PREFIX = "composeResources/game.app.generated.resources"
private val IBL_KTX    = "$RES_PREFIX/files/env/${ChessSetConventions.IBL_ASSET}"
private val SKYBOX_KTX = "$RES_PREFIX/files/env/${ChessSetConventions.SKYBOX_ASSET_BLURRED}"

// 3D board lighting. SceneView's defaults are a neutral 6500 K 3-point setup — main 10000 lux +
// fill 3000 lux + IBL 10000 lux (io.github.sceneview.SceneFactories). These nudge each ~15% brighter
// so the dark pieces read a touch lighter while keeping the natural neutral tone. The iOS Filament
// backend (FilamentChessRenderer.mm) mirrors these exact lux values (scaled for its darker default
// camera exposure) so both platforms match — keep them in sync.
private const val MAIN_LIGHT_INTENSITY = 11_500f
private const val FILL_LIGHT_INTENSITY = 3_450f
private const val IBL_INTENSITY = 11_500f

/** A chess board holds at most 32 pieces (promotion replaces a pawn, never adds). */
private val MAX_PIECES = ChessSetConventions.MAX_PIECES
private val MAX_HIGHLIGHTS = ChessSetConventions.MAX_HIGHLIGHTS

/** chess.glb square-size conversion (2-unit glb squares -> 1-unit game squares). */
private val PIECE_SCALE = ChessSetConventions.PIECE_SCALE

/** Lifts the highlight quad off the tile just enough to beat z-fighting. */
private const val HIGHLIGHT_LIFT_Y = 0.005f

internal fun selectPieceMaterialName(materialNames: List<String>, color: PieceColor): String? {
    val expected = ChessSetMeshNames.getMaterialName(color)
    return materialNames.firstOrNull { it == expected }
        ?: materialNames.firstOrNull { it.substringAfterLast('/').startsWith(expected) }
}

/**
 * Drives SceneView's [CameraNode] from the shared [OrbitCameraController] state.
 *
 * SceneView positions the camera from the CameraNode's *node* transform every frame, so we must
 * move the node (position + lookAt) — setting the raw Filament camera's lookAt has no effect.
 *
 * Portrait FOV boost (same logic as CameraMath.effectiveFovYRad / iOS): with aspect < 1 the
 * horizontal FOV shrinks too far for the board, so widen the vertical FOV to hold a fixed ~60°
 * horizontal FOV. The picker uses the identical formula to stay in sync.
 */
private fun fovYDegrees(cameraParams: CameraParams): Float {
    val aspect = cameraParams.aspect
    return if (aspect < 1f) {
        val minFovXRad  = 60.0 * PI / 180.0
        val tanHalfFovX = tan(minFovXRad / 2.0)
        (2.0 * atan(tanHalfFovX / aspect.toDouble()) * 180.0 / PI).toFloat()
    } else {
        cameraParams.fovYDegrees
    }
}

/**
 * Writes the camera only when it differs from what [CameraParams] asks for. Under
 * `FrameRatePolicy.OnDemand` every CameraNode write requests a frame (`onComponentChanged`), so the
 * per-frame re-apply in `onFrame` must be a no-op on a settled camera: unconditional, it requested
 * the next frame from inside each frame and the idle board never parked (measured on sceneview
 * 4.65: 50–60 fps, untouched, for as long as it was on screen).
 *
 * "Differs" includes SceneView rebuilding its default 28mm lens (on `setView` and on every resize),
 * which leaves our [CameraParams] unchanged but the camera's vertical FOV at 46.4°.
 */
private class CameraApplier(private val cameraNode: CameraNode) {
    private var applied: CameraParams? = null

    fun apply(cameraParams: CameraParams) {
        val fovY = fovYDegrees(cameraParams)
        val shown = cameraNode.camera.getFieldOfViewInDegrees(Camera.Fov.VERTICAL)
        if (cameraParams == applied && abs(shown - fovY) < FOV_MATCH_DEGREES) return
        applyCamera(cameraNode, cameraParams, fovY)
        applied = cameraParams
    }
}

private const val FOV_MATCH_DEGREES = 0.01

private fun applyCamera(cameraNode: CameraNode, cameraParams: CameraParams, fovY: Float) {
    val pos    = cameraParams.position
    val tgt    = cameraParams.target
    val up     = cameraParams.up
    val aspect = cameraParams.aspect

    cameraNode.position = Float3(pos.x, pos.y, pos.z)
    cameraNode.lookAt(
        targetWorldPosition = Float3(tgt.x, tgt.y, tgt.z),
        upDirection = Float3(up.x, up.y, up.z),
        smooth = false,
    )
    cameraNode.setProjection(
        fovInDegrees = fovY.toDouble(),
        near = cameraParams.near,
        far = cameraParams.far,
        direction = Camera.Fov.VERTICAL,
        aspect = aspect.toDouble(),
    )
}

/**
 * SceneView-backed 3D chess board surface.
 *
 * SceneView's [SceneView] composable owns the Filament engine and render loop. [SurfaceType.Surface]
 * uses a platform surface that lets Compose [androidx.compose.ui.window.Dialog]s (promotion,
 * game-over) layer above it without z-ordering issues.
 *
 * Rendering: chess.glb (placed in Android assets by the ComposeResources → assets build hack in
 * app/build.gradle.kts) is read once into a byte buffer and instanced synchronously per node. One
 * board node shows the marble tiles + frame; a fixed pool of [MAX_PIECES] piece nodes — all created
 * in the first composition — each render boardScene.pieces[i], updated reactively (position,
 * rotation, visibility, and which mesh) without adding/removing nodes after init. Selection is shown
 * by bouncing the picked piece (its scene y oscillates), not a coloured disc. The shared Board3DHost
 * camera/gestures drive the camera via a transparent overlay (SceneView's SurfaceView consumes
 * touches otherwise).
 */
@Composable
fun AndroidBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier) {
    val svRenderer = renderer as? AndroidSceneViewChessRenderer ?: return

    val engine         = rememberEngine()
    val modelLoader    = rememberModelLoader(engine)
    val envLoader      = rememberEnvironmentLoader(engine)

    // Parse chess.glb exactly once and create every board/piece instance in a single call. The
    // earlier approach called createModelInstance() once per node, re-parsing the whole GLB for all
    // MAX_PIECES + 1 nodes synchronously on the UI thread — that stalled composition (freezing the
    // loading spinner) each time the 3D board opened. createInstancedModel() parses once and returns
    // instances that share the parsed geometry while keeping independent transforms, visibility, and
    // material instances, so the per-node selection logic below is unchanged and every instance is
    // still present in the first composition's node batch. Index 0 is the board; 1..MAX_PIECES are
    // the piece pool slots.
    val glbBytes = svRenderer.glbBytes
    val modelInstances = remember(modelLoader, glbBytes) {
        val buffer = ByteBuffer.allocateDirect(glbBytes.size).order(ByteOrder.nativeOrder())
        buffer.put(glbBytes).rewind()
        runCatching { modelLoader.createInstancedModel(buffer, MAX_PIECES + 1 + MAX_HIGHLIGHTS) }.getOrNull().orEmpty()
    }

    val environment = remember(envLoader) {
        // createKTX1Environment forces the SceneView default IBL intensity (10000 lux); override it
        // to our slightly brighter value so the ambient fill matches the bumped main/fill lights.
        envLoader.createKTX1Environment(IBL_KTX, SKYBOX_KTX).also { env ->
            env.indirectLight?.intensity = IBL_INTENSITY
        }
    }

    val cameraNode  = rememberCameraNode(engine)
    val cameraParams = svRenderer.cameraParams

    // For the writes below that SceneView's on-demand loop cannot see: renderable visibility and
    // material bindings on the pooled nodes. Everything it owns (nodes moving, the camera node, an
    // unfinished async load, a resize) already wakes it.
    val renderInvalidator = rememberRenderInvalidator()

    // A camera write requests its own frame, so a changed camera needs no invalidation here, and
    // an unchanged one writes nothing (see CameraApplier).
    val cameraApplier = remember(cameraNode) { CameraApplier(cameraNode) }
    SideEffect { cameraApplier.apply(cameraParams) }

    // ...and again immediately before every drawn frame, because a SideEffect is NOT the last word
    // on the projection. SceneView calls `cameraNode.setView(view)`, and CameraNode.setView()
    // calls updateProjection(), which recomputes the projection from its 28mm lens default —
    // vertical FOV 46.4° — discarding the FOV set above. Up to sceneview 4.37 that ran in an
    // unkeyed SideEffect on every recomposition of SceneView's body, i.e. every wake of a parked
    // board, and a one-shot publish (a Hint's highlight squares) drew at 46.4° and parked there —
    // the board abruptly zoomed in. Since 4.38 it is a LaunchedEffect keyed on (view, camera), so
    // it runs once, but still *after* our first SideEffect.
    //
    // `onFrame` runs inside SceneRenderer.renderFrame's `onBeforeRender`, immediately before
    // `beginFrame`, which makes it ordering-proof either way. It is remembered and reads
    // `cameraParams` at frame time, so the lambda identity stays stable and recomposes nothing.
    val applyCameraOnFrame = remember(svRenderer, cameraApplier) {
        { _: Long -> cameraApplier.apply(svRenderer.cameraParams) }
    }

    val boardScene    = svRenderer.boardScene

    // SceneView's SurfaceView installs an OnTouchListener that consumes every touch (returns true,
    // see SceneRenderer.attachToSurfaceView), so the shared Board3DHost pointerInput on `modifier`
    // never receives events if placed on the Scene. Instead, lay the Scene out underneath a
    // transparent sibling that carries `modifier` (testTag + onSizeChanged + gestures). Being the
    // last child it wins Compose hit-testing, so taps/drag/pinch reach our gesture handlers while
    // the Scene below still renders. (iOS achieves the same with UIKitView interactive = false.)
    Box {
        SceneView(
            modifier = Modifier.matchParentSize(),
            surfaceType = SurfaceType.Surface,
            engine = engine,
            modelLoader = modelLoader,
            environment = environment,
            // Bump SceneView's default main (10000) + fill (3000) lights a touch brighter; the iOS
            // Filament backend mirrors these same intensities.
            mainLightNode = rememberMainLightNode(engine) { intensity = MAIN_LIGHT_INTENSITY },
            fillLightNode = rememberFillLightNode(engine) { intensity = FILL_LIGHT_INTENSITY },
            cameraNode = cameraNode,
            // We own the camera (shared OrbitCameraController + Compose gestures in Board3DHost) and
            // the node layout (explicit world positions), so disable SceneView's built-in camera
            // manipulator, which otherwise overwrites cameraNode.transform every frame, and its
            // content auto-center.
            cameraManipulator = null,
            autoCenterContent = false,
            // Draw only when something changed, then park. A continuous loop redraws an untouched
            // board at the panel refresh rate forever — 120 fps on a Fold3, 60 fps and ~1.76 cores
            // on a Pixel 7a, the GPU rail alone at 10.36 J per 10 s. OnDemand is SceneView's default
            // since 4.38; it is spelled out because the board depends on it, and because it replaced
            // the hand-kept `isRendering` dirty signal this file used to compute. It also holds the
            // loop open for chess.glb's async texture uploads, which finish inside the frame loop.
            frameRatePolicy = FrameRatePolicy.OnDemand(),
            renderInvalidator = renderInvalidator,
            // Re-applies our camera after SceneView's own projection reset; see applyCameraOnFrame.
            onFrame = applyCameraOnFrame,
        ) {
            // GLB uses 2-unit squares (board +/-8); game uses 1-unit squares (+/-4). All nodes
            // need scale = 0.5 to match. Template pieces are at GLB origin, so position =
            // squareCenter works directly after scaling.

            // Board node: the GLB carries the 64 marble square tiles (nodes a1..h8) plus the engraved
            // "frame" border; show those and hide everything else.
            val boardInstance = modelInstances.getOrNull(0)
            if (boardInstance != null) {
                ModelNode(
                    modelInstance = boardInstance,
                    scale = Float3(PIECE_SCALE, PIECE_SCALE, PIECE_SCALE),
                    apply = {
                        // `Plane` is hidden here but must stay in the asset: it is the only
                        // primitive bound to the `black` material, which is what keeps that
                        // MaterialInstance alive for the piece pool. Repointing or deleting it
                        // turns every black piece silvery. See ChessSetMeshNames.getMaterialName.
                        val hiddenNames = PieceKind.entries
                            .map { kind -> ChessSetMeshNames.getMeshName(kind, PieceColor.WHITE) }
                            .toSet() + "Plane" + ChessSetConventions.HIGHLIGHT_NODE_NAMES
                        renderableNodes.forEach { rn ->
                            if (rn.name in ChessSetConventions.HIGHLIGHT_NODE_NAMES || rn.name == "Plane") {
                                rn.isShadowCaster = false
                                rn.isShadowReceiver = false
                            }
                            rn.isVisible = rn.name !in hiddenNames
                        }
                    }
                ) {}
            }

            // Piece nodes: a fixed pool of MAX_PIECES nodes, all created once at first composition so
            // they enter the Filament scene in the initial batch. Instead of add/remove, each pool
            // slot shows boardScene.pieces[i] and is updated reactively.
            repeat(MAX_PIECES) { i ->
                val piece = boardScene?.pieces?.getOrNull(i)
                val instance = modelInstances.getOrNull(i + 1)
                if (instance != null) {
                    val materialInstances = remember(instance) {
                        instance.materialInstances.associateBy { it.name }
                    }
                    val nodeState = remember {
                        androidx.compose.runtime.mutableStateOf<io.github.sceneview.node.ModelNode?>(null)
                    }
                    ModelNode(
                        modelInstance = instance,
                        // y comes from the scene so the move animation's arc hop (position.y > 0
                        // mid-flight) lifts the piece off the board; resting pieces stay at y=0.
                        position = Float3(piece?.position?.x ?: 0f, piece?.position?.y ?: 0f, piece?.position?.z ?: 0f),
                        rotation = Float3(0f, piece?.rotationYDegrees ?: 0f, 0f),
                        scale = Float3(PIECE_SCALE, PIECE_SCALE, PIECE_SCALE),
                        isVisible = piece != null,
                        apply = { nodeState.value = this }
                    ) {}
                    val meshName = piece?.let { ChessSetMeshNames.getMeshName(it.kind, it.color) }
                    val materialName = piece?.let { ChessSetMeshNames.getMaterialName(it.color) }
                    androidx.compose.runtime.LaunchedEffect(nodeState.value, meshName, materialName) {
                        val node = nodeState.value ?: return@LaunchedEffect
                        val selectedMaterialName = piece?.color?.let {
                            selectPieceMaterialName(materialInstances.keys.toList(), it)
                        }
                        val selectedMaterial = selectedMaterialName?.let(materialInstances::get)
                        node.renderableNodes.forEach { rn ->
                            rn.isVisible = (meshName != null && rn.name == meshName)
                            if (rn.isVisible && selectedMaterial != null) {
                                // The glTF has one geometry template per piece kind. Its embedded
                                // material is not the piece colour, so every reused pool slot must
                                // explicitly bind white/black when its logical piece changes.
                                rn.setMaterialInstances(selectedMaterial)
                            }
                        }
                        // A mesh or colour swap in place moves nothing, so the loop cannot see it.
                        renderInvalidator.requestRender()
                    }
                }
            }

            // Highlight nodes: a fixed pool of translucent quads marking the squares the move coach
            // cites. Same reconcile-a-pool shape as the piece nodes above, and for the same reason.
            //
            // The ModelNode call MUST stay unconditional. `apply` runs inside SceneView's `remember`,
            // i.e. *during composition*, so making the call conditional on state that `apply` itself
            // writes invalidates the scope, drops the ModelNode call on the very next pass, and
            // Compose then disposes the node — NodeLifecycle's onDispose calls Node.destroy(), which
            // tears down every entity in the instance. That compacts Filament's component managers
            // (swap-with-last), and SceneView caches EntityInstance handles forever
            // (Node._transformInstance), so nodes created *after* these instances — the camera and
            // both lights — are left holding stale transform instances. The visible symptom is not a
            // crash: the camera silently freezes, which also desyncs tap-picking from the drawn board.
            repeat(MAX_HIGHLIGHTS) { i ->
                val highlight = boardScene?.highlightedSquares?.getOrNull(i)
                val instance = modelInstances.getOrNull(MAX_PIECES + 1 + i)
                if (instance != null) {
                    val center = highlight?.let { BoardGeometry.squareCenter(it.square) }
                    val nodeState = remember {
                        androidx.compose.runtime.mutableStateOf<io.github.sceneview.node.ModelNode?>(null)
                    }
                    ModelNode(
                        modelInstance = instance,
                        // chess.glb's `Plane` node carried a baked local translation (the only node in
                        // the file that did); it was zeroed so squareCenter applies directly, matching
                        // the piece-template convention noted above. y is lifted a hair off the tile to
                        // avoid z-fighting with the marble underneath.
                        position = Float3(center?.x ?: 0f, HIGHLIGHT_LIFT_Y, center?.z ?: 0f),
                        scale = Float3(PIECE_SCALE, PIECE_SCALE, PIECE_SCALE),
                        isVisible = highlight != null,
                        // Only capture the node here. `apply` runs once, when SceneView creates the
                        // node — fine for state that never changes, which is what this used to be
                        // (it always showed the single "Highlight" child). The tone does change, so
                        // choosing the child in here pinned every quad to whichever tone was current
                        // at creation: null, i.e. NEUTRAL, i.e. blue for the rest of the session.
                        // The piece pool above hit this first and solved it the same way.
                        apply = { nodeState.value = this }
                    ) {}

                    // No material work, by design: the GLB carries one quad per HighlightTone and
                    // the tone picks which child to show. Recolouring a single quad at runtime looks
                    // simpler and does not work — the colour is the material's `emissiveFactor`, not
                    // `baseColorFactor`, and its ubershader name moves between Filament versions.
                    // See ChessSetConventions.
                    val wantedNode = ChessSetConventions.highlightNodeName(
                        (highlight?.tone ?: HighlightTone.NEUTRAL).ordinal
                    )
                    androidx.compose.runtime.LaunchedEffect(nodeState.value, wantedNode) {
                        val node = nodeState.value ?: return@LaunchedEffect
                        node.renderableNodes.forEach { rn ->
                            rn.isVisible = rn.name == wantedNode
                            if (rn.isVisible) {
                                rn.isShadowCaster = false
                                rn.isShadowReceiver = false
                            }
                        }
                        // A tone change on a quad that stays put moves nothing either.
                        renderInvalidator.requestRender()
                    }
                }
            }
        }

        // Transparent gesture overlay (see Box comment above). Sized by `modifier`
        // (fillMaxWidth().aspectRatio(1f)); SceneView matches it via matchParentSize.
        Box(modifier)
    }
}

@OptIn(ExperimentalResourceApi::class)
fun androidBoard3DSupport(): Board3DSupport = Board3DSupport(
    rendererFactory = {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                // Validate resources before reporting 3D support. SceneView consumes the KTX files by
                // Android asset path, but the same compose-resource copy task makes these bytes
                // available, so a missing asset becomes the existing nullable fallback path.
                val glb = Res.readBytes("files/models/${ChessSetConventions.GLB_ASSET}")
                Res.readBytes("files/env/${ChessSetConventions.IBL_ASSET}")
                Res.readBytes("files/env/${ChessSetConventions.SKYBOX_ASSET_BLURRED}")
                AndroidSceneViewChessRenderer(glb)
            }.getOrNull()
        }
    },
    surfaceContent = { renderer, modifier ->
        AndroidBoard3DSurface(renderer, modifier)
    }
)
