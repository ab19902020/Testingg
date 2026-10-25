package com.clrg.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import com.clrg.core.app.CameraOption
import com.clrg.core.app.CameraState
import com.clrg.core.app.CameraStatus
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.atan

/**
 * Camera2 capture into an ImageReader (YUV_420_888, ~720p). The reader keeps only a couple of buffers and the
 * consumer always takes the *latest* image, so frames never queue up. Handles disconnection (another app taking the
 * camera), errors and the camera becoming available again by retrying.
 */
class CameraSource(private val context: Context) {
    companion object {
        private const val TAG = "CameraSource"
        val TARGET = Size(1280, 720)
    }

    class Info(
        val id: String, val front: Boolean, val sensorOrientation: Int, val hFovDeg: Float, val zoomMin: Float, val realtimeTimestamps: Boolean,
        /** The camera lets the app set exposure time and sensitivity itself (sharp-motion exposure). */
        val manualSensor: Boolean = false,
        val exposureMinNs: Long = 0L, val exposureMaxNs: Long = 0L,
        val isoMin: Int = 0, val isoMax: Int = 0, val maxAnalogIso: Int = 0,
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    @Volatile var status = CameraStatus(CameraState.STOPPED)
        private set
    @Volatile var active: Info? = null
        private set
    @Volatile var reader: ImageReader? = null
        private set
    /** Called (on the camera thread) whenever a new image is available. */
    @Volatile var onFrame: (() -> Unit)? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    /** An openCamera() call is in flight (its callback has not arrived yet). */
    private var opening = false
    /** Incremented for every open/close; callbacks from an older generation are ignored. */
    private var generation = 0
    /** Held while the consumer reads an image and while a reader is closed, so a buffer is never freed mid-read. */
    val frameLock = Any()
    private var wantedId: String? = null
    private var wantWide = false
    private var running = false
    private var retryCount = 0
    @Volatile var fps = 0f
        private set
    /** True while the camera delivers (up to) 60 frames per second instead of 30. */
    @Volatile var highFps = false
        private set
    private var wantHighFps = false
    private var requestBuilder: CaptureRequest.Builder? = null
    private var lastFrameNs = 0L

    val infos: List<Info> by lazy { enumerate() }

    private fun enumerate(): List<Info> = try {
        manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return@mapNotNull null
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null
            if (map.getOutputSizes(ImageFormat.YUV_420_888).isNullOrEmpty()) return@mapNotNull null
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            val hfov = if (sensor != null && focal != null && focal > 0f) Math.toDegrees(2.0 * atan(sensor.width / (2.0 * focal))).toFloat() else 70f
            val zoomMin = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f else 1f
            val ts = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS)
            val er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val analog = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 0
            Info(id, facing == CameraCharacteristics.LENS_FACING_FRONT, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, hfov, zoomMin, ts,
                manual && er != null && ir != null, er?.lower ?: 0L, er?.upper ?: 0L, ir?.lower ?: 0, ir?.upper ?: 0, analog)
        }
    } catch (e: Exception) {
        Log.e(TAG, "enumerate failed", e)
        emptyList()
    }

    fun options(): List<CameraOption> {
        var backs = 0
        var fronts = 0
        return infos.map {
            val n = if (it.front) ++fronts else ++backs
            val label = (if (it.front) "Front camera" else "Back camera") + if (n > 1) " $n" else ""
            CameraOption(it.id, it.front, label, it.hFovDeg, it.zoomMin < 0.99f)
        }
    }

    /** Default: the widest back camera (it can be placed facing the players), otherwise the front one. */
    fun defaultId(): String? = infos.filter { !it.front }.maxByOrNull { it.hFovDeg }?.id ?: infos.firstOrNull()?.id

    fun setPermissionMissing(denied: Boolean) {
        val st = CameraStatus(if (denied) CameraState.DENIED else CameraState.NEEDS_PERMISSION)
        status = st
        handler.post { if (!running) status = st } // also after any stop() still queued on the camera thread
    }

    /** Applies camera choice; restarts the camera if it changed while running. */
    fun configure(id: String?, wide: Boolean) = handler.post { guarded("configure camera", id) { configureInternal(id, wide) } }

    private fun configureInternal(id: String?, wide: Boolean) {
        val newId = id?.takeIf { want -> infos.any { it.id == want } } ?: defaultId()
        val changed = newId != (active?.id ?: wantedId) || wide != wantWide
        wantedId = newId
        wantWide = wide
        if (changed && running) { closeInternal(); openInternal() }
    }

    /** Idempotent: calling it again while the camera is open or opening does nothing. */
    fun start() = handler.post {
        running = true
        retryCount = 0
        guarded("start", wantedId) { openInternal() }
    }

    fun stop() = handler.post {
        running = false
        try { closeInternal() } catch (t: Throwable) { Log.e(TAG, "close failed", t) }
        // keep "permission needed" visible; anything else becomes STOPPED
        if (status.state != CameraState.NEEDS_PERMISSION && status.state != CameraState.DENIED) status = CameraStatus(CameraState.STOPPED)
    }

    /**
     * Runs a camera callback so that nothing it throws can reach the camera thread's looper (which would kill the
     * app): any failure becomes an ERROR status and a retry.
     */
    private inline fun guarded(what: String, id: String?, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
            status = CameraStatus(CameraState.ERROR, "$what: ${t.javaClass.simpleName}", id)
            try { closeInternal() } catch (_: Throwable) {}
            scheduleRetry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openInternal() {
        if (!running || device != null || opening) return
        if (infos.isEmpty()) { status = CameraStatus(CameraState.NO_CAMERA, "no camera"); return }
        val id = wantedId ?: defaultId() ?: return
        val info = infos.firstOrNull { it.id == id } ?: infos.first()
        val gen = ++generation
        status = CameraStatus(CameraState.OPENING, activeId = info.id)
        opening = true
        try {
            manager.openCamera(info.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) = guarded("open", info.id) {
                    if (gen != generation) { try { cam.close() } catch (_: Throwable) {}; return@guarded } // superseded by a newer open or a close
                    opening = false
                    if (!running) { try { cam.close() } catch (_: Throwable) {}; return@guarded }
                    device = cam
                    active = info
                    createSession(cam, info, gen)
                }
                override fun onDisconnected(cam: CameraDevice) = guarded("disconnect", info.id) {
                    Log.w(TAG, "camera disconnected")
                    try { cam.close() } catch (_: Throwable) {}
                    if (gen != generation) return@guarded
                    opening = false
                    if (device === cam || device == null) closeInternal()
                    status = CameraStatus(CameraState.INTERRUPTED, "in use by another app", info.id)
                    scheduleRetry()
                }
                override fun onError(cam: CameraDevice, error: Int) = guarded("error", info.id) {
                    Log.e(TAG, "camera error $error")
                    try { cam.close() } catch (_: Throwable) {}
                    if (gen != generation) return@guarded
                    opening = false
                    if (device === cam || device == null) closeInternal()
                    status = CameraStatus(if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) CameraState.INTERRUPTED else CameraState.ERROR, "code $error", info.id)
                    scheduleRetry()
                }
            })
        } catch (e: SecurityException) {
            opening = false
            status = CameraStatus(CameraState.NEEDS_PERMISSION)
        } catch (e: Throwable) {
            opening = false
            Log.e(TAG, "openCamera failed", e)
            status = CameraStatus(CameraState.ERROR, e.message ?: "open failed", info.id)
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!running) return
        retryCount++
        handler.postDelayed({ guarded("retry", wantedId) { if (running && device == null && !opening) openInternal() } }, minOf(5000L, 800L * retryCount))
    }

    private fun chooseSize(id: String): Size {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).toList()
        val wide = sizes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f && it.height >= 360 }
        val pool = wide.ifEmpty { sizes }
        return pool.minByOrNull { abs(it.width * it.height - TARGET.width * TARGET.height) } ?: sizes.first()
    }

    private fun chooseFps(id: String, high: Boolean): Range<Int>? {
        val ranges = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        if (high) {
            // 60 fps for smoother, lower-latency tracking when the phone can keep up (steady range preferred)
            (ranges.firstOrNull { it.lower == 60 && it.upper == 60 } ?: ranges.filter { it.upper == 60 && it.lower >= 30 }.maxByOrNull { it.lower })?.let { return it }
        }
        // prefer a fixed or near-fixed 30 fps range (steady timing); fall back to the highest upper bound
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper * 100 + it.lower }
    }

    /** Whether this camera offers a 60 fps range at all. */
    fun supportsHighFps(): Boolean = active?.let { a -> chooseFps(a.id, true)?.upper == 60 } ?: false

    /** Asks for 60 (or back to 30) frames per second; applied to the running session without reopening the camera. */
    fun setHighFps(on: Boolean) = handler.post {
        if (wantHighFps == on) return@post
        wantHighFps = on
        val s = session ?: return@post
        val b = requestBuilder ?: return@post
        val info = active ?: return@post
        guarded("frame rate", info.id) {
            val range = chooseFps(info.id, on) ?: return@guarded
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            highFps = range.upper >= 60
            if (ae == Ae.MANUAL) b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs())
            s.setRepeatingRequest(b.build(), captureCb, handler)
        }
    }

    private fun createSession(cam: CameraDevice, info: Info, gen: Int) {
        try {
            val size = chooseSize(info.id)
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({
                try {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (lastFrameNs != 0L) { val inst = 1e9f / (now - lastFrameNs).coerceAtLeast(1); fps += 0.1f * (inst - fps) }
                    lastFrameNs = now
                    onFrame?.invoke()
                } catch (t: Throwable) {
                    Log.e(TAG, "frame callback failed", t)
                }
            }, handler)
            reader = r
            val outputs = listOf(OutputConfiguration(r.surface))
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) = guarded("configure", info.id) {
                    // a session for a camera that has since been closed or replaced: drop it
                    if (gen != generation || device !== cam || !running) { try { s.close() } catch (_: Throwable) {}; return@guarded }
                    session = s
                    val range = chooseFps(info.id, wantHighFps)
                    highFps = (range?.upper ?: 30) >= 60
                    // preview template: frame rate and low delay before post-processing quality (the record template
                    // may add multi-frame noise reduction, which delays frames and smears fast-moving arms)
                    val chars = manager.getCameraCharacteristics(info.id)
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(r.surface)
                        range?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        if (chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) == true)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                        if (chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_FAST) == true)
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                        if (Build.VERSION.SDK_INT >= 30 && wantWide && info.zoomMin < 0.99f) set(CaptureRequest.CONTROL_ZOOM_RATIO, info.zoomMin)
                    }
                    requestBuilder = req
                    try {
                        ae = Ae.AUTO; aeSince = SystemClock.elapsedRealtime(); refLuma = Float.NaN
                        s.setRepeatingRequest(req.build(), captureCb, handler)
                        handler.removeCallbacks(exposureTick)
                        handler.postDelayed(exposureTick, 300)
                        status = CameraStatus(CameraState.RUNNING, "${size.width}x${size.height}", info.id)
                        retryCount = 0
                    } catch (e: Exception) {
                        Log.e(TAG, "repeating request failed", e)
                        status = CameraStatus(CameraState.ERROR, "request failed", info.id)
                        scheduleRetry()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = guarded("configure", info.id) {
                    if (gen != generation) return@guarded
                    status = CameraStatus(CameraState.ERROR, "configuration failed", info.id)
                    closeInternal()
                    scheduleRetry()
                }
            })
            cam.createCaptureSession(cfg)
        } catch (e: Throwable) {
            Log.e(TAG, "createSession failed", e)
            status = CameraStatus(CameraState.ERROR, e.message ?: "session failed", info.id)
            closeInternal()
            scheduleRetry()
        }
    }

    private fun closeInternal() {
        generation++
        opening = false
        handler.removeCallbacks(exposureTick)
        ae = Ae.AUTO
        try { session?.close() } catch (_: Throwable) {}
        session = null
        requestBuilder = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        active = null
        val r = reader
        reader = null
        // waits for the consumer to finish copying any image it holds from this reader
        synchronized(frameLock) { try { r?.close() } catch (_: Throwable) {} }
    }

    // ------------------------------------------------------------------ sharp-motion exposure

    /**
     * Sharper fast movements. Indoors the camera's own auto exposure keeps the shutter open for most of each frame
     * (often 1/30 s), so a swinging arm is a long blur and the body tracker loses the hand. Where the camera allows
     * it, once auto exposure has settled the same brightness is set up with a much shorter exposure (1/100 or 1/120 s,
     * matched to the room's mains-light flicker) and a higher sensitivity, and the sensitivity is then adjusted to
     * keep the picture as bright as auto exposure had it. Falls back to auto exposure when the light changes a lot,
     * when the option is off, and for good if the camera refuses.
     */
    @Volatile var sharpMotion = true
    /** For the motion test screen: how the camera is exposing right now. */
    @Volatile var exposureInfo = "-"
        private set
    @Volatile private var lastExpNs = 0L
    @Volatile private var lastIso = 0
    @Volatile private var lastFlicker = -1
    @Volatile private var luma = Float.NaN
    private var manualFailed = false
    private enum class Ae { AUTO, MANUAL }
    private var ae = Ae.AUTO
    private var aeSince = 0L
    private var manualExp = 0L
    private var manualIso = 0
    private var refLuma = Float.NaN
    private var offSince = 0L

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExpNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.STATISTICS_SCENE_FLICKER)?.let { lastFlicker = it }
        }
    }

    /** Mean brightness (0..255) of the latest frame, measured by the tracking thread. */
    fun reportLuma(v: Float) { luma = v }

    private val exposureTick = object : Runnable {
        override fun run() {
            val info = active
            try { exposureStep() } catch (t: Throwable) {
                Log.w(TAG, "sharp-motion exposure refused, staying on auto exposure", t)
                manualFailed = true
                try { toAuto() } catch (_: Throwable) {}
            }
            if (running && info != null && session != null) handler.postDelayed(this, 250)
        }
    }

    private fun frameNs(): Long = if (highFps) 16_666_666L else 33_333_333L

    /** An exposure that does not beat with the room's mains-powered lights (1/120 s for 60 Hz, else 1/100 s). */
    private fun flickerSafeNs(): Long = if (lastFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) 8_333_333L else 10_000_000L

    private fun isoCap(info: Info): Int {
        val analogTwice = if (info.maxAnalogIso > 0) info.maxAnalogIso * 2 else info.isoMax
        return minOf(info.isoMax, analogTwice, 3200).coerceAtLeast(info.isoMin)
    }

    private fun exposureStep() {
        session ?: return
        requestBuilder ?: return
        val info = active ?: return
        val now = SystemClock.elapsedRealtime()
        val want = sharpMotion && info.manualSensor && !manualFailed
        when (ae) {
            Ae.AUTO -> {
                exposureInfo = if (lastExpNs > 0) "auto exposure %.0f ms, ISO %d".format(lastExpNs / 1e6, lastIso) else "auto exposure"
                if (!want || now - aeSince < 1500) return
                val e0 = lastExpNs; val s0 = lastIso
                val l = luma
                if (e0 <= 0L || s0 <= 0 || l.isNaN()) return
                val target = flickerSafeNs().coerceIn(info.exposureMinNs, info.exposureMaxNs)
                if (e0 <= target * 1.15) return // a bright room: auto exposure is already short
                val cap = isoCap(info)
                var exp = target
                var iso = (s0.toDouble() * e0 / target).toInt()
                if (iso > cap) { iso = cap; exp = (s0.toDouble() * e0 / cap).toLong().coerceIn(target, e0) }
                if (exp >= e0 * 0.8) return // too dark for a real gain
                applyManual(exp, iso)
                refLuma = l
                ae = Ae.MANUAL; aeSince = now; offSince = 0L
            }
            Ae.MANUAL -> {
                if (!want) { toAuto(); return }
                exposureInfo = "sharp-motion exposure %.1f ms, ISO %d".format(manualExp / 1e6, manualIso)
                val l = luma
                if (l.isNaN() || refLuma.isNaN()) return
                val ratio = refLuma / l.coerceAtLeast(1f)
                // the lights changed a lot: let auto exposure measure again
                if (ratio > 2.5f || ratio < 0.4f) {
                    if (offSince == 0L) offSince = now else if (now - offSince > 1200) { toAuto(); return }
                } else offSince = 0L
                if (kotlin.math.abs(kotlin.math.ln(ratio)) < 0.12f) return
                val gain = Math.pow(ratio.toDouble(), 0.6)
                val cap = isoCap(info)
                val iso = (manualIso * gain).toInt().coerceIn(info.isoMin, cap)
                var exp = manualExp
                val maxExp = (frameNs() - 1_000_000L).coerceAtMost(info.exposureMaxNs)
                if (iso == cap && gain > 1.05) exp = (exp * gain).toLong().coerceAtMost(maxExp) // as a last resort, a longer exposure
                if (iso == info.isoMin && gain < 0.95) exp = (exp * gain).toLong().coerceAtLeast(maxOf(info.exposureMinNs, 2_000_000L))
                if (iso != manualIso || exp != manualExp) applyManual(exp, iso)
            }
        }
    }

    private fun applyManual(exp: Long, iso: Int) {
        val s = session ?: return
        val b = requestBuilder ?: return
        val info = active ?: return
        manualExp = exp.coerceIn(info.exposureMinNs, info.exposureMaxNs)
        manualIso = iso.coerceIn(info.isoMin, info.isoMax)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExp)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs())
        s.setRepeatingRequest(b.build(), captureCb, handler)
    }

    private fun toAuto() {
        val s = session
        val b = requestBuilder
        ae = Ae.AUTO
        aeSince = SystemClock.elapsedRealtime()
        refLuma = Float.NaN
        if (s == null || b == null) return
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        s.setRepeatingRequest(b.build(), captureCb, handler)
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }
}
