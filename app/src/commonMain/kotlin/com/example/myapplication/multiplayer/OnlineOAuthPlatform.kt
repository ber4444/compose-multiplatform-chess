package com.example.myapplication.multiplayer

import io.ktor.http.parseQueryString

internal interface OnlineOAuthPlatform {
    fun prepareBrowser() = Unit
    fun randomBytes(size: Int): ByteArray
    suspend fun sha256(bytes: ByteArray): ByteArray
    suspend fun authorize(buildUrl: (redirectUri: String) -> String): OnlineOAuthCallback
}

internal expect fun onlineOAuthPlatform(openUri: (String) -> Unit): OnlineOAuthPlatform

internal const val MOBILE_LICHESS_SCHEME = "io.github.ber4444.chess"
internal const val MOBILE_LICHESS_REDIRECT = "$MOBILE_LICHESS_SCHEME://oauth/lichess"

internal fun parseOnlineOAuthCallback(url: String, redirect: String, state: String): OnlineOAuthCallback? {
    if (url.length > 4096 || '#' in url || url.substringBefore('?') != redirect) return null
    val query = runCatching { parseQueryString(url.substringAfter('?', "")) }.getOrNull() ?: return null
    if (query.getAll("state")?.singleOrNull() != state) return null
    val codes = query.getAll("code").orEmpty()
    val errors = query.getAll("error").orEmpty()
    val code = codes.singleOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
    val error = errors.singleOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
    return when {
        code != null && errors.isEmpty() -> OnlineOAuthCallback(redirect, state, code)
        error != null && codes.isEmpty() -> OnlineOAuthCallback(redirect, state, null, error)
        else -> null
    }
}
