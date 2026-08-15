#!/usr/bin/env python3
"""
Generate finished-game fixtures for the Game Summary benchmark.

The bench had one hand-written game whose `MoveRecord`s were fabricated — every ply carried
`uci = "e2e4"`, `fenAfter` = the start position, and hand-set centipawn losses. That is enough to
exercise the code path and not enough to measure a model: the summary's whole job is to pick the
moments that decided *this* game, and a fixture whose turning points were chosen by hand cannot
tell you whether it did.

This plays real games and assesses them with a real engine:

  * White is deliberately weak (low Skill Level) and Black is strong, so the games contain genuine
    blunders in the player's own moves rather than manufactured ones.
  * Every player ply gets `cpBefore` / `cpPlayed` / `cpBest` from Stockfish, plus the move the
    engine preferred. **The centipawn thresholds are deliberately not applied here** — the fixture
    carries raw engine numbers and `MoveAssessor` in `:chess-core` derives `MoveClass` and
    `winPercentLost`, so the bench cannot drift from the app's own definition of a blunder.

Usage:
    python3 tools/generate_summary_fixtures.py [--games N] [--out path]
"""
from __future__ import annotations

import argparse
import json
import sys

try:
    import chess
    import chess.engine
except ImportError:
    sys.exit("needs python-chess: pip install chess (ferryman's .venv has it)")

STOCKFISH = "/opt/homebrew/bin/stockfish"
# The player side, i.e. the side the summary coaches. White is the weak one on purpose.
PLAYER = chess.WHITE


def play_game(engine: chess.engine.SimpleEngine, weak_skill: int, strong_skill: int, max_plies: int):
    board = chess.Board()
    while not board.is_game_over() and len(board.move_stack) < max_plies:
        skill = weak_skill if board.turn == PLAYER else strong_skill
        engine.configure({"Skill Level": skill})
        result = engine.play(board, chess.engine.Limit(time=0.05))
        if result.move is None:
            break
        board.push(result.move)
    return board


def score_cp(info, pov) -> int:
    """Centipawns from `pov`'s side, mate folded to a large finite value like the app does."""
    return info["score"].pov(pov).score(mate_score=10000)


def assess(engine: chess.engine.SimpleEngine, board: chess.Board, think: float):
    """Replay the game, assessing every player ply the way GoldenFixtureAssessor does."""
    plies = []
    replay = chess.Board()
    for move in board.move_stack:
        is_player = replay.turn == PLAYER
        san = replay.san(move)
        uci = move.uci()
        if not is_player:
            replay.push(move)
            plies.append({"san": san, "uci": uci, "fenAfter": replay.fen(), "isPlayer": False})
            continue

        # Best move and the evaluation of the position before the move — one search does both.
        before = engine.analyse(replay, chess.engine.Limit(time=think))
        cp_best = score_cp(before, PLAYER)
        best_move = before.get("pv", [None])[0]
        best_san = replay.san(best_move) if best_move and best_move != move else None

        replay.push(move)
        after = engine.analyse(replay, chess.engine.Limit(time=think))
        cp_played = score_cp(after, PLAYER)

        plies.append({
            "san": san,
            "uci": uci,
            "fenAfter": replay.fen(),
            "isPlayer": True,
            "cpBefore": cp_best,
            "cpPlayed": cp_played,
            "cpBest": cp_best,
            "bestMoveSan": best_san,
        })
    return plies


def to_pgn(board: chess.Board) -> str:
    replay = chess.Board()
    out = []
    for i, move in enumerate(board.move_stack):
        if i % 2 == 0:
            out.append(f"{i // 2 + 1}.")
        out.append(replay.san(move))
        replay.push(move)
    return " ".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--games", type=int, default=12)
    parser.add_argument("--max-plies", type=int, default=50)
    parser.add_argument("--think", type=float, default=0.1)
    parser.add_argument("--out", default="build/bench/summary-fixtures.json")
    args = parser.parse_args()

    engine = chess.engine.SimpleEngine.popen_uci(STOCKFISH)
    games = []
    try:
        for i in range(args.games):
            # Vary the weak side's strength so the set has a spread of game quality rather than
            # twelve games that all fall apart the same way.
            weak = [0, 1, 2, 3][i % 4]
            board = play_game(engine, weak_skill=weak, strong_skill=15, max_plies=args.max_plies)
            plies = assess(engine, board, args.think)
            blunders = sum(1 for p in plies if p.get("isPlayer") and p["cpBest"] - p["cpPlayed"] > 300)
            games.append({
                "id": f"game-{i + 1:03d}",
                "pgn": to_pgn(board),
                "playerSide": "WHITE",
                "result": board.result(claim_draw=True),
                "plies": plies,
            })
            print(f"  {games[-1]['id']}: {len(plies)} plies, result {games[-1]['result']}, "
                  f"{blunders} player blunders (>300cp)")
    finally:
        engine.quit()

    with open(args.out, "w") as handle:
        json.dump(games, handle, indent=1)
    print(f"wrote {len(games)} games to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
