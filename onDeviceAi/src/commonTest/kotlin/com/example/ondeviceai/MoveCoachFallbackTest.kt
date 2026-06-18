package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertTrue

class MoveCoachFallbackTest {

    private fun req(tags: List<String>, move: String = "Nf3") = MoveCoachRequest(
        fenBefore = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        bestMoveUci = "g1f3",
        bestMoveDisplay = move,
        sideToMove = "white",
        evaluationBeforeCp = 20,
        evaluationAfterCp = 30,
        deterministicTags = tags,
    )

    @Test
    fun `checkmate tag drives headline`() {
        val text = MoveCoachFallback.build(
            req(listOf(MoveCoachFallback.TAG_CHECKMATE), move = "Qh5")
        )
        assertTrue(text.startsWith("Qh5 delivers checkmate."), message = text)
    }

    @Test
    fun `check tag drives headline`() {
        val text = MoveCoachFallback.build(req(listOf(MoveCoachFallback.TAG_CHECK)))
        assertTrue(text.startsWith("Nf3 gives check."), message = text)
    }

    @Test
    fun `castle kingside tag drives headline`() {
        val text = MoveCoachFallback.build(
            req(listOf(MoveCoachFallback.TAG_CASTLE_KS), move = "O-O")
        )
        assertTrue(text.contains("castles kingside"), message = text)
    }

    @Test
    fun `castle queenside tag drives headline`() {
        val text = MoveCoachFallback.build(
            req(listOf(MoveCoachFallback.TAG_CASTLE_QS), move = "O-O-O")
        )
        assertTrue(text.contains("castles queenside"), message = text)
    }

    @Test
    fun `promotion tag drives headline`() {
        val text = MoveCoachFallback.build(
            req(listOf(MoveCoachFallback.TAG_PROMOTION), move = "e8=Q")
        )
        assertTrue(text.contains("promotes the pawn"), message = text)
    }

    @Test
    fun `capture tag drives headline`() {
        val text = MoveCoachFallback.build(
            req(listOf(MoveCoachFallback.TAG_CAPTURE), move = "Nxe5")
        )
        assertTrue(text.contains("captures material"), message = text)
    }

    @Test
    fun `material swing tag drives reason`() {
        val text = MoveCoachFallback.build(req(listOf(MoveCoachFallback.TAG_MATERIAL_SWING)))
        assertTrue(text.contains("material balance"), message = text)
    }

    @Test
    fun `defends tag drives reason`() {
        val text = MoveCoachFallback.build(req(listOf(MoveCoachFallback.TAG_DEFENDS)))
        assertTrue(text.contains("defends"), message = text)
    }

    @Test
    fun `eval-based reason when no tactical tags`() {
        val text = MoveCoachFallback.build(req(emptyList()))
        assertTrue(text.contains("0.3"), message = text)
    }

    @Test
    fun `fallback text is bounded by MAX_OUTPUT_CHARS`() {
        val manyTags = listOf(
            MoveCoachFallback.TAG_CAPTURE,
            MoveCoachFallback.TAG_CHECK,
            MoveCoachFallback.TAG_PROMOTION,
            MoveCoachFallback.TAG_MATERIAL_SWING,
            MoveCoachFallback.TAG_DEFENDS,
        )
        val text = MoveCoachFallback.build(req(manyTags))
        assertTrue(text.length <= MoveCoachPromptBuilder.MAX_OUTPUT_CHARS, "len=${text.length}")
    }
}
