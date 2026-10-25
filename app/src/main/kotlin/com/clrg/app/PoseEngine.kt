package com.clrg.app

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.clrg.core.app.PerfSnapshot
import com.clrg.core.app.PipelineDebug
import com.clrg.core.app.PreviewFrame
import com.clrg.core.party.QualityMode
import com.clrg.core.pose.BlazePose
import com.clrg.core.pose.FrameGeometry
import com.clrg.core.pose.FrameOrientation
import com.clrg.core.pose.MultiPosePipeline
import com.clrg.core.pose.PersonPose
import com.clrg.core.pose.PipelineConfig
import com.clrg.core.pose.PoseFrame
import com.clrg.core.pose.PoseModelRunner
import com.clrg.core.pose.YuvImage
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** TensorFlow Lite implementation of the two BlazePose networks. Must be used from one thread. */
class TfliteRunner(assets: AssetManager, val variant: String, wantGpu: Boolean) : PoseModelRunner, AutoCloseable {
    private val gpu: GpuDelegate?
    private val detector: Interpreter
    private val landmarks: Interpreter
    val delegateName: String

    /** Direct native-order buffer with a float view sharing its memory. */
    private class Buf(n: Int) {
        val bytes: ByteBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        val floats: FloatBuffer = bytes.asFloatBuffer()
        fun write(src: FloatArray): ByteBuffer { floats.rewind(); floats.put(src); bytes.rewind(); return bytes }
        fun out(): ByteBuffer { bytes.rewind(); return bytes }
        fun read(dst: FloatArray) { floats.rewind(); floats.get(dst) }
    }

    private val detIn = Buf(BlazePose.DETECTOR_SIZE * BlazePose.DETECTOR_SIZE * 3)
    private val detReg = Buf(BlazePose.NUM_ANCHORS * BlazePose.DETECTOR_COORDS)
    private val detScore = Buf(BlazePose.NUM_ANCHORS)
    private val lmIn = Buf(BlazePose.LANDMARK_SIZE * BlazePose.LANDMARK_SIZE * 3)
    private val lmOut = Buf(BlazePose.NUM_LANDMARKS_ALL * BlazePose.LANDMARK_VALUES)
    private val lmFlag = Buf(1)
    private val lmHeat = Buf(BlazePose.HEATMAP_SIZE * BlazePose.HEATMAP_SIZE * BlazePose.NUM_LANDMARKS_ALL)
    private val detOutIdx: IntArray
    private val lmOutIdx: IntArray

    init {
        val detModel = map(assets, "models/${variant}_pose_detector.tflite")
        val lmModel = map(assets, "models/${variant}_pose_landmarks_detector.tflite")
        var g: GpuDelegate? = null
        var det: Interpreter? = null
        var lm: Interpreter? = null
        if (wantGpu) {
            try {
                CrashReporter.beginStep(CrashReporter.STEP_GPU_INIT)
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    g = GpuDelegate(compat.bestOptionsForThisDevice)
                    det = Interpreter(detModel, Interpreter.Options().addDelegate(g))
                    lm = Interpreter(lmModel, Interpreter.Options().addDelegate(g))
                }
            } catch (t: Throwable) {
                Log.w("PoseEngine", "GPU delegate unavailable, using CPU", t)
                try { det?.close() } catch (_: Throwable) {}
                try { lm?.close() } catch (_: Throwable) {}
                try { g?.close() } catch (_: Throwable) {}
                g = null; det = null; lm = null
            }
        }
        if (det == null || lm == null) {
            CrashReporter.beginStep(CrashReporter.STEP_CPU_INIT)
            val threads = max(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2))
            det = Interpreter(detModel, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true))
            lm = Interpreter(lmModel, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true))
            delegateName = "CPU x$threads"
        } else delegateName = "GPU"
        CrashReporter.endStep()
        gpu = g
        detector = det
        landmarks = lm
        detOutIdx = intArrayOf(detector.getOutputIndex("Identity"), detector.getOutputIndex("Identity_1"))
        lmOutIdx = intArrayOf(landmarks.getOutputIndex("Identity"), landmarks.getOutputIndex("Identity_1"), landmarks.getOutputIndex("Identity_3"))
    }

    private val detOuts = HashMap<Int, Any>(2)
    private val lmOuts = HashMap<Int, Any>(3)
    private val oneInput = arrayOfNulls<Any>(1)

    override fun runDetector(input: FloatArray, regressors: FloatArray, scores: FloatArray) {
        oneInput[0] = detIn.write(input)
        detOuts[detOutIdx[0]] = detReg.out()
        detOuts[detOutIdx[1]] = detScore.out()
        detector.runForMultipleInputsOutputs(oneInput, detOuts)
        detReg.read(regressors)
        detScore.read(scores)
    }

    override fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?) {
        oneInput[0] = lmIn.write(input)
        lmOuts.clear()
        lmOuts[lmOutIdx[0]] = lmOut.out()
        lmOuts[lmOutIdx[1]] = lmFlag.out()
        if (heatmap != null) lmOuts[lmOutIdx[2]] = lmHeat.out()
        this.landmarks.runForMultipleInputsOutputs(oneInput, lmOuts)
        lmOut.read(landmarks)
        lmFlag.read(flag)
        if (heatmap != null) lmHeat.read(heatmap)
    }

    override fun close() {
        detector.close()
        landmarks.close()
        gpu?.close()
    }

    companion object {
        fun map(assets: AssetManager, name: String): MappedByteBuffer {
            val fd = assets.openFd(name) // .tflite files are stored uncompressed in the APK
            FileInputStream(fd.fileDescriptor).use { s ->
                return s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }
}

/**
 * Inference thread. Wakes when the camera has a new image, always takes the *latest* one (older ones are dropped),
 * copies the YUV planes, runs the multi-person pipeline and publishes a PoseFrame plus a small preview bitmap.
 * Inference runs off the render/UI thread and at its own rate (decoupled from rendering).
 */
class PoseEngine(private val context: Context, private val camera: CameraSource, private val sink: (PoseFrame) -> Unit) {
    @Volatile var quality = QualityMode.AUTO
    @Volatile var mirror = true
    @Volatile var displayRotationDeg = 90
    @Volatile var enabled = true

    private val lock = Object()
    private var pending = false
    @Volatile private var running = false
    /** Each start() gets a new token; a thread whose token is stale exits, so only one thread ever runs the models. */
    @Volatile private var token = 0
    @Volatile private var releaseOnExit = false
    private var thread: Thread? = null
    private var runner: TfliteRunner? = null
    private var pipeline: MultiPosePipeline? = null
    private var variant = "full"
    private val prefs = context.getSharedPreferences("pose_engine", Context.MODE_PRIVATE)
    /** GPU is skipped for good once it failed (or crashed the app) on this phone. */
    private var gpuFailed = prefs.getBoolean(KEY_GPU_OFF, false)
    private var initFailures = 0
    /** Camera the pipeline's tracked regions belong to; a different camera means a different picture. */
    private var lastCameraId: String? = null
    private var nextInitAttemptNs = 0L
    /** Plain-language reason when pose tracking cannot run (shown on screen), or null. */
    @Volatile var problem: String? = null
        private set

    // buffers reused across frames
    private var yBuf = ByteArray(0)
    private var uBuf = ByteArray(0)
    private var vBuf = ByteArray(0)
    private val previewBitmaps = arrayOfNulls<Bitmap>(2)
    private var previewIndex = 0
    private var previewPixels = IntArray(0)
    private val latestPreview = AtomicReference<PreviewFrame?>(null)
    private val latestDebug = AtomicReference<PipelineDebug?>(null)

    // stats
    @Volatile private var perfSnap = PerfSnapshot()
    private var infFps = 0f
    private var lastDone = 0L
    private var avgMs = 0f
    private var slowSince = 0L
    private var fastSince = 0L
    private var frameCount = 0L
    private var skipToggle = 0L
    @Volatile var dropped = 0L
        private set
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun start() {
        if (running) return
        releaseOnExit = false
        val my = ++token
        running = true
        camera.onFrame = { synchronized(lock) { if (pending) dropped++; pending = true; lock.notifyAll() } }
        val previous = thread
        thread = Thread({
            // a previous thread may still be finishing a slow model load: never let two threads use the models
            try { previous?.join() } catch (_: InterruptedException) {}
            loop(my)
        }, "pose-inference").also { it.start() }
    }

    /** Stops quickly without blocking the UI thread for long; the models stay loaded for the next start(). */
    fun stop() {
        running = false
        token++
        synchronized(lock) { lock.notifyAll() }
        thread?.join(300)
    }

    /** Stops and frees the models (activity destroyed). */
    fun release() {
        releaseOnExit = true
        stop()
        val t = thread
        if (t == null || !t.isAlive) { runner?.close(); runner = null; pipeline = null }
    }

    fun preview(): PreviewFrame? = latestPreview.get()
    fun debug(): PipelineDebug? = latestDebug.get()
    fun perf(): PerfSnapshot = perfSnap

    private fun loop(my: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        while (running && token == my) {
            synchronized(lock) {
                while (running && token == my && !pending) lock.wait(500)
                pending = false
            }
            if (!running || token != my) break
            if (!enabled) continue
            // critical heat: process every other camera frame (the tracker copes with 15 Hz)
            if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL && (skipToggle++ % 2L == 0L)) continue
            try {
                processLatest()
            } catch (t: Throwable) {
                Log.e("PoseEngine", "frame failed", t)
                if (t is OutOfMemoryError) System.gc()
            }
        }
        if (releaseOnExit) {
            try { runner?.close() } catch (_: Throwable) {}
            runner = null
            pipeline = null
        }
    }

    private fun ensureRunner() {
        val wanted = when (quality) {
            QualityMode.PERFORMANCE -> "lite"
            QualityMode.QUALITY -> "full"
            QualityMode.AUTO -> variant
        }
        if (runner != null && runner!!.variant == wanted) return
        if (System.nanoTime() < nextInitAttemptNs) return
        try { runner?.close() } catch (_: Throwable) {}
        runner = null
        pipeline = null
        val r = try {
            try { TfliteRunner(context.assets, wanted, !gpuFailed) } catch (t: Throwable) {
                Log.e("PoseEngine", "runner init failed (gpu=${!gpuFailed})", t)
                if (!gpuFailed) disableGpu()
                TfliteRunner(context.assets, wanted, false)
            }
        } catch (t: Throwable) {
            // e.g. the TensorFlow Lite native library cannot be loaded on this phone: report it, retry slowly
            CrashReporter.endStep()
            initFailures++
            nextInitAttemptNs = System.nanoTime() + minOf(30_000_000_000L, 2_000_000_000L * initFailures)
            problem = "Body tracking could not start: ${t.javaClass.simpleName}: ${t.message?.take(160) ?: ""}"
            Log.e("PoseEngine", "runner init failed", t)
            return
        }
        problem = null
        initFailures = 0
        runner = r
        variant = wanted
        val cfg = if (wanted == "lite") PipelineConfig(maxPoses = 3, detectIntervalWhenSatisfied = 6, refineWithHeatmap = false)
        else PipelineConfig(maxPoses = 3, detectIntervalWhenSatisfied = 4, refineWithHeatmap = true)
        pipeline = MultiPosePipeline(r, cfg)
    }

    private fun processLatest() {
        val captureNs: Long
        val w: Int; val h: Int
        val yuv: YuvImage
        val info: CameraSource.Info
        // the camera thread closes readers under the same lock, so the image memory stays valid while copying
        synchronized(camera.frameLock) {
            val reader = camera.reader ?: return
            info = camera.active ?: return
            val image = try { reader.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
            try {
                w = image.width; h = image.height
                val planes = image.planes
                val yP = planes[0]; val uP = planes[1]; val vP = planes[2]
                yBuf = copy(yP.buffer, yBuf)
                uBuf = copy(uP.buffer, uBuf)
                vBuf = copy(vP.buffer, vBuf)
                yuv = YuvImage(w, h, yBuf, yP.rowStride, uBuf, vBuf, uP.rowStride, uP.pixelStride)
                // camera timestamps -> System.nanoTime base (only meaningful for REALTIME timestamp sources)
                captureNs = if (info.realtimeTimestamps) System.nanoTime() - (SystemClock.elapsedRealtimeNanos() - image.timestamp) else System.nanoTime()
            } finally {
                image.close()
            }
        }
        ensureRunner()
        val pipe = pipeline ?: return
        if (info.id != lastCameraId) { pipe.reset(); lastCameraId = info.id }
        val rot = FrameOrientation.rotationFor(info.sensorOrientation, displayRotationDeg, info.front)
        val orient = FrameOrientation(w, h, rot)
        val t0 = System.nanoTime()
        val onGpu = runner?.delegateName == "GPU"
        // the first GPU inferences are where buggy GPU drivers crash: mark them so a crash disables the GPU next time
        val watch = onGpu && frameCount < 3
        if (watch) CrashReporter.beginStep(CrashReporter.STEP_GPU_RUN)
        val raws = try {
            pipe.process(yuv, orient)
        } catch (e: Exception) {
            if (watch) CrashReporter.endStep()
            // e.g. a GPU delegate that initialised but fails at run time: fall back to the CPU for good
            Log.e("PoseEngine", "inference failed on ${runner?.delegateName}", e)
            if (onGpu) { disableGpu(); try { runner?.close() } catch (_: Throwable) {}; runner = null; pipeline = null }
            return
        }
        if (watch) CrashReporter.endStep()
        val t1 = System.nanoTime()
        val geo = FrameGeometry(orient.uprightWidth, orient.uprightHeight, mirror)
        val persons = raws.map { PersonPose.fromRaw(it, geo) }
        sink(PoseFrame(captureNs, geo.aspect, persons, processedNanos = t1))
        frameCount++
        if (frameCount % 2 == 0L) makePreview(yuv, orient)
        publishDebug(pipe, geo)
        updateStats(pipe, t0, t1, captureNs, info.realtimeTimestamps, orient)
        adapt(t1)
    }

    private fun disableGpu() {
        gpuFailed = true
        prefs.edit().putBoolean(KEY_GPU_OFF, true).apply()
    }

    private fun copy(src: ByteBuffer, dst: ByteArray): ByteArray {
        src.rewind()
        val n = src.remaining()
        val out = if (dst.size >= n) dst else ByteArray(n)
        src.get(out, 0, n)
        return out
    }

    /** Small upright RGB preview for set-up screens and the in-game camera inset. Not stored anywhere. */
    private fun makePreview(yuv: YuvImage, o: FrameOrientation) {
        val pw = 384
        val ph = (pw * o.uprightHeight / o.uprightWidth.toFloat()).toInt().coerceAtLeast(1)
        val idx = previewIndex
        var bmp = previewBitmaps[idx]
        if (bmp == null || bmp.width != pw || bmp.height != ph) {
            bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            previewBitmaps[idx] = bmp
        }
        if (previewPixels.size != pw * ph) previewPixels = IntArray(pw * ph)
        val m = o.uprightToBuffer()
        val sx = o.uprightWidth / pw.toFloat()
        val sy = o.uprightHeight / ph.toFloat()
        val tmp = FloatArray(3)
        for (y in 0 until ph) {
            val uy = (y + 0.5f) * sy
            for (x in 0 until pw) {
                val ux = (x + 0.5f) * sx
                val bx = m.mapX(ux, uy).toInt().coerceIn(0, yuv.width - 1)
                val by = m.mapY(ux, uy).toInt().coerceIn(0, yuv.height - 1)
                yuv.readPixel(bx, by, tmp, 0)
                previewPixels[y * pw + x] = (0xFF shl 24) or (tmp[0].toInt() shl 16) or (tmp[1].toInt() shl 8) or tmp[2].toInt()
            }
        }
        bmp!!.setPixels(previewPixels, 0, pw, 0, 0, pw, ph)
        latestPreview.set(PreviewFrame(AndroidImage(bmp), mirror))
        previewIndex = 1 - previewIndex
    }

    private fun publishDebug(pipe: MultiPosePipeline, geo: FrameGeometry) {
        val regions = pipe.lastRegions.map { r -> floatArrayOf(geo.toFrameX(r.x), geo.toFrameY(r.y), geo.toFrameX(r.x + r.w), geo.toFrameY(r.y + r.h)) }
        val rois = pipe.lastRois.map { r -> floatArrayOf(geo.toFrameX(r.cx), geo.toFrameY(r.cy), r.side / geo.uprightHeight) }
        latestDebug.set(PipelineDebug(regions, rois))
    }

    private fun updateStats(pipe: MultiPosePipeline, t0: Long, t1: Long, captureNs: Long, realtime: Boolean, o: FrameOrientation) {
        val ms = (t1 - t0) / 1e6f
        avgMs = if (avgMs == 0f) ms else avgMs + 0.1f * (ms - avgMs)
        if (lastDone != 0L) { val inst = 1e9f / (t1 - lastDone).coerceAtLeast(1); infFps += 0.1f * (inst - infFps) }
        lastDone = t1
        val st = pipe.stats
        val thermal = if (Build.VERSION.SDK_INT >= 29) when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"; PowerManager.THERMAL_STATUS_LIGHT -> "light"; PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"; PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"; else -> "EMERGENCY"
        } else "-"
        perfSnap = PerfSnapshot(
            cameraFps = camera.fps, inferenceFps = infFps, inferenceMs = avgMs,
            preprocessMs = st.preprocessMs, detectorMs = st.detectorMs, landmarkMs = st.landmarkMs,
            captureToResultMs = if (realtime) (t1 - captureNs) / 1e6f else Float.NaN,
            droppedFrames = dropped, delegate = runner?.delegateName ?: "-", model = "BlazePose $variant",
            inputSize = "${o.bufferWidth}x${o.bufferHeight} rot ${o.rotationCw}", thermal = thermal,
            adaptiveLevel = "${quality.label}/$variant",
        )
    }

    companion object {
        const val KEY_GPU_OFF = "gpu_off"

        /** Called at startup when the previous run crashed inside the GPU driver. */
        fun disableGpuForGood(context: Context) =
            context.getSharedPreferences("pose_engine", Context.MODE_PRIVATE).edit().putBoolean(KEY_GPU_OFF, true).apply()
    }

    /** AUTO quality: drop to the lite model when slow or hot, return to full when there is headroom. */
    private fun adapt(now: Long) {
        if (quality != QualityMode.AUTO) return
        val hot = Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        if (variant == "full") {
            if (avgMs > 30f || hot) { if (slowSince == 0L) slowSince = now } else slowSince = 0L
            if (slowSince != 0L && now - slowSince > 3_000_000_000L) { variant = "lite"; slowSince = 0L; avgMs = 0f }
        } else {
            if (avgMs < 12f && !hot) { if (fastSince == 0L) fastSince = now } else fastSince = 0L
            if (fastSince != 0L && now - fastSince > 15_000_000_000L) { variant = "full"; fastSince = 0L; avgMs = 0f }
        }
    }
}
