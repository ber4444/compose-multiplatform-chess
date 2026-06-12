import Foundation
import ChessApp        // Kotlin framework (ChessEngine protocol, UciEvaluation, KotlinInt)
import ChessKitEngine

private let sharedMoveTimeMs = 1_000
private let sharedEvalDepth: Int = 12
private let sharedReadyTimeout: TimeInterval = 15
private let sharedResponseTimeout: TimeInterval = 8

private final class SharedStockfishCore {
    static let shared = SharedStockfishCore()
    
    let engine = Engine(type: .stockfish)
    let requestLock = NSLock()
    let stateQueue = DispatchQueue(label: "stockfish.adapter.state")
    let readySemaphore = DispatchSemaphore(value: 0)
    var isReady = false
    var pendingCompletion: ((String?) -> Void)?
    var lastRawScoreCp: Int32?
    
    private init() {
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self, let stream = await self.engine.responseStream else { return }
            Task {
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
    
    func waitUntilReady() -> Bool {
        if stateQueue.sync(execute: { isReady }) { return true }
        _ = readySemaphore.wait(timeout: .now() + sharedReadyTimeout)
        readySemaphore.signal()
        return stateQueue.sync { isReady }
    }
    
    func runSearch(fen: String, go: EngineCommand, checkClosed: () -> Bool) -> String? {
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
        Task { [engine] in
            await engine.send(command: .position(.fen(fen)))
            await engine.send(command: go)
        }
        if done.wait(timeout: .now() + sharedResponseTimeout) == .timedOut {
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
        let move = SharedStockfishCore.shared.runSearch(fen: fen, go: .go(movetime: sharedMoveTimeMs)) {
            self.localQueue.sync { self.isClosed }
        }
        completionHandler(move, nil)
    }

    func evaluate(fen: String, completionHandler: @escaping (KotlinInt?, Error?) -> Void) {
        guard SharedStockfishCore.shared.runSearch(fen: fen, go: .go(depth: sharedEvalDepth), checkClosed: {
            self.localQueue.sync { self.isClosed }
        }) != nil else {
            completionHandler(nil, nil)
            return
        }
        guard let raw = SharedStockfishCore.shared.stateQueue.sync(execute: { SharedStockfishCore.shared.lastRawScoreCp }) else {
            completionHandler(nil, nil)
            return
        }
        let whiteToMove = UciEvaluation.shared.isWhiteToMove(fen: fen)
        let cp = UciEvaluation.shared.toWhitePerspective(scoreCp: raw, whiteToMove: whiteToMove)
        completionHandler(KotlinInt(int: cp), nil)
    }

    func close() {
        localQueue.sync { isClosed = true }
        // We only stop the current search to unblock if it's currently running.
        // We do NOT stop the shared Engine process.
        Task { await SharedStockfishCore.shared.engine.send(command: .stop) }
    }
}
