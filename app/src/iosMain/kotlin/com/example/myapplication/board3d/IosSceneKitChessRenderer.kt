package com.example.myapplication.board3d

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.writeToFile
import platform.SceneKit.SCNBox
import platform.SceneKit.SCNCamera
import platform.SceneKit.SCNCylinder
import platform.SceneKit.SCNGeometry
import platform.SceneKit.SCNLight
import platform.SceneKit.SCNLightTypeDirectional
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNScene
import platform.CoreImage.CIImage
import platform.CoreImage.CIContext
import platform.CoreImage.createCGImage

import platform.SceneKit.SCNView
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.SceneKit.SCNVector3Make

class IosSceneKitSurface(
    val scnView: SCNView,
    override val widthPx: Int,
    override val heightPx: Int
) : Chess3DSurface

@OptIn(ExperimentalForeignApi::class)
class IosSceneKitChessRenderer(
    private val geometries: Map<String, SCNGeometry> = emptyMap(),
    private val textures: Map<String, platform.Foundation.NSData> = emptyMap()
) : Chess3DBoardRenderer {

    private var surface: Chess3DSurface? = null
    private var scnView: SCNView? = null
    private var scene: SCNScene? = null
    private var rootNode: SCNNode? = null
    private var pieceNodes: MutableList<SCNNode> = mutableListOf()

    private var pendingFen: String? = null
    private var selectedSquare: BoardSquare? = null
    private var cameraNode: SCNNode? = null

    init {
        scene = SCNScene.scene()
        rootNode = SCNNode.node()
        scene?.rootNode?.addChildNode(rootNode!!)

        cameraNode = SCNNode.node()
        val cam = SCNCamera.camera()
        cam.wantsHDR = true                                   // filmic tonemapping
        cam.bloomIntensity = 0.25
        cam.bloomThreshold = 0.85
        cam.wantsExposureAdaptation = false
        cam.screenSpaceAmbientOcclusionIntensity = 0.6        // contact darkening
        cam.screenSpaceAmbientOcclusionRadius = 0.5
        // Depth of field: focus on the board, let the distant skybox melt into bokeh (matches Android).
        cam.wantsDepthOfField = true
        cam.focusDistance = 4.0      // Focus tightly on the board
        cam.fStop = 0.1               // Extreme shallow depth of field for heavy bokeh
        cam.focalBlurSampleCount = 0  // Maximum quality blur
        cam.focalLength = 22.0        // ...but the board's shallow depth stays readable
        cam.apertureBladeCount = 6    // hexagonal bokeh highlights
        cameraNode?.camera = cam
        // Move camera slightly down and closer if floor is gone? 
        // Actually keep same position as before.
        cameraNode?.position = SCNVector3Make(0.0f, 6.5f, 6.5f)
        
        val lookAt = platform.SceneKit.SCNLookAtConstraint.lookAtConstraintWithTarget(rootNode)
        cameraNode?.constraints = listOf(lookAt)
        
        scene?.rootNode?.addChildNode(cameraNode!!)

        // Key directional light with soft shadows.
        val lightNode = SCNNode.node()
        val light = SCNLight.light()
        light.type = SCNLightTypeDirectional
        light.castsShadow = true
        light.shadowMode = platform.SceneKit.SCNShadowModeDeferred
        light.shadowRadius = 8.0
        light.shadowSampleCount = 16.toULong()
        light.shadowColor = UIColor.colorWithRed(0.0, green = 0.0, blue = 0.0, alpha = 0.45)
        light.intensity = 300.0
        lightNode.light = light
        lightNode.eulerAngles = SCNVector3Make((-kotlin.math.PI / 3.0).toFloat(), (kotlin.math.PI / 4.0).toFloat(), 0.0f)
        scene?.rootNode?.addChildNode(lightNode)

        // Soft ambient fill.
        val ambientNode = SCNNode.node()
        val ambient = SCNLight.light()
        ambient.type = platform.SceneKit.SCNLightTypeAmbient
        ambient.intensity = 0.0
        ambient.color = UIColor.colorWithRed(0.60, green = 0.66, blue = 0.76, alpha = 1.0)
        ambientNode.light = ambient
        scene?.rootNode?.addChildNode(ambientNode)

        // Image-based ambient/reflection + sky background.
        // SceneKit only auto-detects spherical (2:1) or strip (6:1 / 1:6) layouts from a single
        // image; the 4:3 papermill cross was being smeared on as a sphere (black corners and all).
        // Feed the six decoded faces as a real cube map instead -> correct skybox AND real PBR
        // reflections on the marble board and varnished pieces.
        val cubeMap = loadCubeMapImages()
        if (cubeMap != null) {
            scene?.lightingEnvironment?.contents = cubeMap
            scene?.lightingEnvironment?.intensity = 2.2
            scene?.background?.contents = cubeMap
        } else {
            scene?.lightingEnvironment?.contents = UIColor.colorWithRed(0.70, green = 0.76, blue = 0.85, alpha = 1.0)
            scene?.lightingEnvironment?.intensity = 1.2
            scene?.background?.contents = UIColor.colorWithRed(0.62, green = 0.70, blue = 0.82, alpha = 1.0)
        }

        // We drop the separate floor node per M7 (environment is the ground).

        val boardGeom = geometries["BOARD"]
        if (boardGeom != null) {
            val boardNode = SCNNode.nodeWithGeometry(boardGeom)
            val boardMat = SCNMaterial.material()
            boardMat.lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
            boardMat.roughness.contents = 0.18 // polished marble: crisp environment reflection
            boardMat.metalness.contents = 0.0
            if (textures["board3.jpg"] != null) {
                boardMat.diffuse.contents = platform.UIKit.UIImage.imageWithData(textures["board3.jpg"]!!)
            } else {
                boardMat.diffuse.contents = UIColor.darkGrayColor
            }
            boardNode.geometry?.materials = listOf(boardMat)

            // Adjust to align with the camera correctly. The glTF models are likely centered at origin.
            // We scale it down slightly if needed, or it matches exactly the Desktop renderer.
            // In Desktop, TARGET_KING_HEIGHT is used to scale, but we'll see if the raw OBJ size works.
            rootNode?.addChildNode(boardNode)

            // Stone rim around the playing surface (the engraved border seen on Android/desktop).
            // frame.obj carries the glb's UVs (pre-scaled 0.5 to the ±4 board), so the exact
            // marble-speckled veining + A-H labels map on, with the normal map for surface relief.
            val frameGeom = geometries["FRAME"]
            if (frameGeom != null) {
                val frameNode = SCNNode.nodeWithGeometry(frameGeom)
                val frameMat = SCNMaterial.material()
                frameMat.lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
                frameMat.roughness.contents = 0.3   // polished stone
                frameMat.metalness.contents = 0.0
                val albedo = textures["marble-speckled-albedo.png"]
                if (albedo != null) {
                    frameMat.diffuse.contents = platform.UIKit.UIImage.imageWithData(albedo)
                    frameMat.multiply.contents = UIColor.colorWithRed(0.27, green = 0.27, blue = 0.30, alpha = 1.0)
                    textures["marble-speckled-normal.png"]?.let {
                        frameMat.normal.contents = platform.UIKit.UIImage.imageWithData(it)
                    }
                } else {
                    frameMat.diffuse.contents = UIColor.colorWithRed(0.66 * 0.27, green = 0.65 * 0.27, blue = 0.63 * 0.30, alpha = 1.0)
                }
                frameNode.geometry?.materials = listOf(frameMat)
                rootNode?.addChildNode(frameNode)
            }
        } else {
            val boardBase = SCNNode.nodeWithGeometry(SCNBox.boxWithWidth(8.0, height = 0.5, length = 8.0, chamferRadius = 0.0))
            boardBase.position = SCNVector3Make(0.0f, -0.25f, 0.0f)
            val boardMaterial = SCNMaterial.material()
            boardMaterial.diffuse.contents = UIColor.darkGrayColor
            boardBase.geometry?.materials = listOf(boardMaterial)
            rootNode?.addChildNode(boardBase)

            for (file in 0..7) {
                for (rank in 0..7) {
                    val isLight = (file + rank) % 2 != 0
                    val squareNode = SCNNode.nodeWithGeometry(SCNBox.boxWithWidth(1.0, height = 0.05, length = 1.0, chamferRadius = 0.0))
                    val squareMat = SCNMaterial.material()
                    squareMat.diffuse.contents = if (isLight) UIColor.lightGrayColor else UIColor.grayColor
                    squareNode.geometry?.materials = listOf(squareMat)
                    
                    val cx = (file - 3.5).toFloat()
                    val cz = (3.5 - rank).toFloat()
                    squareNode.position = SCNVector3Make(cx, 0.0f, cz)
                    rootNode?.addChildNode(squareNode)
                }
            }
        }
    }

    /**
     * Decodes the six cube faces (face_0..face_5 == px, nx, py, ny, pz, nz) into an NSArray of
     * UIImages, the layout SceneKit unambiguously treats as a cube map. Returns null if any face
     * is missing or fails to decode, so the caller can fall back to a solid-colour environment.
     */
    private fun loadCubeMapImages(): platform.Foundation.NSMutableArray? {
        val ciContext = CIContext.context()
        val tempDir = platform.Foundation.NSTemporaryDirectory()
        val images = platform.Foundation.NSMutableArray()
        for (i in 0..5) {
            val data = textures["face_$i.exr"] ?: return null
            val path = tempDir + "face_$i.exr"
            data.writeToFile(path, atomically = true)
            val url = platform.Foundation.NSURL.fileURLWithPath(path)
            val ci = CIImage.imageWithContentsOfURL(url) ?: return null
            val cg = ciContext.createCGImage(ci, fromRect = ci.extent) ?: return null
            images.addObject(UIImage.imageWithCGImage(cg))
        }
        return images
    }

    override fun attach(surface: Chess3DSurface) {
        val scnSurface = surface as? IosSceneKitSurface ?: return
        this.surface = surface
        this.scnView = scnSurface.scnView
        
        scnSurface.scnView.scene = this.scene
        scnSurface.scnView.allowsCameraControl = false
        scnSurface.scnView.autoenablesDefaultLighting = false // use our explicit lighting rig
        scnSurface.scnView.jitteringEnabled = true            // antialiasing for stills
        scnSurface.scnView.backgroundColor = UIColor.colorWithRed(0.62, green = 0.70, blue = 0.82, alpha = 1.0)

        if (pendingFen != null) {
            updatePosition(pendingFen!!)
            pendingFen = null
        }
    }

    override fun detach() {
        this.surface = null
        this.scnView?.scene = null
        this.scnView = null
    }

    override fun updatePosition(fen: String) {
        if (scnView == null) {
            pendingFen = fen
            return
        }
        rebuildPieces(fen)
    }

    /**
     * Rebuilds the piece nodes from [fen]. Touches only [rootNode]/[scene] (both alive from init),
     * so it works whether or not an [SCNView] is attached — the snapshot harness drives it headless.
     */
    private fun rebuildPieces(fen: String) {
        val boardScene = Board3DSceneMapper.fromFen(fen)
        
        pieceNodes.forEach { it.removeFromParentNode() }
        pieceNodes.clear()

        val whiteMat = SCNMaterial.material()
        whiteMat.lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
        whiteMat.roughness.contents = 0.4
        whiteMat.metalness.contents = 0.0
        if (textures["whites.png"] != null) {
            whiteMat.diffuse.contents = platform.UIKit.UIImage.imageWithData(textures["whites.png"]!!)
        } else {
            whiteMat.diffuse.contents = UIColor.whiteColor
        }

        val blackMat = SCNMaterial.material()
        blackMat.lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
        blackMat.roughness.contents = 0.4
        blackMat.metalness.contents = 0.0
        if (textures["blacks.png"] != null) {
            blackMat.diffuse.contents = platform.UIKit.UIImage.imageWithData(textures["blacks.png"]!!)
        } else {
            blackMat.diffuse.contents = UIColor.blackColor
        }

        for (piece in boardScene.pieces) {
            val isWhite = piece.color == PieceColor.WHITE
            val mat = if (isWhite) whiteMat else blackMat
            
            val geomName = piece.kind.name
            val baseGeom = geometries[geomName]
            
            val node = if (baseGeom != null) {
                val copyGeom = baseGeom.copy() as platform.SceneKit.SCNGeometry
                copyGeom.materials = listOf(mat)
                SCNNode.nodeWithGeometry(copyGeom)
            } else {
                val fallbackGeom = if (piece.kind == PieceKind.PAWN) {
                    SCNCylinder.cylinderWithRadius(0.3, height = 0.6)
                } else {
                    SCNCylinder.cylinderWithRadius(0.35, height = 0.8)
                }
                val fbNode = SCNNode.nodeWithGeometry(fallbackGeom)
                fbNode.geometry?.materials = listOf(mat)
                fbNode
            }
            
            // Adjust placement since models are likely centered at origin
            val cx = piece.position.x
            val cz = piece.position.z
            // If using exact models, y = 0 since they sit on the board.
            // But fallbacks need an offset.
            val cy = if (baseGeom != null) 0.0f else piece.position.y + (if (piece.kind == PieceKind.PAWN) 0.3f else 0.4f)
            node.position = SCNVector3Make(cx, cy, cz)
            
            rootNode?.addChildNode(node)
            pieceNodes.add(node)
        }
    }

    private var currentAspect = 1.0
    private var currentFovY = 45.0

    private fun updateCameraNode() {
        if (currentAspect < 1.0) {
            val minFovXRad = 60.0 * platform.posix.M_PI / 180.0
            val tanHalfFovX = platform.posix.tan(minFovXRad / 2.0)
            val fovYRad = 2.0 * platform.posix.atan(tanHalfFovX / currentAspect)
            cameraNode?.camera?.fieldOfView = (fovYRad * 180.0 / platform.posix.M_PI)
        } else {
            cameraNode?.camera?.fieldOfView = currentFovY
        }
        cameraNode?.camera?.projectionDirection = platform.SceneKit.SCNCameraProjectionDirectionVertical
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.Resize -> {
                if (event.heightPx > 0) {
                    currentAspect = event.widthPx.toDouble() / event.heightPx.toDouble()
                    updateCameraNode()
                }
            }
            is Board3DInput.SetCamera -> {
                val p = event.camera.position
                cameraNode?.position = SCNVector3Make(p.x, p.y, p.z)
                currentFovY = event.camera.fovYDegrees.toDouble()
                cameraNode?.camera?.zNear = event.camera.near.toDouble()
                cameraNode?.camera?.zFar = event.camera.far.toDouble()
                // Keep the board in focus as the user orbits/zooms (target is the origin, so the
                // orbit radius is just |position|); the distant skybox stays in bokeh.
                cameraNode?.camera?.focusDistance = kotlin.math.sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble())
                updateCameraNode()
                // SceneKit SCNLookAtConstraint on cameraNode handles looking at the center
            }
            else -> {}
        }
    }

    private var selectionNode: SCNNode? = null

    override fun setSelectedSquare(square: BoardSquare?) {
        this.selectedSquare = square
        selectionNode?.removeFromParentNode()
        selectionNode = null

        if (square != null) {
            val center = BoardGeometry.squareCenter(square)
            val box = platform.SceneKit.SCNBox.boxWithWidth(1.0, height = 0.05, length = 1.0, chamferRadius = 0.0)
            val mat = platform.SceneKit.SCNMaterial.material()
            val green = UIColor.colorWithRed(0.30, green = 0.95, blue = 0.40, alpha = 1.0)
            mat.diffuse.contents = green
            mat.emission.contents = green   // glow so it reads under the lighting rig
            mat.transparency = 0.55
            box.materials = listOf(mat)

            selectionNode = platform.SceneKit.SCNNode.nodeWithGeometry(box)
            selectionNode?.position = platform.SceneKit.SCNVector3Make(center.x, 0.03f, center.z)
            rootNode?.addChildNode(selectionNode!!)
        }
    }

    /**
     * Headless render of the current scene to PNG bytes, for the snapshot regression test. Builds
     * [fen]'s pieces, points an offscreen [SCNRenderer] at this renderer's own scene + camera (so it
     * captures the exact lighting/IBL/materials the app uses), and encodes the result. Returns null
     * if no Metal device is available (e.g. an unsupported CI runner).
     */
    @OptIn(ExperimentalForeignApi::class)
    internal fun renderSnapshotPng(fen: String, widthPx: Int, heightPx: Int, camera: CameraParams): ByteArray? {
        onUserInteraction(Board3DInput.Resize(widthPx, heightPx))
        onUserInteraction(Board3DInput.SetCamera(camera))
        rebuildPieces(fen)

        // No Metal device under a headless `simctl spawn` test runner — caller treats null as "skip".
        val device = platform.Metal.MTLCreateSystemDefaultDevice() ?: return null
        val sceneRenderer = platform.SceneKit.SCNRenderer.rendererWithDevice(device, options = null)
        sceneRenderer.scene = scene
        sceneRenderer.pointOfView = cameraNode

        val image = sceneRenderer.snapshotAtTime(
            time = 0.0,
            withSize = platform.CoreGraphics.CGSizeMake(widthPx.toDouble(), heightPx.toDouble()),
            antialiasingMode = platform.SceneKit.SCNAntialiasingMode.SCNAntialiasingModeMultisampling4X
        )
        val png = platform.UIKit.UIImagePNGRepresentation(image) ?: return null
        val len = png.length.toInt()
        val out = ByteArray(len)
        if (len > 0) out.usePinned { platform.posix.memcpy(it.addressOf(0), png.bytes, png.length) }
        return out
    }

    override fun dispose() {
        scene = null
        rootNode = null
        selectionNode = null
        pieceNodes.clear()
    }
}
