package com.example.myapplication.multiplayer

import io.ktor.http.Url
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.CoreCrypto.CC_SHA256
import platform.darwin.NSObject
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
internal actual fun onlineOAuthPlatform(openUri: (String) -> Unit): OnlineOAuthPlatform = IosOnlineOAuth()

@OptIn(ExperimentalForeignApi::class)
private class IosOnlineOAuth : OnlineOAuthPlatform {
    private var presentationContext: OAuthPresentationContext? = null

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).apply {
        usePinned { check(SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) == 0) }
    }

    override suspend fun sha256(bytes: ByteArray): ByteArray = ByteArray(32).apply {
        usePinned { output ->
            bytes.usePinned { input ->
                CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0).reinterpret())
            }
        }
    }

    override suspend fun authorize(buildUrl: (String) -> String): OnlineOAuthCallback = withContext(Dispatchers.Main) {
        val url = buildUrl(MOBILE_LICHESS_REDIRECT)
        val state = checkNotNull(Url(url).parameters["state"])
        val window = UIApplication.sharedApplication.connectedScenes.asSequence()
            .filterIsInstance<UIWindowScene>()
            .flatMap { it.windows.asSequence().filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() }
            ?: error("No window is available for Lichess sign-in.")
        val context = OAuthPresentationContext(window)
        presentationContext = context
        try {
            withTimeoutOrNull(180_000) {
                suspendCancellableCoroutine { continuation ->
                    val session = ASWebAuthenticationSession(
                        checkNotNull(NSURL.URLWithString(url)),
                        MOBILE_LICHESS_SCHEME,
                    ) { callback, _ ->
                        if (!continuation.isActive) return@ASWebAuthenticationSession
                        val result = callback?.absoluteString?.let {
                            parseOnlineOAuthCallback(it, MOBILE_LICHESS_REDIRECT, state)
                        }
                        if (result == null) continuation.resumeWithException(IllegalStateException("Lichess sign-in closed."))
                        else continuation.resume(result)
                    }
                    session.presentationContextProvider = context
                    continuation.invokeOnCancellation { dispatch_async(dispatch_get_main_queue()) { session.cancel() } }
                    if (!session.start() && continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Could not open Lichess sign-in."))
                    }
                }
            } ?: error("Lichess sign-in timed out.")
        } finally {
            presentationContext = null
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class OAuthPresentationContext(private val window: UIWindow) :
    NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow = window
}
