package colourfilm.app.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import colourfilm.core.VideoColour

/** What we learned about a source video, and whether this phone can process it. */
data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationUs: Long,
    /** Coded (stored) picture size, before rotation. */
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Float,
    val videoMime: String,
    val hasDecoder: Boolean,
    val audioMime: String?,
    val audioDecodable: Boolean,
    val colour: VideoColour,
    /** Output picture size (even, within encoder limits); equals source unless noted. */
    val outWidth: Int,
    val outHeight: Int,
    val resizeReason: String?,
    val looksMonochrome: Boolean?,
    val problems: List<String>,
    val warnings: List<String>,
) {
    val displayWidth get() = if (rotation % 180 == 0) width else height
    val displayHeight get() = if (rotation % 180 == 0) height else width
    val canProcess get() = problems.isEmpty()
    val audioPassthrough get() = audioMime == MediaFormat.MIMETYPE_AUDIO_AAC
}

object VideoProbe {

    fun probe(context: Context, uri: Uri): VideoInfo {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var name = uri.lastPathSegment ?: "video"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }

        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            throw UnsupportedVideoException(
                "This file can't be opened as a video on this phone (${e.message ?: e.javaClass.simpleName}). " +
                    "It may be damaged, protected, or in a container Android doesn't read (MP4, MOV, MKV, WebM and 3GP are supported)."
            )
        }
        try {
            var vIndex = -1; var aIndex = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vIndex < 0 && mime.startsWith("video/")) vIndex = i
                if (aIndex < 0 && mime.startsWith("audio/")) aIndex = i
            }
            if (vIndex < 0) throw UnsupportedVideoException("No playable video was found in this file. It may be damaged, incomplete (for example an interrupted download), or not a video.")
            val vf = ex.getTrackFormat(vIndex)
            val vMime = vf.getString(MediaFormat.KEY_MIME)!!
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val decoderName = runCatching { codecList.findDecoderForFormat(stripForLookup(vf)) }.getOrNull()
            val hasDecoder = decoderName != null
            if (!hasDecoder) problems += "This phone has no decoder for the video codec ($vMime${profileText(vf)}). " +
                "Convert it to H.264 MP4 first (e.g. in CapCut or a desktop converter) and choose the converted file."

            var w = vf.getInteger(MediaFormat.KEY_WIDTH)
            var h = vf.getInteger(MediaFormat.KEY_HEIGHT)
            if (vf.containsKey("crop-right") && vf.containsKey("crop-left")) {
                w = vf.getInteger("crop-right") - vf.getInteger("crop-left") + 1
                h = vf.getInteger("crop-bottom") - vf.getInteger("crop-top") + 1
            }

            val mmr = MediaMetadataRetriever()
            var rotation = 0; var durationUs = 0L; var mono: Boolean? = null
            try {
                mmr.setDataSource(context, uri)
                rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                mono = runCatching { checkMonochrome(mmr, durationUs) }.getOrNull()
            } finally { runCatching { mmr.release() } }
            if (vf.containsKey(MediaFormat.KEY_ROTATION)) rotation = vf.getInteger(MediaFormat.KEY_ROTATION)
            if (durationUs <= 0 && vf.containsKey(MediaFormat.KEY_DURATION)) durationUs = vf.getLong(MediaFormat.KEY_DURATION)
            if (durationUs <= 0) problems += "The video's duration couldn't be read; the file may be incomplete or damaged."

            val fps = if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { vf.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                .getOrElse { runCatching { vf.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) } else 0f

            val colour = readColour(vf, h)
            if (vf.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                val tr = vf.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                if (tr == MediaFormat.COLOR_TRANSFER_ST2084 || tr == MediaFormat.COLOR_TRANSFER_HLG)
                    problems += "HDR video isn't supported. Export an SDR version first."
            }
            if (vf.containsKey("bits-per-sample") || vMime == MediaFormat.MIMETYPE_VIDEO_HEVC && profileIs10Bit(vf))
                warnings += "10-bit video will be exported as 8-bit H.264."

            // Output size: even dimensions, within the H.264 encoder's capabilities.
            val enc = EncoderSupport.pick(w and 1.inv(), h and 1.inv())
            var resize: String? = null
            if ((w and 1) != 0 || (h and 1) != 0) resize = "Odd picture size: the last row/column is trimmed to fit H.264 (${w}x$h → ${w and 1.inv()}x${h and 1.inv()})."
            if (enc == null) problems += "This phone has no H.264 encoder available for export."
            else if (enc.first != (w and 1.inv()) || enc.second != (h and 1.inv())) {
                resize = "This phone's H.264 encoder can't write ${w}x$h, so the export is scaled to ${enc.first}x${enc.second} (same aspect ratio, no cropping)."
            }

            var aMime: String? = null; var aOk = false
            if (aIndex >= 0) {
                val af = ex.getTrackFormat(aIndex)
                aMime = af.getString(MediaFormat.KEY_MIME)
                aOk = aMime == MediaFormat.MIMETYPE_AUDIO_AAC ||
                    runCatching { codecList.findDecoderForFormat(stripForLookup(af)) != null }.getOrDefault(false)
                if (!aOk) warnings += "The soundtrack ($aMime) can't be decoded on this phone, so the export will be silent."
            }
            if (mono == false) warnings += "This video already appears to contain colour. ColourFilm will replace its colour with AI-estimated colour."

            return VideoInfo(
                uri, name, size, durationUs, w, h, rotation, fps, vMime, hasDecoder, aMime, aOk, colour,
                enc?.first ?: (w and 1.inv()), enc?.second ?: (h and 1.inv()), resize, mono, problems, warnings,
            )
        } finally {
            ex.release()
        }
    }

    private fun profileText(f: MediaFormat) = if (f.containsKey(MediaFormat.KEY_PROFILE)) ", profile ${f.getInteger(MediaFormat.KEY_PROFILE)}" else ""

    private fun profileIs10Bit(f: MediaFormat) = f.containsKey(MediaFormat.KEY_PROFILE) &&
        f.getInteger(MediaFormat.KEY_PROFILE) == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10

    /** findDecoderForFormat rejects formats that carry a frame rate on some API levels. */
    private fun stripForLookup(f: MediaFormat): MediaFormat {
        val c = MediaFormat(f)
        c.setString(MediaFormat.KEY_FRAME_RATE, null)
        return c
    }

    fun readColour(f: MediaFormat, height: Int): VideoColour {
        val def = VideoColour.defaultFor(height)
        val matrix = if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) when (f.getInteger(MediaFormat.KEY_COLOR_STANDARD)) {
            MediaFormat.COLOR_STANDARD_BT709 -> VideoColour.Matrix.BT709
            MediaFormat.COLOR_STANDARD_BT2020 -> VideoColour.Matrix.BT2020
            MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> VideoColour.Matrix.BT601
            else -> def.matrix
        } else def.matrix
        val full = f.containsKey(MediaFormat.KEY_COLOR_RANGE) && f.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        return VideoColour(matrix, full)
    }

    /** Samples a few frames and checks whether they carry any real colour. */
    private fun checkMonochrome(mmr: MediaMetadataRetriever, durationUs: Long): Boolean? {
        var colourful = 0; var checked = 0
        for (k in 1..3) {
            val bmp = mmr.getScaledFrameAtTime(durationUs * k / 4, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96) ?: continue
            var sat = 0.0; var n = 0
            for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                sat += (maxOf(r, g, b) - minOf(r, g, b)); n++
            }
            bmp.recycle()
            checked++
            if (sat / n > 18) colourful++
        }
        return if (checked == 0) null else colourful < 2
    }
}

class UnsupportedVideoException(message: String) : Exception(message)

object EncoderSupport {
    /** Returns an encodable (w, h) no larger than requested, preserving aspect ratio. */
    fun pick(w: Int, h: Int): Pair<Int, Int>? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        if (infos.isEmpty()) return null
        val caps = infos.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .map { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
        for (c in caps) if (c.isSizeSupported(w, h)) return w to h
        // also try the transposed orientation limits by scaling down until supported
        var scale = 1.0
        while (scale > 0.2) {
            scale -= 0.05
            val sw = ((w * scale).toInt() and 15.inv()).coerceAtLeast(16)
            val sh = ((h * scale).toInt() and 1.inv()).coerceAtLeast(16)
            for (c in caps) if (c.isSizeSupported(sw, sh)) return sw to sh
        }
        return null
    }

    fun encoderName(w: Int, h: Int): String? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
        return infos.firstOrNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.isSizeSupported(w, h) }?.name
    }
}
