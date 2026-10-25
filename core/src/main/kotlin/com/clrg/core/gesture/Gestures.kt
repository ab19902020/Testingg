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

    override fun toString(): String = "${this::class.simpleName}(t=%.3f, hand=$hand)".format(t)
}

/**
 * Gesture thresholds in body units (S = torso length), per movement mode. Reduced-movement and seated modes
 * need smaller, slower motions; everything is additionally scaled by the player's calibrated reach.
 */
class GestureTuning(val mode: MovementMode, val hand: Hand, reach: Float = 1.5f, sensitivity: Float = 1f) {
    private val k = clamp(reach / 1.5f, 0.55f, 1.3f) / clamp(sensitivity, 0.5f, 2f)
    private val std = mode == MovementMode.STANDARD
    private val seated = mode == MovementMode.SEATED

    val swingDistance = (if (std) 0.7f else 0.38f) * k
    val swingSpeed = (if (std) 3.2f else 1.8f) * k
    val swingCooldown = 0.35f

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

/** Detects fast arm swings with either hand (shared cooldown so a two-handed swing counts once). */
class SwingDetector {
    private val armed = booleanArrayOf(true, true)
    private var cooldownUntil = -1.0

    fun reset() { armed.fill(true); cooldownUntil = -1.0 }

    /** @param scale < 1 for games that use compact strokes (table tennis). */
    fun update(h: BodyHistory, tune: GestureTuning, out: MutableList<GestureEvent>, scale: Float = 1f) {
        val now = h.latest ?: return
        val past = h.atAge(0.15) ?: return
        val prev = h.get(1)
        val dtWin = (now.t - past.t).toFloat()
        val dtStep = (now.t - prev.t).toFloat()
        if (dtWin <= 0f || dtStep <= 0f) return
        val minDist = tune.swingDistance * scale
        val minSpeed = tune.swingSpeed * scale
        var bestHand = -1
        var bestDist = 0f
        for (hand in 0..1) {
            val stepSpeed = hypot(now.wx[hand] - prev.wx[hand], now.wy[hand] - prev.wy[hand]) / dtStep
            if (!armed[hand] && stepSpeed < minSpeed * 0.35f) armed[hand] = true // hysteresis re-arm
            if (!armed[hand] || now.wv[hand] < 0.2f || past.wv[hand] < 0.2f) continue
            val d = hypot(now.wx[hand] - past.wx[hand], now.wy[hand] - past.wy[hand])
            val speed = d / dtWin
            if (d >= minDist && speed >= minSpeed && d > bestDist) { bestDist = d; bestHand = hand }
        }
        if (bestHand < 0 || now.t < cooldownUntil) return
        val dx = now.wx[bestHand] - past.wx[bestHand]
        val dy = now.wy[bestHand] - past.wy[bestHand]
        val len = max(1e-4f, hypot(dx, dy))
        val speed = len / dtWin
        val power = clamp((speed - minSpeed) / (minSpeed * 1.5f), 0f, 1f)
        // Forehand: the playing hand moves from its own side across the body (right hand: toward screen left).
        val playing = if (tune.hand == Hand.RIGHT) RIGHT else LEFT
        val acrossDir = if (bestHand == RIGHT) -1f else 1f
        val forehand = if (bestHand == playing) sign(dx) == acrossDir else sign(dx) != acrossDir
        out.add(GestureEvent.Swing(peakTime(h, bestHand, now.t), bestHand, dx / len, dy / len, power, forehand))
        armed[0] = false; armed[1] = false
        cooldownUntil = now.t + tune.swingCooldown
    }

    /**
     * When the swing happened: the moment the hand moved fastest (where a racket meets the ball), looked up in the
     * last [PEAK_SEARCH_SEC]. A swing is only recognised once enough distance has been covered, which is usually a
     * little after that moment; stamping the event with the fastest moment keeps the judged timing independent of
     * how quickly the detector fired.
     */
    private fun peakTime(h: BodyHistory, hand: Int, fallback: Double): Double {
        val nowT = h.latest?.t ?: return fallback
        var best = -1f
        var t = fallback
        for (k in 0 until h.size - 1) {
            val a = h.get(k); val b = h.get(k + 1)
            if (nowT - b.t > PEAK_SEARCH_SEC) break
            val dt = (a.t - b.t).toFloat()
            if (dt <= 0f) continue
            val v = hypot(a.wx[hand] - b.wx[hand], a.wy[hand] - b.wy[hand]) / dt
            if (v > best) { best = v; t = (a.t + b.t) / 2.0 }
        }
        return t
    }

    companion object {
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
