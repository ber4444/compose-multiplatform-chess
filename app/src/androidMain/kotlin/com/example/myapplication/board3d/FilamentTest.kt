package com.example.myapplication.board3d
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset

fun hideTemplates(engine: Engine, filamentAsset: FilamentAsset) {
    val rm = engine.renderableManager
    PieceKind.entries.forEach { kind ->
        val name = ChessSetMeshNames.getMeshName(kind, PieceColor.WHITE)
        val templateEntity = filamentAsset.getFirstEntityByName(name)
        if (templateEntity != 0) {
            val rInst = rm.getInstance(templateEntity)
            if (rInst != 0) {
                rm.setLayerMask(rInst, 255, 0) // 0xFF select, 0 value
            }
        }
    }
}
