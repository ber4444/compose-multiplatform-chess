package com.example.myapplication.board3d

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * iOS 3D board support backed by Metal-native Filament. The Swift app target supplies
 * [filamentFactory], which creates the CAMetalLayer-hosted native view while shared Kotlin keeps
 * ownership of camera, ray picking, selection, and animation state.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosBoard3DSupport(filamentFactory: FilamentChessViewFactory): Board3DSupport =
    Board3DSupport(
        rendererFactory = { FilamentIosChessRenderer(filamentFactory) },
        surfaceContent = { renderer, modifier ->
            FilamentIosBoard3DSurface(renderer, modifier)
        }
    )
