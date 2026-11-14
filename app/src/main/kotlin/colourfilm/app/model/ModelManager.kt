package colourfilm.app.model

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads and verifies the DDColor model on first use.
 *
 * Source: Qualcomm AI Hub Models v0.66.0 ONNX export of DDColor (`ddcolor_paper_tiny`
 * checkpoint, Apache-2.0), published at https://huggingface.co/qualcomm/DDColor.
 * The archive is pinned by SHA-256 so a tampered or truncated download is never used.
 */
class ModelManager(private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(if (isInstalled()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state

    val dir: File get() = File(context.filesDir, "models/$MODEL_DIR")
    val modelFile: File get() = File(dir, "ddcolor.onnx")

    fun isInstalled() = File(dir, "ddcolor.onnx").length() > 1_000_000 &&
        File(dir, "ddcolor.data").length() == DATA_BYTES && File(dir, "ok").exists()

    suspend fun ensureDownloaded(): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled()) { _state.value = State.Ready; return@withContext true }
        try {
            val free = StatFs(context.filesDir.path).availableBytes
            val needed = ZIP_BYTES + DATA_BYTES + 50L * 1024 * 1024
            if (free < needed) throw IOException(
                "Not enough free space for the AI model: needs about ${needed / 1_000_000} MB, ${free / 1_000_000} MB free."
            )
            val zip = File(context.cacheDir, "ddcolor-onnx-float.zip.part")
            download(zip)
            _state.value = State.Verifying
            val sha = sha256(zip)
            if (sha != ZIP_SHA256) {
                zip.delete()
                throw IOException("The downloaded model failed its integrity check. Please try again.")
            }
            dir.deleteRecursively(); dir.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (name == "ddcolor.onnx" || name == "ddcolor.data") {
                        FileOutputStream(File(dir, name)).use { zin.copyTo(it, 1 shl 16) }
                    }
                }
            }
            zip.delete()
            if (File(dir, "ddcolor.data").length() != DATA_BYTES) throw IOException("Model archive was incomplete.")
            File(dir, "ok").writeText(ZIP_SHA256)
            _state.value = State.Ready
            true
        } catch (e: Exception) {
            _state.value = State.Failed(
                (e.message ?: e.javaClass.simpleName) + if (e is java.net.UnknownHostException) " (no internet connection?)" else ""
            )
            false
        }
    }

    /** Resumable HTTP download (uses Range when a partial file exists). */
    private fun download(target: File) {
        var attempt = 0
        while (true) {
            try {
                val have = if (target.exists()) target.length() else 0L
                if (have == ZIP_BYTES) return
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000; conn.readTimeout = 30_000
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                val append = code == 206
                if (code != 200 && code != 206) throw IOException("Model server answered HTTP $code")
                var done = if (append) have else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(target, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            done += r
                            if (done - last > 512 * 1024) { last = done; _state.value = State.Downloading(done, ZIP_BYTES) }
                        }
                    }
                }
                if (target.length() != ZIP_BYTES) throw IOException("Download ended early (${target.length()} of $ZIP_BYTES bytes)")
                return
            } catch (e: IOException) {
                if (++attempt >= 4) throw e
                Thread.sleep(2000L * attempt)
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(1 shl 16)
            while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_DIR = "ddcolor-tiny-qaihub-0.66.0"
        const val MODEL_URL =
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/ddcolor/releases/v0.66.0/ddcolor-onnx-float.zip"
        const val ZIP_SHA256 = "512e788cab02db6796ede174af0db2302247130284a3a00eab9aafddafa48a49"
        const val ZIP_BYTES = 206_251_342L
        const val DATA_BYTES = 225_312_128L
    }
}
