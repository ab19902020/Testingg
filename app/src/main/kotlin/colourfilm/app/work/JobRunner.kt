package colourfilm.app.work

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.PowerManager
import android.os.StatFs
import colourfilm.app.media.Assembler
import colourfilm.app.media.SegmentFile
import colourfilm.app.media.SegmentRenderer
import colourfilm.app.media.VideoInfo
import colourfilm.app.media.VideoProbe
import colourfilm.app.model.ModelManager
import colourfilm.app.model.ModelSessions
import colourfilm.core.Parallel
import colourfilm.core.StorageEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Live progress of the running job, published for the UI and the notification. */
data class RunState(
    val jobId: String,
    val name: String,
    val phase: Phase,
    val message: String,
    /** Fraction of the video's duration processed (from real frame timestamps), or null if unknown. */
    val fraction: Float?,
    val processedUs: Long,
    val totalUs: Long,
    /** Remaining time from measured throughput; null until enough frames were measured. */
    val etaMs: Long?,
    val speedRatio: Float?,
    val backend: String?,
    val msPerModelRun: Double?,
    val thermal: String?,
) {
    enum class Phase { PREPARING, DOWNLOADING_MODEL, PROCESSING, COOLING, ASSEMBLING, DONE, FAILED, PAUSED, CANCELLED }
}

object RunBus {
    val state = MutableStateFlow<RunState?>(null)
    @Volatile var runningJobId: String? = null
}

/**
 * Executes a job: model setup → per-segment rendering with durable checkpoints → assembly
 * → validation. Throws [CancellationException] when cancelled or paused.
 */
class JobRunner(
    private val context: Context,
    private val store: JobStore,
    private val models: ModelManager,
    private val stopRequested: () -> StopReason?,
) {
    enum class StopReason { PAUSE, CANCEL, SYSTEM_TIMEOUT }

    private val power = context.getSystemService(PowerManager::class.java)

    fun run(initial: Job) {
        var job = initial.copy(status = JobStatus.RUNNING, error = null)
        store.save(job)
        fun publish(phase: RunState.Phase, msg: String, fraction: Float? = null, processedUs: Long = 0, eta: Long? = null,
                    ratio: Float? = null, backend: String? = null, ms: Double? = null) {
            RunBus.state.value = RunState(job.id, job.name, phase, msg, fraction, processedUs, job.durationUs, eta, ratio, backend, ms, thermalText())
        }

        publish(RunState.Phase.PREPARING, "Checking the video…")
        val info: VideoInfo = try { VideoProbe.probe(context, job.uri) } catch (e: SecurityException) {
            throw JobFailure("ColourFilm no longer has permission to read this video. Choose it again from Home.")
        } catch (e: Exception) {
            throw JobFailure("The source video can't be read any more (${e.message}). Was it moved or deleted?")
        }
        if (!info.canProcess) throw JobFailure(info.problems.joinToString("\n"))
        if (sourceKey(info) != job.sourceKey) {
            // The file changed since the job was created: never mix old segments into the new export.
            store.deleteIntermediates(job.id)
            job = job.copy(segments = emptyList(), sourceKey = sourceKey(info)); store.save(job)
        }

        // Storage check before doing any work.
        val fps = if (info.frameRate > 0) info.frameRate else 30f
        val vbps = StorageEstimate.videoBitrate(info.outWidth, info.outHeight, fps)
        val need = StorageEstimate.bytesNeeded(job.durationUs, vbps, if (info.audioMime != null) 192_000 else 0)
        val free = StatFs(context.filesDir.path).availableBytes
        if (free < need) throw JobFailure(
            "Not enough free storage. This export needs about ${need / 1_000_000} MB while working; ${free / 1_000_000} MB is free."
        )

        if (!models.isInstalled()) {
            publish(RunState.Phase.DOWNLOADING_MODEL, "Downloading the AI model (about 206 MB, one time)…")
            val ok = runBlocking { models.ensureDownloaded() }
            if (!ok) throw JobFailure("The AI model couldn't be downloaded: " +
                ((models.state.value as? ModelManager.State.Failed)?.message ?: "unknown error") + ". Check your connection and tap Resume.")
        }
        val loaded = ModelSessions.open(context, models, ModelSessions.Preference.AUTO) { publish(RunState.Phase.PREPARING, it) }
        job = job.copy(backend = loaded.backend); store.save(job)

        val plan = colourfilm.core.JobPlan(job.startUs, job.endUs, job.segmentUs)
        val done = job.segments.associateBy { it.index }.toMutableMap()
        var sessionFrames = 0L
        val sessionStart = System.nanoTime()
        var sessionMediaUs = 0L
        val parallel = Parallel()
        try {
            val renderer = SegmentRenderer(context, info, loaded.model, job.settings, parallel)
            for (i in 0 until plan.segmentCount) {
                if (done[i]?.let { it.frames == 0 || store.segmentFile(job.id, i).exists() } == true) continue
                val s0 = plan.segmentStart(i); val s1 = plan.segmentEnd(i)
                val prevState = if (i > 0 && done.containsKey(i - 1)) store.loadState(job.id, i - 1) else null
                val doneUs = done.keys.sumOf { plan.segmentEnd(it) - plan.segmentStart(it) }
                val segT0 = System.nanoTime()
                val res = renderer.render(s0, s1, store.segmentFile(job.id, i), prevState, isCancelled = {
                    stopRequested()?.let { throw StopException(it) }
                    waitIfHot(job, ::publish)
                    false
                }) { pts ->
                    sessionFrames++
                    val segUs = (pts - s0).coerceAtLeast(0)
                    val processed = doneUs + segUs
                    val elapsedMs = (System.nanoTime() - sessionStart) / 1_000_000
                    val mediaMs = (sessionMediaUs + segUs) / 1000
                    val ratio = if (sessionFrames >= 20 && elapsedMs > 3000) mediaMs.toFloat() / elapsedMs else null
                    val eta = ratio?.let { ((job.durationUs - processed) / 1000 / it).toLong() }
                    if (sessionFrames % 5 == 0L) publish(
                        RunState.Phase.PROCESSING, "Colourising", processed.toFloat() / job.durationUs, processed, eta, ratio,
                        loaded.backend, loaded.msPerRun,
                    )
                }
                sessionMediaUs += s1 - s0
                store.saveState(job.id, i, res.state)
                done[i] = SegmentRecord(i, res.frames, res.firstPtsUs, res.lastPtsUs)
                job = job.copy(segments = done.values.sortedBy { it.index }, framesDone = job.framesDone + res.frames,
                    processingMs = job.processingMs + (System.nanoTime() - segT0) / 1_000_000)
                store.save(job)
            }
        } finally {
            parallel.close()
            loaded.model.close()
        }

        publish(RunState.Phase.ASSEMBLING, "Adding the soundtrack and finishing the MP4…", 1f, job.durationUs)
        val segs = done.values.sortedBy { it.index }.filter { it.frames > 0 }.map { SegmentFile(store.segmentFile(job.id, it.index), it.firstPtsUs) }
        if (segs.isEmpty()) throw JobFailure("No video frames were found in the selected range.")
        val safeName = job.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "_").take(40)
        val out = File(store.exportsDir(), "${safeName}_colour${if (job.preview) "_preview" else ""}_${job.id.take(6)}.mp4")
        Assembler(context, info).assemble(segs, job.startUs, job.endUs, out, job.includeAudio, { stopRequested()?.let { throw StopException(it) }; false }) {}
        validateExport(out)
        job = job.copy(status = JobStatus.DONE, output = out.absolutePath); store.save(job)
        store.deleteIntermediates(job.id)
        publish(RunState.Phase.DONE, "Finished", 1f, job.durationUs)
    }

    private fun validateExport(f: File) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val v = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
                ?: throw JobFailure("The export has no H.264 video track.")
            ex.selectTrack((0 until ex.trackCount).first { ex.getTrackFormat(it) == v })
            val buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
            if (ex.readSampleData(buf, 0) <= 0) throw JobFailure("The export contains no video frames.")
        } catch (e: JobFailure) { f.delete(); throw e } catch (e: Exception) {
            f.delete(); throw JobFailure("The export file couldn't be verified: ${e.message}")
        } finally { ex.release() }
    }

    private fun thermalText(): String? = when (power.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> null
        PowerManager.THERMAL_STATUS_MODERATE -> "Phone is warm"
        PowerManager.THERMAL_STATUS_SEVERE -> "Phone is hot"
        else -> "Phone is very hot"
    }

    /** Pauses work while the phone reports severe thermal status, then continues. */
    private fun waitIfHot(job: Job, publish: (RunState.Phase, String, Float?, Long, Long?, Float?, String?, Double?) -> Unit) {
        if (power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) return
        publish(RunState.Phase.COOLING, "Phone is hot — waiting for it to cool down before continuing", null, 0, null, null, null, null)
        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            stopRequested()?.let { throw StopException(it) }
            Thread.sleep(3000)
        }
    }

    companion object {
        fun sourceKey(info: VideoInfo) = "${info.uri}|${info.sizeBytes}|${info.durationUs}|${info.width}x${info.height}"
    }
}

class JobFailure(message: String) : Exception(message)
class StopException(val reason: JobRunner.StopReason) : CancellationException(reason.name)
