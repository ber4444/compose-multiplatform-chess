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
            val geometries = mutableMapOf<String, platform.SceneKit.SCNGeometry>()
            val textures = mutableMapOf<String, platform.Foundation.NSData>()
            val models = listOf("KING", "QUEEN", "ROOK", "BISHOP", "KNIGHT", "PAWN", "BOARD")
            val texNames = listOf("whites.png", "blacks.png", "board3.jpg")
            try {
                for (name in models) {
                    val bytes = Res.readBytes("files/models/ios/$name.obj")
                    val geom = loadObjGeometryFromBytes(bytes, name)
                    if (geom != null) {
                        geometries[name] = geom
                    }
                }
                for (tName in texNames) {
                    val bytes = Res.readBytes("files/models/ios/$tName")
                    textures[tName] = bytes.usePinned { pinned ->
                        platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                    }
                }
            } catch (e: Exception) {
                // If anything fails, it will just drop back to primitives or lack thereof
            }
            IosSceneKitChessRenderer(geometries, textures)
        },
        surfaceContent = { renderer, modifier ->
            IosBoard3DSurface(renderer, modifier)
        }
    )
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
