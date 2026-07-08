package com.example.myapplication

import game.app.generated.resources.Res
import game.app.generated.resources.bishop_dark
import game.app.generated.resources.bishop_light
import game.app.generated.resources.king_dark
import game.app.generated.resources.king_light
import game.app.generated.resources.knight_dark
import game.app.generated.resources.knight_light
import game.app.generated.resources.pawn_dark
import game.app.generated.resources.pawn_light
import game.app.generated.resources.queen_dark
import game.app.generated.resources.queen_light
import game.app.generated.resources.rook_dark
import game.app.generated.resources.rook_light
import org.jetbrains.compose.resources.DrawableResource

/**
 * Resolves a [Piece] to its Compose [DrawableResource] (the piece's 2D vector drawable).
 *
 * This lives in `:app` (not `:chess-core`) because the core is Compose-free and platform-agnostic —
 * it has no concept of drawables. The `:app` UI asks for a piece's drawable through this lookup
 * instead of via a `Piece.asset` field, keeping the core decoupled from Compose resources.
 */
fun Piece.asset(): DrawableResource = when (this) {
    is King -> if (set == Set.WHITE) Res.drawable.king_light else Res.drawable.king_dark
    is Bishop -> if (set == Set.WHITE) Res.drawable.bishop_light else Res.drawable.bishop_dark
    is Knight -> if (set == Set.WHITE) Res.drawable.knight_light else Res.drawable.knight_dark
    is Pawn -> if (set == Set.WHITE) Res.drawable.pawn_light else Res.drawable.pawn_dark
    is Queen -> if (set == Set.WHITE) Res.drawable.queen_light else Res.drawable.queen_dark
    is Rook -> if (set == Set.WHITE) Res.drawable.rook_light else Res.drawable.rook_dark
    else -> error("Unknown Piece type: ${this::class}")
}
