package com.clrg.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import com.clrg.core.app.CameraOption
import com.clrg.core.app.CameraStatus
import com.clrg.core.app.DeviceInfo
import com.clrg.core.app.DisplayStatus
import com.clrg.core.app.PerfSnapshot
import com.clrg.core.app.PipelineDebug
import com.clrg.core.app.Platform
import com.clrg.core.app.PreviewFrame
import com.clrg.core.party.KeyValueStore
import com.clrg.core.party.Settings

/** Android implementation of the core [Platform] services. Methods are called on the render (game) thread. */
class AndroidPlatform(
    private val activity: Activity,
    override val store: KeyValueStore,
    private val camera: CameraSource,
    private val pose: PoseEngine,
    private val router: DisplayRouter,
    private val crashReport: String? = null,
) : Platform {
    private val device by lazy { DeviceCheck.info() }
    private val options by lazy { camera.options() }

    override fun cameraOptions(): List<CameraOption> = options
    override fun cameraStatus(): CameraStatus = camera.status

    fun hasCameraPermission() = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun requestCameraPermission() {
        activity.runOnUiThread { activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) }
    }

    override fun openAppSettings() {
        activity.runOnUiThread {
            val i = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        }
    }

    override fun applySettings(settings: Settings) {
        camera.configure(settings.cameraId, settings.wideAngle)
        // mirroring and orientation are per camera (phones differ, e.g. a front camera delivered already mirrored)
        val id = settings.cameraId?.takeIf { want -> camera.infos.any { it.id == want } } ?: camera.defaultId()
        pose.mirror = settings.mirrorFor(id)
        pose.upsideDown = settings.upsideDown(id)
        pose.quality = settings.quality
        pose.allowHighFps = settings.highFpsCamera
        camera.sharpMotion = settings.sharpMotion
        router.preferPresentation = settings.preferTvAsSecondScreen
    }

    override fun displayStatus(): DisplayStatus = router.status
    override fun deviceInfo(): DeviceInfo = device
    override fun perf(): PerfSnapshot = pose.perf()
    override fun preview(): PreviewFrame? = pose.preview()
    override fun pipelineDebug(): PipelineDebug? = pose.debug()
    override fun setSimulatedInput(on: Boolean) { pose.enabled = !on }
    override fun nowNanos(): Long = System.nanoTime()
    override fun previousCrash(): String? = crashReport
    override fun trackingProblem(): String? = pose.problem
    override fun setExpectedPeople(n: Int) { pose.expectedPeople = n }
    override fun setHeldObjects(models: Array<com.clrg.core.pose.ObjectColorModel?>, tracks: IntArray) {
        pose.held.models = models.copyOf()
        pose.held.tracks = tracks.copyOf()
    }
    override fun learnHeldObject(trackId: Int, player: Int) = pose.held.requestLearn(trackId, player)
    override fun cancelHeldLearning() = pose.held.cancelLearn()
    override fun heldLearnStatus(): com.clrg.core.pose.HeldLearnStatus? = pose.held.status
    override fun setHandTracking(tracks: IntArray, masks: IntArray) { pose.handTracks = tracks.copyOf(); pose.handMasks = masks.copyOf() }

    override fun shareText(subject: String, text: String) {
        activity.runOnUiThread {
            try {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
                activity.startActivity(Intent.createChooser(send, subject))
            } catch (t: Throwable) {
                android.util.Log.w("AndroidPlatform", "share failed", t)
            }
        }
    }
    override fun quit() { activity.runOnUiThread { activity.finish() } }

    companion object {
        const val REQ_CAMERA = 41
    }
}
