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
        }

        fun fixture(name: String): Uri {
            val f = File(ctx.cacheDir, name)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
            return Uri.fromFile(f)
        }
    }

    private fun job(uri: Uri, segmentUs: Long = 15_000_000, speed: ColouriseSettings.Speed = ColouriseSettings.Speed.FAST): Job {
        val info = VideoProbe.probe(ctx, uri)
        val id = "test-" + uri.lastPathSegment!!.replace('.', '-') + "-" + segmentUs
        store.delete(id)
        return Job(id, uri, info.displayName, JobRunner.sourceKey(info), 0, info.durationUs, segmentUs,
            ColouriseSettings(speed = speed), preview = false, includeAudio = true, status = JobStatus.QUEUED)
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
        assertEquals(src.first(), t.firstUs)
        assertEquals(src.last(), t.lastUs)
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
