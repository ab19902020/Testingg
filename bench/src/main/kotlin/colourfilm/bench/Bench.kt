package colourfilm.bench

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import colourfilm.core.ColouriseSettings
import colourfilm.core.FrameProcessor
import colourfilm.core.OrtChromaModel
import colourfilm.core.Parallel
import colourfilm.core.VideoColour
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Desktop harness: runs the exact Kotlin colourisation core used by the Android app, with the
 * same ONNX model via ONNX Runtime, over any file ffmpeg can decode. Used for benchmarking,
 * quality inspection and generating sample exports. (The Android app uses MediaCodec instead
 * of ffmpeg for decode/encode.)
 *
 * Usage: bench <model.onnx> <input> <output.mp4> [--start S] [--dur S] [--speed BEST|BALANCED|FAST]
 *              [--stability OFF|LIGHT|STANDARD|STRONG] [--look NATURAL|VIVID] [--intensity F]
 *              [--no-refine] [--threads N] [--ort-threads N]
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: bench <model.onnx> <input> <output.mp4> [options]" }
    val (modelPath, input, output) = args
    val opt = HashMap<String, String>()
    run {
        val rest = args.drop(3)
        var i = 0
        while (i < rest.size) {
            val k = rest[i]
            if (i + 1 < rest.size && !rest[i + 1].startsWith("--")) { opt[k] = rest[i + 1]; i += 2 } else { opt[k] = ""; i++ }
        }
    }
    val flags = args.toSet()
    val greyOut = "--grey" in flags   // write restored black-and-white (for measuring restoration)
    val base = ColouriseSettings(
        intensity = opt["--intensity"]?.toFloat() ?: 1f,
        look = ColouriseSettings.Look.valueOf(opt["--look"] ?: "NATURAL"),
        speed = ColouriseSettings.Speed.valueOf(opt["--speed"] ?: "BALANCED"),
        stability = ColouriseSettings.Stability.valueOf(opt["--stability"] ?: "STANDARD"),
        edgeRefine = "--no-refine" !in flags,
        deflicker = "--no-deflicker" !in flags,
        autoContrast = "--no-contrast" !in flags,
        grain = ColouriseSettings.Grain.valueOf(opt["--grain"] ?: "LIGHT"),
        dust = "--no-dust" !in flags,
        sharpen = "--no-sharpen" !in flags,
    )
    val settings = if ("--no-restore" in flags) base.withoutRestoration() else base
    colourfilm.core.TemporalColouriser.enableSearch = "--no-search" !in flags
    colourfilm.core.TemporalColouriser.enableClean = "--no-clean" !in flags
    opt["--alpha"]?.let { colourfilm.core.TemporalColouriser.alphaOverride = it.toFloat() }
    colourfilm.core.TemporalColouriser.enableShotMemory = "--no-shots" !in flags
    colourfilm.core.TemporalColouriser.enableModelDenoise = "--no-model-denoise" !in flags
    val probe = Probe.of(input)
    val w = probe.width and 1.inv(); val h = probe.height and 1.inv()
    println("source ${probe.width}x${probe.height} fps=${probe.fps} audio=${probe.hasAudio} -> ${w}x$h settings=${settings.fingerprint()}")

    val env = OrtEnvironment.getEnvironment()
    val so = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(opt["--ort-threads"]?.toInt() ?: 4)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    val t0 = System.nanoTime()
    val model = OrtChromaModel(env, env.createSession(modelPath, so), "ddcolor-tiny-qaihub-0.66.0/fp32")
    println("model load ${(System.nanoTime() - t0) / 1_000_000} ms, input ${model.inputSize}")

    val timed = TimedModel(opt["--cache"]?.let { CachingModel(model, File(it)) } ?: model)
    val parallel = Parallel(opt["--threads"]?.toInt() ?: 4)
    val colour = VideoColour.defaultFor(h)
    val between = opt["--between"]?.toInt() ?: 0   // in-between frames per source pair
    val proc = FrameProcessor(w, h, colour, timed, settings, parallel, between)
    val neutral = ByteArray((w / 2) * (h / 2)) { 128.toByte() }

    val seek = buildList { opt["--start"]?.let { add("-ss"); add(it) } }
    val dur = buildList { opt["--dur"]?.let { add("-t"); add(it) } }
    val dec = ProcessBuilder(listOf("ffmpeg", "-v", "error") + seek + listOf("-i", input) + dur +
        listOf("-vf", "crop=$w:$h:0:0", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"))
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val audioArgs = if (probe.hasAudio) seek + listOf("-i", input) + listOf("-map", "0:v", "-map", "1:a:0", "-c:a", "aac", "-b:a", "192k")
    else listOf("-map", "0:v")
    val enc = ProcessBuilder(listOf("ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "${w}x$h",
        "-r", if (opt["--between"] != null) "${probe.fps * ((opt["--between"]!!.toInt()) + 1)}" else probe.fpsRational, "-i", "-") + audioArgs + dur +
        listOf("-c:v", "libx264", "-preset", "medium", "-crf", opt["--crf"] ?: "17", "-pix_fmt", "yuv420p",
            "-color_primaries", if (h >= 720) "bt709" else "smpte170m", "-colorspace", if (h >= 720) "bt709" else "smpte170m",
            "-color_trc", if (h >= 720) "bt709" else "smpte170m",
            "-movflags", "+faststart", "-shortest", output))
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()

    val ySize = w * h; val cSize = (w / 2) * (h / 2)
    val y = ByteArray(ySize); val u = ByteArray(cSize); val v = ByteArray(cSize)
    val din = DataInputStream(dec.inputStream.buffered(1 shl 20))
    val out = enc.outputStream.buffered(1 shl 20)
    var frames = 0
    val st0 = proc.stats
    val start = System.nanoTime()
    var outFrames = 0
    fun emit() {
        for (j in 1..proc.inBetweenAvailable) {
            if (proc.renderInBetween(j)) { out.write(proc.midY); out.write(if (greyOut) neutral else proc.midU); out.write(if (greyOut) neutral else proc.midV) }
            else { // keep a constant frame rate in this raw-video harness: repeat the nearer frame
                out.write(proc.outY); out.write(if (greyOut) neutral else proc.outU); out.write(if (greyOut) neutral else proc.outV) }
            outFrames++
        }
        if (between > 0 && proc.inBetweenAvailable == 0 && frames > 0) repeat(between) {
            out.write(proc.outY); out.write(if (greyOut) neutral else proc.outU); out.write(if (greyOut) neutral else proc.outV); outFrames++ }
        outFrames++
        out.write(proc.outY); out.write(if (greyOut) neutral else proc.outU); out.write(if (greyOut) neutral else proc.outV)
        if ("--trace" in flags) println("f=$frames conf=%.3f hist=%.3f model=${st0.lastModelRun} cut=${st0.lastCut}".format(st0.lastMeanConfidence, st0.lastHistDistance))
        frames++
        if (frames % 50 == 0) print("\rframes $frames")
    }
    while (readFully(din, y) && readFully(din, u) && readFully(din, v)) {
        if (proc.push(y, 0, w)) emit()
    }
    while (proc.flush()) emit()
    out.close()
    dec.waitFor(); enc.waitFor()
    val wall = (System.nanoTime() - start) / 1e9
    val st = proc.stats
    println()
    println("frames=$frames wall=%.1fs (%.2f fps) modelRuns=${st.modelRuns} cuts=${st.cuts} shotsResumed=${st.shotsResumed}".format(wall, frames / wall))
    println("per frame: model %.1f ms/run, analysis(total incl. model) %.1f ms, compose %.1f ms".format(
        timed.nanos / 1e6 / maxOf(1, timed.runs), proc.analysisNanos / 1e6 / maxOf(1, frames), proc.composeNanos / 1e6 / maxOf(1, frames)))
    println("restore %.1f ms/frame; dust spots removed=${proc.dustStats.spotsRemoved} (${proc.dustStats.pixelsRepaired} px); noise sigma=%.1f; last gain=%.2f".format(
        proc.restoreNanos / 1e6 / maxOf(1, frames), proc.enhancerStats.lastNoiseSigma, proc.enhancerStats.lastGain))
    if (between > 0) println("in-between frames made=${proc.inBetweenMade} skipped=${proc.inBetweenSkipped}, %.1f ms each incl. motion".format(proc.interpolateNanos / 1e6 / maxOf(1, proc.inBetweenMade + proc.inBetweenSkipped)))
    println("encoder exit=${enc.exitValue()} -> $output")
    parallel.close(); model.close()
}

private fun readFully(din: DataInputStream, buf: ByteArray): Boolean = try {
    din.readFully(buf); true
} catch (e: java.io.EOFException) { false }

class TimedModel(private val m: colourfilm.core.ChromaModel) : colourfilm.core.ChromaModel by m {
    var nanos = 0L; var runs = 0
    override fun predict(grey: FloatArray, outAb: FloatArray) {
        val t = System.nanoTime(); m.predict(grey, outAb); nanos += System.nanoTime() - t; runs++
    }
}

data class Probe(val width: Int, val height: Int, val fps: Double, val fpsRational: String, val hasAudio: Boolean) {
    companion object {
        fun of(path: String): Probe {
            fun run(vararg a: String): String {
                val p = ProcessBuilder(*a).redirectErrorStream(true).start()
                val s = p.inputStream.readAllBytes().decodeToString(); p.waitFor(); return s.trim()
            }
            val v = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,avg_frame_rate",
                "-of", "csv=p=0", path).lines().first().split(",")
            val a = run("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", path)
            val (n, d) = v[2].split("/").map { it.toDouble() }
            return Probe(v[0].toInt(), v[1].toInt(), n / d, v[2], a.isNotBlank())
        }
    }
}

@Suppress("unused")
private fun InputStream.drain() = readAllBytes()
@Suppress("unused")
private fun exists(p: String) = File(p).exists()

/** Caches model outputs on disk keyed by the input image, so pipeline experiments rerun quickly. */
class CachingModel(private val m: colourfilm.core.ChromaModel, private val dir: File) : colourfilm.core.ChromaModel by m {
    init { dir.mkdirs() }
    override fun predict(grey: FloatArray, outAb: FloatArray) {
        val crc = java.util.zip.CRC32()
        val bb = java.nio.ByteBuffer.allocate(grey.size * 4); bb.asFloatBuffer().put(grey); crc.update(bb.array())
        val f = File(dir, "%08x.bin".format(crc.value))
        if (f.exists()) {
            java.nio.ByteBuffer.wrap(f.readBytes()).asFloatBuffer().get(outAb, 0, 2 * grey.size)
        } else {
            m.predict(grey, outAb)
            val ob = java.nio.ByteBuffer.allocate(outAb.size * 4); ob.asFloatBuffer().put(outAb); f.writeBytes(ob.array())
        }
    }
}
