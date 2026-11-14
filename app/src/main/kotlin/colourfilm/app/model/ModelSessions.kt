package colourfilm.app.model

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import colourfilm.app.BuildConfig
import colourfilm.core.ChromaModel
import colourfilm.core.OrtChromaModel
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** A ready-to-run model plus an honest description of how it is executing. */
class LoadedModel(val model: ChromaModel, val backend: String, val msPerRun: Double, val note: String?)

/**
 * Opens DDColor with the best runtime that is *verified* to work on this phone.
 *
 * On Snapdragon builds it first tries the Qualcomm NPU (QNN HTP, FP16). The NPU result is
 * compared against the CPU result on a test image; it is only used if it agrees and is
 * actually faster. Otherwise ONNX Runtime CPU is used. Nothing is assumed from the chipset.
 */
object ModelSessions {
    private const val TAG = "ModelSessions"
    private const val PREFS = "model_backend"

    private val env: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "colourfilm").also {
            runCatching { it.setTelemetry(false) }
        }
    }

    enum class Preference { AUTO, CPU_ONLY }

    /** Instrumented tests only: substitute a model (e.g. a fast stand-in on slow emulators). */
    @Volatile var overrideForTests: ((Context) -> LoadedModel)? = null

    fun open(context: Context, models: ModelManager, pref: Preference, onStatus: (String) -> Unit): LoadedModel {
        overrideForTests?.let { return it(context) }
        val path = models.modelFile.absolutePath
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (probe == null) probe = runCatching {
            context.assets.open("probe_grey_256.bin").use { it.readBytes() }.let { b -> FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f } }
        }.getOrNull()
        val key = "${ModelManager.MODEL_DIR}|${Build.FINGERPRINT}|ort1.29.0"
        val verdict = prefs.getString(key, null)

        if (BuildConfig.HAS_QNN && pref == Preference.AUTO && verdict != "cpu") {
            try {
                onStatus(if (verdict == null) "Preparing the NPU (first time only, can take a minute)…" else "Starting the NPU…")
                val npu = openQnn(context, path)
                val npuModel = OrtChromaModel(env, npu, "ddcolor-tiny-0.66.0/qnn-htp-fp16")
                val npuMs = timeRuns(npuModel)
                if (verdict == "npu") return LoadedModel(npuModel, "Qualcomm NPU (QNN HTP, FP16)", npuMs, null)

                onStatus("Checking NPU results against the CPU…")
                val cpu = OrtChromaModel(env, openCpu(path), "ddcolor-tiny-0.66.0/cpu-fp32")
                val cpuMs = timeRuns(cpu, 2)
                val diff = compare(npuModel, cpu)
                cpu.close()
                Log.i(TAG, "NPU ${npuMs}ms CPU ${cpuMs}ms meanAbsDiff=$diff")
                if (diff < 2.5 && npuMs < cpuMs * 0.8) {
                    prefs.edit().putString(key, "npu").apply()
                    return LoadedModel(npuModel, "Qualcomm NPU (QNN HTP, FP16)", npuMs, null)
                }
                npuModel.close()
                prefs.edit().putString(key, "cpu").apply()
                val why = if (diff >= 2.5) "NPU output differed from CPU (Δ=%.1f)".format(diff) else "NPU wasn't faster"
                onStatus("Using CPU ($why)")
                val m = OrtChromaModel(env, openCpu(path), "ddcolor-tiny-0.66.0/cpu-fp32")
                return LoadedModel(m, "CPU (ONNX Runtime)", timeRuns(m, 2), why)
            } catch (t: Throwable) {
                Log.w(TAG, "QNN unavailable", t)
                prefs.edit().putString(key, "cpu").apply()
                File(context.filesDir, "qnn_ctx").deleteRecursively()
                onStatus("NPU not available on this phone; using CPU")
                val m = OrtChromaModel(env, openCpu(path), "ddcolor-tiny-0.66.0/cpu-fp32")
                return LoadedModel(m, "CPU (ONNX Runtime)", timeRuns(m, 2), "NPU unavailable: ${t.message?.take(120)}")
            }
        }
        onStatus("Loading AI model…")
        val m = OrtChromaModel(env, openCpu(path), "ddcolor-tiny-0.66.0/cpu-fp32")
        return LoadedModel(m, "CPU (ONNX Runtime)", timeRuns(m, 2), null)
    }

    /** Forget a cached NPU/CPU decision (Settings → "Re-test NPU"). */
    fun forgetVerdict(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        File(context.filesDir, "qnn_ctx").deleteRecursively()
    }

    private fun cpuThreads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    private fun openCpu(path: String): OrtSession {
        val so = OrtSession.SessionOptions()
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        so.setIntraOpNumThreads(cpuThreads())
        return env.createSession(path, so)
    }

    private fun openQnn(context: Context, path: String): OrtSession {
        val ctxDir = File(context.filesDir, "qnn_ctx").apply { mkdirs() }
        val ctxFile = File(ctxDir, "ddcolor_ctx.onnx")
        fun options(generate: Boolean) = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
            addQnn(
                mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "sustained_high_performance",
                    "enable_htp_fp16_precision" to "1",
                    "htp_graph_finalization_optimization_mode" to "3",
                )
            )
            if (generate) {
                // Cache the compiled NPU graph so later launches start in seconds.
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_file_path", ctxFile.absolutePath)
                addConfigEntry("ep.context_embed_mode", "0")
            }
        }
        if (ctxFile.exists()) {
            try { return env.createSession(ctxFile.absolutePath, options(false)) } catch (t: Throwable) {
                Log.w(TAG, "cached NPU context unusable, rebuilding", t)
                ctxDir.listFiles()?.forEach { it.delete() }
            }
        }
        return env.createSession(path, options(true))
    }

    /** Real greyscale photo (OpenCV sample "building.jpg", 256x256) used to compare backends. */
    @Volatile private var probe: FloatArray? = null

    private fun testImage(s: Int): FloatArray = probe?.takeIf { it.size == s * s } ?: FloatArray(s * s) { i ->
        val x = i % s; val y = i / s
        (0.5f + 0.3f * sin(x * 0.05f) * sin(y * 0.07f) + 0.15f * sin((x + y) * 0.2f)).coerceIn(0f, 1f)
    }

    private fun timeRuns(m: ChromaModel, runs: Int = 3): Double {
        val s = m.inputSize
        val img = testImage(s); val out = FloatArray(2 * s * s)
        m.predict(img, out) // warm-up
        val t = System.nanoTime()
        repeat(runs) { m.predict(img, out) }
        return (System.nanoTime() - t) / 1e6 / runs
    }

    private fun compare(a: ChromaModel, b: ChromaModel): Double {
        val s = a.inputSize
        val img = testImage(s)
        val oa = FloatArray(2 * s * s); val ob = FloatArray(2 * s * s)
        a.predict(img, oa); b.predict(img, ob)
        var d = 0.0
        for (i in oa.indices) d += abs(oa[i] - ob[i])
        return d / oa.size
    }
}
