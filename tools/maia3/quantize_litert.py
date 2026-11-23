"""Quantize the float LiteRT export of Maia-3 5M to int8 weights: the file Android and iOS bundle.

Input is the float `maia3-5m.tflite` that export_litert.py writes, checked against its pinned SHA-256.
The recipe is weight-only int8 (`weight_only_wi8_afp32`): each weight matrix is stored as per-channel
int8 and dequantized to float32 once, when the interpreter is prepared, so every op still computes in
float32. 20.8 MB becomes 5.9 MB, and the model's own output barely moves.

Refuses to write the file unless the int8 model stays close to the float one:

1. MaiaGolden (the PyTorch top-5 for each case and rating, as BundledMaiaParity checks it on device):
   the same top move, and each of PyTorch's five moves within GOLDEN_SHARE_TOLERANCE of its share.
2. Positions from Maia's own self-play at the four difficulty ratings, scored at those same four
   ratings: the total-variation distance between the float and int8 legal-move distributions must
   average at most MEAN_TV_MAX and never exceed WORST_TV_MAX. A TV distance of 0.005 means the
   opponent's sampled move follows a different distribution about half a percent of the time.

The encoder below is a port of MaiaEncoder.kt. Step 1 runs the *float* model against MaiaGolden first,
token indices included, and that is what proves the port: a wrong encoding cannot reproduce the
PyTorch top-5.

Needs its own environment (tools/maia3/requirements-quantize.txt); it does not need torch.

Usage:
    python quantize_litert.py maia3-5m.tflite maia3-5m-int8.tflite
"""

import hashlib
import os
import random
import re
import sys

import chess
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from litert_quantizer import quantizer, recipe

FLOAT_SHA256 = "f0acad2d11db917c10ecc4418a5ef5600c00e21dcac1f54b77008acfeeed998e"
GOLDEN = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "app/src/commonTest/kotlin/com/example/myapplication/explorer/MaiaGolden.kt",
)
# BundledMaiaParity's tolerance for an int8 runtime must be at least this.
GOLDEN_SHARE_TOLERANCE = 0.02
MEAN_TV_MAX = 0.01
WORST_TV_MAX = 0.05
# The Maia ratings EngineDifficulty maps to (RatingBand.forDifficulty).
RATINGS = (1100, 1500, 1900, 2350)
SELF_PLAY_GAMES = 40
SEED = 7

HISTORY, PLANES = 8, 12
WIDTH = PLANES * HISTORY + 1
PIECES, PROMOTIONS = "pnbrqk", "qrbn"


def planes(fen):
    """(square, plane) for every piece, in the side-to-move frame: mirrored and colour-swapped for Black."""
    board = chess.Board(fen)
    mirror = board.turn == chess.BLACK
    out = []
    for square, piece in board.piece_map().items():
        white = piece.color == chess.WHITE
        sq, mine = (square ^ 56, not white) if mirror else (square, white)
        out.append((sq, PIECES.index(piece.symbol().lower()) + (0 if mine else 6)))
    return out


def tokens(history):
    recent = history[-HISTORY:]
    padded = [recent[0]] * (HISTORY - len(recent)) + recent
    out = np.zeros(64 * WIDTH, np.float32)
    for slot, fen in enumerate(padded):
        for square, plane in planes(fen):
            out[square * WIDTH + slot * PLANES + plane] = 1
    return out


def mirror_move(uci):
    return uci[0] + str(9 - int(uci[1])) + uci[2] + str(9 - int(uci[3])) + uci[4:]


def move_index(uci):
    start = (int(uci[1]) - 1) * 8 + ord(uci[0]) - 97
    end = (int(uci[3]) - 1) * 8 + ord(uci[2]) - 97
    if len(uci) == 4:
        return start * 64 + end
    return 4096 + ((ord(uci[0]) - 97) * 8 + (ord(uci[2]) - 97)) * 4 + PROMOTIONS.index(uci[4])


def legal_moves(fen):
    board = chess.Board(fen)
    black = board.turn == chess.BLACK
    return [(move_index(mirror_move(m.uci()) if black else m.uci()), m.uci()) for m in board.legal_moves]


class Model:
    def __init__(self, path):
        self.interpreter = Interpreter(model_path=path, num_threads=2)
        self.interpreter.allocate_tensors()
        self.inputs = self.interpreter.get_input_details()
        self.output = self.interpreter.get_output_details()[0]

    def distribution(self, history, rating):
        """Softmax over the legal moves only, as MaiaHumanMovePredictor computes it."""
        legal = legal_moves(history[-1])
        tokens_in, self_in, oppo_in = self.inputs
        self.interpreter.set_tensor(tokens_in["index"], tokens(history).reshape(1, 64, WIDTH))
        self.interpreter.set_tensor(self_in["index"], np.array([rating], self_in["dtype"]))
        self.interpreter.set_tensor(oppo_in["index"], np.array([rating], oppo_in["dtype"]))
        self.interpreter.invoke()
        logits = self.interpreter.get_tensor(self.output["index"])[0]
        x = logits[[index for index, _ in legal]].astype(np.float64)
        x = np.exp(x - x.max())
        return [uci for _, uci in legal], x / x.sum()


def golden_cases():
    text = open(GOLDEN).read()
    pattern = (r'startFen = "([^"]+)",\s*moves = listOf\(([^)]*)\),\s*tokenIndices = intArrayOf\(([^)]*)\),'
               r'.*?top = mapOf\((.*?)\)\),\n')
    for fen, moves, token_indices, top in re.findall(pattern, text, re.S):
        board = chess.Board(fen)
        history = [board.fen()]
        for uci in re.findall(r'"(\w+)"', moves):
            board.push_uci(uci)
            history.append(board.fen())
        expected = {
            int(rating): [(uci, float(share)) for uci, share in re.findall(r'"(\w+)" to ([\d.]+)', body)]
            for rating, body in re.findall(r'(\d+) to listOf\((.*?)\)(?:,|$)', top)
        }
        yield history[-HISTORY:], sorted(int(v) for v in token_indices.split(",")), expected


def worst_golden_share_error(model, check_tokens):
    worst = 0.0
    cases = list(golden_cases())
    if not cases:
        raise SystemExit(f"no cases parsed from {GOLDEN}")
    for history, token_indices, expected in cases:
        if check_tokens and sorted(np.nonzero(tokens(history))[0].tolist()) != token_indices:
            raise SystemExit(f"encoder port disagrees with MaiaGolden's token indices for {history[-1]}")
        for rating, want in expected.items():
            moves, p = model.distribution(history, rating)
            shares = dict(zip(moves, p))
            # The top move must agree; the tail is compared by share, not rank, because PyTorch's
            # fourth and fifth moves are often within a few tenths of a percent of each other.
            if moves[int(np.argmax(p))] != want[0][0]:
                raise SystemExit(f"top move differs from PyTorch for {history[-1]} @ {rating}")
            worst = max(worst, max(abs(shares[uci] - share) for uci, share in want))
    return worst


def self_play_positions(model):
    """Realistic positions: Maia sampling from its own top 5 moves, as MaiaOpponent plays."""
    rng = random.Random(SEED)
    positions = []
    for game in range(SELF_PLAY_GAMES):
        rating = RATINGS[game % len(RATINGS)]
        board = chess.Board()
        history = [board.fen()]
        for ply in range(rng.randint(20, 70)):
            if board.is_game_over():
                break
            moves, p = model.distribution(history[-HISTORY:], rating)
            top = np.argsort(-p)[:5]
            board.push_uci(moves[top[rng.choices(range(len(top)), p[top])[0]]])
            history.append(board.fen())
            if not board.is_game_over() and ply % 3 == 0:
                positions.append(history[-HISTORY:])
    return positions


def main(float_path, out_path):
    digest = hashlib.sha256(open(float_path, "rb").read()).hexdigest()
    if digest != FLOAT_SHA256:
        raise SystemExit(f"{float_path} is not the pinned float export (SHA-256 {digest})")

    reference = Model(float_path)
    float_error = worst_golden_share_error(reference, check_tokens=True)
    if float_error > 1e-3:
        raise SystemExit(f"float model is {float_error:.2e} off MaiaGolden; the encoder port is wrong")

    q = quantizer.Quantizer(float_path)
    q.load_quantization_recipe(recipe.weight_only_wi8_afp32())
    candidate = out_path + ".candidate"
    q.quantize().export_model(candidate, overwrite=True)
    try:
        quantized = Model(candidate)
        golden_error = worst_golden_share_error(quantized, check_tokens=False)
        if golden_error > GOLDEN_SHARE_TOLERANCE:
            raise SystemExit(f"int8 shares are up to {golden_error:.4f} off MaiaGolden (limit {GOLDEN_SHARE_TOLERANCE})")

        tv, same_top = [], 0
        positions = self_play_positions(reference)
        for history in positions:
            for rating in RATINGS:
                _, p0 = reference.distribution(history, rating)
                _, p1 = quantized.distribution(history, rating)
                tv.append(0.5 * np.abs(p0 - p1).sum())
                same_top += np.argmax(p0) == np.argmax(p1)
        tv = np.array(tv)
        if tv.mean() > MEAN_TV_MAX or tv.max() > WORST_TV_MAX:
            raise SystemExit(f"int8 drifts from float: mean TV {tv.mean():.4f} (limit {MEAN_TV_MAX}), "
                             f"worst {tv.max():.4f} (limit {WORST_TV_MAX})")
    except BaseException:
        os.remove(candidate)
        raise
    os.replace(candidate, out_path)
    print(f"wrote {out_path} ({os.path.getsize(out_path)} bytes, SHA-256 "
          f"{hashlib.sha256(open(out_path, 'rb').read()).hexdigest()})")
    print(f"MaiaGolden: worst share error {golden_error:.4f} (float {float_error:.1e})")
    print(f"{len(positions)} self-play positions x {len(RATINGS)} ratings: same top move {same_top / tv.size:.1%}, "
          f"TV mean {tv.mean():.4f}, p95 {np.percentile(tv, 95):.4f}, worst {tv.max():.4f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
