package colourfilm.app

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import colourfilm.app.media.VideoInfo
import colourfilm.app.media.VideoProbe
import colourfilm.app.work.ColouriseService
import colourfilm.app.work.Job
import colourfilm.app.work.JobRunner
import colourfilm.app.work.JobStatus
import colourfilm.app.work.RunBus
import colourfilm.app.work.RunState
import colourfilm.core.ColouriseSettings
import colourfilm.core.JobPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Review(val info: VideoInfo) : Screen
    data class Processing(val jobId: String) : Screen
    data class Result(val jobId: String) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val cf = app as ColourFilmApp
    val store = cf.jobs
    val models = cf.models

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen
    private val _jobs = MutableStateFlow(store.all())
    val jobs: StateFlow<List<Job>> = _jobs
    val run: StateFlow<RunState?> = RunBus.state
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    var settings = MutableStateFlow(ColouriseSettings())
    var includeAudio = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            RunBus.state.collect { refreshJobs() }
        }
    }

    fun refreshJobs() { viewModelScope.launch(Dispatchers.IO) { _jobs.value = store.all() } }

    fun go(s: Screen) { _screen.value = s; refreshJobs() }

    fun back(): Boolean {
        if (_screen.value is Screen.Home) return false
        go(Screen.Home); return true
    }

    fun openVideo(uri: Uri) {
        val ctx = getApplication<Application>()
        // Keep read access so interrupted jobs can resume after a restart. Not every provider
        // grants persistable access; processing still works for the current session.
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        busy.value = true
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { VideoProbe.probe(ctx, uri) }
                go(Screen.Review(info))
            } catch (e: Exception) {
                message.value = e.message ?: "This video couldn't be opened."
            } finally { busy.value = false }
        }
    }

    /** Creates (or reuses) a job and starts the foreground service. */
    fun start(info: VideoInfo, previewAtUs: Long? = null) {
        val st = settings.value
        val (s0, s1) = if (previewAtUs != null) {
            val start = previewAtUs.coerceIn(0, maxOf(0, info.durationUs - 1))
            start to minOf(info.durationUs, start + PREVIEW_US)
        } else 0L to info.durationUs
        val plan = JobPlan(s0, maxOf(s1, s0 + 1))
        val modelId = "ddcolor-tiny-0.66.0"
        val id = JobPlan.fingerprint(info.uri.toString(), modelId, st, plan, info.outWidth, info.outHeight) +
            if (includeAudio.value) "" else "-na"
        val existing = store.load(id)
        val job = if (existing != null && existing.status != JobStatus.DONE) existing.copy(status = JobStatus.QUEUED)
        else Job(id, info.uri, info.displayName, JobRunner.sourceKey(info), plan.startUs, plan.endUs, plan.segmentUs, st,
            preview = previewAtUs != null, includeAudio = includeAudio.value, status = JobStatus.QUEUED)
        if (existing?.status == JobStatus.DONE && existing.output?.let { File(it).exists() } == true) {
            go(Screen.Result(id)); return
        }
        store.save(job)
        RunBus.state.value = RunState(id, job.name, RunState.Phase.PREPARING, "Starting…", null, 0, job.durationUs, null, null, null, null, null)
        ColouriseService.start(getApplication(), id)
        go(Screen.Processing(id))
    }

    fun resume(job: Job) {
        store.save(job.copy(status = JobStatus.QUEUED))
        RunBus.state.value = RunState(job.id, job.name, RunState.Phase.PREPARING, "Resuming…", null, 0, job.durationUs, null, null, null, null, null)
        ColouriseService.start(getApplication(), job.id)
        go(Screen.Processing(job.id))
    }

    fun pause() = ColouriseService.send(getApplication(), ColouriseService.ACTION_PAUSE)

    fun cancel(jobId: String) {
        if (RunBus.runningJobId == jobId) ColouriseService.send(getApplication(), ColouriseService.ACTION_CANCEL, jobId)
        else store.delete(jobId)
        go(Screen.Home)
    }

    fun delete(job: Job) {
        if (RunBus.runningJobId == job.id) return
        job.output?.let { File(it).delete() }
        store.delete(job.id)
        refreshJobs()
    }

    fun openJob(job: Job) {
        when {
            job.id == RunBus.runningJobId -> go(Screen.Processing(job.id))
            job.status == JobStatus.DONE -> go(Screen.Result(job.id))
            else -> go(Screen.Processing(job.id))
        }
    }

    /** Copies the export into the shared Movies/ColourFilm folder via MediaStore. */
    fun saveToGallery(job: Job) {
        val ctx = getApplication<Application>()
        val src = File(job.output ?: return)
        viewModelScope.launch {
            busy.value = true
            try {
                val uri = withContext(Dispatchers.IO) {
                    val free = StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
                    if (free < src.length() + 20_000_000) throw IllegalStateException(
                        "Not enough free storage to save (${src.length() / 1_000_000} MB needed, ${free / 1_000_000} MB free).")
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ColourFilm")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val r = ctx.contentResolver
                    val dest = r.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                        ?: throw IllegalStateException("The gallery refused the new video.")
                    try {
                        r.openOutputStream(dest)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 20) } }
                        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        r.update(dest, values, null, null)
                    } catch (e: Exception) { r.delete(dest, null, null); throw e }
                    dest
                }
                store.save(job.copy(savedUri = uri.toString()))
                refreshJobs()
                message.value = "Saved to Movies/ColourFilm"
            } catch (e: Exception) {
                message.value = e.message ?: "Couldn't save the video."
            } finally { busy.value = false }
        }
    }

    fun shareIntent(job: Job): Intent? {
        val ctx = getApplication<Application>()
        val f = File(job.output ?: return null)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share colour video")
    }

    companion object { const val PREVIEW_US = 5_000_000L }
}
