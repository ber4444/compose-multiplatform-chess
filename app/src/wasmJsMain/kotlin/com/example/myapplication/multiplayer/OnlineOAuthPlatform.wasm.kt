package com.example.myapplication.multiplayer

import io.ktor.http.Url
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal actual fun onlineOAuthPlatform(openUri: (String) -> Unit): OnlineOAuthPlatform =
    object : OnlineOAuthPlatform {
        override fun randomBytes(size: Int): ByteArray = secureRandomHex(size).hexToByteArray()

        override suspend fun sha256(bytes: ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
            digestHex(bytes.toHexString()) {
                if (continuation.isActive) {
                    if (it.isEmpty()) continuation.resumeWithException(IllegalStateException("Lichess sign-in requires HTTPS."))
                    else continuation.resume(it.hexToByteArray())
                }
            }
        }

        override suspend fun authorize(buildUrl: (String) -> String): OnlineOAuthCallback =
            suspendCancellableCoroutine { continuation ->
                val redirect = callbackUrl()
                val url = buildUrl(redirect)
                val state = checkNotNull(Url(url).parameters["state"])
                val handle = openOAuthPopup(url, state) { returnedUrl ->
                    if (continuation.isActive) {
                        val callback = parseOnlineOAuthCallback(returnedUrl, redirect, state)
                        if (callback == null) continuation.resumeWithException(IllegalStateException("Lichess sign-in closed."))
                        else continuation.resume(callback)
                    }
                }
                if (handle == null) continuation.resumeWithException(IllegalStateException("Allow popups to sign in to Lichess."))
                else continuation.invokeOnCancellation { closeOAuthPopup(handle) }
            }
    }

@JsFun("(size) => Array.from(crypto.getRandomValues(new Uint8Array(size)), b => b.toString(16).padStart(2, '0')).join('')")
private external fun secureRandomHex(size: Int): String

@JsFun("""(hex, done) => {
    if (!globalThis.crypto?.subtle) { done(''); return; }
    const data = Uint8Array.from(hex.match(/../g) ?? [], b => parseInt(b, 16));
    crypto.subtle.digest('SHA-256', data)
        .then(out => done(Array.from(new Uint8Array(out), b => b.toString(16).padStart(2, '0')).join('')))
        .catch(() => done(''));
}""")
private external fun digestHex(hex: String, done: (String) -> Unit)

@JsFun("() => new URL('lichess-callback.html', location.href).href")
private external fun callbackUrl(): String

@JsFun("""(url, state, done) => {
    if (!('BroadcastChannel' in globalThis)) return null;
    const channel = new BroadcastChannel('chess-lichess:' + state);
    const popup = window.open(url, '_blank', 'popup,width=640,height=760');
    if (!popup) { channel.close(); return null; }
    const timeout = setTimeout(() => finish(''), 180000);
    const close = () => { clearTimeout(timeout); channel.close(); popup.close(); };
    const finish = value => { close(); done(value); };
    channel.onmessage = event => { if (typeof event.data === 'string') finish(event.data); };
    return { close };
}""")
private external fun openOAuthPopup(url: String, state: String, done: (String) -> Unit): JsAny?

@JsFun("(handle) => handle.close()")
private external fun closeOAuthPopup(handle: JsAny)
