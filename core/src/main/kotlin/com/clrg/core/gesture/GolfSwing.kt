package com.clrg.core.gesture

import com.clrg.core.math.clamp
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/** Which golf movement the detector looks for. */
enum class GolfStroke { NONE, FULL, PUTT }

/**
 * Golf swings from the front-facing camera, in body units (S = torso length, y up, origin between the shoulders).
 *
 * Both hands hold the club, so the detector follows the *hands*: the midpoint of the two wrists (weighted by how
 * clearly each is seen, so a wrist hidden behind the head during the backswing does not drag it off).
 *
 * Full swing (driver, woods, irons, wedge):
 *  0. **Grip** - both hands together, as if holding a club ([grip], [handsGap] show how close they are).
 *  1. **Address** - hands gripped, low in front of you and still for a moment.
 *  2. **Backswing** - hands rise to either side. How high they go sets the power (a full swing takes them about
 *     level with your head; going much higher is an overswing - a little more distance, less accuracy).
 *  3. **Downswing** - hands come back down quickly. The shot is struck at the lowest point of the downswing; the
 *     further the hands are from where they started (sideways), the more the ball strays left or right.
 *  Coming back down slowly cancels the swing (a practice swing), so lowering your hands is never a shot.
 *
 * Putt: the same address, then a small pendulum: hands swing back sideways (how far sets the strength) and forward
 * through the address point.
 *
 * Thresholds scale with the player's calibrated reach and movement mode, like every other gesture.
 */
class GolfSwingDetector {
    enum class Phase { IDLE, ADDRESS, BACKSWING, FOLLOW }

    class Shot(
        /** Pose-clock time of impact (s). */
        val t: Double,
        /** 0..1 (up to [MAX_POWER] for an overswing). */
        val power: Float,
        /** -1..1: where the hands came through relative to where they started (sideways); + = screen right. */
        val offset: Float,
        val stroke: GolfStroke,
    )

    var phase = Phase.IDLE
        private set
    /** Live power while swinging back (for the on-screen meter), 0 when not swinging. */
    var livePower = 0f
        private set
    /** Seconds the hands have been held in the address position (0..[ADDRESS_HOLD_SEC]). */
    var addressProgress = 0f
        private set
    /** Hands position (body units) in the latest frame, NaN when not seen. */
    var handsX = Float.NaN
        private set
    var handsY = Float.NaN
        private set
    /** Distance between the wrists (body units), NaN unless both are seen. */
    var handsGap = Float.NaN
        private set
    /** True while both hands are seen together, as if holding a club. */
    var grip = false
        private set
    private var lostSince = Double.NaN

    private var stillSince = Double.NaN
    private var addrX = 0f
    private var addrY = 0f
    private var peak = 0f
    private var side = 0f
    private var backStart = 0.0
    private var followUntil = 0.0
    private var prevX = Float.NaN
    private var prevY = Float.NaN
    private var prevT = 0.0
    private var lowY = Float.MAX_VALUE
    private var lowX = 0f
    private var lowT = 0.0
    private var descending = false
    private var downSpeed = 0f

    fun reset() {
        phase = Phase.IDLE; livePower = 0f; addressProgress = 0f; stillSince = Double.NaN
        prevX = Float.NaN; prevY = Float.NaN; descending = false; grip = false; handsGap = Float.NaN; lostSince = Double.NaN
    }

    /** Hands position from a body frame; false when neither wrist is seen well enough. */
    private fun hands(f: BodyFrame): Boolean {
        val v0 = f.wv[LEFT]; val v1 = f.wv[RIGHT]
        handsGap = if (v0 >= 0.3f && v1 >= 0.3f) hypot(f.wx[LEFT] - f.wx[RIGHT], f.wy[LEFT] - f.wy[RIGHT]) else Float.NaN
        grip = !handsGap.isNaN() && handsGap < GRIP_GAP
        if (v0 < 0.2f && v1 < 0.2f) return false
        val w0 = max(0f, v0 - 0.15f); val w1 = max(0f, v1 - 0.15f)
        handsX = (f.wx[LEFT] * w0 + f.wx[RIGHT] * w1) / (w0 + w1)
        handsY = (f.wy[LEFT] * w0 + f.wy[RIGHT] * w1) / (w0 + w1)
        return true
    }

    /**
     * Feed every new body frame. [stroke] selects the movement (NONE ignores everything); [mode] and [reach] scale
     * the thresholds. Returns a shot when one is struck.
     */
    fun update(f: BodyFrame, stroke: GolfStroke, mode: MovementMode, reach: Float, sensitivity: Float = 1f): Shot? {
        if (stroke == GolfStroke.NONE) { reset(); return null }
        if (!hands(f)) {
            // hands hidden for a moment at the top of the backswing (behind the head, blurred, out of shot): keep going
            if (phase == Phase.BACKSWING) {
                if (lostSince.isNaN()) lostSince = f.t
                if (f.t - lostSince > LOST_GRACE_SEC) reset()
            } else if (phase != Phase.FOLLOW) reset()
            return null
        }
        lostSince = Double.NaN
        val k = clamp(reach / 1.5f, 0.55f, 1.3f) / clamp(sensitivity, 0.5f, 2f)
        val std = mode == MovementMode.STANDARD
        val x = handsX; val y = handsY; val t = f.t
        val dt = if (prevX.isNaN()) 0f else (t - prevT).toFloat()
        val speed = if (dt > 0f) hypot(x - prevX, y - prevY) / dt else 0f
        val vy = if (dt > 0f) (y - prevY) / dt else 0f
        val vx = if (dt > 0f) (x - prevX) / dt else 0f
        prevX = x; prevY = y; prevT = t
        // address needs a real grip: both hands seen, together; once gripped, a hand hidden behind the other is fine
        val together = grip || (phase == Phase.ADDRESS && (handsGap.isNaN() || handsGap < GRIP_GAP * 1.4f))
        val lowEnough = y < (if (std) -0.55f else if (mode == MovementMode.SEATED) -0.35f else -0.45f)
        var shot: Shot? = null
        when (phase) {
            Phase.FOLLOW -> if (t >= followUntil) { phase = Phase.IDLE; stillSince = Double.NaN }
            Phase.IDLE, Phase.ADDRESS -> {
                livePower = 0f
                val still = speed < 0.7f
                if (together && lowEnough && (still || phase == Phase.ADDRESS)) {
                    if (stillSince.isNaN() || phase == Phase.IDLE && !still) { stillSince = t; addrX = x; addrY = y }
                    // the address point follows small settling movements until the address is set
                    if (still && phase == Phase.IDLE) { addrX += 0.3f * (x - addrX); addrY += 0.3f * (y - addrY) }
                    addressProgress = ((t - stillSince) / ADDRESS_HOLD_SEC).toFloat().coerceIn(0f, 1f)
                    if (phase == Phase.IDLE && addressProgress >= 1f) phase = Phase.ADDRESS
                } else if (phase == Phase.IDLE) {
                    stillSince = Double.NaN; addressProgress = 0f
                }
                if (phase == Phase.ADDRESS) {
                    val start = if (stroke == GolfStroke.PUTT) abs(x - addrX) > 0.07f * k else y - addrY > 0.22f * k
                    if (start) {
                        phase = Phase.BACKSWING; backStart = t; peak = 0f
                        side = sign(x - addrX).let { if (it == 0f) 1f else it }
                        descending = false; lowY = Float.MAX_VALUE; downSpeed = 0f
                    } else if (!together || !lowEnough) {
                        phase = Phase.IDLE; stillSince = Double.NaN; addressProgress = 0f
                    }
                }
            }
            Phase.BACKSWING -> {
                if (stroke == GolfStroke.PUTT) {
                    val back = (x - addrX) * side
                    if (back > peak) peak = back
                    livePower = puttPower(peak, k)
                    // forward through the address point with some pace: struck
                    if (back < 0f && peak > 0.07f * k) {
                        if (abs(vx) > 0.25f * k) shot = Shot(t, puttPower(peak, k), 0f, GolfStroke.PUTT)
                        phase = Phase.FOLLOW; followUntil = t + 0.8
                    } else if (t - backStart > 4.0 || !lowEnough && y - addrY > 0.6f * k) {
                        phase = Phase.IDLE; stillSince = Double.NaN // not a putt (hands went up)
                    }
                } else {
                    val rise = y - addrY
                    if (!descending) {
                        if (rise > peak) { peak = rise; side = sign(x - addrX).let { if (it == 0f) side else it } }
                        livePower = fullPower(peak, k, std)
                        // clearly on the way down
                        if (peak > 0.3f * k && rise < peak - 0.2f * k && vy < -1.2f * k) descending = true
                    }
                    if (descending) {
                        downSpeed = max(downSpeed, -vy)
                        if (y < lowY) { lowY = y; lowX = x; lowT = t }
                        // the lowest point has passed (hands rising again or level) near address height: impact
                        val pastLow = y > lowY + 0.04f * k || (vy > -0.3f * k && lowY < addrY + 0.35f * k)
                        if (lowY < addrY + 0.45f * k && pastLow) {
                            if (downSpeed >= MIN_DOWNSWING_SPEED * k) {
                                val off = (lowX - addrX)
                                val dead = 0.1f * k
                                val o = if (abs(off) <= dead) 0f else sign(off) * (abs(off) - dead) / (0.5f * k)
                                shot = Shot(lowT, fullPower(peak, k, std), clamp(o, -1f, 1f), GolfStroke.FULL)
                            }
                            phase = Phase.FOLLOW; followUntil = t + 1.0
                        }
                    }
                    // gave up (hands stayed up, or wandered off)
                    if (phase == Phase.BACKSWING && t - backStart > 5.0) { phase = Phase.IDLE; stillSince = Double.NaN }
                }
                if (phase != Phase.BACKSWING) { livePower = 0f; addressProgress = 0f }
            }
        }
        return shot
    }

    companion object {
        /** Wrists closer than this (body units) count as holding a club together. */
        const val GRIP_GAP = 0.42f
        /** How long the hands may be lost during a backswing (s). */
        const val LOST_GRACE_SEC = 0.5
        const val ADDRESS_HOLD_SEC = 0.45
        const val MAX_POWER = 1.15f
        /** Downswing speed (body lengths per second) below which the swing is a practice swing. */
        const val MIN_DOWNSWING_SPEED = 2.2f
        /** Rise of the hands (S) for a full-power swing in standard mode. */
        const val FULL_RISE = 1.35f

        fun fullPower(rise: Float, k: Float, standard: Boolean): Float {
            val full = (if (standard) FULL_RISE else 0.85f) * k
            return clamp((rise - 0.12f * k) / (full - 0.12f * k), 0f, MAX_POWER)
        }

        /** Putt strength from how far back (S) the hands went: a little back = a tap, ~0.6 S = the longest putt. */
        fun puttPower(back: Float, k: Float): Float = clamp((back - 0.04f * k) / (0.56f * k), 0f, 1f)
    }
}
