package com.clrg.core.game

import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.math.clamp
import com.clrg.core.math.clamp01
import com.clrg.core.math.lerp
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Computer opponent difficulty for solo play. [skill] (0..1) is what every game's CPU player uses; it sets reaction
 * time, hand speed and accuracy. The CPU never sees anything the human player cannot see.
 */
enum class CpuLevel(val label: String, val botName: String, val skill: Float, val blurb: String, val colorIndex: Int) {
    EASY("Easy", "Sprout", 0.3f, "Slow to react and makes plenty of mistakes", 7),
    MEDIUM("Medium", "Jingles", 0.6f, "A fair match for most players", 4),
    HARD("Hard", "Blitz", 0.88f, "Quick and accurate - a real challenge", 3);

    companion object {
        fun fromName(name: String?) = values().firstOrNull { it.name == name } ?: MEDIUM
    }
}

/** Human-like limits shared by the CPU players of all games, derived from skill (0..1). */
object CpuTraits {
    /** Seconds before the CPU starts reacting to something new. */
    fun reaction(skill: Float) = lerp(0.62f, 0.2f, clamp01(skill))
    /** Hand speed in reach units per second (1 = comfortable arm's reach). */
    fun handSpeed(skill: Float) = lerp(1.7f, 3.8f, clamp01(skill))

    /** Moves a reach-space hand toward a target at most [speed] * dt. */
    fun moveHand(inp: PlayerInput, h: Int, tx: Float, ty: Float, speed: Float, dt: Float) {
        val dx = tx - inp.handX[h]
        val dy = ty - inp.handY[h]
        val d = hypot(dx, dy)
        val step = speed * dt
        if (d <= step || d < 1e-5f) { inp.handX[h] = tx; inp.handY[h] = ty } else { inp.handX[h] += dx / d * step; inp.handY[h] += dy / d * step }
        inp.handVisible[h] = true
    }
}

/**
 * Gives the CPU's character believable arm movements. Camera players' characters copy their real arms; the CPU has
 * no body, so this turns what it does (hand positions, swings, throws, bowls, arm pumping, leaning) into an
 * [AvatarPose]. Purely visual: it never changes game state.
 */
class BotBody {
    private enum class Anim(val duration: Float) { NONE(0f), SWING(0.34f), THROW(0.5f), BOWL(0.65f), HANDS_UP(0.9f) }

    private var anim = Anim.NONE
    private var animT = 0f
    private var forehand = true
    private var pumpPhase = 0f
    private var t = 0f
    /** Current (smoothed) wrist positions in body units, relative to the shoulder centre, y up. */
    private val wx = floatArrayOf(-0.42f, 0.42f)
    private val wy = floatArrayOf(-1.05f, -1.05f)
    private val tx = FloatArray(2)
    private val ty = FloatArray(2)

    fun reset(inp: PlayerInput) {
        anim = Anim.NONE
        wx[0] = -0.42f; wx[1] = 0.42f; wy[0] = -1.05f; wy[1] = -1.05f
        write(inp.avatar, 0f)
    }

    /**
     * Advances the animation by [dt]. [newEvents] are the gestures the CPU performed this step. When [poseOwnedByGame]
     * (the dance game poses its CPU dancer itself) only validity is ensured.
     */
    fun step(inp: PlayerInput, dt: Float, newEvents: List<GestureEvent>, poseOwnedByGame: Boolean) {
        t += dt
        if (poseOwnedByGame) { inp.avatar.valid = true; inp.avatar.tilt = inp.lean * 0.35f; return }
        for (e in newEvents) when (e) {
            is GestureEvent.Swing -> { anim = Anim.SWING; animT = 0f; forehand = e.forehand }
            is GestureEvent.Throw -> { anim = Anim.THROW; animT = 0f }
            is GestureEvent.Bowl -> { anim = Anim.BOWL; animT = 0f }
            is GestureEvent.HandsUp -> { anim = Anim.HANDS_UP; animT = 0f }
            else -> Unit
        }
        if (anim != Anim.NONE) { animT += dt; if (animT >= anim.duration) anim = Anim.NONE }
        val h = inp.dominant
        val sd = if (h == 0) -1f else 1f
        val reach = 1.5f
        // default: relaxed arms with a little breathing
        for (i in 0..1) { tx[i] = if (i == 0) -0.42f else 0.42f; ty[i] = -1.02f + 0.03f * sin(t * 2f + i) }
        var fast = false
        when {
            anim != Anim.NONE -> {
                val p = clamp01(animT / anim.duration)
                fast = true
                when (anim) {
                    Anim.SWING -> {
                        val dir = if (forehand) 1f else -1f
                        val e = smooth(p)
                        // the playing hand sweeps across the chest (forehand from its own side, backhand the other way)
                        tx[h] = sd * dir * lerp(0.95f, -0.55f, e) * reach * 0.8f
                        ty[h] = (lerp(-0.05f, 0.2f, e) - 0.35f * sin(e * PI.toFloat())) * reach * 0.8f
                    }
                    Anim.THROW -> {
                        val (x, y) = path(p, floatArrayOf(0f, 0.4f, 0.6f, 1f), floatArrayOf(0.3f, 0.35f, -0.05f, -0.2f), floatArrayOf(-0.5f, 0.75f, 0.5f, -0.3f))
                        tx[h] = sd * x * reach; ty[h] = y * reach
                    }
                    Anim.BOWL -> {
                        val (x, y) = path(p, floatArrayOf(0f, 0.45f, 0.75f, 1f), floatArrayOf(0.3f, 0.45f, 0.2f, 0.15f), floatArrayOf(-0.55f, -0.75f, 0.0f, 0.25f))
                        tx[h] = sd * x * reach; ty[h] = y * reach
                    }
                    Anim.HANDS_UP -> for (i in 0..1) { tx[i] = (if (i == 0) -0.3f else 0.3f) * reach; ty[i] = 0.9f * reach }
                    Anim.NONE -> Unit
                }
            }
            inp.pump > 0.05f -> {
                pumpPhase += dt * (2f * PI.toFloat()) * (1.1f + 1.6f * inp.pump)
                for (i in 0..1) {
                    val s = sin(pumpPhase + i * PI.toFloat())
                    tx[i] = (if (i == 0) -0.45f else 0.45f); ty[i] = -0.35f + 0.4f * s * (0.5f + 0.5f * inp.pump)
                }
                fast = true
            }
            inp.tuck -> for (i in 0..1) { tx[i] = if (i == 0) -0.1f else 0.1f; ty[i] = -0.1f }
            else -> for (i in 0..1) if (inp.handVisible[i]) { tx[i] = inp.handX[i] * reach; ty[i] = inp.handY[i] * reach }
        }
        // ease toward the targets (fast during scripted movements so they keep their shape)
        val k = clamp01(dt * if (fast) 30f else 14f)
        for (i in 0..1) { wx[i] += (tx[i] - wx[i]) * k; wy[i] += (ty[i] - wy[i]) * k }
        write(inp.avatar, inp.lean * 0.35f)
    }

    /** Two-bone arm IK (body units: shoulders at +-0.37, upper arm 0.62, forearm 0.56), elbows kept low. */
    private fun write(a: AvatarPose, tilt: Float) {
        val l1 = 0.62f
        val l2 = 0.56f
        for (i in 0..1) {
            val sx = if (i == 0) -0.37f else 0.37f
            var dx = wx[i] - sx
            var dy = wy[i]
            val d0 = hypot(dx, dy)
            val d = clamp(d0, 0.12f, l1 + l2 - 0.002f)
            if (d0 > 1e-4f) { dx *= d / d0; dy *= d / d0 } else { dx = 0f; dy = -d }
            val base = atan2(dy, dx)
            val c = clamp((l1 * l1 + d * d - l2 * l2) / (2f * l1 * d), -1f, 1f)
            val off = acos(c)
            val u1 = base + off
            val u2 = base - off
            val upper = if (sin(u1) < sin(u2)) u1 else u2
            val ex = l1 * cos(upper)
            val ey = l1 * sin(upper)
            a.upper[i] = upper
            a.fore[i] = atan2(dy - ey, dx - ex)
        }
        a.tilt = tilt
        a.valid = true
    }

    private fun smooth(p: Float) = p * p * (3f - 2f * p)

    /** Piecewise-linear path through keyframes (times [ts], positions [xs], [ys]). */
    private fun path(p: Float, ts: FloatArray, xs: FloatArray, ys: FloatArray): Pair<Float, Float> {
        for (i in 1 until ts.size) {
            if (p <= ts[i]) {
                val f = smooth(clamp01((p - ts[i - 1]) / (ts[i] - ts[i - 1])))
                return lerp(xs[i - 1], xs[i], f) to lerp(ys[i - 1], ys[i], f)
            }
        }
        return xs.last() to ys.last()
    }
}
