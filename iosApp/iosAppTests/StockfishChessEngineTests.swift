import XCTest
import ChessApp
@testable import iosApp

final class StockfishChessEngineTests: XCTestCase {

    let startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    func testBestMoveFromStartPositionIsUciMove() {
        let engine = StockfishChessEngine()
        defer { engine.close() }

        let exp = expectation(description: "getBestMove returns a valid move")
        var move: String?

        DispatchQueue.global().async {
            engine.getBestMove(fen: self.startFen) { bestMove, _ in
                move = bestMove
                exp.fulfill()
            }
        }

        wait(for: [exp], timeout: 60)
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
            engine.evaluate(fen: self.startFen) { scoreResult, _ in
                score = scoreResult
                exp.fulfill()
            }
        }

        wait(for: [exp], timeout: 60)
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
            engine.getBestMove(fen: self.startFen) { bestMove, _ in
                move = bestMove
                exp.fulfill()
            }
        }

        wait(for: [exp], timeout: 60)
        XCTAssertNil(move)
    }
}
