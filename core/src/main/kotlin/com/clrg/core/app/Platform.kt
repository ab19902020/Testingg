package com.clrg.core.app

import com.clrg.core.party.KeyValueStore
import com.clrg.core.party.Settings
import com.clrg.core.render.ImageRef

data class CameraOption(val id: String, val front: Boolean, val label: String, val horizontalFovDeg: Float, val wideAvailable: Boolean)

enum class CameraState { NEEDS_PERMISSION, DENIED, OPENING, RUNNING, INTERRUPTED, ERROR, NO_CAMERA, STOPPED }

data class CameraStatus(val state: CameraState, val message: String = "", val activeId: String? = null)

data class DisplayStatus(
    /** An external display (HDMI/USB-C, or a wireless display) is attached. */
    val externalConnected: Boolean = false,
    val externalName: String? = null,
    val externalWidth: Int = 0,
    val externalHeight: Int = 0,
    val externalRefreshHz: Float = 0f,
    /** The game is being drawn on the external display as a second screen (Presentation). */
    val presentationActive: Boolean = false,
    /** True when the phone screen is the game screen (mirrored to the TV or no TV). */
    val phoneIsGameScreen: Boolean = true,
)

data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val soc: String? = null,
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    /** true/false when the chipset is recognised, null when unknown. */
    val meetsTarget: Boolean? = null,
    val note: String = "",
)

/** Live performance numbers for the developer overlay (all measured on the device, never invented). */
class PerfSnapshot(
    val cameraFps: Float = 0f,
    val inferenceFps: Float = 0f,
    val inferenceMs: Float = 0f,
    val preprocessMs: Float = 0f,
    val detectorMs: Float = 0f,
    val landmarkMs: Float = 0f,
    /** Camera capture -> pose available (ms), or NaN when the camera timestamp base is unknown. */
    val captureToResultMs: Float = Float.NaN,
    val droppedFrames: Long = 0,
    val delegate: String = "-",
    val model: String = "-",
    val inputSize: String = "-",
    val thermal: String = "-",
    val adaptiveLevel: String = "-",
)

/** Upright (non-mirrored) camera preview image plus whether it should be mirrored when shown. */
class PreviewFrame(val image: ImageRef, val mirror: Boolean)

/** Debug geometry from the pose pipeline, in frame units (see FrameGeometry). */
class PipelineDebug(val regions: List<FloatArray>, val rois: List<FloatArray>)

/** Services provided by the Android layer. All methods are called on the game thread. */
interface Platform {
    val store: KeyValueStore
    fun cameraOptions(): List<CameraOption>
    fun cameraStatus(): CameraStatus
    fun requestCameraPermission()
    fun openAppSettings()
    /** Applies camera/tracking settings (camera id, wide angle, mirror, quality). */
    fun applySettings(settings: Settings)
    fun displayStatus(): DisplayStatus
    fun deviceInfo(): DeviceInfo
    fun perf(): PerfSnapshot
    fun preview(): PreviewFrame?
    fun pipelineDebug(): PipelineDebug?
    /** Developer simulated input: stop/start feeding camera poses. */
    fun setSimulatedInput(on: Boolean)
    fun nowNanos(): Long
    fun quit()
    /** Report about the previous run if it ended in a crash (collected once at startup), else null. */
    fun previousCrash(): String? = null
    /** Plain-language reason why body tracking cannot run right now (e.g. the model runtime failed to load), else null. */
    fun trackingProblem(): String? = null
    /** Opens the system share sheet with [text] (only ever on the player's request). */
    fun shareText(subject: String, text: String) {}
    /** How many players the camera should follow (1 in solo and golf, 2 otherwise): tracking does less work for one. */
    fun setExpectedPeople(n: Int) {}
}

/** Platform stub for tests and headless tools. */
open class NullPlatform(override val store: KeyValueStore) : Platform {
    var camState = CameraStatus(CameraState.RUNNING)
    var display = DisplayStatus()
    var clock = 0L
    override fun cameraOptions() = listOf(CameraOption("0", false, "Back camera", 70f, false), CameraOption("1", true, "Front camera", 78f, false))
    override fun cameraStatus() = camState
    override fun requestCameraPermission() {}
    override fun openAppSettings() {}
    override fun applySettings(settings: Settings) {}
    override fun displayStatus() = display
    override fun deviceInfo() = DeviceInfo("Test", "JVM", null, "-", 0, null, "")
    override fun perf() = PerfSnapshot()
    override fun preview(): PreviewFrame? = null
    override fun pipelineDebug(): PipelineDebug? = null
    override fun setSimulatedInput(on: Boolean) {}
    override fun nowNanos() = clock
    override fun quit() {}
    var crash: String? = null
    var problem: String? = null
    val shared = ArrayList<String>()
    override fun previousCrash() = crash
    override fun trackingProblem() = problem
    override fun shareText(subject: String, text: String) { shared.add(text) }
}
