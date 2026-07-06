package com.example.myapplication.board3d

object BoardGeometry {
    const val SQUARE_SIZE: Float = 1f
    const val BOARD_HALF_EXTENT: Float = 4f * SQUARE_SIZE

    /**
     * World space: board centered at origin on the y=0 plane, +x toward file h, +z toward rank 1 (White's side).
     * a1 = BoardSquare(7, 0) -> Vec3(-3.5f, 0f, 3.5f)
     */
    fun squareCenter(square: BoardSquare): Vec3 {
        return Vec3(
            (square.col - 3.5f) * SQUARE_SIZE,
            0f,
            (square.row - 3.5f) * SQUARE_SIZE
        )
    }

    /**
     * Converts world x, z coordinates to BoardSquare.
     * Returns null if coordinates are outside the 8x8 area.
     */
    fun squareFromWorld(x: Float, z: Float): BoardSquare? {
        if (x < -BOARD_HALF_EXTENT || x >= BOARD_HALF_EXTENT) return null
        if (z < -BOARD_HALF_EXTENT || z >= BOARD_HALF_EXTENT) return null

        val col = kotlin.math.floor((x / SQUARE_SIZE) + 4f).toInt().coerceIn(0, 7)
        val row = kotlin.math.floor((z / SQUARE_SIZE) + 4f).toInt().coerceIn(0, 7)

        return BoardSquare(row, col)
    }
}
