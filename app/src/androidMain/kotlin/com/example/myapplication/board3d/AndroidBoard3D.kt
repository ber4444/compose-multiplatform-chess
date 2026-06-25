package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.android.filament.Camera
import dev.romainguy.kotlin.math.Float3
import game.app.generated.resources.Res
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberFillLightNode
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
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

/** chess.glb square-size conversion (2-unit glb squares -> 1-unit game squares). */
private val PIECE_SCALE = ChessSetConventions.PIECE_SCALE

internal fun selectPieceMaterialName(materialNames: List<String>, color: PieceColor): String? {
    val expected = ChessSetMeshNames.getMaterialName(color)
    return materialNames.firstOrNull { it == expected }
        ?: materialNames.firstOrNull { it.substringAfterLast('/').startsWith(expected) }
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
        runCatching { modelLoader.createInstancedModel(buffer, MAX_PIECES + 1) }.getOrNull().orEmpty()
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

    // Drive the camera from the shared OrbitCameraController state. SceneView positions the
    // camera from the CameraNode's *node* transform every frame, so we must move the node
    // (position + lookAt) — setting the raw Filament camera's lookAt has no effect.
    //
    // Portrait FOV boost (same logic as CameraMath.effectiveFovYRad / iOS): with aspect < 1 the
    // horizontal FOV shrinks too far for the board, so widen the vertical FOV to hold a fixed
    // ~60° horizontal FOV. The board is laid out square today (aspect ~1), so this is a guard for
    // any future non-square viewport; the picker uses the identical formula to stay in sync.
    SideEffect {
        val pos    = cameraParams.position
        val tgt    = cameraParams.target
        val up     = cameraParams.up
        val aspect = cameraParams.aspect

        val fovY = if (aspect < 1f) {
            val minFovXRad  = 60.0 * PI / 180.0
            val tanHalfFovX = tan(minFovXRad / 2.0)
            (2.0 * atan(tanHalfFovX / aspect.toDouble()) * 180.0 / PI).toFloat()
        } else {
            cameraParams.fovYDegrees
        }

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
                        val hiddenNames = PieceKind.entries
                            .map { kind -> ChessSetMeshNames.getMeshName(kind, PieceColor.WHITE) }
                            .toSet() + "Plane"
                        renderableNodes.forEach { rn ->
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
