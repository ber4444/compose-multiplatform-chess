package com.example.myapplication.multiplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.ktor.http.Url
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private data class PendingOAuth(val state: String, val result: CompletableDeferred<OnlineOAuthCallback>)
private val pendingOAuth = AtomicReference<PendingOAuth?>(null)

internal actual fun onlineOAuthPlatform(openUri: (String) -> Unit): OnlineOAuthPlatform =
    object : JvmOnlineOAuthCrypto() {
        override suspend fun authorize(buildUrl: (String) -> String): OnlineOAuthCallback {
            val url = buildUrl(MOBILE_LICHESS_REDIRECT)
            val pending = PendingOAuth(checkNotNull(Url(url).parameters["state"]), CompletableDeferred())
            check(pendingOAuth.compareAndSet(null, pending)) { "Lichess sign-in is already open." }
            try {
                openUri(url)
                return withTimeoutOrNull(180_000) { pending.result.await() }
                    ?: error("Lichess sign-in timed out.")
            } finally {
                pendingOAuth.compareAndSet(pending, null)
            }
        }
    }

/** Receives an untrusted browser URI and completes only the matching in-memory PKCE request. */
class LichessOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_VIEW) {
            val pending = pendingOAuth.get()
            val url = intent.dataString
            if (pending != null && url != null) {
                parseOnlineOAuthCallback(url, MOBILE_LICHESS_REDIRECT, pending.state)?.let { callback ->
                    if (pendingOAuth.compareAndSet(pending, null)) pending.result.complete(callback)
                }
            }
        }
        finish()
    }
}
