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
    var valid = false

    fun update(f: BodyFrame, lean: Float) {
        for (h in 0..1) {
            if (f.wv[h] < 0.15f) continue
            upper[h] = atan2(f.ey[h] - f.sy[h], f.ex[h] - f.sx[h])
            fore[h] = atan2(f.wy[h] - f.ey[h], f.wx[h] - f.ex[h])
        }
        tilt = lean * 0.35f
        valid = true
    }

    fun copyFrom(o: AvatarPose) {
        o.upper.copyInto(upper); o.fore.copyInto(fore); tilt = o.tilt; valid = o.valid
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

    val history = BodyHistory()
    private val frame = BodyFrame()
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
    var hasPose = false
        private set

    fun reset() {
        history.clear()
        swing.reset(); throwD.reset(); bowl.reset(); leanT.reset(); pump.reset(); raise.reset()
        pending.clear()
        lastT = -1.0
        hasPose = false
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
        history.push(frame)
        hasPose = true
        val t = tuning
        swing.update(history, t, pending)
        throwD.update(history, t, pending)
        bowl.update(history, t, pending)
        leanT.update(frame, t, dt)
        pump.update(frame, t, dt)
        raise.update(frame, t, dt, pending)
        avatar.update(frame, leanT.value)
        val reach = calibration?.reach ?: 1.5f
        for (h in 0..1) {
            handX[h] = frame.wx[h] / reach
            handY[h] = frame.wy[h] / reach
            handVisible[h] = frame.wv[h] > 0.3f
        }
        tuck = frame.wv[0] > 0.3f && frame.wv[1] > 0.3f &&
            hypot(frame.wx[0] - frame.wx[1], frame.wy[0] - frame.wy[1]) < 0.6f &&
            frame.wy[0] in -1.0f..0.35f && frame.wy[1] in -1.0f..0.35f
    }

    /** Moves pending events into [out] (called once per game tick). */
    fun drainEvents(out: MutableList<GestureEvent>) {
        out.addAll(pending)
        pending.clear()
    }

    fun discardEvents() = pending.clear()
}
