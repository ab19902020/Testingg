package com.clrg.core.gesture

import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Punches for boxing, from the camera's front view. A punch is a short burst of fist speed; when the burst ends its
 * direction says what it was:
 *  - **straight** (jab / cross): towards the camera. The body model's depth for the wrist drops, and the forearm
 *    looks shorter on screen as it points at the camera (that 2D cue backs up the less precise depth);
 *  - **hook**: round from the side, across in front of the face;
 *  - **uppercut**: upwards from below the chin.
 * Pulling a fist back (away from the camera, outwards or down) is never a punch. Guard (both fists up in front of
 * the face) is reported continuously.
 */
class PunchDetector {
    /** Both fists up in front of the face. */
    var guard = false
        private set
    private val run = Array(2) { Run() }
    private val lastT = DoubleArray(2) { Double.NaN }
    private val lastX = FloatArray(2)
    private val lastY = FloatArray(2)
    private val lastZ = FloatArray(2)
    private val speed = FloatArray(2)
    private val cooldownUntil = DoubleArray(2) { -1.0 }
    private val pdx = FloatArray(2)
    private val pdy = FloatArray(2)
    private val pdz = FloatArray(2)

    private class Run {
        var active = false
        var t0 = 0.0
        var x0 = 0f; var y0 = 0f; var z0 = 0f
        var fore0 = 0f
        var elbowY0 = 0f
        var peakV = 0f
        var peakT = 0.0
        var path = 0f
    }

    fun reset() {
        guard = false
        for (h in 0..1) { run[h].active = false; lastT[h] = Double.NaN; speed[h] = 0f; cooldownUntil[h] = -1.0 }
    }

    fun update(hist: BodyHistory, reach: Float, mode: MovementMode, sensitivity: Float, out: MutableList<GestureEvent>) {
        val f = hist.latest ?: return
        val k = (if (mode == MovementMode.STANDARD) 1f else REDUCED) / sensitivity.coerceIn(0.5f, 2f) * (reach / 1.5f).coerceIn(0.75f, 1.3f)
        guard = (0..1).all { h -> f.wv[h] > 0.3f && f.wy[h] > -0.25f && abs(f.wx[h]) < 0.55f }
        for (h in 0..1) {
            val ok = f.rwv[h] > 0.25f
            if (!ok) { finish(h, f, reach, k, out); lastT[h] = Double.NaN; continue }
            val x = f.rwx[h]; val y = f.rwy[h]; val z = f.rwz[h] * Z_WEIGHT
            val prevT = lastT[h]
            lastT[h] = f.t
            if (prevT.isNaN() || f.t - prevT > 0.2 || f.t - prevT <= 1e-4) { lastX[h] = x; lastY[h] = y; lastZ[h] = z; speed[h] = 0f; continue }
            val dt = (f.t - prevT).toFloat()
            val dx = x - lastX[h]; val dy = y - lastY[h]; val dz = z - lastZ[h]
            val v = sqrt(dx * dx + dy * dy + dz * dz) / dt
            speed[h] += 0.7f * (v - speed[h])
            val r = run[h]
            if (!r.active && speed[h] > START_V * k) {
                // start from the previous frame: that is where the fist was before it took off
                r.active = true; r.t0 = prevT
                r.x0 = lastX[h]; r.y0 = lastY[h]; r.z0 = lastZ[h]
                r.fore0 = foreLen(hist.get(1), h); r.elbowY0 = hist.get(1).ey[h]
                r.peakV = 0f; r.path = 0f
            }
            // a wind-up then the punch (or two punches back to back): when the fist turns back on itself at speed,
            // what came before is judged on its own and the next punch starts from the turn
            val stepV = sqrt(dx * dx + dy * dy + dz * dz) / dt
            if (r.active && stepV > START_V * k &&
                dx * pdx[h] + dy * pdy[h] + dz * pdz[h] < -0.3f * sqrt(dx * dx + dy * dy + dz * dz) * sqrt(pdx[h] * pdx[h] + pdy[h] * pdy[h] + pdz[h] * pdz[h])) {
                finish(h, hist.get(1), reach, k, out)
                r.active = true; r.t0 = prevT; r.x0 = lastX[h]; r.y0 = lastY[h]; r.z0 = lastZ[h]
                r.fore0 = foreLen(hist.get(1), h); r.elbowY0 = hist.get(1).ey[h]
                r.peakV = stepV; r.peakT = f.t; r.path = 0f
            }
            pdx[h] = dx; pdy[h] = dy; pdz[h] = dz
            if (r.active) {
                r.path += sqrt(dx * dx + dy * dy + dz * dz)
                if (speed[h] > r.peakV) { r.peakV = speed[h]; r.peakT = f.t }
            }
            lastX[h] = x; lastY[h] = y; lastZ[h] = z
            if (r.active && (speed[h] < END_V * k || f.t - r.t0 > MAX_SEC)) finish(h, f, reach, k, out)
        }
    }

    private fun foreLen(f: BodyFrame, h: Int) = hypot(f.rwx[h] - f.rex[h], f.rwy[h] - f.rey[h])

    private fun finish(h: Int, f: BodyFrame, reach: Float, k: Float, out: MutableList<GestureEvent>) {
        val r = run[h]
        if (!r.active) return
        r.active = false
        if (f.t < cooldownUntil[h]) return
        val dx = lastX[h] - r.x0; val dy = lastY[h] - r.y0; val dz = lastZ[h] - r.z0
        if (r.peakV < PEAK_V * k) return
        val inward = if (h == RIGHT) -dx else dx
        // towards the camera: depth dropping, backed up by the forearm looking shorter and the elbow coming up
        val shrink = (r.fore0 - foreLen(f, h)).coerceAtLeast(0f)
        val elbowUp = (f.ey[h] - r.elbowY0).coerceAtLeast(0f)
        val forward = -dz + FORE_W * shrink + ELBOW_W * elbowUp
        val disp = max(forward, max(inward, dy))
        if (disp < MIN_DISP * k) return
        // the fist should end up in front of the body at a sensible height (not dropping to the side)
        if (lastY[h] < MIN_END_Y) return
        val kind = when {
            forward >= inward * 0.85f && forward >= dy * 0.85f -> PunchKind.STRAIGHT
            inward >= dy -> PunchKind.HOOK
            else -> PunchKind.UPPERCUT
        }
        val power = ((r.peakV / k - PEAK_V) / (PEAK_V * 1.6f)).coerceIn(0f, 1f) * 0.7f + 0.3f
        out.add(GestureEvent.Punch(f.t, h, kind, power, lastX[h] / reach, lastY[h] / reach))
        cooldownUntil[h] = f.t + COOLDOWN_SEC
    }

    companion object {
        /** Depth from the body model counts this much (it is less precise than left/right and up/down). */
        const val Z_WEIGHT = 0.7f
        const val FORE_W = 0.9f
        const val ELBOW_W = 0.5f
        /** Burst of fist speed (body units per second): starts above, ends below, must peak above. */
        const val START_V = 1.4f
        const val END_V = 0.8f
        const val PEAK_V = 2.6f
        /** At least this far (body units) in the punch's direction. */
        const val MIN_DISP = 0.32f
        const val MAX_SEC = 0.4
        const val COOLDOWN_SEC = 0.18
        const val MIN_END_Y = -0.75f
        /** Reduced-movement / seated players: shorter, slower punches count. */
        const val REDUCED = 0.6f
    }
}
