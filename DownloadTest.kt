import java.net.HttpURLConnection
import java.net.URL

fun main() {
    val url = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_q4_block32_ekv1280.litertlm"
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = true
    conn.connect()
    println("Response code: ${conn.responseCode}")
    println("Content length: ${conn.contentLengthLong}")
    println("Redirect location: ${conn.getHeaderField("Location")}")
}
