package com.example.myapplication.board3d

object WasmKtxLoader {
    class KtxImage(
        val width: Int,
        val height: Int,
        val mipLevels: Int,
        val faces: Int,
        val data: ByteArray,
        val mipOffsets: IntArray,
        val mipSizes: IntArray
    )

    private fun ByteArray.readIntLE(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
               ((this[offset + 1].toInt() and 0xFF) shl 8) or
               ((this[offset + 2].toInt() and 0xFF) shl 16) or
               ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun load(bytes: ByteArray): KtxImage? {
        var pos = 12 // Skip 12-byte identifier
        
        val endianness = bytes.readIntLE(pos); pos += 4
        if (endianness != 0x04030201) return null // Only little endian supported
        
        val glType = bytes.readIntLE(pos); pos += 4
        val glTypeSize = bytes.readIntLE(pos); pos += 4
        val glFormat = bytes.readIntLE(pos); pos += 4
        val glInternalFormat = bytes.readIntLE(pos); pos += 4
        val glBaseInternalFormat = bytes.readIntLE(pos); pos += 4
        
        val width = bytes.readIntLE(pos); pos += 4
        val height = bytes.readIntLE(pos); pos += 4
        val depth = bytes.readIntLE(pos).coerceAtLeast(1); pos += 4
        val arrayElements = bytes.readIntLE(pos).coerceAtLeast(1); pos += 4
        val faces = bytes.readIntLE(pos).coerceAtLeast(1); pos += 4
        val mips = bytes.readIntLE(pos).coerceAtLeast(1); pos += 4
        val kvLen = bytes.readIntLE(pos); pos += 4
        
        pos += kvLen
        
        // Pre-calculate total data size
        var scanPos = pos
        var totalDataSize = 0
        for (i in 0 until mips) {
            val imageSize = bytes.readIntLE(scanPos)
            scanPos += 4
            val mipBytes = imageSize * faces
            totalDataSize += mipBytes
            scanPos += mipBytes
            val padding = (4 - (scanPos % 4)) % 4
            scanPos += padding
        }
        
        val outData = ByteArray(totalDataSize)
        val mipOffsets = IntArray(mips)
        val mipSizes = IntArray(mips)
        
        var currentOffset = 0
        for (i in 0 until mips) {
            val imageSize = bytes.readIntLE(pos)
            pos += 4
            val mipBytes = imageSize * faces
            
            mipOffsets[i] = currentOffset
            mipSizes[i] = mipBytes
            
            bytes.copyInto(outData, currentOffset, pos, pos + mipBytes)
            
            currentOffset += mipBytes
            pos += mipBytes
            
            val padding = (4 - (pos % 4)) % 4
            pos += padding
        }
        
        return KtxImage(width, height, mips, faces, outData, mipOffsets, mipSizes)
    }
}
