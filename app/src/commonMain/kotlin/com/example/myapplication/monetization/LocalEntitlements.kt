package com.example.myapplication.monetization

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

/**
 * Debug-only blanket unlock, consulted by [isProUnlocked].
 *
 * Exists so the override applies to **all five** Pro surfaces from one place. Threading a
 * `forceProUnlocked` boolean through individual branches unlocked Rules Q&A and Position Chat while
 * leaving Game Summary and the Opening Explainer — which gate through `ProGate` -> [isProUnlocked]
 * — still locked, so a debug build showed a partially-paywalled app that matches no real user.
 *
 * Defaults to `false`, and entry points must only set it from a debug signal (`FLAG_DEBUGGABLE` /
 * `Platform.isDebugBinary`). It deliberately does **not** affect `PaywallScreen`, which reads
 * [LocalEntitlements] directly so it can still be inspected in a debug build.
 */
val LocalProUnlockOverride = staticCompositionLocalOf { false }
