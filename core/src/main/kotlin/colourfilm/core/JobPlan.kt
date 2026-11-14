package colourfilm.core

import java.security.MessageDigest

/**
 * Splits a video timeline into fixed-length segments. Each segment is rendered to its own
 * small MP4 so that finished work survives the app being killed, and is reused on resume
 * only when the job fingerprint (source + model + settings + range) still matches.
 */
data class JobPlan(
    val startUs: Long,
    val endUs: Long,
    val segmentUs: Long = DEFAULT_SEGMENT_US,
) {
    init { require(endUs > startUs && segmentUs > 0) }

    val segmentCount: Int get() = (((endUs - startUs) + segmentUs - 1) / segmentUs).toInt()

    fun segmentStart(i: Int): Long = startUs + i * segmentUs
    fun segmentEnd(i: Int): Long = minOf(endUs, startUs + (i + 1) * segmentUs)

    /** Index of the segment containing presentation time [ptsUs] (clamped). */
    fun segmentOf(ptsUs: Long): Int = ((ptsUs - startUs) / segmentUs).toInt().coerceIn(0, segmentCount - 1)

    companion object {
        const val DEFAULT_SEGMENT_US = 15_000_000L

        fun fingerprint(sourceKey: String, modelId: String, settings: ColouriseSettings, plan: JobPlan, outW: Int, outH: Int): String {
            val text = "v2|$sourceKey|$modelId|${settings.fingerprint()}|${plan.startUs}-${plan.endUs}/${plan.segmentUs}|${outW}x$outH"
            val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return md.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}

/** Rough output-size estimate used for storage checks before export. */
object StorageEstimate {
    /** H.264 bitrate chosen for a given output size and frame rate (bits per second). */
    fun videoBitrate(width: Int, height: Int, fps: Float): Int {
        val pixelsPerSecond = width.toLong() * height * fps.coerceIn(10f, 60f)
        // ~0.12 bits per pixel: visually transparent for film grain at 1080p30 (~7.5 Mbps).
        return (pixelsPerSecond * 0.12).toLong().coerceIn(1_500_000L, 40_000_000L).toInt()
    }

    /** Bytes needed for segments + final file + margin. Segments are deleted after assembly. */
    fun bytesNeeded(durationUs: Long, videoBps: Int, audioBps: Int): Long {
        val seconds = durationUs / 1_000_000.0
        val one = seconds * (videoBps + audioBps) / 8.0
        return (one * 2.0 + 200L * 1024 * 1024).toLong()
    }
}
