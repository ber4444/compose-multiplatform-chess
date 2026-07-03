package com.example.myapplication.share

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * iOS [PgnSharer]. Presents a [UIActivityViewController] with the PGN as text from the active
 * scene's key window's root view controller. (Sharing an `NSString` covers Mail/Notes/Messages/AirDrop
 * and "Copy" — sufficient for PGN export without a tmp-file URI.)
 */
@OptIn(ExperimentalForeignApi::class)
class IosPgnSharer : PgnSharer {
    override fun share(pgn: String, suggestedFileName: String) {
        val presenter = topViewController() ?: return
        val activityItems = listOf<Any>(pgn as NSString)
        val activityVC = UIActivityViewController(activityItems = activityItems, applicationActivities = null)
        presenter.presentViewController(activityVC, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        val window = keyWindow() ?: return null
        var top = window.rootViewController ?: return null
        while (top.presentedViewController != null) top = top.presentedViewController!!
        return top
    }

    /** Finds the first window across the app's connected scenes (single-window app). */
    @Suppress("UNCHECKED_CAST")
    private fun keyWindow(): UIWindow? {
        val scenes = UIApplication.sharedApplication.connectedScenes
        for (scene in scenes) {
            val windowScene = scene as? UIWindowScene ?: continue
            // `UIWindowScene.windows` is typed `List<*>` in this K/N binding; cast the elements.
            // Single-window app: the first window hosts Compose.
            val windows = windowScene.windows as List<UIWindow>
            val first = windows.firstOrNull()
            if (first != null) return first
        }
        return null
    }
}

/** Factory mirroring `iosBoard3DSupport(...)` — constructed at the iOS entry point. */
fun iosPgnSharer(): PgnSharer = IosPgnSharer()
