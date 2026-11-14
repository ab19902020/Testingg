package colourfilm.app.work

import android.content.Context
import android.net.Uri
import colourfilm.core.ColouriseSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

enum class JobStatus { QUEUED, RUNNING, PAUSED, INTERRUPTED, DONE, FAILED, CANCELLED }

data class SegmentRecord(val index: Int, val frames: Int, val firstPtsUs: Long, val lastPtsUs: Long)

/** Durable description of one colourisation job (survives process death and reboots). */
data class Job(
    val id: String,
    val uri: Uri,
    val name: String,
    val sourceKey: String,
    val startUs: Long,
    val endUs: Long,
    val segmentUs: Long,
    val settings: ColouriseSettings,
    val preview: Boolean,
    val includeAudio: Boolean,
    val status: JobStatus,
    val segments: List<SegmentRecord> = emptyList(),
    val output: String? = null,
    val error: String? = null,
    val backend: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val framesDone: Long = 0,
    val processingMs: Long = 0,
    val savedUri: String? = null,
) {
    val durationUs get() = endUs - startUs

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("uri", uri.toString()); put("name", name); put("sourceKey", sourceKey)
        put("startUs", startUs); put("endUs", endUs); put("segmentUs", segmentUs)
        put("intensity", settings.intensity.toDouble()); put("look", settings.look.name); put("speed", settings.speed.name)
        put("stability", settings.stability.name); put("edgeRefine", settings.edgeRefine)
        put("preview", preview); put("includeAudio", includeAudio); put("status", status.name)
        put("segments", JSONArray().apply {
            segments.forEach { put(JSONObject().put("i", it.index).put("n", it.frames).put("f", it.firstPtsUs).put("l", it.lastPtsUs)) }
        })
        output?.let { put("output", it) }; error?.let { put("error", it) }; backend?.let { put("backend", it) }
        savedUri?.let { put("savedUri", it) }
        put("createdAt", createdAt); put("updatedAt", updatedAt); put("framesDone", framesDone); put("processingMs", processingMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = Job(
            id = o.getString("id"), uri = Uri.parse(o.getString("uri")), name = o.getString("name"),
            sourceKey = o.getString("sourceKey"), startUs = o.getLong("startUs"), endUs = o.getLong("endUs"),
            segmentUs = o.getLong("segmentUs"),
            settings = ColouriseSettings(
                intensity = o.getDouble("intensity").toFloat(),
                look = ColouriseSettings.Look.valueOf(o.getString("look")),
                speed = ColouriseSettings.Speed.valueOf(o.getString("speed")),
                stability = ColouriseSettings.Stability.valueOf(o.getString("stability")),
                edgeRefine = o.getBoolean("edgeRefine"),
            ),
            preview = o.getBoolean("preview"), includeAudio = o.optBoolean("includeAudio", true),
            status = JobStatus.valueOf(o.getString("status")),
            segments = o.getJSONArray("segments").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { s -> SegmentRecord(s.getInt("i"), s.getInt("n"), s.getLong("f"), s.getLong("l")) } }
            },
            output = o.optString("output").ifEmpty { null }, error = o.optString("error").ifEmpty { null },
            backend = o.optString("backend").ifEmpty { null }, savedUri = o.optString("savedUri").ifEmpty { null },
            createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt"),
            framesDone = o.optLong("framesDone"), processingMs = o.optLong("processingMs"),
        )
    }
}

/** Stores jobs as JSON + segment files under filesDir/jobs/<id>/, written atomically. */
class JobStore(private val context: Context) {
    private val root = File(context.filesDir, "jobs").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun segmentFile(id: String, i: Int) = File(dir(id), "seg_%05d.mp4".format(i))
    private fun stateFile(id: String, i: Int) = File(dir(id), "state_%05d.bin".format(i))

    @Synchronized
    fun save(job: Job) {
        val d = dir(job.id).apply { mkdirs() }
        val tmp = File(d, "job.json.tmp")
        tmp.writeText(job.copy(updatedAt = System.currentTimeMillis()).toJson().toString())
        tmp.renameTo(File(d, "job.json"))
    }

    @Synchronized
    fun load(id: String): Job? = runCatching { Job.fromJson(JSONObject(File(dir(id), "job.json").readText())) }.getOrNull()

    @Synchronized
    fun all(): List<Job> = (root.listFiles() ?: emptyArray()).mapNotNull { load(it.name) }.sortedByDescending { it.updatedAt }

    fun delete(id: String) { dir(id).deleteRecursively() }

    /** Removes everything except the job description and the final export. */
    fun deleteIntermediates(id: String) {
        dir(id).listFiles()?.forEach { if (it.name.startsWith("seg_") || it.name.startsWith("state_")) it.delete() }
    }

    fun saveState(id: String, i: Int, s: FloatArray?) {
        val f = stateFile(id, i)
        if (s == null) { f.delete(); return }
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o -> o.writeInt(s.size); s.forEach { o.writeFloat(it) } }
        tmp.renameTo(f)
    }

    fun loadState(id: String, i: Int): FloatArray? = runCatching {
        DataInputStream(stateFile(id, i).inputStream().buffered()).use { inp -> FloatArray(inp.readInt()) { inp.readFloat() } }
    }.getOrNull()

    /** On app start: a job marked RUNNING without a live service was interrupted. */
    fun markOrphansInterrupted(running: String?) {
        all().filter { (it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED) && it.id != running }
            .forEach { save(it.copy(status = JobStatus.INTERRUPTED)) }
    }

    fun exportsDir(): File = (context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "exports")).apply { mkdirs() }
}
