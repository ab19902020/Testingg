package colourfilm.app.media

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import colourfilm.core.ChromaModel
import colourfilm.core.ColouriseSettings
import colourfilm.core.FrameProcessor
import colourfilm.core.Parallel
import colourfilm.core.StorageEstimate
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/** Result of rendering one segment. */
data class SegmentResult(val frames: Int, val firstPtsUs: Long, val lastPtsUs: Long, val state: FloatArray?)

/**
 * Decodes one time range of the source with MediaCodec, colourises every frame with the
 * shared core, and encodes it as H.264 into a small video-only MP4.
 *
 * Original presentation timestamps are passed straight through, so variable-frame-rate
 * timing is preserved exactly. Memory is bounded: one decoded frame and one chroma frame.
 */
class SegmentRenderer(
    private val context: Context,
    private val info: VideoInfo,
    model: ChromaModel,
    settings: ColouriseSettings,
    private val parallel: Parallel,
) : AutoCloseable {
    private val outW = info.outWidth
    private val outH = info.outHeight
    private val processor = FrameProcessor(outW, outH, info.colour, model, settings, parallel)
    private val yBuf = ByteArray(outW * outH)
    private var srcBuf = ByteArray(0)
    private val uBuf = ByteArray((outW / 2) * (outH / 2))
    private val vBuf = ByteArray((outW / 2) * (outH / 2))
    val stats get() = processor.stats

    /**
     * Renders frames with presentation time in [startUs, endUs) to [out].
     * @param restoreState temporal state saved at the end of the previous segment (or null)
     * @param onFrame called after each frame with its presentation time
     */
    fun render(
        startUs: Long, endUs: Long, out: File, restoreState: FloatArray?,
        isCancelled: () -> Boolean, onFrame: (Long) -> Unit,
    ): SegmentResult {
        processor.importState(restoreState)
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var muxTrack = -1
        var frames = 0
        var firstPts = -1L; var lastPts = -1L
        try {
            ex.setDataSource(context, info.uri, null)
            val vTrack = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(vTrack)
            val inFmt = ex.getTrackFormat(vTrack)
            val decName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(MediaFormat(inFmt).apply { setString(MediaFormat.KEY_FRAME_RATE, null) })
                ?: throw IllegalStateException("No decoder for ${info.videoMime}")
            dec = MediaCodec.createByCodecName(decName)
            inFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            dec.configure(inFmt, null, null, 0)
            dec.start()
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val encName = EncoderSupport.encoderName(outW, outH) ?: throw IllegalStateException("No H.264 encoder for ${outW}x$outH")
            enc = MediaCodec.createByCodecName(encName)
            configureEncoder(enc)
            enc.start()

            val bi = MediaCodec.BufferInfo()
            val ebi = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var encEosSent = false
            var encDone = false
            var decOutFmt: MediaFormat? = null

            fun drainEncoder(timeoutUs: Long) {
                while (true) {
                    val idx = enc.dequeueOutputBuffer(ebi, timeoutUs)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            out.parentFile?.mkdirs()
                            mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            muxTrack = mux!!.addTrack(enc.outputFormat)
                            mux!!.start()
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)!!
                            if (ebi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) ebi.size = 0
                            if (ebi.size > 0 && mux != null) {
                                buf.position(ebi.offset); buf.limit(ebi.offset + ebi.size)
                                mux!!.writeSampleData(muxTrack, buf, ebi)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (ebi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encDone = true; return }
                        }
                    }
                }
            }

            fun sendEncoderEos() {
                if (encEosSent) return
                while (true) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        enc.queueInputBuffer(idx, 0, 0, maxOf(lastPts, 0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encEosSent = true; return
                    }
                    drainEncoder(0)
                }
            }

            while (!decodeDone) {
                if (isCancelled()) throw CancellationException("cancelled")
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oIdx = dec.dequeueOutputBuffer(bi, 5_000)
                if (oIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { decOutFmt = dec.outputFormat; continue }
                if (oIdx < 0) { drainEncoder(0); continue }
                val pts = bi.presentationTimeUs
                val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (bi.size > 0 && pts >= startUs && pts < endUs) {
                    readLuma(dec, oIdx, decOutFmt ?: dec.outputFormat)
                    dec.releaseOutputBuffer(oIdx, false)
                    processor.process(yBuf, 0, outW, uBuf, vBuf)
                    // hand the frame to the encoder
                    while (true) {
                        val ei = enc.dequeueInputBuffer(10_000)
                        if (ei >= 0) {
                            val img = enc.getInputImage(ei)
                            val size = if (img != null) { writeImage(img); outW * outH * 3 / 2 } else writeBuffer(enc.getInputBuffer(ei)!!, enc.inputFormat)
                            enc.queueInputBuffer(ei, 0, size, pts, 0)
                            break
                        }
                        drainEncoder(0)
                    }
                    if (firstPts < 0) firstPts = pts
                    lastPts = pts
                    frames++
                    onFrame(pts)
                    drainEncoder(0)
                } else {
                    dec.releaseOutputBuffer(oIdx, false)
                }
                if (eos || pts >= endUs) decodeDone = true
            }
            if (frames > 0) {
                sendEncoderEos()
                while (!encDone) drainEncoder(10_000)
            }
            return SegmentResult(frames, firstPts, lastPts, processor.exportState())
        } catch (t: Throwable) {
            runCatching { mux?.stop() }; runCatching { mux?.release() }; mux = null
            out.delete()
            throw t
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            runCatching { mux?.stop() }; runCatching { mux?.release() }
            ex.release()
        }
    }

    /**
     * Encoders differ in which optional keys they accept, so configure from the richest format
     * (High profile, VBR, colour tags) down to the minimal mandatory one.
     */
    private fun configureEncoder(enc: MediaCodec) {
        var last: Exception? = null
        for (level in 0..3) {
            val f = encoderFormat(enc, level)
            try {
                enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (level > 0) android.util.Log.i("SegmentRenderer", "encoder ${enc.name} accepted fallback format #$level: $f")
                return
            } catch (e: Exception) {
                last = e
                android.util.Log.w("SegmentRenderer", "encoder ${enc.name} rejected format #$level: $f")
                enc.reset()
            }
        }
        throw IllegalStateException("The phone's H.264 encoder (${enc.name}) refused every configuration for ${outW}x$outH.", last)
    }

    /** simplify: 0 = full, 1 = no profile/level/header repeat, 2 = also no bitrate mode/colour/B-frame keys, 3 = mandatory keys only. */
    private fun encoderFormat(enc: MediaCodec, simplify: Int = 0): MediaFormat {
        val fps = if (info.frameRate in 1f..240f) info.frameRate else 30f
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        f.setInteger(MediaFormat.KEY_BIT_RATE, StorageEstimate.videoBitrate(outW, outH, fps))
        f.setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(fps))
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        if (simplify >= 3) return f
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (simplify >= 2) return f
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, when (info.colour.matrix) {
            colourfilm.core.VideoColour.Matrix.BT709 -> MediaFormat.COLOR_STANDARD_BT709
            colourfilm.core.VideoColour.Matrix.BT2020 -> MediaFormat.COLOR_STANDARD_BT2020
            colourfilm.core.VideoColour.Matrix.BT601 -> MediaFormat.COLOR_STANDARD_BT601_PAL
        })
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, if (info.colour.fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        val caps = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (simplify >= 1) return f
        // Repeat SPS/PPS on keyframes so independently encoded segments always decode after joining.
        f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxByOrNull { it.level }?.let {
            f.setInteger(MediaFormat.KEY_PROFILE, it.profile)
            f.setInteger(MediaFormat.KEY_LEVEL, it.level)
        }
        return f
    }

    // ---- decoder output -> yBuf (outW x outH) ----

    private fun readLuma(dec: MediaCodec, idx: Int, fmt: MediaFormat) {
        val img = runCatching { dec.getOutputImage(idx) }.getOrNull()
        if (img != null) {
            img.use { readLumaFromImage(it) }
            return
        }
        // Fallback for decoders without Image support: planar or semi-planar 8-bit buffers.
        val buf = dec.getOutputBuffer(idx)!!
        val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val stride = if (fmt.containsKey(MediaFormat.KEY_STRIDE)) fmt.getInteger(MediaFormat.KEY_STRIDE) else w
        val left = if (fmt.containsKey("crop-left")) fmt.getInteger("crop-left") else 0
        val top = if (fmt.containsKey("crop-top")) fmt.getInteger("crop-top") else 0
        val cw = if (fmt.containsKey("crop-right")) fmt.getInteger("crop-right") - left + 1 else w
        val chh = if (fmt.containsKey("crop-bottom")) fmt.getInteger("crop-bottom") - top + 1 else fmt.getInteger(MediaFormat.KEY_HEIGHT)
        copyPlane(buf, buf.position() + top * stride + left, stride, 1, cw and 1.inv(), chh and 1.inv())
    }

    private fun readLumaFromImage(img: Image) {
        val crop = img.cropRect
        val p = img.planes[0]
        val is16 = img.format == ImageFormat.YCBCR_P010 || p.pixelStride == 2
        val buf = p.buffer
        val px = if (is16) 2 else p.pixelStride
        val base = crop.top * p.rowStride + crop.left * px + if (is16) 1 else 0 // high byte of LE 16-bit
        copyPlane(buf, base, p.rowStride, px, crop.width() and 1.inv(), crop.height() and 1.inv())
    }

    /** Copies (and if needed rescales) an 8-bit luma plane into yBuf at outW x outH. */
    private fun copyPlane(buf: ByteBuffer, base: Int, rowStride: Int, pixelStride: Int, w: Int, h: Int) {
        val direct = w == outW && h == outH
        val target = if (direct) yBuf else {
            if (srcBuf.size < w * h) srcBuf = ByteArray(w * h)
            srcBuf
        }
        val dup = buf.duplicate()
        if (pixelStride == 1) {
            for (y in 0 until h) { dup.position(base + y * rowStride); dup.get(target, y * w, w) }
        } else {
            for (y in 0 until h) {
                val r = base + y * rowStride
                for (x in 0 until w) target[y * w + x] = dup.get(r + x * pixelStride)
            }
        }
        if (!direct) scaleLuma(srcBuf, w, h, yBuf, outW, outH)
    }

    private fun scaleLuma(src: ByteArray, sw: Int, sh: Int, dst: ByteArray, dw: Int, dh: Int) {
        parallel.rows(dh) { r0, r1 ->
            for (y in r0 until r1) {
                val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, sh - 1f)
                val iy = minOf(fy.toInt(), sh - 2).coerceAtLeast(0); val wy = fy - iy
                for (x in 0 until dw) {
                    val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, sw - 1f)
                    val ix = minOf(fx.toInt(), sw - 2).coerceAtLeast(0); val wx = fx - ix
                    val a = src[iy * sw + ix].toInt() and 0xFF; val b = src[iy * sw + ix + 1].toInt() and 0xFF
                    val c = src[(iy + 1) * sw + ix].toInt() and 0xFF; val d = src[(iy + 1) * sw + ix + 1].toInt() and 0xFF
                    val t = a + (b - a) * wx; val bo = c + (d - c) * wx
                    dst[y * dw + x] = (t + (bo - t) * wy + 0.5f).toInt().toByte()
                }
            }
        }
    }

    // ---- yBuf/uBuf/vBuf -> encoder ----

    private fun writeImage(img: Image) {
        val cw = outW / 2; val ch = outH / 2
        writePlane(img.planes[0], yBuf, outW, outH)
        writePlane(img.planes[1], uBuf, cw, ch)
        writePlane(img.planes[2], vBuf, cw, ch)
    }

    private fun writePlane(p: Image.Plane, src: ByteArray, w: Int, h: Int) {
        val buf = p.buffer
        if (p.pixelStride == 1) {
            for (y in 0 until h) { buf.position(y * p.rowStride); buf.put(src, y * w, w) }
        } else {
            val ps = p.pixelStride
            for (y in 0 until h) {
                val r = y * p.rowStride
                for (x in 0 until w) buf.put(r + x * ps, src[y * w + x])
            }
        }
    }

    /** Legacy ByteBuffer input: I420 or NV12 depending on the encoder's colour format. */
    private fun writeBuffer(buf: ByteBuffer, fmt: MediaFormat): Int {
        val cf = fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        val stride = if (fmt.containsKey(MediaFormat.KEY_STRIDE)) fmt.getInteger(MediaFormat.KEY_STRIDE) else outW
        val slice = if (fmt.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) fmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else outH
        buf.clear()
        for (y in 0 until outH) { buf.position(y * stride); buf.put(yBuf, y * outW, outW) }
        val cw = outW / 2; val ch = outH / 2
        val cBase = stride * slice
        if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            for (y in 0 until ch) for (x in 0 until cw) {
                buf.put(cBase + y * stride + 2 * x, uBuf[y * cw + x]); buf.put(cBase + y * stride + 2 * x + 1, vBuf[y * cw + x])
            }
            return cBase + stride * ch
        }
        val cs = stride / 2
        for (y in 0 until ch) { buf.position(cBase + y * cs); buf.put(uBuf, y * cw, cw) }
        val vBase = cBase + cs * (slice / 2)
        for (y in 0 until ch) { buf.position(vBase + y * cs); buf.put(vBuf, y * cw, cw) }
        return vBase + cs * ch
    }

    override fun close() {}
}
