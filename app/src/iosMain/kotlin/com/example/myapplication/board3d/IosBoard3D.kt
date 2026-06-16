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
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.SceneKit.SCNView

import game.app.generated.resources.Res
import kotlinx.cinterop.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.create

@OptIn(ExperimentalResourceApi::class, ExperimentalForeignApi::class)
fun iosBoard3DSupport(): Board3DSupport {
    return Board3DSupport(
        rendererFactory = {
            val (geometries, textures) = try {
                buildIosChessAssets { Res.readBytes(it) }
            } catch (e: Exception) {
                // If anything fails, drop back to primitives / solid-colour environment.
                mutableMapOf<String, platform.SceneKit.SCNGeometry>() to
                    mutableMapOf<String, platform.Foundation.NSData>()
            }
            IosSceneKitChessRenderer(geometries, textures)
        },
        surfaceContent = { renderer, modifier ->
            IosBoard3DSurface(renderer, modifier)
        }
    )
}

/**
 * Loads the piece/board geometries, diffuse textures, and environment cube faces the SceneKit
 * renderer needs, keyed by the names [IosSceneKitChessRenderer] looks them up by. [readBytes] maps a
 * compose-resource path (e.g. "files/env/face_0.exr") to its bytes; the app passes `Res.readBytes`,
 * the snapshot test passes a host-filesystem reader so both render byte-identical scenes.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun buildIosChessAssets(
    readBytes: suspend (String) -> ByteArray
): Pair<MutableMap<String, platform.SceneKit.SCNGeometry>, MutableMap<String, platform.Foundation.NSData>> {
    val geometries = mutableMapOf<String, platform.SceneKit.SCNGeometry>()
    val textures = mutableMapOf<String, platform.Foundation.NSData>()
    val models = listOf("KING", "QUEEN", "ROOK", "BISHOP", "KNIGHT", "PAWN", "BOARD")
    val texNames = listOf(
        "whites.png", "blacks.png", "board3.jpg",
        "marble-speckled-albedo.png", "marble-speckled-normal.png", // engraved stone rim
    )
    for (name in models) {
        loadObjGeometryFromBytes(readBytes("files/models/ios/$name.obj"), name)?.let { geometries[name] = it }
    }
    // The engraved stone rim around the playing surface (exported at the glb's native scale, so the
    // renderer halves it to match BOARD.obj's logical scale). BOARD.obj is only the 8x8 tiles.
    loadObjGeometryFromBytes(readBytes("files/models/ios/frame.obj"), "FRAME")?.let { geometries["FRAME"] = it }
    for (tName in texNames) {
        textures[tName] = readBytes("files/models/ios/$tName").toNSData()
    }
    // Six cube faces (px, nx, py, ny, pz, nz) for a proper SceneKit cube map. (The 4:3
    // papermill_cross.exr is deliberately not loaded — SceneKit can't interpret a cross layout.)
    for (i in 0..5) {
        textures["face_$i.exr"] = readBytes("files/env/face_$i.exr").toNSData()
    }
    return geometries to textures
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): platform.Foundation.NSData =
    if (isEmpty()) platform.Foundation.NSData()
    else usePinned { pinned ->
        platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
@Composable
fun IosBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier = Modifier) {
    var isAttached by remember { mutableStateOf(false) }
    var currentSize by remember { mutableStateOf(Pair(0, 0)) }

    DisposableEffect(renderer) {
        onDispose {
            if (isAttached) {
                renderer.detach()
                isAttached = false
            }
        }
    }

    UIKitView(
        factory = {
            val view = SCNView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0))
            view.backgroundColor = platform.UIKit.UIColor.whiteColor
            view
        },
        modifier = modifier,
        interactive = false,
        update = { view ->
            val scale = platform.UIKit.UIScreen.mainScreen.scale
            val widthPx = (view.bounds.useContents { size.width } * scale).toInt()
            val heightPx = (view.bounds.useContents { size.height } * scale).toInt()
            
            if (widthPx > 0 && heightPx > 0) {
                if (!isAttached) {
                    val surface = IosSceneKitSurface(view as SCNView, widthPx, heightPx)
                    renderer.attach(surface)
                    isAttached = true
                }
                if (currentSize.first != widthPx || currentSize.second != heightPx) {
                    currentSize = Pair(widthPx, heightPx)
                    renderer.onUserInteraction(Board3DInput.Resize(widthPx, heightPx))
                }
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
