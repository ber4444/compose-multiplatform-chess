package com.example.myapplication.board3d

import io.ygdrasil.webgpu.WGPUContext
import io.ygdrasil.webgpu.glfwContextRenderer

/**
 * M6 3D spike (issue #32) — compile-only probe.
 *
 * This references wgpu4k's public API purely so the Kotlin 2.3.20 compiler must read the
 * `io.ygdrasil:wgpu4k-toolkit` klib/jar metadata. If `:app:compileKotlinDesktop` succeeds, the
 * binary-compatibility kill-switch passes (this is the exact failure mode that killed Materia).
 * Not wired into the app or executed. Delete once the real wgpu4k renderer lands.
 *
 * See docs/plans/issue-32-3d-ui-m6-wgpu4k.md.
 */
@Suppress("unused")
private suspend fun wgpu4kCompileProbe(): WGPUContext =
    glfwContextRenderer(width = 1, height = 1, title = "wgpu4k-spike").wgpuContext
