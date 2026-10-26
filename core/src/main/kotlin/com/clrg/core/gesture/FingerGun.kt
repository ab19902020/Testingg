package com.clrg.core.gesture

import com.clrg.core.pose.HandLm
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The shape of one hand from its 21 finger landmarks: how bent each finger is (in 3D) and where the thumb is.
 *
 * The thumb is read flat on the picture ([thumbLevel]), in hand widths, because the model's depth estimate is its
 * weakest part and a finger gun points at the camera: seen end-on, depth is most of the hand's shape. Measured on
 * real photos with the MediaPipe hand model (HandProbe): a lowered thumb reads about 0.05-0.3, a raised one about 1.1,
 * and a hand pointing at the camera reads the same way (FingerTriggerEndOnTest).
 */
class HandShape {
    var valid = false
        private set
    /** Bend at the middle joint of each finger in degrees (0 = straight): index, middle, ring, pinky. */
    val bend = FloatArray(4)
    /** Fingertip distance from the wrist in palm lengths: index, middle, ring, pinky. */
    val reach = FloatArray(4)
    /** Thumb tip distance from the side of the index finger (its knuckle to middle joint), in palm lengths (3D). */
    var thumbGap = 0f
        private set
    /**
     * How far the thumb tip is, on the picture, from where a lowered thumb rests: the side of the index finger's first
     * bone (a two-finger gun) and the middle finger's middle joint (a one-finger gun), averaged. In hand widths
     * (knuckle to knuckle, or 0.6 palm lengths if larger).
     */
    var thumbLevel = 0f
        private set
    /** The hand is seen from the side (its palm is long on the picture), so its finger bends can be trusted. */
    var sideOn = false
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
        val width = maxOf(dist2(p, HandLm.INDEX_MCP, HandLm.PINKY_MCP), 0.6f * dist2(p, HandLm.WRIST, HandLm.MIDDLE_MCP))
        if (width < 1e-6f) { valid = false; return }
        thumbLevel = 0.5f * (segDist2(p, HandLm.THUMB_TIP, HandLm.INDEX_MCP, HandLm.INDEX_PIP) + dist2(p, HandLm.THUMB_TIP, HandLm.MIDDLE_PIP)) / width
        sideOn = dist2(p, HandLm.WRIST, HandLm.MIDDLE_MCP) > SIDE_ON * dist2(p, HandLm.INDEX_MCP, HandLm.PINKY_MCP)
        valid = true
    }

    fun extended(f: Int) = valid && bend[f] < STRAIGHT_DEG && reach[f] > 1.25f
    fun curled(f: Int) = valid && (bend[f] > CURLED_DEG || reach[f] < 1.05f)

    /** Index finger out, ring and pinky curled in (the middle finger may join the index: a two-finger gun). */
    val gun: Boolean get() = valid && extended(0) && curled(2) && curled(3) && (curled(1) || extended(1))
    /** An open hand (ring and little finger out): not holding a gun, so a thumb movement is not a shot. */
    val open: Boolean get() = valid && extended(2) && extended(3)
    /**
     * Can this hand fire with its thumb? Not an open hand, and - when seen from the side, where the fingers can be
     * read - not a fist (index curled). A finger gun pointing at the camera looks much like a fist with the thumb on
     * top (the index finger is end-on), so seen end-on only an open hand is ruled out.
     */
    val canFire: Boolean get() = valid && !open && !(sideOn && curled(0))

    companion object {
        private val FINGER_MCP = intArrayOf(HandLm.INDEX_MCP, HandLm.MIDDLE_MCP, HandLm.RING_MCP, HandLm.PINKY_MCP)
        const val STRAIGHT_DEG = 45f
        /** Palm at least this many knuckle widths long on the picture: seen from the side. */
        const val SIDE_ON = 0.8f
        const val CURLED_DEG = 80f

        private fun dist(p: FloatArray, a: Int, b: Int): Float {
            val dx = p[a * 3] - p[b * 3]; val dy = p[a * 3 + 1] - p[b * 3 + 1]; val dz = p[a * 3 + 2] - p[b * 3 + 2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        private fun dist2(p: FloatArray, a: Int, b: Int): Float {
            val dx = p[a * 3] - p[b * 3]; val dy = p[a * 3 + 1] - p[b * 3 + 1]
            return sqrt(dx * dx + dy * dy)
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

        /** Distance on the picture (x, y) from point [q] to the segment [a]-[b]. */
        private fun segDist2(p: FloatArray, q: Int, a: Int, b: Int): Float {
            val abx = p[b * 3] - p[a * 3]; val aby = p[b * 3 + 1] - p[a * 3 + 1]
            val aqx = p[q * 3] - p[a * 3]; val aqy = p[q * 3 + 1] - p[a * 3 + 1]
            val ab2 = abx * abx + aby * aby
            val t = if (ab2 < 1e-9f) 0f else ((aqx * abx + aqy * aby) / ab2).coerceIn(0f, 1f)
            val dx = aqx - t * abx; val dy = aqy - t * aby
            return sqrt(dx * dx + dy * dy)
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
 * Finger-gun trigger for one hand: point at the TV (one or two fingers out), raise the thumb ("cocked"), drop it onto
 * the side of the index finger to fire.
 *
 * Built like the pinch and thumb-tap detectors of headset hand tracking, to be quick without firing by itself:
 *  - The thumb is put on a 0..1 scale between this player's own thumb-down and thumb-up positions ([downLevel],
 *    [upLevel]), learned as they play from the average of each, so a thumb that is never raised very high, or never
 *    lowered all the way, still works.
 *  - Wide hysteresis: cocked above [COCK_AT] of the scale, fired below [FIRE_AT], so jitter around one line cannot
 *    fire, and a twitch does not reach the other.
 *  - It counts one quick movement: from the top of the scale ([HIGH_AT], judged on the average of the last five
 *    frames so one noisy frame cannot count as "up") to the fire line within [MAX_FALL_SEC]. Slowly lowering the
 *    thumb, or the hand slowly turning, does not fire.
 *  - A single misread frame cannot fire: the signal is the median of the last three frames.
 *  - The shot is stamped with the moment the thumb passed halfway down (from the unfiltered frames), so games judge
 *    it by when it really happened.
 *  - An open hand, a fist seen from the side, or a hand moving very fast (blur) does not fire.
 */
class FingerTrigger {
    /** The hand is making a gun (index out, other fingers in), held through a frame or two of doubt. For display. */
    var gun = false
        private set
    /** Thumb raised, ready to fire. */
    var cocked = false
        private set
    /** Seconds since the hand model last saw this hand. */
    var unseenSec = 99f
        private set
    /** Filtered thumb measure this frame ([HandShape.thumbLevel]), NaN before the first. */
    var level = Float.NaN
        private set
    /** This player's thumb-up and thumb-down positions, learned as they play. */
    var upLevel = UP0
        private set
    var downLevel = DOWN0
        private set
    /** Above this the thumb counts as raised; below [fireLine] (after a quick drop) it fires. */
    val upLine: Float get() = downLevel + COCK_AT * (upLevel - downLevel)
    val fireLine: Float get() = downLevel + FIRE_AT * (upLevel - downLevel)
    /** The thumb from 0 (down) to 1 (up), for meters. */
    val thumb01: Float get() = if (level.isNaN()) 0f else ((level - downLevel) / (upLevel - downLevel)).coerceIn(0f, 1f)

    private var gunVotes = 0
    // "open hand" and "may fire" are voted over a few frames, so one misread frame neither blocks a shot nor uncocks
    private var openVotes = -3
    private var fireVotes = 3
    private val med = FloatArray(3)
    private var medN = 0
    private var upFrames = 0
    private var cockedSince = -1.0
    private var highT = -9.0
    /** The thumb was dropped from cocked and has not been raised again. */
    private var dropped = false
    private var lastT = Double.NaN
    private var lastShot = -9.0
    // unfiltered recent frames, to stamp the shot
    private val rawT = DoubleArray(4)
    private val rawV = FloatArray(4)
    private var rawN = 0
    // the last five unfiltered frames, averaged: "was the thumb up just now?" (steadier than any one frame)
    private val avg5 = FloatArray(5)

    fun reset() {
        gun = false; cocked = false; gunVotes = 0; openVotes = -3; fireVotes = 3; unseenSec = 99f; level = Float.NaN; medN = 0; upFrames = 0; rawN = 0
        lastT = Double.NaN; upLevel = UP0; downLevel = DOWN0; highT = -9.0; dropped = false
    }

    /** No finger data this frame. */
    fun unseen(dt: Float) {
        unseenSec += dt
        if (unseenSec > LOST_SEC) { gun = false; gunVotes = 0; cocked = false; medN = 0; upFrames = 0; level = Float.NaN }
    }

    /**
     * One frame of finger data at time [t]. [moving]: the hand is moving too fast for its fingers to be read reliably.
     * Returns the shot time when the thumb was just dropped, else NaN.
     */
    fun update(shape: HandShape, t: Double, moving: Boolean = false): Double {
        unseenSec = 0f
        gunVotes = if (shape.gun) minOf(gunVotes + 1, 4) else maxOf(gunVotes - 1, -3)
        if (gunVotes >= 2) gun = true else if (gunVotes <= -2) gun = false
        val dt = if (lastT.isNaN()) 0f else (t - lastT).toFloat().coerceIn(0f, 0.25f)
        lastT = t
        val raw = shape.thumbLevel
        rawT[rawN % 4] = t; rawV[rawN % 4] = raw; avg5[rawN % 5] = raw; rawN++
        med[medN % 3] = raw; medN++
        val s = if (medN < 3) raw else median3(med[0], med[1], med[2])
        val prevLevel = level
        level = s
        openVotes = if (shape.open) minOf(openVotes + 1, 3) else maxOf(openVotes - 1, -3)
        fireVotes = if (shape.canFire) minOf(fireVotes + 1, 3) else maxOf(fireVotes - 1, -3)
        val open = openVotes >= 1
        val canFire = fireVotes >= 0
        // learn this player's thumb-up and thumb-down positions (never from an open hand)
        if (!open) {
            if (cocked) {
                // the average of the raised thumb (not of one on its way down)
                if (s >= upLine) upLevel += (s - upLevel) * (dt / AVG_SEC).coerceAtMost(1f)
            } else if (s > upLevel) {
                // raised higher than before
                upLevel += (s - upLevel) * (dt / AVG_SEC).coerceAtMost(1f)
            } else {
                // a player whose thumb never comes up as far: the top drifts down towards them
                upLevel += (s - upLevel) * (dt / UP_TAU).coerceAtMost(1f)
            }
            if (!cocked && dropped && s < fireLine) {
                // the average of the lowered thumb, just after it was dropped (before that, low readings may be a
                // thumb that is only half raised)
                downLevel += (s - downLevel) * (dt / AVG_SEC).coerceAtMost(1f)
            }
            downLevel = downLevel.coerceIn(DOWN_MIN, DOWN_MAX)
            upLevel = maxOf(upLevel, downLevel + MIN_RANGE)
        }
        val range = upLevel - downLevel
        val recent = if (rawN >= 5) avg5.average().toFloat() else s
        if (recent >= downLevel + HIGH_AT * range) highT = t
        var shot = Double.NaN
        if (!cocked) {
            if (s > upLine && !open) {
                if (++upFrames >= 2) { cocked = true; cockedSince = t; dropped = false }
            } else upFrames = 0
        } else if (open) {
            cocked = false
        } else if (s < fireLine) {
            cocked = false
            dropped = true
            upFrames = 0
            val quick = t - highT <= MAX_FALL_SEC
            val held = highT - cockedSince >= MIN_COCKED_SEC
            if (quick && held && canFire && !moving && t - lastShot >= MIN_GAP_SEC) {
                shot = crossingTime((upLine + fireLine) / 2f, t, prevLevel, s)
                lastShot = shot
            }
        }
        return shot
    }

    /** When the unfiltered thumb last passed [mid] going down (between frames), else the filtered estimate. */
    private fun crossingTime(mid: Float, t: Double, prevFiltered: Float, now: Float): Double {
        val n = minOf(rawN, 4)
        for (k in 0 until n - 1) {
            val i = (rawN - 1 - k) % 4; val j = (rawN - 2 - k) % 4
            if (rawV[j] >= mid && rawV[i] < mid) {
                val f = ((rawV[j] - mid) / (rawV[j] - rawV[i]).coerceAtLeast(1e-6f)).toDouble()
                return rawT[j] + (rawT[i] - rawT[j]) * f
            }
        }
        if (!prevFiltered.isNaN() && prevFiltered > mid && now < mid && n >= 2) {
            val lt = rawT[(rawN - 2) % 4]
            val f = ((prevFiltered - mid) / (prevFiltered - now)).toDouble()
            return lt + (t - lt) * f
        }
        return t
    }

    private fun median3(a: Float, b: Float, c: Float): Float = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    companion object {
        /** Starting thumb-up and thumb-down positions ([HandShape.thumbLevel]) before a player's own are learned. */
        const val UP0 = 0.9f
        const val DOWN0 = 0.2f
        /** The learned scale is never narrower than this (noise must not be able to fire). */
        const val MIN_RANGE = 0.25f
        /** A "lowered" position outside these is not believed. */
        const val DOWN_MAX = 0.55f
        const val DOWN_MIN = 0.02f
        /** Raised above this part of the scale: cocked. Below [FIRE_AT] after a quick drop from [HIGH_AT]: fired. */
        const val COCK_AT = 0.7f
        const val FIRE_AT = 0.3f
        const val HIGH_AT = 0.75f
        /** Learned positions average over about this long; a top that is never reached drifts down over [UP_TAU]. */
        const val AVG_SEC = 0.4f
        const val UP_TAU = 1.2f
        /** From the top of the scale to the fire line in at most this long. */
        const val MAX_FALL_SEC = 0.4
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
        trigger.forEach { it.reset() }; flick.forEach { it.reset() }; spT.fill(Double.NaN); spV.fill(0f)
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
                // a hand sweeping across very fast is blurred: its fingers cannot be trusted for a shot
                val moving = handSpeed(h, f.t, px / reach, py / reach) > FAST_HAND
                val shot = trigger[h].update(shape[h], f.t, moving)
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

    private val spT = DoubleArray(2) { Double.NaN }
    private val spX = FloatArray(2)
    private val spY = FloatArray(2)
    private val spV = FloatArray(2)

    /** Smoothed speed of the knuckles (reach units per second). */
    private fun handSpeed(h: Int, t: Double, x: Float, y: Float): Float {
        val pt = spT[h]
        if (!pt.isNaN() && t > pt && t - pt < 0.25) {
            val v = kotlin.math.hypot(x - spX[h], y - spY[h]) / (t - pt).toFloat()
            spV[h] += 0.5f * (v - spV[h])
        } else spV[h] = 0f
        spT[h] = t; spX[h] = x; spY[h] = y
        return spV[h]
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
        /** Knuckles moving faster than this (reach units per second) are too blurred for a thumb shot. */
        const val FAST_HAND = 3.5f
    }
}
