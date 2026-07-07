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
    // `readySemaphore` is awaited from a sync context (runSearch runs on a background DispatchQueue,
    // not in an async task), so a DispatchSemaphore is correct there. For the uciok handshake below
    // (which runs inside an async Task) we use a continuation instead — blocking a DispatchSemaphore
    // from async code is a Swift 6 language-mode error.
    let readySemaphore = DispatchSemaphore(value: 0)
    var isReady = false
    var isUciOk = false
    // Resumed when the engine acknowledges `uciok`. Set under stateQueue; read by the startup task.
    var uciOkContinuation: CheckedContinuation<Void, Never>?
    var pendingCompletion: ((String?) -> Void)?
    var lastRawScoreCp: Int32?
    // Engine difficulty (issue #39 Phase 4). Defaults to full-strength Stockfish; configure() lowers
    // the Skill Level + the per-move movetime. Accessed under requestLock (runSearch) so plain vars.
    var skillLevel: Int? = nil
    var moveTimeMs: Int = sharedMoveTimeMs
    var evalMoveTimeMs: Int = sharedEvalMoveTimeMs

    private init() {
        // Startup handshake (must be a strict sequence to avoid dropping responses on a cold start):
        //   1. start the engine + get the response stream
        //   2. START ITERATING the stream BEFORE sending any commands — ChessKitEngine's stream is
        //      hot, so a `readyok`/`bestmove` emitted before `for await` begins is lost forever.
        //      This was the root cause of the intermittent "Move was nil!" failures: the `isready`
        //      reply (and sometimes the first `bestmove`) was emitted before the consumer loop ran.
        //   3. send `uci`, await `uciok` (engine accepts `setoption` only after `uciok`)
        //   4. send NNUE EvalFile/EvalFileSmall setoptions + Skill Level
        //   5. send `isready`; `readyok` is awaited lazily by the first runSearch via readySemaphore.
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            await self.engine.start()
            guard let stream = await self.engine.responseStream else { return }

            // Launch the consumer FIRST so no response is missed, then drive the handshake.
            Task {
                for await response in stream { self.handle(response) }
            }
            // Give the consumer a chance to start iterating before we send `uci`. The stream buffers
            // responses that arrive in the gap, so this is belt-and-suspenders.
            try? await Task.sleep(nanoseconds: 100_000_000)

            await self.engine.send(command: .uci)
            // Await uciok before sending setoptions. NNUE setoptions sent before uciok can be silently
            // dropped, leaving the engine unusable and readyok never arriving.
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                self.stateQueue.sync {
                    // If uciok already arrived (fast path), resume immediately; else stash the cont.
                    if self.isUciOk { cont.resume() } else { self.uciOkContinuation = cont }
                }
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
    }

    private func handle(_ response: EngineResponse) {
        stateQueue.sync {
            switch response {
            case .uciok:
                isUciOk = true
                uciOkContinuation?.resume()
                uciOkContinuation = nil
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
