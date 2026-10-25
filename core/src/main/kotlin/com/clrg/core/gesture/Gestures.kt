package com.clrg.core.gesture

import com.clrg.core.math.clamp
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Discrete gesture events. Times are in seconds on the pose clock. */
sealed class GestureEvent(val t: Double, val hand: Int) {
    /**
     * Game play time at which the player made this gesture, as they saw the game (camera and display delays taken
     * off). Set when the event is handed to a game; NaN means "now" (computer players, scripted input).
     */
    var at: Double = Double.NaN

    /**
     * Fast arm swing. dirX/dirY: unit direction on screen (x right, y up). power 0..1.
     * forehand: swing with the playing hand moving across the body from its own side.
     */
    class Swing(t: Double, hand: Int, val dirX: Float, val dirY: Float, val power: Float, val forehand: Boolean) : GestureEvent(t, hand)
    /** Overhand / forward throw. aimX: -1..1 where the hand finished relative to the body. */
    class Throw(t: Double, hand: Int, val aimX: Float, val power: Float) : GestureEvent(t, hand)
    /** Underarm release. aimX -1..1 from the sideways direction of the swing, power 0..1. */
    class Bowl(t: Double, hand: Int, val aimX: Float, val power: Float) : GestureEvent(t, hand)
    /** Both hands went up overhead (rising edge). */
    class HandsUp(t: Double) : GestureEvent(t, -1)
    /** A hand was raised and held long enough (ready / confirm). */
    class RaiseHold(t: Double, hand: Int) : GestureEvent(t, hand)
    /**
     * A shot from a finger gun (thumb dropped) or a held toy gun (a quick upward flick, the recoil). [aimX]/[aimY]: where
     * the hand was pointing just before (reach units, like the hand positions); [byThumb]: fired with the thumb.
     */
    class Shot(t: Double, hand: Int, val aimX: Float, val aimY: Float, val byThumb: Boolean) : GestureEvent(t, hand)
    /** An arrow let go: [power] 0..1 (how far it was drawn), aimed where the bow hand pointed ([aimX], [aimY], reach units). */
    class Release(t: Double, hand: Int, val power: Float, val aimX: Float, val aimY: Float) : GestureEvent(t, hand)
    /**
     * A punch. [kind]: straight (towards the camera), hook (round from the side) or uppercut (upwards). [power] 0..1.
     * [x]/[y]: where the fist ended up (reach units), i.e. what it was aimed at.
     */
    class Punch(t: Double, hand: Int, val kind: PunchKind, val power: Float, val x: Float, val y: Float) : GestureEvent(t, hand)

    override fun toString(): String = "${this::class.simpleName}(t=%.3f, hand=$hand)".format(t)
}

enum class PunchKind { STRAIGHT, HOOK, UPPERCUT }

/**
 * Gesture thresholds in body units (S = torso length), per movement mode. Reduced-movement and seated modes
 * need smaller, slower motions; everything is additionally scaled by the player's calibrated reach.
 */
class GestureTuning(val mode: MovementMode, val hand: Hand, reach: Float = 1.5f, sensitivity: Float = 1f) {
    private val k = clamp(reach / 1.5f, 0.55f, 1.3f) / clamp(sensitivity, 0.5f, 2f)
    private val std = mode == MovementMode.STANDARD
    private val seated = mode == MovementMode.SEATED

    /** A swing: the hand covers this distance within [SwingDetector.WINDOW] at this average speed or faster. */
    val swingDistance = (if (std) 0.6f else 0.34f) * k
    val swingSpeed = (if (std) 2.7f else 1.6f) * k
    val swingCooldown = 0.22f

    val throwStartY = if (std) 0.0f else -0.4f
    val throwDistance = (if (std) 0.55f else 0.3f) * k
    val throwSpeed = (if (std) 2.8f else 1.6f) * k
    val throwCooldown = 0.55f

    val bowlLowY = if (std) -0.72f else if (seated) -0.55f else -0.6f
    val bowlReleaseY = if (std) -0.35f else if (seated) -0.2f else -0.42f
    val bowlTravel = (if (std) 0.42f else 0.25f) * k
    val bowlSpeed = (if (std) 1.3f else 0.75f) * k

    val leanRange = if (std) 0.32f else 0.2f
    val leanDeadZone = 0.12f

    val pumpAmplitude = (if (std) 0.3f else 0.14f) * k
    val pumpMaxCadence = if (std) 3.0f else 2.1f

    /** Hands-up threshold relative to the nose (std) or shoulders (reduced/seated). */
    val handsUpAboveNose = std
    val handsUpY = if (std) 0.02f else 0.3f
    val raiseHoldSec = 1.0f
}

/**
 * Detects fast arm swings with either hand, built to survive a real camera:
 *
 *  - It reads the *unsmoothed* joints, so a swing is seen as soon as the camera sees it.
 *  - A fast hand is often blurred: the model loses the wrist or places it badly for a frame or two. The elbow (which
 *    moves about half as far) and, when one is held, the object's far end are watched as well, and a wrist that
 *    vanishes while moving fast still counts when the game is expecting a swing.
 *  - Movement towards or away from the camera (a forehand swung at the TV) counts too, from the wrist's depth.
 *  - A backswing does not block the real swing that follows it: a hand re-arms as soon as it changes direction, and
 *    the cooldown between swings is short. (Games that judge timing pick the swing closest to the right moment.)
 *  - A one-frame jump of a joint (a mis-detection) is not a swing: the distance has to be covered over several frames.
 *
 * [boost] (0..1) is set by a game while it is waiting for a swing (a ball arriving): thresholds drop by up to 40%,
 * so a gentle or partly-seen swing still counts exactly when it matters.
 */
class SwingDetector {
    private val armed = booleanArrayOf(true, true)
    private val lastDirX = FloatArray(2)
    private val lastDirY = FloatArray(2)
    private val lastEventT = doubleArrayOf(-9.0, -9.0)
    private var deferredAt = -9.0
    /** Frames up to the last detection are never measured again: one movement is one swing. */
    private var usedUpTo = -9.0
    private var cooldownUntil = -1.0

    /** What the last check saw for each hand (for the motion test screen): 0..1+ of the swing threshold. */
    val level = FloatArray(2)
    /** Which part saw the last swing: 0 wrist, 1 elbow, 2 held object (motion test screen). */
    var lastKind = 0
        private set

    fun reset() { armed.fill(true); cooldownUntil = -1.0; level.fill(0f); lastEventT.fill(-9.0); usedUpTo = -9.0 }

    private class Signal {
        var ok = false
        var disp = 0f; var path = 0f; var speed = 0f
        var dx = 0f; var dy = 0f
        var peakT = 0.0; var peakV = 0f
        var lastStepX = 0f; var lastStepY = 0f; var lastStepV = 0f; var lastStepFrom = 0.0
        /** The fastest step is the newest one: the hand may still be speeding up. */
        var peakNewest = false
        fun clear() { ok = false; disp = 0f; path = 0f; speed = 0f; dx = 0f; dy = 0f; peakV = 0f; lastStepV = 0f; peakNewest = false }
    }

    private val sig = Array(3) { Signal() }

    /**
     * Measures one effector (0 = wrist, 1 = elbow, 2 = held object's far end) of [hand] over the last [WINDOW]
     * seconds, in wrist-equivalent body units ([gain]).
     */
    private fun measure(h: BodyHistory, hand: Int, kind: Int, gain: Float, out: Signal) {
        out.clear()
        val nowT = h.latest?.t ?: return
        var newest: BodyFrame? = null
        var oldest: BodyFrame? = null
        var prev: BodyFrame? = null
        var n = 0
        var steps = 0
        var maxStep = 0f
        for (k in 0 until h.size) {
            val f = h.get(k)
            val age = nowT - f.t
            if (age > WINDOW + 0.04 || f.t < usedUpTo) break
            val seen = when (kind) {
                0 -> f.rwv[hand] >= MIN_VIS
                1 -> f.rev[hand] >= MIN_VIS
                else -> f.objHand == hand && f.objConf >= 0.35f
            }
            if (!seen) continue
            if (newest == null) {
                if (age > 0.07) return // this joint has not been seen for a while
                newest = f
            }
            val p = prev
            if (p != null) {
                val dt = (p.t - f.t).toFloat()
                if (dt <= 0f || dt > MAX_GAP) break // a long gap: do not join across it
                val sx = x(p, hand, kind) - x(f, hand, kind)
                val sy = y(p, hand, kind) - y(f, hand, kind)
                val sz = if (kind == 0) (p.rwz[hand] - f.rwz[hand]).coerceIn(-0.5f, 0.5f) * Z_WEIGHT else 0f
                val step = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz) * gain
                out.path += step
                if (step > maxStep) maxStep = step
                val v = step / dt
                if (steps < stepV.size) { stepV[steps] = v; stepT[steps] = (p.t + f.t) / 2.0; steps++ }
                if (n == 1) { out.lastStepX = sx; out.lastStepY = sy; out.lastStepV = v; out.lastStepFrom = f.t }
            }
            prev = f
            oldest = f
            n++
        }
        peak(steps, out)
        val a = newest ?: return
        val b = oldest ?: return
        val span = (a.t - b.t).toFloat()
        if (n < 3 || span < 0.07f) return
        val dx = (x(a, hand, kind) - x(b, hand, kind)) * gain
        val dy = (y(a, hand, kind) - y(b, hand, kind)) * gain
        val dz = if (kind == 0) (a.rwz[hand] - b.rwz[hand]).coerceIn(-0.8f, 0.8f) * Z_WEIGHT * gain else 0f
        out.disp = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        out.dx = dx; out.dy = dy
        out.speed = out.path / span
        // mostly one way (not jitter adding up; elbows travel on an arc), covered over several frames (not one jump)
        out.ok = out.disp >= (if (kind == 1) 0.45f else 0.55f) * out.path && maxStep <= 0.72f * out.path
    }

    private val stepV = FloatArray(16)
    private val stepS = FloatArray(16)
    private val stepT = DoubleArray(16)

    /**
     * The fastest moment: the fastest step, refined between frames with a parabola through it and its neighbours
     * (so the moment is found to a few ms even at 30 frames per second, and one noisy step moves it less).
     */
    private fun peak(steps: Int, out: Signal) {
        if (steps == 0) return
        // light 1-2-1 smoothing: one noisy step should not decide where the peak is
        // (the newest and oldest steps have a neighbour on one side only: left as measured, so they are not pulled
        // towards it - that would move a peak at the newest step back in time)
        for (k in 0 until steps) {
            stepS[k] = if (k == 0 || k == steps - 1) stepV[k] else (stepV[k - 1] + 2f * stepV[k] + stepV[k + 1]) / 4f
        }
        var i = 0
        for (k in 1 until steps) if (stepS[k] > stepS[i]) i = k
        out.peakV = stepV[i]
        out.peakT = stepT[i]
        out.peakNewest = i == 0
        if (i == 0 || i == steps - 1) return
        val a = stepS[i - 1]; val b = stepS[i]; val c = stepS[i + 1] // newer, peak, older
        val den = a - 2f * b + c
        if (den >= -1e-6f) return
        val off = (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f) // + = towards the older step
        val dtNewer = stepT[i - 1] - stepT[i]
        val dtOlder = stepT[i] - stepT[i + 1]
        out.peakT = if (off < 0f) stepT[i] - off * dtNewer else stepT[i] - off * dtOlder
    }

    private fun x(f: BodyFrame, hand: Int, kind: Int) = when (kind) { 0 -> f.rwx[hand]; 1 -> f.rex[hand]; else -> f.objX }
    private fun y(f: BodyFrame, hand: Int, kind: Int) = when (kind) { 0 -> f.rwy[hand]; 1 -> f.rey[hand]; else -> f.objY }

    /** @param scale < 1 for games that use compact strokes (table tennis). */
    fun update(h: BodyHistory, tune: GestureTuning, out: MutableList<GestureEvent>, scale: Float = 1f, boost: Float = 0f) {
        val now = h.latest ?: return
        if (h.size < 3) return
        val relax = 1f - 0.4f * boost.coerceIn(0f, 1f)
        val minDist = tune.swingDistance * scale * relax
        val minSpeed = tune.swingSpeed * scale * relax
        var bestHand = -1
        var bestDisp = 0f
        var bestKind = 0
        var bestPeakT = now.t
        var bdx = 0f; var bdy = 0f; var bSpeed = 0f
        var bestStillRising = false
        for (hand in 0..1) {
            for (kind in 0..2) measure(h, hand, kind, GAIN[kind], sig[kind])
            val w = sig[0]; val e = sig[1]; val o = sig[2]
            // the wrist is being followed when it was seen in the last couple of frames; otherwise the elbow stands in
            val wristSeen = w.lastStepV > 0f
            val primary = if (wristSeen) w else e
            var rearmedNow = false
            if (!armed[hand]) {
                val sinceLast = now.t - lastEventT[hand]
                if (primary.lastStepV > 0f) {
                    // re-arm: the hand has slowed right down, clearly turned back (backswing -> swing), or kept
                    // moving for a while since its last swing
                    val stepLen = hypot(primary.lastStepX, primary.lastStepY)
                    val reversed = stepLen > 1e-4f && primary.lastStepX * lastDirX[hand] + primary.lastStepY * lastDirY[hand] < -0.5f * stepLen && sinceLast > 0.1
                    if (primary.lastStepV < minSpeed * 0.5f || reversed || sinceLast > REARM_SEC) {
                        armed[hand] = true
                        // what came before the slow-down / turn belonged to the last swing
                        usedUpTo = maxOf(usedUpTo, primary.lastStepFrom - 0.001)
                        rearmedNow = true
                    }
                } else if (sinceLast > REARM_SEC) {
                    armed[hand] = true
                    usedUpTo = maxOf(usedUpTo, now.t - 0.001)
                    rearmedNow = true
                }
            }
            // candidates, best first: the wrist and a held object's end; the elbow only when the wrist is not seen
            var handLevel = 0f
            for (kind in ORDER) {
                if (kind == 1 && wristSeen) continue
                val s = sig[kind]
                if (!s.ok) continue
                handLevel = maxOf(handLevel, minOf(s.disp / minDist, s.speed / minSpeed))
                if (rearmedNow || !armed[hand]) continue // measured afresh from the next frame
                if (s.disp >= minDist && s.speed >= minSpeed) {
                    if (s.disp > bestDisp) {
                        bestDisp = s.disp; bestHand = hand; bestKind = kind; bestPeakT = s.peakT
                        bdx = s.dx; bdy = s.dy; bSpeed = s.speed; bestStillRising = s.peakNewest
                    }
                    break
                }
            }
            level[hand] = handLevel
            // a fast wrist that just vanished (motion blur) while the game is waiting for a swing
            if (bestHand < 0 && boost > 0.3f && armed[hand] && now.rwv[hand] < MIN_VIS) {
                val blur = blurredSwing(h, hand, minSpeed)
                if (blur != null) {
                    bestHand = hand; bestKind = 0; bestPeakT = blur[0].toDouble() + now.t
                    bdx = blur[1]; bdy = blur[2]; bSpeed = blur[3]; bestDisp = minDist
                }
            }
        }
        if (bestHand < 0) { deferredAt = -9.0; return }
        // recognised while the hand is still speeding up: wait one more picture so the fastest moment is seen
        // (the swing is judged by that moment, so stamping it now would make every quick swing look early)
        if (bestStillRising && now.t - deferredAt > 0.1) { deferredAt = now.t; return }
        deferredAt = -9.0
        if (now.t < cooldownUntil) {
            // allowed sooner only for a swing back the other way (a backswing followed by the real swing)
            val back = bdx * lastDirX[bestHand] + bdy * lastDirY[bestHand] < -0.5f * hypot(bdx, bdy) && now.t - lastEventT[bestHand] > 0.12
            if (!back) return
        }
        val len = max(1e-4f, hypot(bdx, bdy))
        val power = clamp((bSpeed - minSpeed) / (minSpeed * 1.5f), 0f, 1f)
        // Forehand: the playing hand moves from its own side across the body (right hand: toward screen left).
        val playing = if (tune.hand == Hand.RIGHT) RIGHT else LEFT
        val acrossDir = if (bestHand == RIGHT) -1f else 1f
        val forehand = if (bestHand == playing) sign(bdx) == acrossDir else sign(bdx) != acrossDir
        out.add(GestureEvent.Swing(bestPeakT, bestHand, bdx / len, bdy / len, power, forehand))
        armed[0] = false; armed[1] = false
        lastDirX[bestHand] = bdx / len; lastDirY[bestHand] = bdy / len
        lastEventT[bestHand] = now.t
        usedUpTo = now.t - 0.001
        cooldownUntil = now.t + tune.swingCooldown
        lastKind = bestKind
    }

    /**
     * The wrist was seen moving fast in the last ~0.12 s and is now missing: the classic motion-blur dropout in the
     * middle of a swing. Returns [peak time offset, dx, dy, speed] or null.
     */
    private fun blurredSwing(h: BodyHistory, hand: Int, minSpeed: Float): FloatArray? {
        val nowT = h.latest?.t ?: return null
        var prev: BodyFrame? = null
        var path = 0f
        var span = 0f
        var dx = 0f; var dy = 0f
        var peakV = 0f; var peakT = 0.0
        for (k in 1 until h.size) {
            val f = h.get(k)
            if (nowT - f.t > 0.25 || f.t < usedUpTo) break
            if (f.rwv[hand] < MIN_VIS) { if (prev != null) break else continue }
            if (prev == null && nowT - f.t > 0.12) return null
            val p = prev
            if (p != null) {
                val dt = (p.t - f.t).toFloat()
                if (dt <= 0f) break
                val sx = p.rwx[hand] - f.rwx[hand]; val sy = p.rwy[hand] - f.rwy[hand]
                val step = hypot(sx, sy)
                path += step; span += dt; dx += sx; dy += sy
                if (step / dt > peakV) { peakV = step / dt; peakT = (p.t + f.t) / 2.0 }
            }
            prev = f
        }
        if (span < 0.06f || path < 0.25f) return null
        val v = path / span
        // still moving fast when it vanished, and mostly in one direction
        if (v < minSpeed * 1.3f || hypot(dx, dy) < 0.6f * path) return null
        return floatArrayOf((peakT - nowT).toFloat(), dx, dy, v)
    }

    companion object {
        /** How far back a swing is measured (s). */
        const val WINDOW = 0.22
        /** A joint counts as seen at this visibility. */
        const val MIN_VIS = 0.3f
        /** Depth is less precise than position: weighted down. */
        const val Z_WEIGHT = 0.45f
        /** Wrist-equivalent gain of each effector: wrist, elbow (moves about half as far), held object's far end. */
        val GAIN = floatArrayOf(1f, 1.8f, 0.5f)
        private val ORDER = intArrayOf(0, 2, 1)
        /** Longest gap in a joint's sightings (blurred frames) that a swing is measured across (s). */
        const val MAX_GAP = 0.17f
        /** A hand that has kept moving since its last swing re-arms after this long anyway (s). */
        const val REARM_SEC = 0.45
        const val PEAK_SEARCH_SEC = 0.25
    }
}

/** Overhand throw: the hand starts at/above shoulder height then moves fast forward/down. */
class ThrowDetector {
    private val armed = booleanArrayOf(true, true)
    private var cooldownUntil = -1.0

    fun reset() { armed.fill(true); cooldownUntil = -1.0 }

    fun update(h: BodyHistory, tune: GestureTuning, out: MutableList<GestureEvent>) {
        val now = h.latest ?: return
        val past = h.atAge(0.2) ?: return
        val prev = h.get(1)
        val dtWin = (now.t - past.t).toFloat()
        val dtStep = (now.t - prev.t).toFloat()
        if (dtWin <= 0f || dtStep <= 0f) return
        if (now.t < cooldownUntil) return
        for (hand in 0..1) {
            val stepSpeed = hypot(now.wx[hand] - prev.wx[hand], now.wy[hand] - prev.wy[hand]) / dtStep
            if (!armed[hand] && stepSpeed < tune.throwSpeed * 0.3f) armed[hand] = true
            if (!armed[hand] || now.wv[hand] < 0.2f || past.wv[hand] < 0.2f) continue
            // find the highest point of this hand within the window
            var startY = past.wy[hand]
            var startX = past.wx[hand]
            var startZ = past.wz[hand]
            for (k in 1 until h.size) {
                val f = h.get(k)
                if (now.t - f.t > 0.35) break
                if (f.wy[hand] > startY) { startY = f.wy[hand]; startX = f.wx[hand]; startZ = f.wz[hand] }
            }
            if (startY < tune.throwStartY) continue
            val dx = now.wx[hand] - startX
            val dy = now.wy[hand] - startY
            val dz = now.wz[hand] - startZ
            val d = hypot(dx, dy)
            val forward = dy < -0.25f * d || dz < -0.35f
            val speed = hypot(now.wx[hand] - past.wx[hand], now.wy[hand] - past.wy[hand]) / dtWin
            if (d >= tune.throwDistance && speed >= tune.throwSpeed && forward) {
                val aim = clamp(now.wx[hand] / 0.9f, -1f, 1f)
                val power = clamp((speed - tune.throwSpeed) / (tune.throwSpeed * 1.5f), 0f, 1f)
                out.add(GestureEvent.Throw(now.t, hand, aim, power))
                armed[0] = false; armed[1] = false
                cooldownUntil = now.t + tune.throwCooldown
                return
            }
        }
    }
}

/** Underarm bowling: the hand drops low (back-swing) then swings forward and up past waist height. */
class BowlDetector {
    private val lowSince = doubleArrayOf(-1.0, -1.0)
    private val lastLowT = doubleArrayOf(-10.0, -10.0)
    private val lowX = FloatArray(2)
    private val lowY = FloatArray(2)
    private val armed = booleanArrayOf(false, false)
    private var cooldownUntil = -1.0

    fun reset() {
        lowSince.fill(-1.0); lastLowT.fill(-10.0); armed.fill(false); cooldownUntil = -1.0
    }

    fun update(h: BodyHistory, tune: GestureTuning, out: MutableList<GestureEvent>) {
        val now = h.latest ?: return
        for (hand in 0..1) {
            if (now.wv[hand] < 0.2f) continue
            val y = now.wy[hand]
            if (y < tune.bowlLowY) {
                if (lowSince[hand] < 0) lowSince[hand] = now.t
                if (now.t - lowSince[hand] >= 0.1 && now.t >= cooldownUntil) armed[hand] = true
                if (y <= lowY[hand] || now.t - lastLowT[hand] > 0.2) { lowY[hand] = y; lowX[hand] = now.wx[hand] }
                lastLowT[hand] = now.t
                continue
            }
            lowSince[hand] = -1.0
            if (!armed[hand]) continue
            val since = (now.t - lastLowT[hand]).toFloat()
            if (since > 0.8f) { armed[hand] = false; continue }
            if (y >= tune.bowlReleaseY) {
                val travel = y - lowY[hand]
                val speed = travel / max(0.05f, since + 0.05f)
                armed[hand] = false
                if (travel >= tune.bowlTravel && speed >= tune.bowlSpeed && now.t >= cooldownUntil) {
                    val dx = now.wx[hand] - lowX[hand]
                    val angle = atan2(dx, max(0.1f, travel))
                    val aim = clamp(angle / 0.6f, -1f, 1f)
                    val power = clamp((speed - tune.bowlSpeed) / (tune.bowlSpeed * 2f), 0f, 1f)
                    out.add(GestureEvent.Bowl(now.t, hand, aim, power))
                    armed.fill(false)
                    cooldownUntil = now.t + 0.8
                    return
                }
            }
        }
    }
}

/** Continuous lean in [-1, 1] (negative = player's left = screen left) with dead zone. */
class LeanTracker {
    var value = 0f
        private set
    var raw = 0f
        private set

    fun reset() { value = 0f; raw = 0f }

    fun update(f: BodyFrame, tune: GestureTuning, dt: Float) {
        val r = if (f.hipsVisible) f.tilt + 0.35f * f.shift else f.shift
        raw = r
        var v = r / tune.leanRange
        v = if (abs(v) < tune.leanDeadZone) 0f else (v - sign(v) * tune.leanDeadZone) / (1f - tune.leanDeadZone)
        v = clamp(v, -1f, 1f)
        // light smoothing (the landmarks are already smoothed); quick enough for steering
        val a = clamp(dt / 0.05f, 0f, 1f)
        value += a * (v - value)
    }
}

/**
 * Alternating arm pumping (left up while right down, then swap). Speed comes from stroke *cadence*, capped, so
 * bigger or wilder movements do not win: any stroke beyond the (mode-dependent) minimum amplitude counts the same.
 */
class ArmPumpDetector {
    private var phase = 0
    private val strokes = ArrayDeque<Double>()
    var speed = 0f
        private set
    var strokeCount = 0
        private set

    fun reset() { phase = 0; strokes.clear(); speed = 0f; strokeCount = 0 }

    fun update(f: BodyFrame, tune: GestureTuning, dt: Float) {
        if (f.wv[0] > 0.2f && f.wv[1] > 0.2f) {
            val diff = f.wy[0] - f.wy[1]
            val newPhase = if (diff > tune.pumpAmplitude) 1 else if (diff < -tune.pumpAmplitude) -1 else phase
            if (newPhase != phase && newPhase != 0) {
                if (phase != 0) {
                    val last = strokes.lastOrNull()
                    if (last == null || f.t - last > 0.12) { strokes.addLast(f.t); strokeCount++ } // ignore jitter
                }
                phase = newPhase
            }
        }
        while (strokes.isNotEmpty() && f.t - strokes.first() > 1.5) strokes.removeFirst()
        val cadence = if (strokes.size >= 2) (strokes.size - 1) / max(0.35, strokes.last() - strokes.first()).toFloat() else if (strokes.size == 1) 0.8f else 0f
        val sinceLast = if (strokes.isEmpty()) 9.0 else f.t - strokes.last()
        val decay = if (sinceLast > 0.7) clamp(1f - ((sinceLast - 0.7) / 0.6).toFloat(), 0f, 1f) else 1f
        val target = clamp(cadence / tune.pumpMaxCadence, 0f, 1f) * decay
        val a = clamp(dt / 0.25f, 0f, 1f)
        speed += a * (target - speed)
    }
}

/** Hand raised and held (start / confirm). Also tracks both-hands-up for shields and pause. */
class RaiseDetector {
    var progress = 0f
        private set
    var handsUp = false
        private set
    private var fired = false
    private var raisedHand = -1

    fun reset() { progress = 0f; handsUp = false; fired = false; raisedHand = -1 }

    private fun isUp(f: BodyFrame, hand: Int, tune: GestureTuning): Boolean {
        if (f.wv[hand] < 0.3f) return false
        return if (tune.handsUpAboveNose) f.wy[hand] > f.noseY + tune.handsUpY - 0.1f else f.wy[hand] > tune.handsUpY
    }

    fun update(f: BodyFrame, tune: GestureTuning, dt: Float, out: MutableList<GestureEvent>) {
        val l = isUp(f, LEFT, tune)
        val r = isUp(f, RIGHT, tune)
        val bothThreshold = if (tune.handsUpAboveNose) f.noseY + tune.handsUpY else tune.handsUpY + 0.15f
        val bothUp = f.wv[0] > 0.3f && f.wv[1] > 0.3f && f.wy[0] > bothThreshold && f.wy[1] > bothThreshold
        val bothDown = f.wy[0] < bothThreshold - 0.25f || f.wy[1] < bothThreshold - 0.25f || f.wv[0] < 0.2f || f.wv[1] < 0.2f
        if (!handsUp && bothUp) { handsUp = true; out.add(GestureEvent.HandsUp(f.t)) } else if (handsUp && bothDown) handsUp = false

        if (l || r) {
            if (!fired) {
                progress = min(1f, progress + dt / tune.raiseHoldSec)
                raisedHand = if (r && (!l || f.wy[RIGHT] >= f.wy[LEFT])) RIGHT else LEFT
                if (progress >= 1f) { fired = true; out.add(GestureEvent.RaiseHold(f.t, raisedHand)) }
            }
        } else {
            progress = max(0f, progress - dt * 2f)
            if (progress <= 0f) fired = false
        }
    }
}
