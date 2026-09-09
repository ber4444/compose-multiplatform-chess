// Issue #54 — Swift host for the Filament Metal chess renderer.
//
// A UIView backed by a CAMetalLayer that owns a FilamentChessRenderer (Obj-C++) and drives it from a
// CADisplayLink. Conforms to the Kotlin-exported `FilamentChessNativeView` protocol so the shared
// Kotlin `FilamentIosChessRenderer` can host + drive it — exactly mirroring how `StockfishChessEngine`
// conforms to the Kotlin `ChessEngine` protocol.

import UIKit
import QuartzCore
import ChessApp   // Kotlin framework: FilamentChessNativeView protocol

/// Whether the CADisplayLink should keep firing.
///
/// Split out as a pure value so it is unit-testable: the gate's whole job is to decide when *not*
/// to draw, and getting that wrong shows up as a board that never appears — but a test cannot
/// construct a `FilamentChessRenderer` on a GPU-less CI simulator, so the decision has to be
/// checkable without one. See `FrameLoopGateTests`.
struct FrameLoopGate {

    /// The shared `Board3DAnimationDriver`'s dirty signal, pushed down from Kotlin: *a frame was
    /// published recently*. Deliberately not "an animation is running" — the driver publishes with
    /// its loop parked on mount, on a new game, on a coach highlight landing on an idle board, and
    /// after async init, and the narrower signal strands all four undrawn.
    ///
    /// Starts `true` so anything published before Kotlin's first signal arrives is still drawn.
    var wantsRender = true

    /// Scene/camera/size state has reached the renderer that no *completed* frame has drawn yet.
    ///
    /// This is the backstop `wantsRender` cannot be, and it exists for two concrete races. The view
    /// is created before it has a size, so the renderer — and therefore the display link — is built
    /// later from `layoutSubviews`; the driver's dirty window is a few frame budgets wide and can
    /// easily have lapsed by then, which would start the link parked with a scene queued and never
    /// draw the board at all. And `Renderer::beginFrame` may decline a frame, so asking for one is
    /// not the same as landing one.
    var hasUndrawnState = true

    /// Whether the glTF asset has finished loading. Parking before it has can leave the board
    /// untextured (or never drawn); see `FilamentChessRenderer.assetReady`.
    var assetReady = false

    var shouldRender: Bool { wantsRender || hasUndrawnState || !assetReady }
}

final class FilamentChessView: UIView, FilamentChessNativeView {

    override class var layerClass: AnyClass { CAMetalLayer.self }
    private var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

    private var renderer: FilamentChessRenderer?
    private var displayLink: CADisplayLink?

    /// Drives `displayLink.isPaused`. Without it an untouched 3D board redraws at the panel refresh
    /// rate — up to 120 Hz here — for as long as it is on screen. Same bug, and the same fix in
    /// shape, as SceneView's `isRendering` on the Android backend (see AndroidBoard3D.kt).
    private var gate = FrameLoopGate()

    // State pushed from Kotlin before the renderer exists (the view is created before it has a size);
    // applied once the first non-zero resize creates the renderer.
    private var pendingScene: String?
    private var pendingCamera: String?
    private var sizePx: (Int32, Int32) = (0, 0)

    init() {
        super.init(frame: .zero)
        backgroundColor = .black
        metalLayer.device = MTLCreateSystemDefaultDevice()
        metalLayer.pixelFormat = .bgra8Unorm
        metalLayer.isOpaque = true
        metalLayer.contentsScale = UIScreen.main.scale
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

    // MARK: FilamentChessNativeView

    func metalView() -> UIView { self }

    func setScene(encoded: String) {
        if let r = renderer { r.setSceneEncoded(encoded) } else { pendingScene = encoded }
        stateChanged()
    }

    func setCamera(encoded: String) {
        if let r = renderer { r.setCameraEncoded(encoded) } else { pendingCamera = encoded }
        stateChanged()
    }

    func setRenderingActive(active: Bool) {
        gate.wantsRender = active
        updateFrameLoop()
    }

    func resize(width: Int32, height: Int32) {
        applySize(width: width, height: height)
    }

    // UIKit lays the interop view out to the Compose node's size here (and on rotation), so this is the
    // reliable place to size the Metal drawable / create the renderer — Compose's UIKitView `update`
    // closure does not consistently deliver a non-zero bounds for a hosted (pre-created) view.
    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = (window?.screen.scale ?? UIScreen.main.scale)
        applySize(width: Int32(bounds.width * scale), height: Int32(bounds.height * scale))
    }

    private func applySize(width: Int32, height: Int32) {
        guard width > 0, height > 0 else { return }
        guard (width, height) != sizePx || renderer == nil else { return }
        sizePx = (width, height)
        metalLayer.drawableSize = CGSize(width: Int(width), height: Int(height))
        if let r = renderer {
            r.resizeWidth(width, height: height)
            stateChanged()
        } else {
            createRenderer(width: width, height: height)
        }
    }

    func shutdown() {
        displayLink?.invalidate()
        displayLink = nil
        renderer?.shutdown()
        renderer = nil
    }

    // MARK: internals

    private func createRenderer(width: Int32, height: Int32) {
        guard let r = FilamentChessRenderer(metalLayer: metalLayer, widthPx: width, heightPx: height) else {
            NSLog("[FilamentChess] FilamentChessRenderer init returned nil (\(width)x\(height))")
            return // GPU/asset init failed; Kotlin side keeps the (empty) view, board never appears.
        }
        renderer = r
        if let s = pendingScene { r.setSceneEncoded(s); pendingScene = nil }
        if let c = pendingCamera { r.setCameraEncoded(c); pendingCamera = nil }

        let link = CADisplayLink(target: self, selector: #selector(onFrame))
        // Allow up to 120 Hz on ProMotion devices (Info.plist sets CADisableMinimumFrameDurationOnPhone).
        // The range is right for the animating case and stays; what stops the idle burn is parking the
        // link entirely, below.
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        displayLink = link

        // A brand-new renderer has drawn nothing, whatever Kotlin's dirty signal currently says: the
        // scene queued into `pendingScene` above may well have been published long enough ago for the
        // driver's window to have closed. Reassert it rather than trusting `gate.wantsRender`.
        stateChanged()
    }

    /// New state reached the renderer; keep drawing until a frame has actually put it on screen.
    private func stateChanged() {
        gate.hasUndrawnState = true
        updateFrameLoop()
    }

    private func updateFrameLoop() {
        gate.assetReady = renderer?.isAssetReady ?? false
        displayLink?.isPaused = !gate.shouldRender
    }

    @objc private func onFrame() {
        if renderer?.render() == true { gate.hasUndrawnState = false }
        updateFrameLoop()
    }

    deinit {
        shutdown()
    }
}
