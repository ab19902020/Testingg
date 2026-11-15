package colourfilm.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import colourfilm.app.media.UnsupportedVideoException
import colourfilm.app.media.VideoProbe
import colourfilm.app.model.ModelManager
import colourfilm.app.work.Job
import colourfilm.app.work.JobRunner
import colourfilm.app.work.JobStatus
import colourfilm.app.work.JobStore
import colourfilm.app.work.StopException
import colourfilm.core.ColouriseSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end pipeline tests on a device/emulator: MediaExtractor/MediaCodec decode → real
 * DDColor model → MediaCodec H.264 encode → MediaMuxer, plus audio and resume handling.
 *
 * Requires the model files pushed beforehand (see tools/run_device_tests.sh):
 *   /data/local/tmp/ddcolor.onnx and /data/local/tmp/ddcolor.data
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    companion object {
        private const val TAG = "PipelineTest"
        lateinit var ctx: Context
        lateinit var store: JobStore
        lateinit var models: ModelManager

        @BeforeClass @JvmStatic
        fun setup() {
            ctx = InstrumentationRegistry.getInstrumentation().targetContext
            store = JobStore(ctx)
            models = ModelManager(ctx)
            if (!models.isInstalled()) {
                models.dir.mkdirs()
                for (n in listOf("ddcolor.onnx", "ddcolor.data")) File("/data/local/tmp/$n").copyTo(File(models.dir, n), true)
                File(models.dir, "ok").writeText("test")
            }
            assertTrue("model files missing", models.isInstalled())
            // Software-emulated devices run DDColor at minutes per frame. Unless the runner is given
            // -e realModel true, use a fast deterministic stand-in that still varies colour with
            // picture content, so the Android media pipeline itself can be tested end to end.
            val real = InstrumentationRegistry.getArguments().getString("realModel") == "true"
            if (!real) colourfilm.app.model.ModelSessions.overrideForTests = {
                colourfilm.app.model.LoadedModel(StandInModel(), "stand-in (test)", 0.0, null)
            }
        }

        fun fixture(name: String): Uri {
            val f = File(ctx.cacheDir, name)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
            return Uri.fromFile(f)
        }
    }

    /** Cheap model: brightness-dependent warm/cool colouring, 256x256 like DDColor. */
    class StandInModel : colourfilm.core.ChromaModel {
        override val inputSize = 256
        override val id = "stand-in"
        override fun predict(grey: FloatArray, outAb: FloatArray) {
            val n = inputSize * inputSize
            for (i in 0 until n) { outAb[i] = 40f * (grey[i] - 0.4f); outAb[n + i] = 30f * (0.5f - grey[i]) + 15f }
        }
    }

    private fun job(uri: Uri, segmentUs: Long = 15_000_000, speed: ColouriseSettings.Speed = ColouriseSettings.Speed.FAST,
                    smooth: ColouriseSettings.Smooth = ColouriseSettings.Smooth.OFF): Job {
        val info = VideoProbe.probe(ctx, uri)
        val id = "test-" + uri.lastPathSegment!!.replace('.', '-') + "-" + segmentUs
        store.delete(id)
        return Job(id, uri, info.displayName, JobRunner.sourceKey(info), 0, info.durationUs, segmentUs,
            ColouriseSettings(speed = speed, smooth = smooth), preview = false, includeAudio = true, status = JobStatus.QUEUED)
    }

    private fun run(j: Job, stop: () -> JobRunner.StopReason? = { null }): Job {
        val t = System.nanoTime()
        JobRunner(ctx, store, models, stop).run(j)
        val done = store.load(j.id)!!
        Log.i(TAG, "${j.name}: ${done.framesDone} frames in ${(System.nanoTime() - t) / 1_000_000} ms backend=${done.backend}")
        // keep a copy for host-side inspection
        File(done.output!!).copyTo(File(ctx.getExternalFilesDir(null), "test_" + File(done.output!!).name), true)
        return done
    }

    data class Tracks(val video: MediaFormat?, val audio: MediaFormat?, val frames: Int, val firstUs: Long, val lastUs: Long, val pts: List<Long>)

    private fun tracks(path: String): Tracks {
        val ex = MediaExtractor(); ex.setDataSource(path)
        var v: MediaFormat? = null; var a: MediaFormat? = null; var vi = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i); val m = f.getString(MediaFormat.KEY_MIME)!!
            if (m.startsWith("video/")) { v = f; vi = i } else if (m.startsWith("audio/")) a = f
        }
        val pts = ArrayList<Long>()
        if (vi >= 0) { ex.selectTrack(vi); while (ex.sampleTime >= 0) { pts += ex.sampleTime; ex.advance() } }
        ex.release()
        pts.sort()
        return Tracks(v, a, pts.size, pts.firstOrNull() ?: -1, pts.lastOrNull() ?: -1, pts)
    }

    private fun sourcePts(uri: Uri): List<Long> {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val vi = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(vi)
        val pts = ArrayList<Long>(); while (ex.sampleTime >= 0) { pts += ex.sampleTime; ex.advance() }
        ex.release(); pts.sort(); return pts
    }

    /** Mean max-min channel spread of a decoded frame (0 for greyscale). */
    private fun colourfulness(path: String, atUs: Long): Double {
        val m = MediaMetadataRetriever(); m.setDataSource(path)
        val b: Bitmap = m.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        m.release()
        var s = 0.0; var n = 0
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) {
            val p = b.getPixel(x, y); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
            s += maxOf(r, g, bl) - minOf(r, g, bl); n++
        }
        return s / n
    }

    @Test
    fun videoWithAacAudio_multiSegment() {
        val uri = fixture("bw_audio.mp4")
        val done = run(job(uri, segmentUs = 1_500_000))
        assertEquals(JobStatus.DONE, done.status)
        val t = tracks(done.output!!)
        val src = sourcePts(uri)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, t.video!!.getString(MediaFormat.KEY_MIME))
        assertEquals("every source frame exported once", src.size, t.frames)
        // MP4 stores timestamps in track timescale units, so allow sub-millisecond rounding.
        assertEquals(src.first().toDouble(), t.firstUs.toDouble(), 1000.0)
        assertEquals(src.last().toDouble(), t.lastUs.toDouble(), 1000.0)
        assertNotNull("audio kept", t.audio)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, t.audio!!.getString(MediaFormat.KEY_MIME))
        val c = colourfulness(done.output!!, 2_000_000)
        Log.i(TAG, "colourfulness out=$c")
        assertTrue("output has colour ($c)", c > 4.0)
    }

    @Test
    fun portraitSilent_rotationPreserved() {
        val uri = fixture("portrait_silent.mp4")
        val info = VideoProbe.probe(ctx, uri)
        val done = run(job(uri))
        val m = MediaMetadataRetriever(); m.setDataSource(done.output!!)
        val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
        m.release()
        assertEquals(info.rotation, rot)
        val t = tracks(done.output!!)
        assertNull("silent source stays silent", t.audio)
        assertEquals(sourcePts(uri).size, t.frames)
    }

    @Test
    fun variableFrameRateMkvWithMp3_timestampsKeptAndAudioTranscoded() {
        val uri = fixture("vfr.mkv")
        val done = run(job(uri))
        val t = tracks(done.output!!)
        val src = sourcePts(uri)
        assertEquals(src.size, t.frames)
        for (i in src.indices) assertTrue("pts $i ${src[i]} vs ${t.pts[i]}", kotlin.math.abs(src[i] - t.pts[i]) <= 1000)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, t.audio?.getString(MediaFormat.KEY_MIME))
    }

    @Test
    fun movWithPcmAudio() {
        val done = run(job(fixture("pcm.mov")))
        val t = tracks(done.output!!)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, t.audio?.getString(MediaFormat.KEY_MIME))
        assertTrue(t.frames > 0)
    }

    /** Real DDColor on the device (run with -e realModel true; minutes per frame on emulators). */
    @Test
    fun realModelShortClip() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val uri = fixture("bw_audio.mp4")
        val j = job(uri).let { it.copy(id = it.id + "-real", endUs = 300_000) }
        val done = run(j)
        assertTrue(done.backend!!.contains("CPU") || done.backend!!.contains("NPU"))
        val t = tracks(done.output!!)
        assertTrue(t.frames in 6..9)
        val c = colourfulness(done.output!!, 100_000)
        Log.i(TAG, "real model colourfulness=$c backend=${done.backend}")
        assertTrue("real model output has colour ($c)", c > 3.0)
    }

    /** The real app path: activity in front, foreground service started, job runs to a finished MP4. */
    @Test
    fun foregroundServiceRunsJobToCompletion() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(
            android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val j = job(fixture("portrait_silent.mp4")).let { it.copy(id = it.id + "-svc") }
        store.delete(j.id)
        store.save(j)
        colourfilm.app.work.ColouriseService.start(ctx, j.id)
        val deadline = System.currentTimeMillis() + 8 * 60_000
        var last: Job? = null
        while (System.currentTimeMillis() < deadline) {
            last = store.load(j.id)
            if (last?.status == JobStatus.DONE || last?.status == JobStatus.FAILED) break
            Thread.sleep(2000)
        }
        activity.finish()
        assertEquals("service job error: ${last?.error}", JobStatus.DONE, last?.status)
        assertTrue(File(last!!.output!!).length() > 10_000)
        assertEquals(sourcePts(j.uri).size, tracks(last.output!!).frames)
    }

    /**
     * The whole user flow through the real screens: video opened → Review → Colourise →
     * Processing screen with live progress → Result screen. A crash in any screen fails the run.
     */
    @Test
    fun fullUiFlowReviewProcessingResult() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val uri = fixture("bw_audio.mp4")
        val before = store.all().map { it.id }.toSet()
        // Launch exactly as a user/share would, via the shell (allowed on every Android version,
        // and not subject to startActivitySync's 45 s limit on slow emulators).
        inst.uiAutomation.executeShellCommand(
            "am start -n ${ctx.packageName}/colourfilm.app.MainActivity -a android.intent.action.SEND -t video/mp4 " +
                "--eu android.intent.extra.STREAM $uri --ez colourfilm.autostart true").close()
        val deadline = System.currentTimeMillis() + 14 * 60_000
        var j: Job? = null
        while (System.currentTimeMillis() < deadline) {
            j = store.all().firstOrNull { it.id !in before }
            if (j?.status == JobStatus.DONE || j?.status == JobStatus.FAILED) break
            Thread.sleep(2000)
        }
        assertEquals("job error: ${j?.error}", JobStatus.DONE, j?.status)
        Thread.sleep(15_000) // let the Result screen load its player and before/after frames
        // Reaching this line means no screen crashed the process (a crash would abort the run).
        store.delete(j!!.id)
    }

    @Test
    fun frameRateDoublingAddsInBetweenFramesAcrossSegments() {
        val uri = fixture("bw_audio.mp4")
        val src = sourcePts(uri)
        val done = run(job(uri, segmentUs = 1_500_000, smooth = ColouriseSettings.Smooth.DOUBLE).let { it.copy(id = it.id + "-x2") })
        val t = tracks(done.output!!)
        Log.i(TAG, "doubling: source ${src.size} frames -> ${t.frames}")
        // every source frame, plus up to one new frame per gap (none across the clip's scene cuts)
        assertTrue("frames ${t.frames}", t.frames > src.size * 3 / 2 && t.frames <= 2 * src.size - 1)
        for (i in 1 until t.pts.size) assertTrue("timestamps increase at $i", t.pts[i] > t.pts[i - 1])
        assertEquals(src.first().toDouble(), t.firstUs.toDouble(), 1000.0)
        assertEquals(src.last().toDouble(), t.lastUs.toDouble(), 1000.0)
        assertNotNull(t.audio)
    }

    @Test
    fun softwareCodecFallbackProducesVideo() {
        SafeMode.setSoftCodecs(ctx, true)
        try {
            val uri = fixture("portrait_silent.mp4")
            val done = run(job(uri).let { it.copy(id = it.id + "-soft") })
            assertEquals(sourcePts(uri).size, tracks(done.output!!).frames)
            assertEquals("stage reset after job", "idle", SafeMode.stage(ctx))
        } finally { SafeMode.setSoftCodecs(ctx, false) }
    }

    @Test
    fun interruptedStageSwitchesOffRiskyComponent() {
        SafeMode.setNpuEnabled(ctx, true)
        SafeMode.enter(ctx, "run:npu+hw")
        assertTrue(SafeMode.recover(ctx)!!.contains("AI chip"))
        assertTrue(!SafeMode.npuEnabled(ctx))
        SafeMode.enter(ctx, "run:cpu+hw")
        assertTrue(SafeMode.recover(ctx)!!.contains("codecs"))
        assertTrue(SafeMode.softCodecs(ctx))
        SafeMode.setSoftCodecs(ctx, false)
        assertEquals(null, SafeMode.recover(ctx))
        assertTrue(CrashReporter.diagnostics(ctx).contains("ColourFilm"))
    }

    @Test
    fun bundledModelUnpacksFromApk() {
        org.junit.Assume.assumeTrue(models.bundled)
        models.dir.deleteRecursively()
        assertTrue(!models.isInstalled())
        val ok = kotlinx.coroutines.runBlocking { models.ensureInstalled() }
        assertTrue("unpack failed: ${models.state.value}", ok)
        assertEquals(ModelManager.DATA_BYTES, File(models.dir, "ddcolor.data").length())
    }

    @Test
    fun damagedFileIsRejectedWithExplanation() {
        try {
            VideoProbe.probe(ctx, fixture("damaged.mp4"))
            fail("damaged file should not probe")
        } catch (e: UnsupportedVideoException) {
            Log.i(TAG, "damaged: ${e.message}")
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun pauseThenResumeReusesFinishedSegments() {
        val uri = fixture("bw_audio.mp4")
        val j = job(uri, segmentUs = 1_000_000)
        try {
            JobRunner(ctx, store, models) {
                if ((store.load(j.id)?.segments?.size ?: 0) >= 2) JobRunner.StopReason.PAUSE else null
            }.run(j)
            fail("expected pause")
        } catch (e: StopException) { }
        val paused = store.load(j.id)!!
        val keptSegments = paused.segments.size
        assertTrue(keptSegments >= 2)
        val firstFrames = paused.framesDone
        val done = run(paused.copy(status = JobStatus.QUEUED))
        val t = tracks(done.output!!)
        assertEquals(sourcePts(uri).size, t.frames)
        assertEquals("no frame rendered twice", t.frames.toLong(), done.framesDone)
        assertTrue(firstFrames > 0)
    }

    @Test
    fun cancellationStopsAndCleansUp() {
        val j = job(fixture("bw_audio.mp4"), segmentUs = 1_000_000)
        var calls = 0
        try {
            JobRunner(ctx, store, models) { if (++calls > 10) JobRunner.StopReason.CANCEL else null }.run(j)
            fail("expected cancel")
        } catch (e: StopException) {
            assertEquals(JobRunner.StopReason.CANCEL, e.reason)
        }
        store.delete(j.id)
        assertTrue(!store.dir(j.id).exists())
    }
}
