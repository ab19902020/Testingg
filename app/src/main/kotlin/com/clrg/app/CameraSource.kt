package com.clrg.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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

    class Info(val id: String, val front: Boolean, val sensorOrientation: Int, val hFovDeg: Float, val zoomMin: Float, val realtimeTimestamps: Boolean)

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
            Info(id, facing == CameraCharacteristics.LENS_FACING_FRONT, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, hfov, zoomMin, ts)
        }
    } catch (e: Exception) {
        Log.e(TAG, "enumerate failed", e)
        emptyList()
    }

    fun options(): List<CameraOption> = infos.map {
        CameraOption(it.id, it.front, if (it.front) "Front camera" else "Back camera ${it.id}", it.hFovDeg * (if (it.zoomMin < 0.99f) 1f else 1f), it.zoomMin < 0.99f)
    }

    /** Default: the widest back camera (it can be placed facing the players), otherwise the front one. */
    private fun defaultId(): String? = infos.filter { !it.front }.maxByOrNull { it.hFovDeg }?.id ?: infos.firstOrNull()?.id

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

    private fun chooseFps(id: String): Range<Int>? {
        val ranges = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // prefer a fixed or near-fixed 30 fps range (steady timing); fall back to the highest upper bound
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper * 100 + it.lower }
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
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(r.surface)
                        chooseFps(info.id)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        if (Build.VERSION.SDK_INT >= 30 && wantWide && info.zoomMin < 0.99f) set(CaptureRequest.CONTROL_ZOOM_RATIO, info.zoomMin)
                    }
                    try {
                        s.setRepeatingRequest(req.build(), null, handler)
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
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        active = null
        val r = reader
        reader = null
        // waits for the consumer to finish copying any image it holds from this reader
        synchronized(frameLock) { try { r?.close() } catch (_: Throwable) {} }
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }
}
