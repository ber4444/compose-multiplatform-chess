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

// Custom scheme the iOS board is served under so three.js can XHR the bundled glb/HDR assets
// (file:// XHR is blocked by WKWebView). See BundleAssetSchemeHandler + WKWebViewChessRenderer.attach.
internal const val ASSET_SCHEME = "chessasset"
internal const val ASSET_HOST_URL = "chessasset://app/chess3d-host.html"

/**
 * Serves the app's bundled web assets (chess3d-host.html, chess3d-bundle.js, chess.glb,
 * papermill_*.hdr) over [ASSET_SCHEME]. Unlike file://, a custom scheme is not blocked from
 * XMLHttpRequest/fetch, so three.js's GLTFLoader/RGBELoader can load the model + environment.
 */
@OptIn(ExperimentalForeignApi::class)
private class BundleAssetSchemeHandler : platform.darwin.NSObject(), platform.WebKit.WKURLSchemeHandlerProtocol {
    @kotlinx.cinterop.ObjCSignatureOverride
    override fun webView(webView: platform.WebKit.WKWebView, startURLSchemeTask: platform.WebKit.WKURLSchemeTaskProtocol) {
        val url = startURLSchemeTask.request.URL
        val data = url?.lastPathComponent?.let { loadBundleData(it) }
        if (url == null || data == null) {
            startURLSchemeTask.didFailWithError(platform.Foundation.NSError.errorWithDomain(ASSET_SCHEME, 404L, null))
            return
        }
        val response = platform.Foundation.NSURLResponse(
            uRL = url,
            MIMEType = mimeForFile(url.lastPathComponent ?: ""),
            expectedContentLength = data.length.toLong(),
            textEncodingName = null,
        )
        runCatching {
            startURLSchemeTask.didReceiveResponse(response)
            startURLSchemeTask.didReceiveData(data)
            startURLSchemeTask.didFinish()
        }
    }

    @kotlinx.cinterop.ObjCSignatureOverride
    override fun webView(webView: platform.WebKit.WKWebView, stopURLSchemeTask: platform.WebKit.WKURLSchemeTaskProtocol) {}
}

@OptIn(ExperimentalForeignApi::class)
private fun loadBundleData(fileName: String): platform.Foundation.NSData? {
    val dot = fileName.lastIndexOf('.')
    val name = if (dot >= 0) fileName.substring(0, dot) else fileName
    val ext = if (dot >= 0) fileName.substring(dot + 1) else ""
    val path = platform.Foundation.NSBundle.mainBundle.pathForResource(name, ofType = ext) ?: return null
    return platform.Foundation.NSFileManager.defaultManager.contentsAtPath(path)
}

private fun mimeForFile(file: String): String = when {
    file.endsWith(".html") -> "text/html"
    file.endsWith(".js") -> "application/javascript"
    file.endsWith(".glb") -> "model/gltf-binary"
    file.endsWith(".hdr") -> "image/vnd.radiance"
    else -> "application/octet-stream"
}

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
            // WKWebView blocks XMLHttpRequest/fetch of file:// resources even when the page is loaded
            // via loadFileURL(allowingReadAccessToURL:), so three.js's GLTFLoader/RGBELoader can't
            // fetch chess.glb / papermill_*.hdr (they fail "Load failed" -> blank navy env-fallback).
            // Serve the bundled assets through a custom URL scheme instead, which is not subject to
            // that restriction. (The KVC `allowFileAccessFromFileURLs` pref is not exposed in
            // Kotlin/Native, so a scheme handler is the workable fix.)
            config.setURLSchemeHandler(BundleAssetSchemeHandler(), forURLScheme = ASSET_SCHEME)
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
