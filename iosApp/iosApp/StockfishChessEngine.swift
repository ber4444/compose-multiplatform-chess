import Foundation
import ChessApp        // Kotlin framework (ChessEngine protocol, UciEvaluation, KotlinInt)
import ChessKitEngine

private let sharedMoveTimeMs = 1_000
// Eval uses a wall-clock budget (not a fixed depth): a depth-bounded search has unbounded time and
// can exceed sharedEvalResponseTimeout on slow/contended machines (e.g. a debug build oversubscribed on
// a 3-core CI runner), returning nil. A movetime bound always finishes within the response timeout.
private let sharedEvalMoveTimeMs = 2_000
// Generous on purpose: a cold start on a contended CI runner (Debug build, 3 cores) can spend 30-40s
// loading NNUE before the engine acknowledges `readyok`. The previous 30s timeout produced intermittent
// "Move was nil!" failures on the first Swift test (the engine wasn't ready when the search began).
private let sharedReadyTimeout: TimeInterval = 90
private let sharedBestMoveResponseTimeout: TimeInterval = 30
private let sharedEvalResponseTimeout: TimeInterval = 20
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
    // Awaited from a sync context (runSearch runs on a background DispatchQueue, not an async task),
    // so a DispatchSemaphore is correct here. The startup handshake below drives ALL command/response
    // sequencing inline from a single consumer `for await` loop — no cross-task signaling needed.
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
        // Startup + runtime response processing in ONE consumer task. Driving the UCI handshake inline
        // (uci → uciok → setoptions → isready → readyok) from the `for await` loop is race-free: the
        // loop is single-threaded over the stream, so commands are sent in exact order and no response
        // is ever missed.
        //
        // This fixes the intermittent cold-start crash/failure that hit the Swift tests on CI. The
        // prior structure (a separate handshake Task racing a consumer Task, plus a
        // CheckedContinuation for uciok) could crash when the continuation was abandoned, and the
        // original could drop readyok because the consumer started iterating after `isready` was sent.
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            await self.engine.start()
            guard let stream = await self.engine.responseStream else { return }

            // Kick off the handshake. The loop processes each response and sends the next command.
            await self.engine.send(command: .uci)

            var handshakeDone = false
            for await response in stream {
                // After the handshake, route responses to the shared handler (info/bestmove/...).
                if handshakeDone {
                    self.handleRuntime(response)
                    continue
                }
                switch response {
                case .uciok:
                    // Engine now accepts setoptions. Send NNUE + difficulty, then isready.
                    if let big = Bundle.main.url(forResource: "nn-1111cefa1111", withExtension: "nnue") {
                        await self.engine.send(command: .setoption(id: "EvalFile", value: big.path))
                    }
                    if let small = Bundle.main.url(forResource: "nn-37f18f62d772", withExtension: "nnue") {
                        await self.engine.send(command: .setoption(id: "EvalFileSmall", value: small.path))
                    }
                    if let level = self.stateQueue.sync(execute: { self.skillLevel }) {
                        await self.engine.send(command: .setoption(id: "Skill Level", value: String(level)))
                    }
                    await self.engine.send(command: .isready)
                case .readyok:
                    // Engine has loaded NNUE and is ready to search.
                    self.stateQueue.sync { self.isReady = true }
                    self.readySemaphore.signal()
                    handshakeDone = true
                default:
                    break   // id / info during handshake — ignore
                }
            }
        }
    }

    /// Routes post-handshake engine responses (info scores, bestmove) to the shared mutable state.
    private func handleRuntime(_ response: EngineResponse) {
        stateQueue.sync {
            switch response {
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
            default:
                break   // readyok/uciok re-emits are harmless after startup
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
