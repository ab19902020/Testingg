package com.clrg.core.gesture

import com.clrg.core.pose.HandLm
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The shape of one hand from its 21 finger landmarks, measured in 3D so it does not depend on how big the hand
 * looks or which way it is turned: how bent each finger is and how far the thumb is from the side of the index
 * finger. Values measured on real photos with the MediaPipe hand model (see RealModelHandTest): a straight finger
 * bends 2-10 degrees, a curled one 140-160; a thumb laid against the fingers is 0.1-0.25 palm lengths from the index
 * finger, a raised thumb 0.9 or more.
 */
class HandShape {
    var valid = false
        private set
    /** Bend at the middle joint of each finger in degrees (0 = straight): index, middle, ring, pinky. */
    val bend = FloatArray(4)
    /** Fingertip distance from the wrist in palm lengths: index, middle, ring, pinky. */
    val reach = FloatArray(4)
    /** Thumb tip distance from the side of the index finger (its knuckle to middle joint), in palm lengths. */
    var thumbGap = 0f
        private set

    /** Measures [n]: x, y, z interleaved (21 * 3 values) in any uniform units. */
    fun measure(p: FloatArray) {
        val palm = dist(p, HandLm.WRIST, HandLm.MIDDLE_MCP)
        if (palm < 1e-6f) { valid = false; return }
        for (f in 0 until 4) {
            val mcp = FINGER_MCP[f]
            bend[f] = angleDeg(p, mcp, mcp + 1, mcp + 3)
            reach[f] = dist(p, HandLm.WRIST, mcp + 3) / palm
        }
        thumbGap = segDist(p, HandLm.THUMB_TIP, HandLm.INDEX_MCP, HandLm.INDEX_PIP) / palm
        valid = true
    }

    fun extended(f: Int) = valid && bend[f] < STRAIGHT_DEG && reach[f] > 1.25f
    fun curled(f: Int) = valid && (bend[f] > CURLED_DEG || reach[f] < 1.05f)

    /** Index finger out, ring and pinky curled in (the middle finger may join the index: a two-finger gun). */
    val gun: Boolean get() = valid && extended(0) && curled(2) && curled(3) && (curled(1) || extended(1))

    companion object {
        private val FINGER_MCP = intArrayOf(HandLm.INDEX_MCP, HandLm.MIDDLE_MCP, HandLm.RING_MCP, HandLm.PINKY_MCP)
        const val STRAIGHT_DEG = 45f
        const val CURLED_DEG = 80f

        private fun dist(p: FloatArray, a: Int, b: Int): Float {
            val dx = p[a * 3] - p[b * 3]; val dy = p[a * 3 + 1] - p[b * 3 + 1]; val dz = p[a * 3 + 2] - p[b * 3 + 2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** Angle between segments a->b and b->c (0 = straight on). */
        private fun angleDeg(p: FloatArray, a: Int, b: Int, c: Int): Float {
            val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[b * 3]; val vy = p[c * 3 + 1] - p[b * 3 + 1]; val vz = p[c * 3 + 2] - p[b * 3 + 2]
            val lu = sqrt(ux * ux + uy * uy + uz * uz); val lv = sqrt(vx * vx + vy * vy + vz * vz)
            if (lu < 1e-6f || lv < 1e-6f) return 0f
            val cos = ((ux * vx + uy * vy + uz * vz) / (lu * lv)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }

        /** Distance from point [q] to the segment [a]-[b]. */
        private fun segDist(p: FloatArray, q: Int, a: Int, b: Int): Float {
            val abx = p[b * 3] - p[a * 3]; val aby = p[b * 3 + 1] - p[a * 3 + 1]; val abz = p[b * 3 + 2] - p[a * 3 + 2]
            val aqx = p[q * 3] - p[a * 3]; val aqy = p[q * 3 + 1] - p[a * 3 + 1]; val aqz = p[q * 3 + 2] - p[a * 3 + 2]
            val ab2 = abx * abx + aby * aby + abz * abz
            val t = if (ab2 < 1e-9f) 0f else ((aqx * abx + aqy * aby + aqz * abz) / ab2).coerceIn(0f, 1f)
            val dx = aqx - t * abx; val dy = aqy - t * aby; val dz = aqz - t * abz
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}

/**
 * Finger-gun trigger for one hand: point with the index finger, raise the thumb ("cocked"), and drop the thumb onto
 * the side of the hand to fire. The thumb has to be seen up for a moment before a drop counts, so a hand that simply
 * stays closed never fires, and it has to come up again before the next shot. The shot is stamped with the moment the
 * thumb passed halfway down (between camera frames), so games can judge it by when it really happened.
 */
class FingerTrigger {
    /** The hand is making a gun (index out, other fingers in), held through a frame or two of doubt. */
    var gun = false
        private set
    /** Thumb raised, ready to fire. */
    var cocked = false
        private set
    /** Seconds since the hand model last saw this hand. */
    var unseenSec = 99f
        private set
    private var gunVotes = 0
    private var cockedSince = -1.0
    private var lastGap = Float.NaN
    private var lastT = 0.0
    private var lastShot = -9.0

    fun reset() { gun = false; cocked = false; gunVotes = 0; cockedSince = -1.0; lastGap = Float.NaN; unseenSec = 99f }

    /** No finger data this frame. */
    fun unseen(dt: Float) {
        unseenSec += dt
        if (unseenSec > LOST_SEC) { gun = false; gunVotes = 0; cocked = false; lastGap = Float.NaN }
    }

    /** One frame of finger data at time [t]. Returns the shot time when the thumb was just dropped, else NaN. */
    fun update(shape: HandShape, t: Double): Double {
        unseenSec = 0f
        gunVotes = if (shape.gun) minOf(gunVotes + 1, 4) else maxOf(gunVotes - 1, -3)
        if (gunVotes >= 2) gun = true else if (gunVotes <= -2) gun = false
        val gap = shape.thumbGap
        var shot = Double.NaN
        if (!cocked) {
            if (gap > OPEN_GAP) { cocked = true; cockedSince = t }
        } else if (gap < CLOSED_GAP) {
            cocked = false
            // fire only from a gun that was cocked for a moment (not a hand flicking open and closed)
            if (gun && t - cockedSince >= MIN_COCKED_SEC && t - lastShot >= MIN_GAP_SEC) {
                val prev = lastGap
                val mid = (OPEN_GAP + CLOSED_GAP) / 2f
                // when did the thumb pass halfway? interpolate between the last two frames
                val f = if (!prev.isNaN() && prev > gap && prev > mid && gap < mid) ((prev - mid) / (prev - gap)).toDouble() else 1.0
                shot = lastT + (t - lastT) * f
                lastShot = shot
            }
        }
        lastGap = gap
        lastT = t
        return shot
    }

    companion object {
        /** Thumb counts as raised above this gap and dropped below [CLOSED_GAP] (palm lengths). */
        const val OPEN_GAP = 0.55f
        const val CLOSED_GAP = 0.33f
        const val MIN_COCKED_SEC = 0.08
        const val MIN_GAP_SEC = 0.15
        const val LOST_SEC = 0.35f
    }
}

/**
 * Fallback trigger for a toy gun (or a hand the finger tracker cannot see): a quick upward kick of the forearm, like
 * a recoil. Fires at the moment the kick started, so the aim used is from before the hand moved.
 */
class FlickTrigger {
    private var runStart = -1.0
    private var runY0 = 0f
    private var runX0 = 0f
    private var peakV = 0f
    private var armed = true
    private var cooldownUntil = -1.0
    private var lastT = Double.NaN
    private var lastX = 0f
    private var lastY = 0f
    /** Recent wrist speeds (S/s), to tell a still, aiming hand from one already on the move. */
    private val recentT = DoubleArray(12)
    private val recentV = FloatArray(12)
    private var recentN = 0

    fun reset() { runStart = -1.0; armed = true; lastT = Double.NaN; recentN = 0; cooldownUntil = -1.0 }

    /** One pose frame of hand [h]: x, y in body units (y up), [ok] the wrist is seen. Returns the onset time of a flick, else NaN. */
    fun update(t: Double, x: Float, y: Float, ok: Boolean, scale: Float): Double {
        if (!ok) { lastT = Double.NaN; runStart = -1.0; return Double.NaN }
        if (lastT.isNaN() || t - lastT <= 1e-4 || t - lastT > 0.2) { lastT = t; lastX = x; lastY = y; return Double.NaN }
        val dt = (t - lastT).toFloat()
        val vy = (y - lastY) / dt
        val v = kotlin.math.hypot(x - lastX, y - lastY) / dt
        val i = recentN % recentT.size
        recentT[i] = t; recentV[i] = v; recentN++
        var fired = Double.NaN
        if (vy > START_V * scale) {
            if (runStart < 0) { runStart = lastT; runY0 = lastY; runX0 = lastX; peakV = 0f }
            peakV = maxOf(peakV, vy)
            val rise = y - runY0
            val side = kotlin.math.abs(x - runX0)
            if (armed && t >= cooldownUntil && rise > MIN_RISE * scale && peakV > MIN_PEAK_V * scale && t - runStart <= MAX_SEC &&
                side < rise * 0.7f && runY0 > MIN_HEIGHT && wasSteady(runStart)) {
                fired = runStart
                armed = false
                cooldownUntil = t + COOLDOWN_SEC
            }
        } else {
            runStart = -1.0
            if (vy < REARM_V) armed = true
        }
        lastT = t; lastX = x; lastY = y
        return fired
    }

    /** The hand was fairly still just before [t0] (aiming), not swinging about. */
    private fun wasSteady(t0: Double): Boolean {
        var n = 0; var sum = 0f
        for (k in 0 until minOf(recentN, recentT.size)) {
            val tt = recentT[k]
            if (tt < t0 && tt >= t0 - 0.25) { sum += recentV[k]; n++ }
        }
        return n == 0 || sum / n < STEADY_V
    }

    companion object {
        const val START_V = 0.6f
        const val MIN_PEAK_V = 1.5f
        const val MIN_RISE = 0.11f
        const val MAX_SEC = 0.22
        const val REARM_V = 0.3f
        const val COOLDOWN_SEC = 0.35
        const val STEADY_V = 1.1f
        /** The kick must start from an aiming hand (not one coming up from the side). Body units below the shoulders. */
        const val MIN_HEIGHT = -0.75f
    }
}

/**
 * What a shooting game needs from one player: where each hand aims, whether it makes a finger gun (or holds a learned
 * toy gun), which hand is the gun hand, and shots - by dropping the thumb (finger tracking) or, without finger data,
 * by a recoil flick.
 */
class GunControls {
    val shape = Array(2) { HandShape() }
    val trigger = Array(2) { FingerTrigger() }
    private val flick = Array(2) { FlickTrigger() }
    /** Aim point of each hand (knuckles, reach units from the shoulder centre, y up), measured (not predicted). */
    val aimX = FloatArray(2)
    val aimY = FloatArray(2)
    val aimSeen = BooleanArray(2)
    /** Hand makes a gun (finger gun or a learned toy gun in it). */
    val gun = BooleanArray(2)
    /** Finger landmarks seen for this hand recently. */
    val fingersSeen = BooleanArray(2)
    var gunHand = RIGHT
        private set
    private val offX = FloatArray(2)
    private val offY = FloatArray(2)
    private val hT = Array(2) { DoubleArray(HIST) }
    private val hX = Array(2) { FloatArray(HIST) }
    private val hY = Array(2) { FloatArray(HIST) }
    private val hN = IntArray(2)
    private var lastT = Double.NaN

    fun reset() {
        trigger.forEach { it.reset() }; flick.forEach { it.reset() }
        aimSeen.fill(false); gun.fill(false); fingersSeen.fill(false); hN.fill(0); offX.fill(0f); offY.fill(0f); lastT = Double.NaN
    }

    /**
     * Hands the finger tracker should follow: bit 0/1 = left/right every frame, bit 2/3 = left/right every other frame
     * (the hand not in use, while it is raised, so switching hands is noticed).
     */
    fun fingerMask(f: BodyFrame?): Int {
        var m = 1 shl gunHand
        val o = 1 - gunHand
        if (f != null && f.wv[o] > 0.3f && f.wy[o] > -0.6f) m = m or (4 shl o)
        return m
    }

    fun update(f: BodyFrame, reach: Float, dominant: Int, heldHand: Int, sensitivity: Float, out: MutableList<GestureEvent>) {
        val dt = if (lastT.isNaN()) 0f else (f.t - lastT).toFloat().coerceIn(0f, 0.25f)
        lastT = f.t
        for (h in 0..1) {
            // aim point: the knuckles from the finger landmarks, else the wrist plus the last knuckle offset
            val wOk = f.rwv[h] > 0.3f
            val wx = if (wOk) f.rwx[h] else f.wx[h]
            val wy = if (wOk) f.rwy[h] else f.wy[h]
            var px: Float; var py: Float
            if (f.fingSeen[h]) {
                val p = f.fing[h]
                px = (p[5 * 3] + p[9 * 3]) / 2f; py = (p[5 * 3 + 1] + p[9 * 3 + 1]) / 2f
                offX[h] += 0.5f * (px - wx - offX[h]); offY[h] += 0.5f * (py - wy - offY[h])
                shape[h].measure(p)
            } else {
                px = wx + offX[h]; py = wy + offY[h]
            }
            aimSeen[h] = f.fingSeen[h] || f.wv[h] > 0.3f
            aimX[h] = px / reach; aimY[h] = py / reach
            if (aimSeen[h]) push(h, f.t, aimX[h], aimY[h])
            // finger trigger
            if (f.fingSeen[h]) {
                val shot = trigger[h].update(shape[h], f.t)
                if (!shot.isNaN()) {
                    val (ax, ay) = aimAt(h, shot - PRE_SHOT_SEC)
                    out.add(GestureEvent.Shot(shot, h, ax, ay, byThumb = true))
                }
            } else trigger[h].unseen(dt)
            fingersSeen[h] = trigger[h].unseenSec < 0.6f
            gun[h] = trigger[h].gun || heldHand == h
            // recoil flick: only for a hand whose fingers are not being seen (toy gun, gloves, too far away)
            val fl = flick[h].update(f.t, wx, wy, wOk || f.wv[h] > 0.3f, 1f / sensitivity.coerceIn(0.5f, 2f))
            if (!fl.isNaN() && !fingersSeen[h]) {
                val (ax, ay) = aimAt(h, fl - PRE_SHOT_SEC)
                out.add(GestureEvent.Shot(fl, h, ax, ay, byThumb = false))
            }
        }
        // the gun hand: the one making a gun; with both or neither, the clearly higher one; else keep it
        gunHand = when {
            gun[0] && !gun[1] -> LEFT
            gun[1] && !gun[0] -> RIGHT
            aimSeen[0] && aimSeen[1] && aimY[1 - gunHand] > aimY[gunHand] + 0.25f -> 1 - gunHand
            !aimSeen[gunHand] && aimSeen[1 - gunHand] -> 1 - gunHand
            else -> if (hN[0] == 0 && hN[1] == 0) dominant else gunHand
        }
    }

    private fun push(h: Int, t: Double, x: Float, y: Float) {
        val i = hN[h] % HIST
        hT[h][i] = t; hX[h][i] = x; hY[h][i] = y; hN[h]++
    }

    /** Where hand [h] was aiming at time [t] (the newest sample at or before it, else the oldest one kept). */
    fun aimAt(h: Int, t: Double): Pair<Float, Float> {
        val n = minOf(hN[h], HIST)
        if (n == 0) return aimX[h] to aimY[h]
        var best = -1; var bestT = Double.NEGATIVE_INFINITY; var oldest = -1; var oldestT = Double.POSITIVE_INFINITY
        for (k in 0 until n) {
            val tt = hT[h][k]
            if (tt <= t && tt > bestT) { best = k; bestT = tt }
            if (tt < oldestT) { oldest = k; oldestT = tt }
        }
        val i = if (best >= 0) best else oldest
        return hX[h][i] to hY[h][i]
    }

    companion object {
        const val HIST = 24
        /** Aim is taken this long before the trigger moved (the hand dips a little as the thumb drops). */
        const val PRE_SHOT_SEC = 0.05
    }
}
