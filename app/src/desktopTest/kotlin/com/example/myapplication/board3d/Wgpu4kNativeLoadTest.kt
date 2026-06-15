package com.example.myapplication.board3d

import ffi.LibraryLoader
import io.ygdrasil.webgpu.WGPU
import io.ygdrasil.wgpu.wgpuSetLogLevel
import kotlin.test.Test
import kotlin.test.assertNotNull
import darwin.CAMetalLayer
import ffi.JvmNativeAddress
import java.lang.foreign.MemorySegment
import io.ygdrasil.webgpu.GPUPowerPreference
import kotlinx.coroutines.runBlocking

/**
 * M6 3D spike (issue #32) — desktop runtime step 1: native load.
 *
 * Proves wgpu4k's JVM binding can load `libwgpu_native` via Panama FFM on this machine
 * (macOS arm64 / JDK 26). If this passes, the FFM/native-access risk is retired and the
 * remaining work (adapter → device → render → readback) is plain WebGPU plumbing.
 *
 * Mirrors wgpu4k's own `WGPUTest`. See docs/plans/issue-32-3d-ui-m6-wgpu4k.md.
 */
class Wgpu4kNativeLoadTest {
    @Test
    fun loadsNativeLibrary() {
        LibraryLoader.load()
        // A call into the native lib; no crash == FFM resolved and loaded libwgpu_native.
        wgpuSetLogLevel(1u)
    }

    /** Step 2: create a wgpu instance. (Adapter creation requires a non-null NativeSurface —
     *  `requestAdapter(null)` does not compile — so the desktop renderer must build an offscreen
     *  CAMetalLayer first; that's the next slice.) */
    @Test
    fun createsInstance() {
        LibraryLoader.load()
        val wgpu = WGPU.createInstance()
        assertNotNull(wgpu, "WGPU.createInstance() returned null")
        wgpu.close()
    }

    /** Step 3: create an adapter using an offscreen CAMetalLayer to provide the surface. */
    @Test
    fun createsAdapter() {
        LibraryLoader.load()
        val wgpu = WGPU.createInstance()
        assertNotNull(wgpu, "WGPU.createInstance() returned null")

        // 1. Create a standalone CAMetalLayer via Rococoa
        val layer = CAMetalLayer.layer()
        assertNotNull(layer)
        
        // 2. Extract its native pointer and wrap it for wgpu4k FFI
        val layerAddr = (layer.id() as Number).toLong()
        val memorySegment = MemorySegment.ofAddress(layerAddr)
        val nativeAddress = JvmNativeAddress(memorySegment)
        
        // 3. Create the wgpu surface
        val surface = wgpu.getSurfaceFromMetalLayer(nativeAddress)
        assertNotNull(surface)
        
        // 4. Request the adapter
        val adapter = wgpu.requestAdapter(surface, GPUPowerPreference.HighPerformance)
        assertNotNull(adapter)

        adapter.close()
        wgpu.close()
    }

    /** Step 4: request a device from the adapter. */
    @Test
    fun createsDevice() = runBlocking {
        LibraryLoader.load()
        val wgpu = WGPU.createInstance()!!
        val layer = CAMetalLayer.layer()!!
        val layerAddr = (layer.id() as Number).toLong()
        val nativeAddress = JvmNativeAddress(MemorySegment.ofAddress(layerAddr))
        val surface = wgpu.getSurfaceFromMetalLayer(nativeAddress)!!
        val adapter = wgpu.requestAdapter(surface, GPUPowerPreference.HighPerformance)!!
        
        val deviceResult = adapter.requestDevice()
        val device = deviceResult.getOrNull()
        assertNotNull(device, "Expected device to be successfully created")

        device.close()
        adapter.close()
        wgpu.close()
    }
}
