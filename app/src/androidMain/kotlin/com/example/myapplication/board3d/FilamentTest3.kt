package com.example.myapplication.board3d
import android.util.Log

fun debugPieces(pieces: List<com.example.myapplication.board3d.Piece3DInstance>, instances: Array<com.google.android.filament.gltfio.FilamentInstance?>, filamentAsset: com.google.android.filament.gltfio.FilamentAsset, tm: com.google.android.filament.TransformManager) {
    Log.d("ChessRenderer", "Total pieces: ${pieces.size}")
    for (i in pieces.indices) {
        val piece = pieces[i]
        val instance = instances[i]
        if (instance == null) {
            Log.d("ChessRenderer", "Instance $i is NULL! piece=${piece.kind}")
            continue
        }
        val meshName = ChessSetMeshNames.getMeshName(piece.kind, piece.color)
        val templateEntity = filamentAsset.getFirstEntityByName(meshName)
        val idx = filamentAsset.entities.indexOf(templateEntity)
        val pieceEntity = instance.entities[idx]
        val tInst = tm.getInstance(pieceEntity)
        Log.d("ChessRenderer", "Piece $i (${piece.color} ${piece.kind}): template=$templateEntity, idx=$idx, pieceEntity=$pieceEntity, tInst=$tInst")
    }
}
