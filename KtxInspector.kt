import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    val bytes = File("app/src/desktopMain/resources/papermill_hdr16f_cube.ktx").readBytes()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    
    val id = ByteArray(12)
    buf.get(id)
    println("ID: ${id.joinToString(",") { it.toString(16) }}")
    
    val endianness = buf.getInt()
    println("Endianness: ${endianness.toString(16)}")
    
    println("glType: ${buf.getInt().toString(16)}")
    println("glTypeSize: ${buf.getInt()}")
    println("glFormat: ${buf.getInt().toString(16)}")
    println("glInternalFormat: ${buf.getInt().toString(16)}")
    println("glBaseInternalFormat: ${buf.getInt().toString(16)}")
    
    val width = buf.getInt()
    val height = buf.getInt()
    val depth = buf.getInt()
    println("Width: $width, Height: $height, Depth: $depth")
    
    println("Array elements: ${buf.getInt()}")
    val faces = buf.getInt()
    println("Faces: $faces")
    val mips = buf.getInt()
    println("Mips: $mips")
    
    val kvLen = buf.getInt()
    println("KV length: $kvLen")
    
    buf.position(buf.position() + kvLen)
    
    var offset = buf.position()
    for (i in 0 until mips) {
        val imageSize = buf.getInt()
        println("Mip $i: imageSize=$imageSize")
        for (f in 0 until faces) {
            buf.position(buf.position() + imageSize)
            val padding = 3 - ((imageSize + 3) % 4)
            buf.position(buf.position() + padding)
        }
        val mipPadding = 3 - ((buf.position() + 3) % 4)
        buf.position(buf.position() + mipPadding)
    }
}
