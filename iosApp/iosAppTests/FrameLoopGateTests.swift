import XCTest
@testable import iosApp

/// Pins `FilamentChessView`'s display-link gate — the iOS counterpart of SceneView's `isRendering`
/// on Android, which stops an idle 3D board redrawing at up to 120 Hz forever.
///
/// Only the pure `FrameLoopGate` is exercised, not the view. `FilamentChessView` needs a Metal
/// device and a `FilamentChessRenderer` (Filament `Engine::create`) before it has a display link at
/// all, and the CI simulator has no GPU — the app target deliberately avoids `-ObjC` so that plain
/// app startup never touches Metal (see filament.xcconfig). Splitting the decision out is what makes
/// it checkable here; the drawn result is verified separately by `tools/ios_3d_screenshot.sh`.
///
/// The shared half — that the driver raises its dirty signal on the four paths that publish a scene
/// *without* animating — is pinned in Kotlin by `Board3DAnimationDriverTest` and, at the peer
/// boundary this gate sits behind, by `FilamentEncodedChessRendererTest`. Those four arrive here as
/// nothing more than `wantsRender = true`, so what is worth pinning below is the rest: when the gate
/// may park, and the two races where Kotlin's signal alone is not enough.
final class FrameLoopGateTests: XCTestCase {

    /// The only state in which the board may stop drawing.
    func testSettledBoardParks() {
        var gate = FrameLoopGate()
        gate.wantsRender = false
        gate.hasUndrawnState = false
        gate.assetReady = true

        XCTAssertFalse(gate.shouldRender)
    }

    /// Nothing published before Kotlin's first `setRenderingActive` may be stranded undrawn.
    func testFreshGateRenders() {
        XCTAssertTrue(FrameLoopGate().shouldRender)
    }

    /// Mount / new game / idle-board coach highlight / post-init refresh all reach the gate as this.
    func testDirtySignalAloneWakesTheLoop() {
        var gate = FrameLoopGate()
        gate.wantsRender = true
        gate.hasUndrawnState = false
        gate.assetReady = true

        XCTAssertTrue(gate.shouldRender)
    }

    /// The renderer is built from `layoutSubviews`, well after Compose attaches and publishes the
    /// first scene, so the driver's dirty window can have closed before the display link exists.
    /// Trusting `wantsRender` alone there starts the link parked with a scene queued and never draws
    /// the board.
    func testStatePushedAfterTheDirtyWindowLapsedStillRenders() {
        var gate = FrameLoopGate()
        gate.wantsRender = false
        gate.assetReady = true

        gate.hasUndrawnState = true

        XCTAssertTrue(gate.shouldRender)
    }

    /// `Renderer::beginFrame` declines frames, so the undrawn flag is cleared by a frame that landed,
    /// never by one that was merely requested — the gate must still be open until then.
    func testDeclinedFrameLeavesTheGateOpen() {
        var gate = FrameLoopGate()
        gate.wantsRender = false
        gate.assetReady = true
        gate.hasUndrawnState = true

        // A declined frame leaves hasUndrawnState alone (FilamentChessView.onFrame only clears it on
        // a YES from -render).
        XCTAssertTrue(gate.shouldRender)

        gate.hasUndrawnState = false
        XCTAssertFalse(gate.shouldRender, "the next frame that actually lands may park the loop")
    }

    /// Parking before the glTF asset is loaded is how Android's equivalent gate leaves the board
    /// untextured; the iOS loader is synchronous today, but the gate must not depend on that.
    func testNeverParksBeforeTheAssetIsReady() {
        var gate = FrameLoopGate()
        gate.wantsRender = false
        gate.hasUndrawnState = false
        gate.assetReady = false

        XCTAssertTrue(gate.shouldRender)
    }
}
