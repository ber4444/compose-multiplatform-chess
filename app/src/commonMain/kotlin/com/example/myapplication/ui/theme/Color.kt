package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's palette, replacing the Android Studio template's `Purple40`/`Purple80`.
 *
 * It is built around what the 3D board already looks like: a marble-and-wood set lit by the
 * papermill IBL, which is a warm, low-chroma environment. So the neutrals are warm (paper, not
 * blue-grey) and the accent is a deep teal — far enough from the coach's green/amber/red verdict
 * tints that a teal button is never read as a verdict.
 */

// Accent — deep teal in light, mint in dark.
val Teal40 = Color(0xFF1E6B5E)
val Teal90 = Color(0xFFB7E7DC)
val Teal80 = Color(0xFF7FD6C4)
val Teal20 = Color(0xFF003730)
val Teal30 = Color(0xFF005046)
val Teal10 = Color(0xFF00201A)

// Warm neutrals — paper and ink rather than grey.
val Paper = Color(0xFFFAF6EE)
val Ink = Color(0xFF1D1B17)
val PaperVariant = Color(0xFFE6E0D3)
val InkVariant = Color(0xFF4B4739)
val NightSurface = Color(0xFF15130F)
val NightOn = Color(0xFFE8E2D8)
val NightVariant = Color(0xFF4A4639)
val NightOnVariant = Color(0xFFCDC6B4)
val OutlineLight = Color(0xFF7C7768)
val OutlineDark = Color(0xFF969080)

// Secondary — warm slate, used for chrome that shouldn't compete with the accent.
val Slate40 = Color(0xFF5E5C50)
val Slate90 = Color(0xFFE3E0D0)
val Slate80 = Color(0xFFC7C5B4)
val Slate20 = Color(0xFF303128)
val Slate30 = Color(0xFF464739)

// Tertiary — muted claret, for the occasional non-accent highlight.
val Claret40 = Color(0xFF7A4A57)
val Claret90 = Color(0xFFFFD9E1)
val Claret80 = Color(0xFFEDB6C3)
val Claret20 = Color(0xFF48252F)
val Claret30 = Color(0xFF613B46)

val ErrorLight = Color(0xFFB3261E)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)

/**
 * The 2D board's own colours, and the markers drawn on top of it.
 *
 * These are deliberately **not** colour-scheme roles and do not invert with the theme. The 2D board
 * is a picture of the same wooden set the 3D board renders, and a board that swaps its light and
 * dark squares in dark mode stops being that picture. The squares were previously
 * `colorScheme.secondary` against a hardcoded `Color.White`, which made the board a readout of
 * whatever the theme's secondary role happened to be.
 *
 * The markers are one shape language: a thin rectangle outlines the square you have *selected*, a
 * thick disc marks a square you can *go to*. Colour then separates the two cases within each shape.
 * They keep their original widths and shapes so the affordances read exactly as before.
 */
val BoardLightSquare = Color(0xFFEFDCBB)
val BoardDarkSquare = Color(0xFFB08160)

/** Selected piece that has somewhere to go (thin outline). */
val SelectionRing = Color(0xFFD9A441)
/** Selected piece with no legal move (thin outline). */
val SelectionBlockedRing = Color(0xFFC0473B)
/** A square the selected piece can move to (thick disc). */
val MoveMarker = Color(0xFF2FA694)
/** A square the selected piece can capture on (thick disc). */
val CaptureMarker = Color(0xFFC0473B)
