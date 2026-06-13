package com.example.myapplication.board3d

import kotlinx.cinterop.ExperimentalForeignApi
import platform.SceneKit.SCNBox
import platform.SceneKit.SCNCamera
import platform.SceneKit.SCNCylinder
import platform.SceneKit.SCNGeometry
import platform.SceneKit.SCNLight
import platform.SceneKit.SCNLightTypeDirectional
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNView
import platform.UIKit.UIColor
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
        cameraNode?.camera = SCNCamera.camera()
        cameraNode?.position = SCNVector3Make(0.0f, 6.5f, 6.5f)
        
        val lookAt = platform.SceneKit.SCNLookAtConstraint.lookAtConstraintWithTarget(rootNode)
        cameraNode?.constraints = listOf(lookAt)
        
        scene?.rootNode?.addChildNode(cameraNode!!)

        val lightNode = SCNNode.node()
        val light = SCNLight.light()
        light.type = SCNLightTypeDirectional
        lightNode.light = light
        lightNode.eulerAngles = SCNVector3Make((-kotlin.math.PI / 3.0).toFloat(), (kotlin.math.PI / 4.0).toFloat(), 0.0f)
        scene?.rootNode?.addChildNode(lightNode)

        val boardGeom = geometries["BOARD"]
        if (boardGeom != null) {
            val boardNode = SCNNode.nodeWithGeometry(boardGeom)
            val boardMat = SCNMaterial.material()
            boardMat.lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
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

    override fun attach(surface: Chess3DSurface) {
        val scnSurface = surface as? IosSceneKitSurface ?: return
        this.surface = surface
        this.scnView = scnSurface.scnView
        
        scnSurface.scnView.scene = this.scene
        scnSurface.scnView.allowsCameraControl = false
        scnSurface.scnView.autoenablesDefaultLighting = true
        scnSurface.scnView.backgroundColor = UIColor.whiteColor

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
        
        val boardScene = Board3DSceneMapper.fromFen(fen)
        
        pieceNodes.forEach { it.removeFromParentNode() }
        pieceNodes.clear()

        val whiteMat = SCNMaterial.material()
        whiteMat.lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
        if (textures["whites.png"] != null) {
            whiteMat.diffuse.contents = platform.UIKit.UIImage.imageWithData(textures["whites.png"]!!)
        } else {
            whiteMat.diffuse.contents = UIColor.whiteColor
        }
        
        val blackMat = SCNMaterial.material()
        blackMat.lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
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

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> {
                cameraNode?.position = SCNVector3Make(event.camera.position.x, event.camera.position.y, event.camera.position.z)
                cameraNode?.camera?.fieldOfView = event.camera.fovYDegrees.toDouble()
                cameraNode?.camera?.projectionDirection = platform.SceneKit.SCNCameraProjectionDirectionVertical
                cameraNode?.camera?.zNear = event.camera.near.toDouble()
                cameraNode?.camera?.zFar = event.camera.far.toDouble()
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
            mat.diffuse.contents = platform.UIKit.UIColor.greenColor
            mat.transparency = 0.5
            box.materials = listOf(mat)
            
            selectionNode = platform.SceneKit.SCNNode.nodeWithGeometry(box)
            selectionNode?.position = platform.SceneKit.SCNVector3Make(center.x, 0.025f, center.z)
            rootNode?.addChildNode(selectionNode!!)
        }
    }

    override fun dispose() {
        scene = null
        rootNode = null
        selectionNode = null
        pieceNodes.clear()
    }
}
