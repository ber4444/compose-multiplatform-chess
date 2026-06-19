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

@OptIn(ExperimentalForeignApi::class)
fun iosBoard3DSupport(): Board3DSupport {
    return Board3DSupport(
        rendererFactory = { WKWebViewChessRenderer() },
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
            val config = platform.WebKit.WKWebViewConfiguration()
            val view = platform.WebKit.WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = config)
            view.backgroundColor = platform.UIKit.UIColor.blackColor
            view.scrollView.scrollEnabled = false
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
                    val surface = WkWebViewChess3DSurface(view, widthPx, heightPx)
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
