package com.clrg.core.gesture

import com.clrg.core.math.angleDiff
import com.clrg.core.math.clamp
import com.clrg.core.tracking.Calibration
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import com.clrg.core.tracking.PlayerSlot
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Arm segment angles (radians, body space: 0 = screen right, PI/2 = up) for drawing a mirrored character. */
class AvatarPose {
    /** [LEFT, RIGHT] upper arm (shoulder -> elbow) and forearm (elbow -> wrist) angles. */
    val upper = floatArrayOf((-PI / 2 - 0.2).toFloat(), (-PI / 2 + 0.2).toFloat())
    val fore = floatArrayOf((-PI / 2 - 0.1).toFloat(), (-PI / 2 + 0.1).toFloat())
    /** Sideways lean of the torso (radians, + = toward screen right). */
    var tilt = 0f
    /**
     * A held object (stick / toy racket) seen in the player's hand: its direction (body-space angle, like [fore]), the
     * hand holding it, and its length in body units. NaN angle = none: props follow the forearm.
     */
    var propAngle = Float.NaN
    var propHand = -1
    var propLen = 0f
    /** Sideways tilt of the head relative to the body (radians, + = toward screen right). */
    var head = 0f
    /** Rise of the body from its neutral height (S units: + = up on tiptoes / jumping, - = crouching). */
    var rise = 0f
    var valid = false

    fun update(f: BodyFrame, lean: Float) {
        for (h in 0..1) {
            if (f.wv[h] < 0.15f) continue
            upper[h] = atan2(f.ey[h] - f.sy[h], f.ex[h] - f.sx[h])
            fore[h] = atan2(f.wy[h] - f.ey[h], f.wx[h] - f.ex[h])
        }
        tilt = lean * 0.35f
        head = (atan2(f.noseX, maxOf(f.noseY, 0.15f)) * 0.9f - tilt).coerceIn(-0.6f, 0.6f)
        rise = f.rise.coerceIn(-0.9f, 0.6f)
        valid = true
    }

    fun copyFrom(o: AvatarPose) {
        o.upper.copyInto(upper); o.fore.copyInto(fore); tilt = o.tilt; head = o.head; rise = o.rise; valid = o.valid
        propAngle = o.propAngle; propHand = o.propHand; propLen = o.propLen
    }
}

/** A target arm shape for the dance game: segment angles in degrees (body space, 0 = right, 90 = up). */
class PoseShape(val id: String, val name: String, val leftUpper: Float, val leftFore: Float, val rightUpper: Float, val rightFore: Float) {
    private fun rad(d: Float) = (d * PI / 180.0).toFloat()

    /** 0..1 similarity of [a] to this shape with angular tolerance [tolDeg] per segment. */
    fun similarity(a: AvatarPose, tolDeg: Float): Float {
        if (!a.valid) return 0f
        val tol = rad(tolDeg)
        fun seg(actual: Float, targetDeg: Float, weight: Float) = weight * clamp(1f - angleDiff(actual, rad(targetDeg)) / tol, 0f, 1f)
        val s = seg(a.upper[LEFT], leftUpper, 1.2f) + seg(a.fore[LEFT], leftFore, 0.8f) +
            seg(a.upper[RIGHT], rightUpper, 1.2f) + seg(a.fore[RIGHT], rightFore, 0.8f)
        return s / 4f
    }

    /** Builds the avatar angles for this shape (used to animate the elf and for tests). */
    fun toAvatar(out: AvatarPose) {
        out.upper[LEFT] = rad(leftUpper); out.fore[LEFT] = rad(leftFore)
        out.upper[RIGHT] = rad(rightUpper); out.fore[RIGHT] = rad(rightFore)
        out.tilt = 0f; out.valid = true
    }

    companion object {
        // Standard set: every shape differs from every other by >= 45 degrees on at least two segments, so the
        // 2D tracker can tell them apart reliably. Left arm = screen left (mirror view).
        val T_POSE = PoseShape("t", "Snow Angel", 180f, 180f, 0f, 0f)
        val STAR = PoseShape("star", "Reach for the Star", 105f, 100f, 75f, 80f)
        val VICTORY = PoseShape("v", "Victory V", 135f, 135f, 45f, 45f)
        val WAVE_LEFT = PoseShape("wl", "Left Hand Up", 100f, 95f, -80f, -85f)
        val WAVE_RIGHT = PoseShape("wr", "Right Hand Up", -100f, -95f, 80f, 85f)
        val GOAL_POSTS = PoseShape("gp", "Strong Elf", 180f, 90f, 0f, 90f)
        val DISCO_LEFT = PoseShape("dl", "Disco Left", 135f, 135f, -45f, -45f)
        val DISCO_RIGHT = PoseShape("dr", "Disco Right", -135f, -135f, 45f, 45f)
        val LOW_V = PoseShape("lv", "Jingle Low", -135f, -135f, -45f, -45f)
        val TOPPER = PoseShape("top", "Tree Topper", 100f, 95f, 0f, 0f)

        // Reduced / seated set: nothing above shoulder height except forearms; same distinctness rule.
        val HALF_WAVE_LEFT = PoseShape("hwl", "Little Wave Left", 180f, 90f, -80f, -85f)
        val HALF_WAVE_RIGHT = PoseShape("hwr", "Little Wave Right", -100f, -95f, 0f, 90f)
        val ARMS_OUT_LOW = PoseShape("aol", "Wide Low Arms", -160f, -160f, -20f, -20f)

        val STANDARD_SET = listOf(T_POSE, STAR, VICTORY, WAVE_LEFT, WAVE_RIGHT, GOAL_POSTS, DISCO_LEFT, DISCO_RIGHT, LOW_V, TOPPER)
        val GENTLE_SET = listOf(T_POSE, GOAL_POSTS, LOW_V, HALF_WAVE_LEFT, HALF_WAVE_RIGHT, ARMS_OUT_LOW)

        fun setFor(mode: MovementMode) = if (mode == MovementMode.STANDARD) STANDARD_SET else GENTLE_SET
        fun toleranceFor(mode: MovementMode) = if (mode == MovementMode.STANDARD) 55f else 70f
    }
}

/**
 * All motion controls for one player, fed from the tracker. Every minigame reads the same interface
 * ([PlayerInput]); none of them touch landmarks directly.
 */
class PlayerControls(val playerIndex: Int) {
    var calibration: Calibration? = null
        set(value) {
            field = value
            tuning = GestureTuning(value?.mode ?: MovementMode.STANDARD, value?.dominantHand ?: Hand.RIGHT, value?.reach ?: 1.5f, sensitivity)
        }
    var sensitivity = 1f
        set(value) { field = value; calibration = calibration }
    var tuning = GestureTuning(MovementMode.STANDARD, Hand.RIGHT)
        private set
    /** Set by games that use small, quick strokes (table tennis): swing thresholds are scaled down. */
    var compactSwings = false
    /**
     * 0..1, set by a game while it is waiting for this player to swing (a ball is arriving): the swing detector
     * becomes more sensitive, so a gentle or partly-seen swing still counts when it matters.
     */
    var swingBoost = 0f
    /** Live swing strength per hand, 0..1+ of what counts as a swing (motion test screen). */
    val swingLevel: FloatArray get() = swing.level
    /** Which part of the arm saw the last swing: 0 wrist, 1 elbow, 2 held object. */
    val lastSwingSource: Int get() = swing.lastKind
    /** Golf: which stroke to look for (NONE outside golf). */
    /** Set while a game needs finger tracking (finger guns): the camera thread then follows this player's hands. */
    var wantFingers = false
    /** Aim, finger gun and shots (shooting games). */
    val gunCtl = GunControls()
    /** Punches (boxing). */
    val punch = PunchDetector()
    /** Hands the finger tracker should follow now (see [GunControls.fingerMask]); 0 when no game asks for fingers. */
    val fingerMask: Int get() = if (wantFingers && hasPose) gunCtl.fingerMask(history.latest) else 0

    var golfStroke = GolfStroke.NONE
    val golf = GolfSwingDetector()
    private val golfShots = ArrayList<GolfSwingDetector.Shot>()

    val history = BodyHistory()
    private val frame = BodyFrame()
    /** Latest frame moved forward to the render time (latency compensation for what is shown on screen). */
    private val predicted = BodyFrame()
    /** Smoothed joint velocities in S units/s: wrists, elbows (x, y per side), nose (x, y), rise. */
    private val vel = FloatArray(11)
    private val swing = SwingDetector()
    private val throwD = ThrowDetector()
    private val bowl = BowlDetector()
    private val leanT = LeanTracker()
    private val pump = ArmPumpDetector()
    private val raise = RaiseDetector()
    private val pending = ArrayList<GestureEvent>()
    private var lastUpdate = -1L
    private var lastT = -1.0

    val avatar = AvatarPose()
    val lean: Float get() = leanT.value
    val pumpSpeed: Float get() = pump.speed
    val raiseProgress: Float get() = raise.progress
    val handsUp: Boolean get() = raise.handsUp
    var tuck = false
        private set
    /** Hand positions in the player's reach space: (0,0) = shoulder centre, 1 = comfortable reach; y up. */
    val handX = FloatArray(2)
    val handY = FloatArray(2)
    val handVisible = BooleanArray(2)
    /**
     * Hand positions for pointing (menus): reach space like [handX], measured and smoothed but *not* moved forward
     * in time - prediction is right for characters, but makes a pointer overshoot and wobble.
     */
    val pointX = FloatArray(2)
    val pointY = FloatArray(2)
    val pointSeen = BooleanArray(2)
    var hasPose = false
        private set

    fun reset() {
        history.clear()
        swing.reset(); throwD.reset(); bowl.reset(); leanT.reset(); pump.reset(); raise.reset()
        golf.reset(); golfShots.clear()
        gunCtl.reset(); punch.reset()
        pending.clear()
        lastT = -1.0
        hasPose = false
        pointSeen.fill(false)
    }

    /** Call after each tracker update; does nothing unless the slot received new pose data. */
    fun update(slot: PlayerSlot, tSec: Double) {
        if (slot.updateCount == lastUpdate) return
        lastUpdate = slot.updateCount
        if (slot.state != com.clrg.core.tracking.SlotState.ACTIVE) return
        if (!BodyFrame.fill(frame, slot, tSec, calibration)) return
        val dt = if (lastT < 0) 0f else (tSec - lastT).toFloat().coerceIn(0f, 0.25f)
        if (lastT >= 0 && tSec - lastT > 0.5) history.clear() // gap: don't detect across a tracking gap
        lastT = tSec
        if (history.size > 0 && dt > 0f) {
            val p = history.get(0)
            fun v(i: Int, cur: Float, prev: Float) { vel[i] += 0.5f * ((cur - prev) / dt - vel[i]) }
            for (h in 0..1) {
                v(h * 2, frame.wx[h], p.wx[h]); v(h * 2 + 1, frame.wy[h], p.wy[h])
                v(4 + h * 2, frame.ex[h], p.ex[h]); v(5 + h * 2, frame.ey[h], p.ey[h])
            }
            v(8, frame.noseX, p.noseX); v(9, frame.noseY, p.noseY); v(10, frame.rise, p.rise)
        } else vel.fill(0f)
        history.push(frame)
        hasPose = true
        val t = tuning
        swing.update(history, t, pending, if (compactSwings) 0.7f else 1f, swingBoost)
        throwD.update(history, t, pending)
        bowl.update(history, t, pending)
        leanT.update(frame, t, dt)
        pump.update(frame, t, dt)
        raise.update(frame, t, dt, pending)
        if (golfStroke == GolfStroke.NONE) golf.reset()
        else golf.update(frame, golfStroke, calibration?.mode ?: MovementMode.STANDARD, calibration?.reach ?: 1.5f, sensitivity)?.let { golfShots.add(it) }
        avatar.update(frame, leanT.value)
        updateProp(dt)
        val reach = calibration?.reach ?: 1.5f
        val dominant = if (calibration?.dominantHand == Hand.LEFT) LEFT else RIGHT
        gunCtl.update(frame, reach, dominant, if (frame.objConf >= 0.25f) frame.objHand else -1, sensitivity, pending)
        punch.update(history, reach, calibration?.mode ?: MovementMode.STANDARD, sensitivity, pending)
        for (h in 0..1) {
            handX[h] = frame.wx[h] / reach
            handY[h] = frame.wy[h] / reach
            handVisible[h] = frame.wv[h] > 0.3f
            pointX[h] = handX[h]; pointY[h] = handY[h]; pointSeen[h] = handVisible[h]
        }
        tuck = frame.wv[0] > 0.3f && frame.wv[1] > 0.3f &&
            hypot(frame.wx[0] - frame.wx[1], frame.wy[0] - frame.wy[1]) < 0.6f &&
            frame.wy[0] in -1.0f..0.35f && frame.wy[1] in -1.0f..0.35f
    }

    /**
     * Latency compensation for everything the player *sees* (their character's arms and the hand positions used by
     * reach games and menu cursors): the latest tracked pose is moved forward to [nowSec] along its smoothed velocity
     * (at most [MAX_LEAD] s, damped, and limited in distance). This also turns 30 fps tracking into smooth 60 fps
     * motion. Gesture detection keeps using the measured frames only.
     */
    fun predict(nowSec: Double) {
        if (!hasPose) return
        val f = history.latest ?: return
        var lead = (nowSec - f.t).toFloat()
        if (lead < -0.05f || lead > 0.5f) lead = 0f // different clocks (simulation) or stale data: show as measured
        lead = lead.coerceIn(0f, MAX_LEAD) * LEAD_GAIN
        predicted.copyFrom(f)
        fun d(i: Int) = (vel[i] * lead).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        for (h in 0..1) {
            predicted.wx[h] = f.wx[h] + d(h * 2); predicted.wy[h] = f.wy[h] + d(h * 2 + 1)
            predicted.ex[h] = f.ex[h] + d(4 + h * 2); predicted.ey[h] = f.ey[h] + d(5 + h * 2)
        }
        predicted.noseX = f.noseX + d(8); predicted.noseY = f.noseY + d(9); predicted.rise = f.rise + d(10)
        avatar.update(predicted, leanT.value)
        val reach = calibration?.reach ?: 1.5f
        for (h in 0..1) {
            handX[h] = predicted.wx[h] / reach
            handY[h] = predicted.wy[h] / reach
        }
    }

    companion object {
        /** Longest time the shown pose is moved forward (s). */
        const val MAX_LEAD = 0.12f
        /** Fraction of the full extrapolation used (damps overshoot when a movement stops). */
        const val LEAD_GAIN = 0.75f
        /** Largest forward shift of a joint (S units). */
        const val MAX_SHIFT = 0.35f
    }

    private var propLost = 0f

    /** Held object: smoothed direction from the tracked object; kept a moment through a missed frame or two. */
    private fun updateProp(dt: Float) {
        val f = frame
        if (f.objHand >= 0 && f.objConf >= 0.25f) {
            val a = kotlin.math.atan2(f.objY - f.objBaseY, f.objX - f.objBaseX)
            val len = kotlin.math.hypot(f.objX - f.objBaseX, f.objY - f.objBaseY)
            if (avatar.propAngle.isNaN() || avatar.propHand != f.objHand) { avatar.propAngle = a; avatar.propLen = len }
            else {
                avatar.propAngle = com.clrg.core.math.normalizeRadians(avatar.propAngle + com.clrg.core.math.normalizeRadians(a - avatar.propAngle) * 0.7f)
                avatar.propLen += (len - avatar.propLen) * 0.3f
            }
            avatar.propHand = f.objHand
            propLost = 0f
        } else {
            propLost += dt
            if (propLost > 0.4f) { avatar.propAngle = Float.NaN; avatar.propHand = -1 }
        }
        hasObject = !avatar.propAngle.isNaN()
    }

    /** True while a held object is being followed in this player's hand. */
    var hasObject = false
        private set

    /** Moves pending events into [out] (called once per game tick). */
    fun drainEvents(out: MutableList<GestureEvent>) {
        out.addAll(pending)
        pending.clear()
    }

    fun discardEvents() { pending.clear(); golfShots.clear() }

    /** Golf shots struck since the last call. */
    fun drainGolfShots(out: MutableList<GolfSwingDetector.Shot>) { out.addAll(golfShots); golfShots.clear() }
}
