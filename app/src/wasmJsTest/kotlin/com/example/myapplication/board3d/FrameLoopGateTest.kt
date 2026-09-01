package com.example.myapplication.board3d

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web backend's frame-loop gate: when the `requestAnimationFrame` loop keeps going, and when it
 * parks. Without it an untouched 3D board redraws at the display refresh rate for as long as the
 * page is open — the same bug Android fixed with SceneView's `isRendering` and iOS with
 * `displayLink.isPaused`.
 *
 * These tests drive the *real* glue script (injected into the karma browser), never a copy of its
 * logic, but they deliberately never call `chess3dFilament.init()`: the karma browser has no GPU and
 * `Filament.Engine.create` crashes on software WebGL (memory: wasm-3d-verify-harness). That is why
 * the decision lives in a separate pure `chess3dFrameLoopGate`, exactly as iOS split `FrameLoopGate`
 * out of `FilamentChessView` for a GPU-less CI simulator.
 *
 * What that leaves uncovered is a rendering frame actually landing, which needs the real board:
 * verify that with `./gradlew :app:wasmJsBrowserDevelopmentRun` on a machine with a real GPU.
 */
class FrameLoopGateTest {

    @BeforeTest
    fun setUp() {
        // Once per karma page: the glue declares top-level `const`s, so injecting it twice throws
        // "Identifier 'PIECE_SCALE' has already been declared" and leaves the first copy installed.
        if (!isGlueInstalled()) injectGlue(CHESS3D_FILAMENT_JS)
        // dispose() is the renderer's own reset — fresh gate, loop stopped — and it is safe with no
        // Engine, since every teardown step is null-guarded.
        disposeRenderer()
    }

    // ── the decision ──────────────────────────────────────────────────────────

    @Test
    fun `a settled board parks the loop`() {
        assertFalse(gateShouldRender(wantsRender = false, undrawnFrames = 0, assetReady = true))
    }

    @Test
    fun `the loop runs while Kotlin says a frame was published recently`() {
        assertTrue(gateShouldRender(wantsRender = true, undrawnFrames = 0, assetReady = true))
    }

    @Test
    fun `the loop runs while it still owes a frame to the last state push`() {
        // The backstop that makes the board appear at all: async init routinely outlives the
        // driver's dirty window, so the queued scene arrives with wantsRender already false.
        assertTrue(gateShouldRender(wantsRender = false, undrawnFrames = 1, assetReady = true))
    }

    @Test
    fun `the loop runs until the asset is loaded`() {
        assertTrue(gateShouldRender(wantsRender = false, undrawnFrames = 0, assetReady = false))
    }

    @Test
    fun `a fresh gate draws before Kotlin has said anything`() {
        // The initial values matter as much as the rule: a gate that started parked would never
        // draw the first board, because nothing has published a frame yet.
        assertTrue(freshGateShouldRender())
    }

    // ── the wiring ────────────────────────────────────────────────────────────

    @Test
    fun `Kotlin's signal reaches the gate`() {
        setRenderingActive(false)
        assertFalse(gateWantsRender())

        setRenderingActive(true)
        assertTrue(gateWantsRender())
    }

    @Test
    fun `a scene push re-arms the loop after it has been told to park`() {
        setRenderingActive(false)
        drainOwedFrames()
        assertEquals(0, gateUndrawnFrames(), "precondition: the loop owes nothing")

        pushScene("5,0,0.0,0.0,0.0,0.0")

        assertTrue(gateUndrawnFrames() > 0, "the push must outlive the parked signal")
        // The renderer is not ready here, so setScene drops the payload — and must still have
        // raised the signal on its way out. This is the init race: the Kotlin peer re-pushes from
        // pendingScene once the JS side reports ready, and by then wantsRender is long false.
        assertFalse(gateAssetReady())
    }

    @Test
    fun `a parked loop is never scheduled`() {
        // dispose() ran in setUp, so the loop is inactive: state pushes must not resurrect it.
        // render() used to reschedule unconditionally, which left one live rAF loop per 2D<->3D
        // toggle drawing against a destroyed Engine for the life of the page.
        pushScene("5,0,0.0,0.0,0.0,0.0")

        assertEquals(0, rafHandle(), "a torn-down renderer scheduled a frame")
    }

    @Test
    fun `an active loop schedules the frame its state push asked for`() {
        armLoopWithoutRenderer()

        pushScene("5,0,0.0,0.0,0.0,0.0")

        assertTrue(rafHandle() != 0, "the push did not schedule a frame")
    }
}

// ── browser helpers ───────────────────────────────────────────────────────────

@JsFun(
    "(js) => { const s = document.createElement('script'); s.type = 'application/javascript'; " +
        "s.textContent = js; document.head.appendChild(s); }"
)
private external fun injectGlue(js: String)

@JsFun("() => (typeof window.chess3dFrameLoopGate !== 'undefined')")
private external fun isGlueInstalled(): Boolean

@JsFun(
    "(wantsRender, undrawnFrames, assetReady) => " +
        "window.chess3dFrameLoopGate.shouldRender({ wantsRender, undrawnFrames, assetReady })"
)
private external fun gateShouldRender(
    wantsRender: Boolean,
    undrawnFrames: Int,
    assetReady: Boolean,
): Boolean

@JsFun("() => window.chess3dFrameLoopGate.shouldRender(window.chess3dFrameLoopGate.create())")
private external fun freshGateShouldRender(): Boolean

@JsFun("() => { window.chess3dFilament.dispose(); }")
private external fun disposeRenderer()

@JsFun("(active) => { window.chess3dFilament.setRenderingActive(active); }")
private external fun setRenderingActive(active: Boolean)

@JsFun("(s) => { window.chess3dFilament.setScene(s); }")
private external fun pushScene(s: String)

@JsFun("() => window.chess3dFilament.gate.wantsRender")
private external fun gateWantsRender(): Boolean

@JsFun("() => window.chess3dFilament.gate.undrawnFrames")
private external fun gateUndrawnFrames(): Int

@JsFun("() => window.chess3dFilament.gate.assetReady")
private external fun gateAssetReady(): Boolean

@JsFun("() => window.chess3dFilament.rafHandle")
private external fun rafHandle(): Int

/** Spend the frames the gate currently owes, without a renderer to draw them. */
@JsFun("() => { window.chess3dFilament.gate.undrawnFrames = 0; }")
private external fun drainOwedFrames()

/**
 * Put the loop in the state `init()` leaves it in — active, with a callback — but with a callback
 * that draws nothing, so scheduling can be observed on a machine with no GPU.
 */
@JsFun(
    "() => { const f = window.chess3dFilament; f.loopActive = true; " +
        "f.boundRender = () => { f.rafHandle = 0; }; }"
)
private external fun armLoopWithoutRenderer()
