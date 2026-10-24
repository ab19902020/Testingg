package com.clrg.core.tracking

import com.clrg.core.pose.Lm
import com.clrg.core.pose.PersonPose
import kotlin.math.hypot

/**
 * Body measurements of one detected person in frame units. The body scale S (shoulder-centre to hip-centre
 * distance, i.e. torso length) is the unit every gesture threshold is expressed in, so tall/short players and
 * near/far positions behave the same.
 */
class BodyMetrics private constructor(
    val pose: PersonPose,
    val shoulderX: Float,
    val shoulderY: Float,
    val shoulderWidth: Float,
    val hipsVisible: Boolean,
    val hipX: Float,
    val hipY: Float,
    /** Torso length (shoulder centre to hip centre) if hips visible, else NaN. */
    val torso: Float,
    /** Body scale S (frame units). */
    val scale: Float,
    /** 0..1: how well the upper body (shoulders, elbows, wrists) is seen. */
    val confidence: Float,
) {
    companion object {
        const val DEFAULT_TORSO_RATIO = 1.35f

        /**
         * @param torsoRatio torso / shoulder width for this person (calibrated) - used when the hips are hidden,
         *                   e.g. a seated player behind a coffee table.
         */
        fun of(p: PersonPose, torsoRatio: Float = DEFAULT_TORSO_RATIO): BodyMetrics? {
            val ls = Lm.LEFT_SHOULDER
            val rs = Lm.RIGHT_SHOULDER
            if (p.vis(ls) < 0.35f || p.vis(rs) < 0.35f) return null
            val sx = (p.x[ls] + p.x[rs]) / 2f
            val sy = (p.y[ls] + p.y[rs]) / 2f
            val sw = hypot(p.x[ls] - p.x[rs], p.y[ls] - p.y[rs])
            val hipsVisible = p.vis(Lm.LEFT_HIP) >= 0.5f && p.vis(Lm.RIGHT_HIP) >= 0.5f
            val hx = if (hipsVisible) (p.x[Lm.LEFT_HIP] + p.x[Lm.RIGHT_HIP]) / 2f else sx
            val hy = if (hipsVisible) (p.y[Lm.LEFT_HIP] + p.y[Lm.RIGHT_HIP]) / 2f else sy + sw * torsoRatio
            val torso = if (hipsVisible) hypot(hx - sx, hy - sy) else Float.NaN
            // Torso length is robust to turning sideways; fall back to shoulder width (and guard against a
            // shoulder width collapsed by turning) when hips are hidden.
            var scale = if (hipsVisible) torso else sw * torsoRatio
            if (hipsVisible && sw * torsoRatio > 1.6f * torso) scale = (torso + sw * torsoRatio) / 2f // leaning toward camera
            if (scale < 0.02f) return null
            var conf = 0f
            for (i in Lm.CORE_UPPER) conf += p.vis(i)
            conf = conf / Lm.CORE_UPPER.size * (0.5f + 0.5f * p.score)
            return BodyMetrics(p, sx, sy, sw, hipsVisible, hx, hy, torso, scale, conf)
        }
    }
}
