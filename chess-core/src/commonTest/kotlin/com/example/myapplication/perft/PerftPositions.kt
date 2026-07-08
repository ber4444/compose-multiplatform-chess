package com.example.myapplication.perft

// ============================================================================
// CANONICAL PERFT REFERENCE VALUES — DO NOT EDIT.
// ----------------------------------------------------------------------------
// These are arithmetic facts (see chessprogramming.org/Perft_Results), not
// test fixtures. If a test fails, the generator is wrong, not these numbers.
// The autonomous perft loop is explicitly forbidden from editing this file
// (see docs/plans/perft-loop-brief.md and the integrity guard in the plan).
// ============================================================================

/**
 * @property expected indexed by depth — `expected[0]` is the depth-1 count,
 *           `expected[1]` is depth-2, etc. Missing depths (where a position
 *           has no published value past a certain point) simply aren't queried.
 */
data class PerftPosition(
    val name: String,
    val fen: String,
    val expected: List<Long>
)

object PerftPositions {
    val START: PerftPosition = PerftPosition(
        "Start",
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        listOf(20L, 400L, 8_902L, 197_281L, 4_865_609L, 119_060_324L)
    )

    val KIWIPETE: PerftPosition = PerftPosition(
        "Kiwipete",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        listOf(48L, 2_039L, 97_862L, 4_085_603L, 193_690_690L)
    )

    val POSITION_3: PerftPosition = PerftPosition(
        "Position 3",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        listOf(14L, 191L, 2_812L, 43_238L, 674_624L, 11_030_083L)
    )

    val POSITION_4: PerftPosition = PerftPosition(
        "Position 4",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        listOf(6L, 264L, 9_467L, 422_333L, 15_833_292L)
    )

    val POSITION_5: PerftPosition = PerftPosition(
        "Position 5",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        listOf(44L, 1_486L, 62_379L, 2_103_487L, 89_941_194L)
    )

    val POSITION_6: PerftPosition = PerftPosition(
        "Position 6",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
        listOf(46L, 2_079L, 89_890L, 3_894_594L)
    )

    /** All six canonical positions, in the order they appear in the plan. */
    val ALL: List<PerftPosition> = listOf(START, KIWIPETE, POSITION_3, POSITION_4, POSITION_5, POSITION_6)
}
