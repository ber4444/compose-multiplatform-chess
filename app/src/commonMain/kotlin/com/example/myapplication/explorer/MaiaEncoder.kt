package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.legalUciMoves

/**
 * Kotlin port of Maia-3's input/output encoding (`maia3/dataset.py`, `maia3/utils.py`), pinned
 * against the reference Python by `MaiaEncoderTest` over `MaiaGolden`.
 *
 * **Input** is a `(64, 97)` float tensor, flattened row-major: for each of the 64 squares
 * (a1 = 0, b1 = 1, …, h8 = 63), eight 12-wide one-hot blocks — one per history position, oldest
 * first, padded at the front with the oldest when the game is shorter — then one clock column that
 * is always 0 (the 5M checkpoint is trained without time info; the column is sliced off inside the
 * model).
 *
 * **Every position is encoded from its own side-to-move's point of view.** A position with Black to
 * move is mirrored vertically *and* colour-swapped first, exactly like python-chess's
 * `Board.mirror()`. History therefore alternates orientation ply by ply. Getting this wrong does not
 * fail loudly: the model still returns a plausible-looking distribution, for a different position.
 *
 * **Output** is 4352 logits: `from * 64 + to` for every square pair, then 256 promotions at
 * `4096 + (fromFile * 8 + toFile) * 4 + {q, r, b, n}`, always from the 7th to the 8th rank because the
 * board was mirrored for Black.
 */
internal object MaiaEncoder {
    const val SQUARES = 64
    const val PLANES = 12
    const val WIDTH = PLANES * MAIA_HISTORY + 1
    const val VOCAB = 64 * 64 + 8 * 8 * 4

    fun tokens(history: List<String>): FloatArray {
        require(history.isNotEmpty()) { "Maia needs at least the current position" }
        val recent = history.takeLast(MAIA_HISTORY)
        val padded = List(MAIA_HISTORY - recent.size) { recent.first() } + recent
        val out = FloatArray(SQUARES * WIDTH)
        padded.forEachIndexed { slot, fen ->
            for ((square, plane) in planes(fen)) out[square * WIDTH + slot * PLANES + plane] = 1f
        }
        return out
    }

    /** Legal moves of [fen] as `(vocabulary index, real UCI)`. */
    fun legalMoves(fen: String): List<Pair<Int, String>> {
        val state = FenConverter.fenToGameState(fen)
        val blackToMove = sideToMoveIsBlack(fen)
        return legalUciMoves(state).map { uci -> moveIndex(if (blackToMove) mirrorMove(uci) else uci) to uci }
    }

    /** Index of a move already in the side-to-move frame (i.e. mirrored if Black is to move). */
    fun moveIndex(uci: String): Int {
        val from = square(uci, 0)
        val to = square(uci, 2)
        if (uci.length == 4) return from * 64 + to
        val piece = PROMOTIONS.indexOf(uci[4])
        require(piece >= 0 && uci[1] == '7' && uci[3] == '8') { "Not a promotion in the mover's frame: $uci" }
        return 4096 + ((uci[0] - 'a') * 8 + (uci[2] - 'a')) * 4 + piece
    }

    fun mirrorMove(uci: String): String = buildString {
        append(uci[0]); append('9' - (uci[1] - '0'))
        append(uci[2]); append('9' - (uci[3] - '0'))
        if (uci.length > 4) append(uci.substring(4))
    }

    /** `(square, plane)` for every piece of [fen], in the side-to-move frame. */
    private fun planes(fen: String): List<Pair<Int, Int>> {
        val board = fen.substringBefore(' ')
        val mirror = sideToMoveIsBlack(fen)
        val ranks = board.split('/')
        require(ranks.size == 8) { "Bad FEN board: $board" }
        val result = ArrayList<Pair<Int, Int>>(32)
        ranks.forEachIndexed { fromTop, rankText ->
            val rank = 7 - fromTop
            var file = 0
            for (c in rankText) {
                if (c.isDigit()) {
                    file += c - '0'
                    continue
                }
                val type = PIECE_TYPES.indexOf(c.lowercaseChar())
                require(type >= 0 && file < 8) { "Bad FEN piece '$c' in $board" }
                val white = c.isUpperCase()
                val square = rank * 8 + file
                // Board.mirror(): flip ranks and swap colours, so the mover is always "white".
                val (sq, moverOwned) = if (mirror) (square xor 56) to !white else square to white
                result += sq to (type + if (moverOwned) 0 else 6)
                file++
            }
        }
        return result
    }

    private fun sideToMoveIsBlack(fen: String) = fen.split(' ').getOrNull(1) == "b"

    private fun square(uci: String, offset: Int): Int {
        val file = uci[offset] - 'a'
        val rank = uci[offset + 1] - '1'
        require(file in 0..7 && rank in 0..7) { "Bad square in $uci" }
        return rank * 8 + file
    }

    private const val PIECE_TYPES = "pnbrqk"
    private const val PROMOTIONS = "qrbn"
}
