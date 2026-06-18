# Transparent 3D Controls Design

## Goal

Restore the lightweight transparent appearance of the controls shown over the 3D board while keeping them legible against the environment.

## Visual treatment

- Remove any surrounding white panel, gray scrim, or solid control background.
- Render the Reset and Offer Draw buttons with transparent containers and black text.
- Give each button a thin light-gray underline. Do not add a full button border.
- Render the `3D` label in black.
- Render the switch with a transparent track, a thin light-gray track outline, and a dark thumb.
- Represent disabled states by reducing black and gray opacity; do not introduce a fill.

## Scope

The change is confined to the shared 3D control styling in `GameScreen.kt`. Layout, click behavior, switch behavior, renderer lifecycle, and platform-specific renderer implementations remain unchanged.

## Verification

- Add or update focused UI assertions where the existing test surface exposes control semantics.
- Build the Android debug app and Wasm development bundle.
- Visually inspect the 3D controls in Chrome to confirm the background remains visible through every control and that labels remain readable.
