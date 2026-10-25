package com.clrg.core.tracking

import com.clrg.core.math.OneEuroFilter
import com.clrg.core.pose.Lm
import com.clrg.core.pose.PersonPose
import kotlin.math.hypot

/**
 * Per-landmark One Euro smoothing in frame units. Strong smoothing while still (no jitter on menus or dwell
 * targets), little lag during fast swings.
 *
 * The speed term is normalised by the player's body size, so a child far from the camera gets the same
 * responsiveness as an adult close to it (in frame units a far player's movements are small and would otherwise be
 * smoothed - and delayed - much more). Landmarks the model is unsure about are smoothed more, and physically
 * impossible jumps (a joint moving more than ~30 body lengths per second) are ignored for up to two frames.
 *
 * @param betaBody speed coefficient per body length per second; 0.6 matches the original tuning at a typical
 *   body size of a quarter of the frame height.
 */
class SmoothedPose(private val minCutoff: Float = 1.6f, private val betaBody: Float = 0.6f) {
    private val fx = Array(Lm.COUNT) { OneEuroFilter(minCutoff, 2.5f, 1.0f) }
    private val fy = Array(Lm.COUNT) { OneEuroFilter(minCutoff, 2.5f, 1.0f) }
    private val rejected = IntArray(Lm.COUNT)
    val x = FloatArray(Lm.COUNT)
    val y = FloatArray(Lm.COUNT)
    val vx = FloatArray(Lm.COUNT)
    val vy = FloatArray(Lm.COUNT)
    val vis = FloatArray(Lm.COUNT)
    var valid = false
        private set

    fun reset() {
        fx.forEach { it.reset() }
        fy.forEach { it.reset() }
        rejected.fill(0)
        valid = false
    }

    /** [scale] = the player's body size S in frame units (torso length). */
    fun update(p: PersonPose, dt: Float, scale: Float = 0.24f) {
        val s = scale.coerceIn(0.04f, 1f)
        for (i in 0 until Lm.COUNT) {
            val v = p.vis(i)
            if (valid && dt > 0f) {
                // reject a teleporting joint (a mis-detection), but never for long: real fast motion keeps going
                val speed = hypot(p.x[i] - x[i], p.y[i] - y[i]) / dt / s
                if (speed > MAX_BODY_SPEED && rejected[i] < 2) {
                    rejected[i]++
                    vis[i] += 0.5f * (minOf(v, vis[i]) - vis[i])
                    continue
                }
            }
            rejected[i] = 0
            // unsure landmarks: lower cutoff and speed gain (heavier smoothing); confident ones: normal tuning
            val conf = ((v - 0.2f) / 0.5f).coerceIn(0f, 1f)
            val cut = minCutoff * (0.45f + 0.55f * conf)
            val beta = betaBody / s * (0.35f + 0.65f * conf)
            fx[i].minCutoff = cut; fy[i].minCutoff = cut
            fx[i].beta = beta; fy[i].beta = beta
            x[i] = fx[i].filter(p.x[i], dt)
            y[i] = fy[i].filter(p.y[i], dt)
            vx[i] = fx[i].derivative
            vy[i] = fy[i].derivative
            vis[i] = if (!valid) v else vis[i] + 0.5f * (v - vis[i])
        }
        valid = true
    }

    /** Average jitter-free confidence of the joints the games rely on (shoulders, elbows, wrists). */
    fun armConfidence(): Float {
        var sum = 0f
        for (i in ARM_JOINTS) sum += vis[i]
        return sum / ARM_JOINTS.size
    }

    companion object {
        /** Body lengths per second above which a single-frame jump is treated as a mis-detection. */
        const val MAX_BODY_SPEED = 30f
        val ARM_JOINTS = intArrayOf(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, Lm.LEFT_WRIST, Lm.RIGHT_WRIST)
    }
}
