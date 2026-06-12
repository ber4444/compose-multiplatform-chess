import Foundation
import ChessApp        // Kotlin framework (ChessEngine protocol, UciEvaluation, KotlinInt)
import ChessKitEngine

/// Bridges the synchronous Kotlin ChessEngine interface to ChessKitEngine's async API.
/// getBestMove/evaluate block and MUST be called off the main thread
/// (GameViewModel calls them from Dispatchers.Default).
final class StockfishChessEngine: NSObject, ChessEngine {

    private static let moveTimeMs = 1_000          // mirrors BaseStockfishEngine think time
    private static let evalDepth: Int = 12         // mirrors BaseStockfishEngine.EVAL_DEPTH
    private static let readyTimeout: TimeInterval = 15
    private static let responseTimeout: TimeInterval = 8

    private let engine = Engine(type: .stockfish)
    private let requestLock = NSLock()             // serializes getBestMove/evaluate
    private let stateQueue = DispatchQueue(label: "stockfish.adapter.state")
    private let readySemaphore = DispatchSemaphore(value: 0)
    private var isReady = false
    private var isClosed = false
    private var pendingCompletion: ((String?) -> Void)?
    private var lastRawScoreCp: Int32?             // side-to-move cp from latest info line

    override init() {
        super.init()
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self, let stream = await self.engine.responseStream else { return }
            Task {                                  // start AFTER the stream is subscribed
                await self.engine.start()
                if let big = Bundle.main.url(forResource: "nn-1111cefa1111", withExtension: "nnue") {
                    await self.engine.send(command: .setoption(id: "EvalFile", value: big.path))
                }
                if let small = Bundle.main.url(forResource: "nn-37f18f62d772", withExtension: "nnue") {
                    await self.engine.send(command: .setoption(id: "EvalFileSmall", value: small.path))
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

    private func waitUntilReady() -> Bool {
        if stateQueue.sync(execute: { isReady || isClosed }) { return isReady && !isClosed }
        _ = readySemaphore.wait(timeout: .now() + Self.readyTimeout)
        readySemaphore.signal() // stay signaled for subsequent waiters
        return stateQueue.sync { isReady && !isClosed }
    }

    /// Sends position+go and blocks until bestmove (or timeout).
    private func runSearch(fen: String, go: EngineCommand) -> String? {
        guard !Thread.isMainThread else { return nil }            // deadlock guard
        requestLock.lock(); defer { requestLock.unlock() }
        guard waitUntilReady() else { return nil }

        let done = DispatchSemaphore(value: 0)
        var bestMove: String?
        stateQueue.sync {
            lastRawScoreCp = nil
            pendingCompletion = { move in bestMove = move; done.signal() }
        }
        Task { [engine] in
            await engine.send(command: .position(.fen(fen)))
            await engine.send(command: go)
        }
        if done.wait(timeout: .now() + Self.responseTimeout) == .timedOut {
            stateQueue.sync { pendingCompletion = nil }
            return nil
        }
        return bestMove
    }

    // MARK: ChessEngine (Kotlin)

    func getBestMove(fen: String, completionHandler: @escaping (String?, Error?) -> Void) {
        let move = runSearch(fen: fen, go: .go(movetime: Self.moveTimeMs))
        completionHandler(move, nil)
    }

    func evaluate(fen: String, completionHandler: @escaping (KotlinInt?, Error?) -> Void) {
        guard runSearch(fen: fen, go: .go(depth: Self.evalDepth)) != nil else {
            completionHandler(nil, nil)
            return
        }
        guard let raw = stateQueue.sync(execute: { lastRawScoreCp }) else {
            completionHandler(nil, nil)
            return
        }
        let whiteToMove = UciEvaluation.shared.isWhiteToMove(fen: fen)
        let cp = UciEvaluation.shared.toWhitePerspective(scoreCp: raw, whiteToMove: whiteToMove)
        completionHandler(KotlinInt(int: cp), nil)
    }

    func close() {
        stateQueue.sync { isClosed = true; isReady = false; pendingCompletion = nil }
        Task { [engine] in await engine.stop() }   // idempotent
    }
}
