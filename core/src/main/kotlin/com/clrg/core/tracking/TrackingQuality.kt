package com.clrg.core.tracking

import com.clrg.core.pose.Lm

/**
 * A plain-language rating of how well one player is being tracked right now, with the single most useful tip.
 * Shown while players get into place so they can fix the usual problems (too far, too close, hands out of the
 * picture, poor light, a slow or hot phone) before playing.
 */
class TrackingQuality(val score: Float, val label: String, val tip: String?) {
    /** 0..5 filled bars. */
    val bars: Int get() = (score * 5f + 0.5f).toInt().coerceIn(0, 5)

    companion object {
        /**
         * @param inferenceFps measured pose updates per second (0 when unknown, e.g. simulated input).
         */
        fun of(slot: PlayerSlot, inferenceFps: Float): TrackingQuality {
            if (!slot.isPlaying || !slot.smooth.valid) return TrackingQuality(0f, "Not seen", "Step into the picture")
            val p = slot.smooth
            val arms = p.armConfidence()
            val wrists = minOf(p.vis[Lm.LEFT_WRIST], p.vis[Lm.RIGHT_WRIST])
            val s = slot.scale
            val sizeScore = when {
                s < 0.07f -> 0.35f
                s < 0.1f -> 0.7f
                s > 0.42f -> 0.5f
                else -> 1f
            }
            val fpsScore = if (inferenceFps <= 0f) 1f else ((inferenceFps - 10f) / 14f).coerceIn(0.3f, 1f)
            val score = (arms.coerceIn(0f, 1f) * 0.6f + wrists.coerceIn(0f, 1f) * 0.4f) * sizeScore * fpsScore
            val tip = when {
                wrists < 0.45f -> "Keep both hands in the picture"
                s < 0.1f -> "Come a little closer"
                s > 0.42f -> "Step back a little"
                slot.confidence < 0.55f || arms < 0.6f -> "More light please - avoid bright light behind you"
                inferenceFps in 0.1f..18f -> "Tracking is slow - let the phone cool or close other apps"
                else -> null
            }
            val label = when {
                score >= 0.8f -> "Excellent"
                score >= 0.62f -> "Good"
                score >= 0.42f -> "Fair"
                else -> "Weak"
            }
            return TrackingQuality(score, label, tip)
        }
    }
}
