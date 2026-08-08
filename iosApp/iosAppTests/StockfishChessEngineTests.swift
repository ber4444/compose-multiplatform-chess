import XCTest
import ChessApp
@testable import iosApp

final class StockfishSearchTimeoutTests: XCTestCase {

    func testTimeoutStopsSearchAndAcceptsBestMoveDuringGracePeriod() {
        let done = DispatchSemaphore(value: 0)
        var stopCount = 0

        let completed = waitForSearchCompletion(
            done: done,
            timeout: 0,
            stop: {
                stopCount += 1
                done.signal()
            },
            stopGraceTimeout: 0.1
        )

        XCTAssertTrue(completed)
        XCTAssertEqual(stopCount, 1)
    }
}

final class StockfishChessEngineTests: XCTestCase {

    let startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    func testBestMoveFromStartPositionIsUciMove() throws {
        if ProcessInfo.processInfo.environment["CI"] != nil {
            throw XCTSkip("Flaky on CI - Stockfish initialization timeout")
        }
        let engine = StockfishChessEngine()
        defer { engine.close() }

        let exp = expectation(description: "getBestMove returns a valid move")
        var move: String?

        DispatchQueue.global().async {
            engine.getBestMove(fen: self.startFen, thinkTimeMs: nil) { bestMove, _ in
                move = bestMove?.uci
                exp.fulfill()
            }
        }

        wait(for: [exp], timeout: 120)
        guard let unwrappedMove = move else {
            XCTFail("Move was nil!")
            return
        }
        XCTAssertTrue(unwrappedMove.count >= 4 && unwrappedMove.count <= 5)
    }

    func testEvaluateStartPositionIsRoughlyBalanced() {
        let engine = StockfishChessEngine()
        defer { engine.close() }

        let exp = expectation(description: "evaluate returns a balanced score")
        var score: KotlinInt?

        DispatchQueue.global().async {
            // thinkTimeMs: nil keeps the pre-existing behaviour — the shared core's default eval
            // movetime. A value here is the per-ply analysis budget the coach passes; this test is
            // about the engine agreeing the start position is balanced, not about that budget.
            engine.evaluate(fen: self.startFen, thinkTimeMs: nil) { scoreResult, _ in
                score = scoreResult
                exp.fulfill()
            }
        }

        wait(for: [exp], timeout: 120)
        XCTAssertNotNil(score)
        if let s = score {
            XCTAssertTrue(abs(s.intValue) <= 200)
        }
    }

    func testCloseIsIdempotentAndSafeBeforeReady() {
        let engine = StockfishChessEngine()
        engine.close()
        engine.close() // Idempotency check
        defer { engine.close() }

        let exp = expectation(description: "getBestMove returns nil after close")
        var move: String? = "initial"

        DispatchQueue.global().async {
            engine.getBestMove(fen: self.startFen, thinkTimeMs: nil) { bestMove, _ in
                move = bestMove?.uci
                exp.fulfill()
            }
        }

        wait(for: [exp], timeout: 60)
        XCTAssertNil(move)
    }
}
