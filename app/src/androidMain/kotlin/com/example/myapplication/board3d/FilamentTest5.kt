package com.example.myapplication.board3d
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset

fun testCreateInstance(loader: AssetLoader, asset: FilamentAsset) {
    val inst = loader.createInstance(asset)
}
