package com.example.myapplication.multiplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LichessOAuthCallbackTest {
    @Test
    fun requiresExactRedirectAndSingleMatchingState() {
        val redirect = MOBILE_LICHESS_REDIRECT
        assertEquals("liu_123", parseOnlineOAuthCallback("$redirect?state=abc&code=liu_123", redirect, "abc")?.code)
        assertEquals("access_denied", parseOnlineOAuthCallback("$redirect?state=abc&error=access_denied", redirect, "abc")?.error)
        assertNull(parseOnlineOAuthCallback("$redirect/other?state=abc&code=liu_123", redirect, "abc"))
        assertNull(parseOnlineOAuthCallback("$redirect?state=stale&code=liu_123", redirect, "abc"))
        assertNull(parseOnlineOAuthCallback("$redirect?state=abc&state=abc&code=liu_123", redirect, "abc"))
        assertNull(parseOnlineOAuthCallback("$redirect?state=abc&code=liu_123&error=access_denied", redirect, "abc"))
    }
}
