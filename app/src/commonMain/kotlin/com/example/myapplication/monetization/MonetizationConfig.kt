package com.example.myapplication.monetization

/**
 * Master switch for the whole monetization seam. **`false` for the first store release.**
 *
 * The v1 build ships every Pro surface free to everyone and offers no in-app purchase at all. That
 * is one decision, but it has four separate consequences, and leaving any of them out ships an app
 * that contradicts itself — so they are all driven from here rather than from four call sites:
 *
 *  - `AppRoot` treats Pro as unlocked, which opens Game Summary, Position Chat, the Opening
 *    Explainer and Rules Q&A. (The Move Coach was never a Pro surface.)
 *  - Every route to `Screen.PAYWALL` becomes `null`, which removes the Settings *"Upgrade to Chess
 *    Coach Pro"* row and any *See Pro* button. This is the one that does **not** follow from the
 *    unlock: that row keys off `onOpenPaywall != null`, not off `isProUnlocked()`, so an unlock
 *    alone would ship a free app whose Settings screen still advertises an upgrade and still routes
 *    to a live purchase screen.
 *  - The entry points skip `RevenueCatEntitlements.createOrNull(...)`, so the SDK is never
 *    configured and makes no network call on launch.
 *  - `PaywallScreen` itself is left untouched and still compiles — it is simply unreachable.
 *
 * To turn monetization on for a later release, flip this to `true`. Nothing else needs to change:
 * the gating code, the paywall, the plans and the RevenueCat wiring are all still present and still
 * covered by `EntitlementsTest`. Verify the keys are configured first (see the four-key note in
 * CLAUDE.md — production keys are never resolved from a `test_…` key in a release build).
 *
 * This is deliberately a compile-time `const` rather than a build-type flag or a setting: a release
 * that gives Pro away and a release that sells it are different products, and which one shipped
 * should be readable from the source at the commit that shipped it.
 */
const val MONETIZATION_ENABLED = false
