import Foundation
import ChessApp        // Kotlin framework (ChessEngine protocol, UciEvaluation, KotlinInt)
import ChessKitEngine

private let sharedMoveTimeMs = 1_000
// Eval uses a wall-clock budget (not a fixed depth): a depth-bounded search has unbounded time and
// can exceed sharedEvalResponseTimeout on slow/contended machines (e.g. a debug build oversubscribed on
// a 3-core CI runner), returning nil. A movetime bound always finishes within the response timeout.
private let sharedEvalMoveTimeMs = 2_000
private let sharedReadyTimeout: TimeInterval = 30
private let sharedBestMoveResponseTimeout: TimeInterval = 20
private let sharedEvalResponseTimeout: TimeInterval = 8
private let sharedStopGraceTimeout: TimeInterval = 5

func waitForSearchCompletion(
    done: DispatchSemaphore,
    timeout: TimeInterval,
    stop: () -> Void,
    stopGraceTimeout: TimeInterval
) -> Bool {
    if done.wait(timeout: .now() + timeout) == .success { return true }
    stop()
    return done.wait(timeout: .now() + stopGraceTimeout) == .success
}

private final class SharedStockfishCore {
    static let shared = SharedStockfishCore()

    let engine = Engine(type: .stockfish)
    let requestLock = NSLock()
    let stateQueue = DispatchQueue(label: "stockfish.adapter.state")
    let readySemaphore = DispatchSemaphore(value: 0)
    var isReady = false
    var pendingCompletion: ((String?) -> Void)?
    var lastRawScoreCp: Int32?
    // Engine difficulty (issue #39 Phase 4). Defaults to full-strength Stockfish; configure() lowers
    // the Skill Level + the per-move movetime. Accessed under requestLock (runSearch) so plain vars.
    var skillLevel: Int? = nil
    var moveTimeMs: Int = sharedMoveTimeMs
    var evalMoveTimeMs: Int = sharedEvalMoveTimeMs

    private init() {
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            await self.engine.start()
            guard let stream = await self.engine.responseStream else { return }
            Task {
                while !(await self.engine.isRunning) {
                    try? await Task.sleep(nanoseconds: 50_000_000)
                }
                if let big = Bundle.main.url(forResource: "nn-1111cefa1111", withExtension: "nnue") {
                    await self.engine.send(command: .setoption(id: "EvalFile", value: big.path))
                }
                if let small = Bundle.main.url(forResource: "nn-37f18f62d772", withExtension: "nnue") {
                    await self.engine.send(command: .setoption(id: "EvalFileSmall", value: small.path))
                }
                // Apply any difficulty configured before the engine came up.
                if let level = self.stateQueue.sync(execute: { self.skillLevel }) {
                    await self.engine.send(command: .setoption(id: "Skill Level", value: String(level)))
                }
                await self.engine.send(command: .isready)
            }
            for await response in stream { self.handle(response) }
        }
    }
    
    private func handle(_ response: EngineResponse) {
        stateQueue.sync {
            switch response {
            case .readyok:
                if !isReady { isReady = true; readySemaphore.signal() }
            case let .info(info):
                if let score = info.score {
                    if let cp = score.cp {
                        lastRawScoreCp = Int32(cp)
                    } else if let mate = score.mate {
                        lastRawScoreCp = UciEvaluation.shared.mateToCp(matePlies: Int32(mate))
                    }
                }
            case let .bestmove(move, _):
                pendingCompletion?(move)
                pendingCompletion = nil
            default: break
            }
        }
    }
    
    func waitUntilReady() -> Bool {
        if stateQueue.sync(execute: { isReady }) { return true }
        guard readySemaphore.wait(timeout: .now() + sharedReadyTimeout) == .success else { return false }
        return stateQueue.sync { isReady }
    }
    
    func runSearch(
        fen: String,
        go: EngineCommand,
        timeout: TimeInterval,
        checkClosed: () -> Bool
    ) -> String? {
        guard !Thread.isMainThread else { return nil }
        requestLock.lock(); defer { requestLock.unlock() }
        
        guard !checkClosed() else { return nil }
        guard waitUntilReady() else { return nil }
        guard !checkClosed() else { return nil }

        let done = DispatchSemaphore(value: 0)
        var bestMove: String?
        stateQueue.sync {
            lastRawScoreCp = nil
            pendingCompletion = { move in bestMove = move; done.signal() }
        }
        // Engine difficulty (issue #39 Phase 4): re-assert the Skill Level before each search so a
        // configure() mid-session takes effect on the next move. Mirrors the EvalFile setoption sends.
        let level = stateQueue.sync { skillLevel }
        Task { [engine] in
            if let level { await engine.send(command: .setoption(id: "Skill Level", value: String(level))) }
            await engine.send(command: .position(.fen(fen)))
            await engine.send(command: go)
        }
        let completed = waitForSearchCompletion(
            done: done,
            timeout: timeout,
            stop: {
                Task { [engine] in await engine.send(command: .stop) }
            },
            stopGraceTimeout: sharedStopGraceTimeout
        )
        if !completed {
            stateQueue.sync { pendingCompletion = nil }
            return nil
        }
        return bestMove
    }
}

/// Bridges the synchronous Kotlin ChessEngine interface to ChessKitEngine's async API.
/// getBestMove/evaluate block and MUST be called off the main thread
/// (GameViewModel calls them from Dispatchers.Default).
final class StockfishChessEngine: NSObject, ChessEngine {
    private var isClosed = false
    private let localQueue = DispatchQueue(label: "stockfish.local.state")

    override init() {
        super.init()
        // Initialize shared core implicitly if not already
        _ = SharedStockfishCore.shared
    }

    func getBestMove(fen: String, completionHandler: @escaping (String?, Error?) -> Void) {
        let checkClosed = { [weak self] in self?.localQueue.sync { self?.isClosed ?? true } ?? true }
        // The first search after engine init can fail on slow/contended CI runners — the engine's
        // async response pipeline isn't fully primed yet, so the bestmove response is missed and
        // runSearch returns nil after the full timeout. A single retry reliably produces a move
        // because by the second attempt the pipeline is warmed up.
        let moveTimeMs = SharedStockfishCore.shared.stateQueue.sync { SharedStockfishCore.shared.moveTimeMs }
        var move = SharedStockfishCore.shared.runSearch(
            fen: fen,
            go: .go(movetime: moveTimeMs),
            timeout: sharedBestMoveResponseTimeout,
            checkClosed: checkClosed
        )
        if move == nil && !checkClosed() {
            move = SharedStockfishCore.shared.runSearch(
                fen: fen,
                go: .go(movetime: moveTimeMs),
                timeout: sharedBestMoveResponseTimeout,
                checkClosed: checkClosed
            )
        }
        completionHandler(move, nil)
    }

    func evaluate(fen: String, completionHandler: @escaping (KotlinInt?, Error?) -> Void) {
        let checkClosed = { [weak self] in self?.localQueue.sync { self?.isClosed ?? true } ?? true }

        // Like getBestMove, a search on a cold/contended CI pipeline can have its bestmove
        // response missed, so runSearch times out and returns nil (leaving lastRawScoreCp nil).
        // Retry once: the second attempt runs against a warmed-up pipeline and reliably scores.
        let evalMoveTimeMs = SharedStockfishCore.shared.stateQueue.sync { SharedStockfishCore.shared.evalMoveTimeMs }
        func attempt() -> KotlinInt? {
            guard SharedStockfishCore.shared.runSearch(
                fen: fen,
                go: .go(movetime: evalMoveTimeMs),
                timeout: sharedEvalResponseTimeout,
                checkClosed: checkClosed
            ) != nil else { return nil }
            guard let raw = SharedStockfishCore.shared.stateQueue.sync(execute: {
                SharedStockfishCore.shared.lastRawScoreCp
            }) else { return nil }
            let whiteToMove = UciEvaluation.shared.isWhiteToMove(fen: fen)
            let cp = UciEvaluation.shared.toWhitePerspective(scoreCp: raw, whiteToMove: whiteToMove)
            return KotlinInt(int: cp)
        }

        var result = attempt()
        if result == nil && !checkClosed() {
            result = attempt()
        }
        completionHandler(result, nil)
    }

    /// Engine difficulty (issue #39 Phase 4). Conformance to the Kotlin `ChessEngine.configure`.
    /// Stores the skill level + movetime on the shared core; runSearch re-asserts the Skill Level
    /// before the next `go`, and getBestMove/evaluate use the configured movetime. Additive only.
    func configure(difficulty: EngineDifficulty, completionHandler: @escaping (Error?) -> Void) {
        SharedStockfishCore.shared.stateQueue.sync {
            SharedStockfishCore.shared.skillLevel = Int(difficulty.skillLevel)
            SharedStockfishCore.shared.moveTimeMs = Int(difficulty.thinkTimeMs)
            // Keep the eval movetime proportional to the play movetime (eval uses 2x the play budget
            // in the defaults), so weaker difficulty also evaluates faster.
            SharedStockfishCore.shared.evalMoveTimeMs = max(Int(difficulty.thinkTimeMs) * 2, 200)
        }
        completionHandler(nil)
    }

    func close() {
        localQueue.sync { isClosed = true }
        // We only stop the current search to unblock if it's currently running.
        // We do NOT stop the shared Engine process.
        Task { await SharedStockfishCore.shared.engine.send(command: .stop) }
    }
}
