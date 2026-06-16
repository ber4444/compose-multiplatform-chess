package com.example.myapplication.board3d

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Uint16Array
import org.khronos.webgl.Uint32Array
import kotlin.js.Promise
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@JsFun("(promise, onFulfilled, onRejected) => promise.then((v) => onFulfilled(v), (e) => onRejected(e))")
private external fun thenSafe(promise: JsAny, onFulfilled: (JsAny?) -> Unit, onRejected: (JsAny?) -> Unit)

// Awaits a JS Promise without kotlinx.coroutines' Promise.await(), whose interop internals throw
// "Cannot set property for WebAssembly GC object" under Kotlin 2.3.x Wasm GC. Returns null when the
// promise resolves to null/undefined (e.g. requestAdapter()'s no-adapter case).
suspend fun awaitPromiseSafe(promise: Promise<JsAny?>): JsAny? = suspendCancellableCoroutine { cont ->
    thenSafe(promise,
        onFulfilled = { value -> cont.resumeWith(Result.success(value)) },
        onRejected = { error -> cont.resumeWithException(Exception("Promise rejected: $error")) }
    )
}

// Top-level JS interop for WebGPU
internal fun hasWebGpu(): Boolean = js("navigator.gpu !== undefined && navigator.gpu !== null")
internal fun getNavigatorGpu(): JsAny? = js("navigator.gpu")

internal fun requestAdapterJs(gpu: JsAny): Promise<JsAny> = js("(() => { try { return gpu.requestAdapter().catch(e => null); } catch(e) { return Promise.resolve(null); } })()")

internal fun requestDeviceJs(adapter: JsAny): Promise<JsAny> = js("adapter.requestDevice()")

internal fun getGpuContextJs(canvas: JsAny): JsAny = js("canvas.getContext('webgpu')")

internal fun getPreferredCanvasFormatJs(gpu: JsAny): String = js("gpu.getPreferredCanvasFormat()")

internal fun configureContextJs(context: JsAny, device: JsAny, format: String): Unit = 
    js("context.configure({ device: device, format: format })")

internal fun createShaderModuleJs(device: JsAny, code: String): JsAny = 
    js("device.createShaderModule({ code: code })")

internal fun createRenderPipelineJs(device: JsAny, module: JsAny, format: String): JsAny = js("""
        device.createRenderPipeline({
            layout: 'auto',
            vertex: {
                module: module,
                entryPoint: 'vs_main',
                buffers: [{
                    arrayStride: 44,
                    attributes: [
                        { shaderLocation: 0, offset: 0, format: 'float32x3' },
                        { shaderLocation: 1, offset: 12, format: 'float32x3' },
                        { shaderLocation: 2, offset: 24, format: 'float32x2' },
                        { shaderLocation: 3, offset: 32, format: 'float32x3' }
                    ]
                }]
            },
            fragment: {
                module: module,
                entryPoint: 'fs_main',
                targets: [{ format: format }]
            },
            primitive: {
                topology: 'triangle-list',
                cullMode: 'none'
            },
            depthStencil: {
                depthWriteEnabled: true,
                depthCompare: 'less',
                format: 'depth24plus'
            }
        })
""")

internal fun createSkyPipelineJs(device: JsAny, module: JsAny, format: String): JsAny = js("""
        device.createRenderPipeline({
            layout: 'auto',
            vertex: {
                module: module,
                entryPoint: 'vs_sky'
            },
            fragment: {
                module: module,
                entryPoint: 'fs_sky',
                targets: [{ format: format }]
            },
            primitive: {
                topology: 'triangle-list',
                cullMode: 'none'
            },
            depthStencil: {
                depthWriteEnabled: false,
                depthCompare: 'always',
                format: 'depth24plus'
            }
        })
""")

internal fun createUniformBufferJs(device: JsAny, size: Int): JsAny = js("device.createBuffer({ size: size, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST })")
internal fun createVertexBufferJs(device: JsAny, size: Int): JsAny = js("device.createBuffer({ size: size, usage: GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST })")
internal fun createIndexBufferJs(device: JsAny, size: Int): JsAny = js("device.createBuffer({ size: size, usage: GPUBufferUsage.INDEX | GPUBufferUsage.COPY_DST })")

internal fun newFloat32ArrayJs(size: Int): JsAny = js("new Float32Array(size)")
internal fun newUint32ArrayJs(size: Int): JsAny = js("new Uint32Array(size)")
internal fun newUint8ArrayJs(size: Int): JsAny = js("new Uint8Array(size)")
internal fun setFloatJs(arr: JsAny, index: Int, value: Float): Unit = js("arr[index] = value")
internal fun setIntJs(arr: JsAny, index: Int, value: Int): Unit = js("arr[index] = value")
internal fun setByteJs(arr: JsAny, index: Int, value: Byte): Unit = js("arr[index] = value")
internal fun writeBufferNativeJs(device: JsAny, buffer: JsAny, arr: JsAny): Unit = js("device.queue.writeBuffer(buffer, 0, arr)")
internal fun writeTextureNativeJs(device: JsAny, texture: JsAny, arr: JsAny, width: Int, height: Int): Unit = js("device.queue.writeTexture({ texture: texture }, arr, { bytesPerRow: width * 4 }, [width, height, 1])")
internal fun writeCubeFaceNativeJs(device: JsAny, texture: JsAny, arr: JsAny, mipLevel: Int, faceIndex: Int, width: Int, height: Int): Unit = js("device.queue.writeTexture({ texture: texture, mipLevel: mipLevel, origin: [0, 0, faceIndex] }, arr, { bytesPerRow: width * 8 }, [width, height, 1])")

internal fun writeBufferFloatArrayJs(device: JsAny, buffer: JsAny, data: FloatArray) {
    val f32 = newFloat32ArrayJs(data.size)
    for (i in data.indices) setFloatJs(f32, i, data[i])
    writeBufferNativeJs(device, buffer, f32)
}

internal fun writeBufferIntArrayJs(device: JsAny, buffer: JsAny, data: IntArray) {
    val u32 = newUint32ArrayJs(data.size)
    for (i in data.indices) setIntJs(u32, i, data[i])
    writeBufferNativeJs(device, buffer, u32)
}

internal fun createTextureJs(device: JsAny, width: Int, height: Int, format: String): JsAny = js("device.createTexture({ size: [width, height, 1], format: format, usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST | GPUTextureUsage.RENDER_ATTACHMENT })")

internal fun createDepthTextureJs(device: JsAny, width: Int, height: Int): JsAny = js("device.createTexture({ size: [width, height, 1], format: 'depth24plus', usage: GPUTextureUsage.RENDER_ATTACHMENT })")

internal fun createCubeTextureJs(device: JsAny, width: Int, height: Int, mipLevels: Int): JsAny = js("device.createTexture({ size: [width, height, 6], format: 'rgba16float', usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST, mipLevelCount: mipLevels })")

internal fun writeTextureJs(device: JsAny, texture: JsAny, width: Int, height: Int, data: ByteArray) {
    val u8 = newUint8ArrayJs(data.size)
    for (i in data.indices) setByteJs(u8, i, data[i])
    writeTextureNativeJs(device, texture, u8, width, height)
}

internal fun writeCubeFaceJs(device: JsAny, texture: JsAny, mipLevel: Int, faceIndex: Int, width: Int, height: Int, data: ByteArray) {
    val u8 = newUint8ArrayJs(data.size)
    for (i in data.indices) setByteJs(u8, i, data[i])
    writeCubeFaceNativeJs(device, texture, u8, mipLevel, faceIndex, width, height)
}

internal fun createTextureViewJs(texture: JsAny): JsAny = js("texture.createView()")
internal fun createCubeTextureViewJs(texture: JsAny): JsAny = js("texture.createView({ dimension: 'cube', arrayLayerCount: 6 })")

internal fun createSamplerMipmapJs(device: JsAny): JsAny = js("device.createSampler({ magFilter: 'linear', minFilter: 'linear', mipmapFilter: 'linear' })")
internal fun createSamplerNoMipmapJs(device: JsAny): JsAny = js("device.createSampler({ magFilter: 'linear', minFilter: 'linear' })")
internal fun createSamplerJs(device: JsAny, mipmap: Boolean = false): JsAny = if (mipmap) createSamplerMipmapJs(device) else createSamplerNoMipmapJs(device)

internal fun createBindGroupJs(device: JsAny, pipeline: JsAny, groupIndex: Int, entriesJs: JsAny): JsAny = js("device.createBindGroup({ layout: pipeline.getBindGroupLayout(groupIndex), entries: entriesJs })")

internal fun makeBindGroupEntriesForObject(view: JsAny, uniform: JsAny, sampler: JsAny, envView: JsAny, envSampler: JsAny, matBuffer: JsAny): JsAny = js("[ { binding: 0, resource: view }, { binding: 1, resource: { buffer: uniform } }, { binding: 2, resource: sampler }, { binding: 3, resource: envView }, { binding: 4, resource: envSampler }, { binding: 5, resource: { buffer: matBuffer } } ]")

internal fun makeBindGroupEntriesForSky(uniform: JsAny, envView: JsAny, envSampler: JsAny): JsAny = js("[ { binding: 0, resource: { buffer: uniform } }, { binding: 1, resource: envView }, { binding: 2, resource: envSampler } ]")

internal fun getCurrentTextureViewJs(context: JsAny): JsAny = js("context.getCurrentTexture().createView()")

internal fun createCommandEncoderJs(device: JsAny): JsAny = js("device.createCommandEncoder()")

internal fun beginRenderPassJs(encoder: JsAny, colorView: JsAny, depthView: JsAny): JsAny = js("""
    encoder.beginRenderPass({
        colorAttachments: [{
            view: colorView,
            clearValue: { r: 0.1, g: 0.2, b: 0.3, a: 1.0 },
            loadOp: 'clear',
            storeOp: 'store'
        }],
        depthStencilAttachment: {
            view: depthView,
            depthClearValue: 1.0,
            depthLoadOp: 'clear',
            depthStoreOp: 'store'
        }
    })
""")

internal fun passSetPipelineJs(pass: JsAny, pipeline: JsAny): Unit = js("pass.setPipeline(pipeline)")
internal fun passSetBindGroupJs(pass: JsAny, index: Int, bindGroup: JsAny): Unit = js("pass.setBindGroup(index, bindGroup)")
internal fun passSetVertexBufferJs(pass: JsAny, index: Int, buffer: JsAny): Unit = js("pass.setVertexBuffer(index, buffer)")
internal fun passSetIndexBufferJs(pass: JsAny, buffer: JsAny): Unit = js("pass.setIndexBuffer(buffer, 'uint32')")
internal fun passDrawJs(pass: JsAny, vertexCount: Int): Unit = js("pass.draw(vertexCount)")
internal fun passDrawIndexedJs(pass: JsAny, indexCount: Int): Unit = js("pass.drawIndexed(indexCount)")
internal fun passEndJs(pass: JsAny): Unit = js("pass.end()")

internal fun finishEncoderJs(encoder: JsAny): JsAny = js("encoder.finish()")
internal fun submitQueueJs(device: JsAny, commandBuffer: JsAny): Unit = js("device.queue.submit([commandBuffer])")
