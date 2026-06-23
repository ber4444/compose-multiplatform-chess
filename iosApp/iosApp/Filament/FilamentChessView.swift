// Issue #54 — Swift host for the Filament Metal chess renderer.
//
// A UIView backed by a CAMetalLayer that owns a FilamentChessRenderer (Obj-C++) and drives it from a
// CADisplayLink. Conforms to the Kotlin-exported `FilamentChessNativeView` protocol so the shared
// Kotlin `FilamentIosChessRenderer` can host + drive it — exactly mirroring how `StockfishChessEngine`
// conforms to the Kotlin `ChessEngine` protocol.

import UIKit
import QuartzCore
import ChessApp   // Kotlin framework: FilamentChessNativeView protocol

final class FilamentChessView: UIView, FilamentChessNativeView {

    override class var layerClass: AnyClass { CAMetalLayer.self }
    private var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

    private var renderer: FilamentChessRenderer?
    private var displayLink: CADisplayLink?

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
    }

    func setCamera(encoded: String) {
        if let r = renderer { r.setCameraEncoded(encoded) } else { pendingCamera = encoded }
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
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    @objc private func onFrame() {
        renderer?.render()
    }

    deinit {
        shutdown()
    }
}
