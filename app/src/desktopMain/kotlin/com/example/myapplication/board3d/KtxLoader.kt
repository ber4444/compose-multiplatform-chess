package com.example.myapplication.board3d

import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

object KtxLoader {
    class KtxImage(
        val width: Int,
        val height: Int,
        val mipLevels: Int,
        val faces: Int,
        val totalSize: Int,
        val data: ByteBuffer,
        val mipOffsets: IntArray,
        val mipSizes: IntArray
    ) {
        fun free() {
            MemoryUtil.memFree(data)
        }
    }

    fun load(bytes: ByteArray): KtxImage? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Skip 12-byte identifier
        buf.position(12)
        
        val endianness = buf.getInt()
        if (endianness != 0x04030201) return null // Only little endian supported for now
        
        val glType = buf.getInt()
        val glTypeSize = buf.getInt()
        val glFormat = buf.getInt()
        val glInternalFormat = buf.getInt()
        val glBaseInternalFormat = buf.getInt()
        
        val width = buf.getInt()
        val height = buf.getInt()
        val depth = buf.getInt().coerceAtLeast(1)
        val arrayElements = buf.getInt().coerceAtLeast(1)
        val faces = buf.getInt().coerceAtLeast(1)
        val mips = buf.getInt().coerceAtLeast(1)
        val kvLen = buf.getInt()
        
        buf.position(buf.position() + kvLen)
        
        // Pre-calculate total data size to allocate native buffer
        var scanPos = buf.position()
        var totalDataSize = 0
        for (i in 0 until mips) {
            val imageSize = buf.getInt(scanPos)
            scanPos += 4
            val mipBytes = imageSize * faces
            totalDataSize += mipBytes
            scanPos += mipBytes
            // Mip padding
            val padding = (4 - (scanPos % 4)) % 4
            scanPos += padding
        }
        
        val nativeData = MemoryUtil.memAlloc(totalDataSize)
        val mipOffsets = IntArray(mips)
        val mipSizes = IntArray(mips)
        
        var currentOffset = 0
        for (i in 0 until mips) {
            val imageSize = buf.getInt()
            val mipBytes = imageSize * faces
            
            mipOffsets[i] = currentOffset
            mipSizes[i] = mipBytes
            
            // Read all faces for this mip directly into nativeData
            val slice = buf.slice()
            slice.limit(mipBytes)
            nativeData.position(currentOffset)
            nativeData.put(slice)
            
            currentOffset += mipBytes
            buf.position(buf.position() + mipBytes)
            
            val padding = (4 - (buf.position() % 4)) % 4
            buf.position(buf.position() + padding)
        }
        nativeData.position(0)
        
        return KtxImage(width, height, mips, faces, totalDataSize, nativeData, mipOffsets, mipSizes)
    }
}
