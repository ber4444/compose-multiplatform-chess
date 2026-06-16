# Issue #32 — 3D UI, Milestone 5: Interaction, Animation, Polish

Prereqs: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) and merged M1–M4 (or at minimum M1; the features here are common-code and benefit every shipped backend). Suggested branch: `issue-32-3d-m5`.

Only now does the renderer interface grow. Both additions are backward compatible: existing backends keep compiling and keep working (they just snap instead of animating).

> **Status:** Feature 1 (tap-to-move + selection highlight) is **implemented on desktop** — the `Board3D` host ray-picks taps and routes them through the 2D board's `updateSelected`/`playerMove` path, and the renderer draws the selected square. Feature 2 (the scene differ + animated transitions) is **not yet implemented**. The interface change below for animation (`updatePosition(fen, transition)`) is still pending; the shipped `setSelectedSquare` default-method addition covers selection.

> **Priority note:** Feature 1 (tap-to-move) is **not optional polish** for this project. Because the UI hides the 2D board in 3D mode (a swap, not a co-display — see overview), 3D mode is *view-only* until this lands: a human cannot make a move while in 3D. Treat 3D tap-to-move as the requirement that makes 3D mode actually playable; animation (Feature 2) is the polish.

## Feature 1: Tap-to-move in 3D

The 2D model remains the source of truth for rules and selection state (per the issue: game state stays in Compose). Camera state already lives in the common host's `OrbitCameraController`, so picking is pure common code — no renderer involvement:

- `Board3D` gains `onSquareTapped: (BoardSquare) -> Unit`. The host maps `Board3DInput.Tap(xNorm, yNorm)` → `CameraMath.rayFromScreen(controller.camera, xNorm, yNorm)` → `BoardRayPicker.pickSquare(ray)` → callback (when non-null).
- `GameScreen` routes `onSquareTapped` through the **same** `updateSelected` / `playerMove` lambdas the 2D `Square.onClick` uses: tapped square holds a white piece → `updateSelected`; tapped square is a legal destination of the current selection → `playerMove`. No new ViewModel API.
- Selection feedback in 3D: the host includes `gameState.selectedSquare` when mapping (`Board3DScene.selectedSquare`, reserved since M1); renderers highlight that square (emissive tint or a quad under the piece).

## Feature 2: Smooth piece animation

- New commonMain file `board3d/Board3DSceneDiffer.kt`:

```kotlin
sealed interface Board3DTransition {
    data class Move(
        val from: BoardSquare, val to: BoardSquare,
        val kind: PieceKind, val color: PieceColor,
        val secondary: Move? = null,          // castling rook
    ) : Board3DTransition
    data class Capture(val move: Move, val capturedSquare: BoardSquare) : Board3DTransition  // capturedSquare != move.to for en passant
    data class Promotion(val move: Move, val promotedTo: PieceKind) : Board3DTransition
    data object Reset : Board3DTransition     // new game / no diffable relation

    object Board3DSceneDiffer {
        fun diff(previous: Board3DScene, next: Board3DScene): Board3DTransition?
    }
}
```

- `Chess3DBoardRenderer` gains a default-arg overload so M1–M4 backends compile unchanged:

```kotlin
fun updatePosition(fen: String, transition: Board3DTransition?) = updatePosition(fen)
```

- The host computes `diff(previousScene, nextScene)` on each FEN change and calls the new overload. Backends that implement it tween the moving piece (and the castling rook) over ~500 ms to match the 2D `tween(500)` feel; capture victims fade/sink; promotion swaps the mesh at the end.
- **Decision Resolved:** We are rejecting the optional polish to collapse the 2D board into a thumbnail; the 2D board will remain fully hidden in 3D mode (see [issue-32-3d-ui-unresolved-questions.md](issue-32-3d-ui-unresolved-questions.md)).

## Tests

Unit tests (`app/src/commonTest/kotlin/com/example/myapplication/board3d/`):

- **`Board3DSceneDifferTest`** — simple move; capture; kingside/queenside castle (Move with `secondary` rook move); promotion; en passant (Capture with `capturedSquare != move.to`); reset/new game → `Reset`; identical scenes → null.
- **`TapToSquareTest`** — with `DEFAULT_WHITE_VIEW`: tap coords computed via `CameraMath.worldToScreen(squareCenter(BoardSquare(6, 4)))` resolve back to `BoardSquare(6, 4)` (e2); repeat for corner squares.
- **`OrbitCameraControllerTest`** (extend) — after a drag (orbit), the worldToScreen→pick round-trip still resolves correct squares with the updated camera.

UI tests (desktopTest + one mobile platform, reusing `FakeChess3DRenderer` extended to record the `transition` argument):

- `tap on 3d board selects piece` — fake support, 3D on; send a synthetic tap (pointer input on the `board_3d` node) at coordinates computed from the default camera for a white pawn's square; assert the 2D board shows the selection (the selected square's testTag changes per the existing `squareTestTag` scheme).
- `3d move receives transition` — perform e2–e4 via the 2D board; assert the fake recorded `updatePosition` with a `Move` transition from `(6,4)` to `(4,4)`.

## CI

No changes — all new tests run under the existing `:app:check` / platform test tasks.

## Definition of done

- Tapping pieces/destinations on the 3D board plays moves end-to-end on at least desktop; selection highlight visible in 3D; 2D and 3D never disagree (same lambdas, same state).
- Pieces glide in 3D for moves/castles; captures and promotions animate on backends that implement the overload; others still snap correctly.
- All unit and UI tests above green; full CI matrix builds (overview "Execution rules").
