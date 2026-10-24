package com.clrg.core.tracking

import com.clrg.core.math.OneEuroFilter
import com.clrg.core.pose.Lm
import com.clrg.core.pose.PersonPose

/**
 * Per-landmark One Euro smoothing in frame units. Strong smoothing while still (no jitter on menus or dwell
 * targets), little lag during fast swings.
 */
class SmoothedPose(minCutoff: Float = 1.6f, beta: Float = 2.5f) {
    private val fx = Array(Lm.COUNT) { OneEuroFilter(minCutoff, beta, 1.0f) }
    private val fy = Array(Lm.COUNT) { OneEuroFilter(minCutoff, beta, 1.0f) }
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
        valid = false
    }

    fun update(p: PersonPose, dt: Float) {
        for (i in 0 until Lm.COUNT) {
            x[i] = fx[i].filter(p.x[i], dt)
            y[i] = fy[i].filter(p.y[i], dt)
            vx[i] = fx[i].derivative
            vy[i] = fy[i].derivative
            val v = p.vis(i)
            vis[i] = if (!valid) v else vis[i] + 0.5f * (v - vis[i])
        }
        valid = true
    }
}
