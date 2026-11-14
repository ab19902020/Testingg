package colourfilm.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/** A finished segment on disk and the source presentation time of its first frame. */
data class SegmentFile(val file: File, val firstPtsUs: Long)

/**
 * Joins colourised segments into the final MP4 and adds the original soundtrack.
 *
 * AAC audio is copied bit-for-bit. Other decodable audio (AC-3, MP3, Opus, PCM, …) is
 * re-encoded to AAC-LC 192 kb/s. Timestamps on both tracks are the source's presentation
 * times minus the export start, so picture and sound stay in sync.
 */
class Assembler(private val context: Context, private val info: VideoInfo) {

    fun assemble(
        segments: List<SegmentFile>, startUs: Long, endUs: Long, out: File,
        includeAudio: Boolean, isCancelled: () -> Boolean, onProgress: (Float) -> Unit,
    ) {
        require(segments.isNotEmpty()) { "No video frames were produced in the selected range." }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.delete()
        val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            mux.setOrientationHint(info.rotation)
            // video format from the first segment
            val vFmt = MediaExtractor().run {
                setDataSource(segments.first().file.absolutePath)
                val f = getTrackFormat(0); release(); f
            }
            val vTrack = mux.addTrack(vFmt)
            val audio = if (includeAudio && info.audioMime != null && info.audioDecodable) AudioSource(context, info, startUs, endUs) else null
            val aTrack = audio?.let { mux.addTrack(it.outputFormat) } ?: -1
            mux.start(); started = true

            // Interleave by writing video segment-by-segment and audio up to the same time.
            val buf = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            segments.forEachIndexed { si, seg ->
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(seg.file.absolutePath)
                    ex.selectTrack(0)
                    var base = Long.MIN_VALUE
                    while (true) {
                        if (isCancelled()) throw CancellationException("cancelled")
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val t = ex.sampleTime
                        if (base == Long.MIN_VALUE) base = t
                        bi.set(0, n, (t - base) + seg.firstPtsUs - startUs, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        mux.writeSampleData(vTrack, buf, bi)
                        audio?.writeUntil(mux, aTrack, bi.presentationTimeUs)
                        ex.advance()
                    }
                } finally { ex.release() }
                onProgress((si + 1f) / segments.size)
            }
            audio?.writeUntil(mux, aTrack, Long.MAX_VALUE)
            audio?.close()
            mux.stop(); started = false
            mux.release()
            if (!tmp.renameTo(out)) throw IllegalStateException("Couldn't finalise the export file")
        } catch (t: Throwable) {
            if (started) runCatching { mux.stop() }
            runCatching { mux.release() }
            tmp.delete()
            throw t
        }
    }
}

/** Supplies audio samples for [startUs, endUs): passthrough for AAC, else decode + AAC encode. */
private class AudioSource(context: Context, info: VideoInfo, private val startUs: Long, private val endUs: Long) : AutoCloseable {
    private val ex = MediaExtractor()
    private val track: Int
    private val srcFmt: MediaFormat
    private val passthrough = info.audioPassthrough
    private val buf = ByteBuffer.allocateDirect(1 shl 20)
    private var done = false

    // transcoding state
    private var dec: MediaCodec? = null
    private var enc: MediaCodec? = null
    private var decInDone = false
    private var decOutDone = false
    private var encDone = false
    private var encFmt: MediaFormat? = null
    private val pending = ArrayDeque<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
    private var encodedPts = 0L

    val outputFormat: MediaFormat

    init {
        ex.setDataSource(context, info.uri, null)
        track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(track)
        srcFmt = ex.getTrackFormat(track)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        outputFormat = if (passthrough) srcFmt else setupTranscode()
    }

    private fun setupTranscode(): MediaFormat {
        val sr = srcFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val chIn = srcFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val d = MediaCodec.createByCodecName(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(srcFmt)!!)
        // AAC-LC encoders on Android reliably handle mono/stereo; downmix requests above that.
        if (chIn > 2) srcFmt.setInteger("max-output-channel-count", 2)
        d.configure(srcFmt, null, null, 0); d.start(); dec = d
        val ch = minOf(chIn, 2)
        val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch)
        ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        ef.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        e.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); e.start(); enc = e
        // Pull until the encoder reports its real output format (needed before muxer.start()).
        while (encFmt == null && !encDone) pump()
        return encFmt ?: throw IllegalStateException("Audio encoder produced no format")
    }

    /** Writes audio samples with output time <= [untilUs] (relative to export start). */
    fun writeUntil(mux: MediaMuxer, trackIdx: Int, untilUs: Long) {
        if (passthrough) {
            val bi = MediaCodec.BufferInfo()
            while (!done) {
                val t = ex.sampleTime
                if (t < 0 || t >= endUs) { done = true; break }
                if (t - startUs > untilUs) break
                val n = ex.readSampleData(buf, 0)
                if (n < 0) { done = true; break }
                if (t >= startUs) {
                    bi.set(0, n, t - startUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    mux.writeSampleData(trackIdx, buf, bi)
                }
                ex.advance()
            }
            return
        }
        while (true) {
            while (pending.isNotEmpty() && pending.first().second.presentationTimeUs <= untilUs) {
                val (b, i) = pending.removeFirst()
                mux.writeSampleData(trackIdx, b, i)
            }
            if (encDone || (pending.isNotEmpty() && pending.first().second.presentationTimeUs > untilUs)) return
            pump()
        }
    }

    /** One step of extractor -> decoder -> (trim) -> encoder -> pending queue. */
    private fun pump() {
        val d = dec!!; val e = enc!!
        if (!decInDone) {
            val i = d.dequeueInputBuffer(2_000)
            if (i >= 0) {
                val n = ex.readSampleData(d.getInputBuffer(i)!!, 0)
                val t = ex.sampleTime
                if (n < 0 || t >= endUs) { d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); decInDone = true }
                else { d.queueInputBuffer(i, 0, n, t, 0); ex.advance() }
            }
        }
        if (!decOutDone) {
            val bi = MediaCodec.BufferInfo()
            val o = d.dequeueOutputBuffer(bi, 2_000)
            if (o >= 0) {
                val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pcm = d.getOutputBuffer(o)!!
                if (bi.size > 0 && bi.presentationTimeUs >= startUs && bi.presentationTimeUs < endUs) {
                    pcm.position(bi.offset); pcm.limit(bi.offset + bi.size)
                    while (pcm.hasRemaining()) {
                        val ei = e.dequeueInputBuffer(5_000)
                        if (ei < 0) { drainEncoder(); continue }
                        val inBuf = e.getInputBuffer(ei)!!
                        val n = minOf(inBuf.remaining(), pcm.remaining())
                        val slice = pcm.duplicate(); slice.limit(pcm.position() + n)
                        inBuf.put(slice)
                        pcm.position(pcm.position() + n)
                        e.queueInputBuffer(ei, 0, n, bi.presentationTimeUs - startUs, 0)
                    }
                }
                d.releaseOutputBuffer(o, false)
                if (eos) {
                    decOutDone = true
                    while (true) {
                        val ei = e.dequeueInputBuffer(5_000)
                        if (ei >= 0) { e.queueInputBuffer(ei, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                        drainEncoder()
                    }
                }
            }
        }
        drainEncoder()
    }

    private fun drainEncoder() {
        val e = enc!!
        val bi = MediaCodec.BufferInfo()
        while (true) {
            val o = e.dequeueOutputBuffer(bi, if (decOutDone) 5_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> encFmt = e.outputFormat
                o >= 0 -> {
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bi.size > 0) {
                        val src = e.getOutputBuffer(o)!!
                        src.position(bi.offset); src.limit(bi.offset + bi.size)
                        val copy = ByteBuffer.allocateDirect(bi.size).put(src).also { it.flip() }
                        val info = MediaCodec.BufferInfo()
                        // keep timestamps strictly increasing for the muxer
                        val pts = maxOf(bi.presentationTimeUs, encodedPts)
                        encodedPts = pts + 1
                        info.set(0, bi.size, pts, 0)
                        pending.addLast(copy to info)
                    }
                    e.releaseOutputBuffer(o, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encDone = true; return }
                }
            }
        }
    }

    override fun close() {
        runCatching { dec?.stop() }; runCatching { dec?.release() }
        runCatching { enc?.stop() }; runCatching { enc?.release() }
        ex.release()
    }
}
