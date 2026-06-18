package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Camera
import com.google.android.filament.gltfio.FilamentInstance
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
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import org.jetbrains.compose.resources.ExperimentalResourceApi

// Compose resources land at this prefix inside Android assets (set by compose.resources config
// in app/build.gradle.kts: packageOfResClass = "game.app.generated.resources").
private const val RES_PREFIX = "composeResources/game.app.generated.resources"
private const val IBL_KTX    = "$RES_PREFIX/files/env/papermill_ibl.ktx"
private const val SKYBOX_KTX = "$RES_PREFIX/files/env/papermill_skybox.ktx"

/** A chess board holds at most 32 pieces (promotion replaces a pawn, never adds). */
private const val MAX_PIECES = 32

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
 * rotation, visibility, and which mesh) without adding/removing nodes after init. A green
 * [highlightMaterial] disk marks the selected square. The shared Board3DHost camera/gestures drive
 * the camera via a transparent overlay (SceneView's SurfaceView consumes touches otherwise).
 */
@Composable
fun AndroidBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier) {
    val svRenderer = renderer as? AndroidSceneViewChessRenderer ?: return

    val engine         = rememberEngine()
    val modelLoader    = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val envLoader      = rememberEnvironmentLoader(engine)

    // Read chess.glb once and create each node's FilamentInstance synchronously from the cached
    // bytes. (SceneView's rememberModelInstance() helper loads asynchronously — fine for a single
    // viewer model, but here we create one instance per board/piece node up front and want them all
    // present in the first composition's node batch.)
    val glbBytes = svRenderer.glbBytes
    val newInstance: () -> FilamentInstance? = {
        val buffer = ByteBuffer.allocateDirect(glbBytes.size).order(ByteOrder.nativeOrder())
        buffer.put(glbBytes).rewind()
        runCatching { modelLoader.createModelInstance(buffer) }.getOrNull()
    }

    val environment = remember(envLoader) {
        envLoader.createKTX1Environment(IBL_KTX, SKYBOX_KTX)
    }

    // Material for the selected-square highlight disk. Slightly glowing green so it reads under a
    // piece without being washed out by the bright IBL. Created once and reused for every selection.
    val highlightMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(
            color = Color(0xFF3DDC6B),
            metallic = 0f,
            roughness = 0.35f,
        )
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
    val selectedSquare = svRenderer.selectedSquare

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
            val boardInstance = remember { newInstance() }
            if (boardInstance != null) {
                ModelNode(
                    modelInstance = boardInstance,
                    scale = Float3(0.5f, 0.5f, 0.5f),
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
                val instance = remember(i) { newInstance() }
                if (instance != null) {
                    val materialInstances = remember(instance) {
                        instance.materialInstances.associateBy { it.name }
                    }
                    val nodeState = remember {
                        androidx.compose.runtime.mutableStateOf<io.github.sceneview.node.ModelNode?>(null)
                    }
                    ModelNode(
                        modelInstance = instance,
                        position = Float3(piece?.position?.x ?: 0f, 0f, piece?.position?.z ?: 0f),
                        rotation = Float3(0f, piece?.rotationYDegrees ?: 0f, 0f),
                        scale = Float3(0.5f, 0.5f, 0.5f),
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

            if (selectedSquare != null) {
                val hp = BoardGeometry.squareCenter(selectedSquare)
                CylinderNode(
                    radius = 0.46f,
                    height = 0.04f,
                    sideCount = 48,
                    materialInstance = highlightMaterial,
                    position = Float3(hp.x, 0.03f, hp.z),
                )
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
                val glb = Res.readBytes("files/models/chess.glb")
                Res.readBytes("files/env/papermill_ibl.ktx")
                Res.readBytes("files/env/papermill_skybox.ktx")
                AndroidSceneViewChessRenderer(glb)
            }.getOrNull()
        }
    },
    surfaceContent = { renderer, modifier ->
        AndroidBoard3DSurface(renderer, modifier)
    }
)
