package com.example.myapplication.persistence

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal for [Entitlements] (§0.4 monetization seam).
 *
 * Installed at the top level by [com.example.myapplication.AppRoot] so UI components
 * (such as [GameScreen] and AI coach panels) can gate features or present purchase options
 * without prop-drilling.
 *
 * Defaults to `null` so tests without entitlement wrappers treat `null` as "unrestricted access"
 * or fall back gracefully.
 */
val LocalEntitlements = staticCompositionLocalOf<Entitlements?> { null }
