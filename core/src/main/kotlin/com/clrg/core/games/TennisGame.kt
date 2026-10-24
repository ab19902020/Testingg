package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.math.PI_F
import com.clrg.core.math.Rect
import com.clrg.core.math.clamp01
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Prop
import kotlin.math.max
import kotlin.math.sin

/** 1. Christmas Tennis - side-on festive court, first to seven points. */
class TennisGame(env: GameEnv) : RallyGame(env) {
    override val info = INFO

    override val cfg = Config(
        gravity = 2.8f, netH = 0.11f, hitH = 0.2f, window = 0.32f,
        slowFlight = 1.3f, fastFlight = 0.85f, speedupPerHit = 0.025f, minFlightFactor = 0.72f,
        pointsToWin = 7, serveEvery = 1, restitution = 0.72f, friction = 0.9f,
        homeX = 0.95f, moveSpeed = 3.2f, racketReach = 0.09f, autoServeSec = 8f,
    )

    private val groundY = 850f
    private fun unit() = screenW * 0.3f
    override fun toScreenX(x: Float) = screenW / 2f + x * unit()
    override fun toScreenY(h: Float) = groundY - h * unit()

    override fun status(): String? = when (state) {
        State.SERVE -> "${env.name(server)}: swing to serve"
        State.POINT_OVER -> "${env.name(pointWinner)} wins the point - ${pointReason.text}"
        else -> null
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        val w = g.width
        Draw.nightSky(g, Rect(0f, 0f, w, g.height), time)
        Draw.pineRow(g, 0f, w, 640f, 0xFF143A2E.toInt(), 150f, 3)
        Draw.snowHills(g, Rect(0f, 0f, w, g.height), 700f)
        // crowd of snowmen behind the court
        for (i in 0 until 9) {
            val cx = w * (0.08f + i * 0.105f)
            val bob = sin((time * 3 + i).toFloat()) * 4f
            Draw.buddy(g, cx, 742f + bob, 110f, com.clrg.core.render.PlayerColors.get(i + 2), null, Expression.HAPPY, t = time + i)
        }
        Draw.stringLights(g, 0f, w, 10f, time)
        // court
        val l = toScreenX(-1f); val r = toScreenX(1f)
        val pts = floatArrayOf(l - 70f, groundY + 60f, r + 70f, groundY + 60f, r - 30f, groundY - 70f, l + 30f, groundY - 70f)
        g.fillPolygon(pts, 4, 0xFFB23A3A.toInt())
        g.polyline(pts, 4, 6f, Colors.WHITE, closed = true)
        g.line(w / 2f, groundY - 70f, w / 2f, groundY + 60f, 4f, 0x99FFFFFF.toInt())
        for (x in floatArrayOf(-0.55f, 0.55f)) g.line(toScreenX(x) - 10f, groundY - 70f, toScreenX(x) + 10f, groundY + 60f, 4f, 0x88FFFFFF.toInt())
        // highlight the side that must hit next
        val rcv = receiver
        if (state == State.RALLY && rcv >= 0) {
            val a = 0.15f + 0.1f * sin((time * 8).toFloat())
            val x0 = if (rcv == 0) l - 70f else w / 2f
            val x1 = if (rcv == 0) w / 2f else r + 70f
            g.fillRect(x0, groundY - 70f, x1, groundY + 60f, Colors.withAlpha(env.color(rcv), a))
        }
        // characters (behind the net for depth)
        for (k in 0..1) drawPlayer(g, k, inputs[k], time)
        // net in perspective: spans the court from back to front, with mesh and tinsel along the top
        val nh = cfg.netH * unit() * 1.4f
        val bxN = w / 2f; val backY = groundY - 70f; val frontY = groundY + 60f
        val net = floatArrayOf(bxN + 12f, backY, bxN - 12f, frontY, bxN - 12f, frontY - nh, bxN + 12f, backY - nh)
        g.fillPolygon(net, 4, 0x55FFFFFF)
        for (i in 1 until 6) {
            val f = i / 6f
            g.line(bxN + 12f - 24f * f, backY + (frontY - backY) * f, bxN + 12f - 24f * f, backY + (frontY - backY) * f - nh, 2f, 0x77FFFFFF)
        }
        for (i in 1 until 4) {
            val f = i / 4f
            g.line(bxN + 12f, backY - nh * f, bxN - 12f, frontY - nh * f, 2f, 0x77FFFFFF)
        }
        g.line(bxN + 12f, backY - nh - 16f, bxN + 12f, backY + 4f, 8f, 0xFFECEFF1.toInt())
        g.line(bxN - 12f, frontY - nh - 16f, bxN - 12f, frontY + 4f, 8f, 0xFFECEFF1.toInt())
        g.line(bxN + 12f, backY - nh, bxN - 12f, frontY - nh, 12f, Colors.GOLD)
        for (i in 0..8) { val f = i / 8f; g.fillCircle(bxN + 12f - 24f * f, backY + (frontY - backY) * f - nh, 6f, if (i % 2 == 0) Colors.CANDY else Colors.PINE) }
        // ball, shadow, timing ring
        if (ballVisible) {
            val sx = toScreenX(bx)
            val sy = toScreenY(bh)
            val shadowW = max(8f, 26f - bh * 30f)
            g.fillOval(sx - shadowW, groundY - 6f, sx + shadowW, groundY + 6f, Colors.SHADOW)
            if (state == State.RALLY && rcv >= 0 && !hitTime.isNaN()) {
                val f = approachFraction()
                val ringR = 22f + 130f * (1f - f)
                val c = if (inWindow()) Colors.GOOD else env.color(rcv)
                g.strokeCircle(sx, sy, ringR, 7f, Colors.withAlpha(c, 0.9f))
            }
            g.glow(sx, sy, 46f, 0x55FFF59D)
            g.fillCircle(sx, sy, 17f, 0xFFE6FF5C.toInt())
            g.arc(sx, sy, 12f, 0.6f, 1.9f, 3f, 0x99FFFFFF.toInt())
        }
        Hud.topBar(g, env, scores, "First to ${cfg.pointsToWin}", status(), inputs)
        if (state == State.SERVE) {
            val sxp = toScreenX(px[server])
            val pulse = 1f + 0.08f * sin((time * 6).toFloat())
            g.text("SWING TO SERVE", sxp, groundY - 420f, 46f * pulse, env.color(server), outline = Colors.BLACK, outlineWidth = 6f)
            if (stateTime > cfg.autoServeSec - 3f) g.text("Auto-serve in ${(cfg.autoServeSec - stateTime).toInt() + 1}", sxp, groundY - 365f, 32f, Colors.TEXT_DIM)
        }
    }

    private fun drawPlayer(g: Gfx, k: Int, input: PlayerInput, time: Double) {
        val x = toScreenX(px[k])
        val c = env.color(k)
        val isReceiver = state == State.RALLY && receiver == k
        if (isReceiver) {
            g.fillOval(x - 110f, groundY - 22f, x + 110f, groundY + 22f, Colors.withAlpha(c, 0.55f))
            val bob = sin((time * 10).toFloat()) * 8f
            Draw.tri(g, x - 26f, groundY - 400f + bob, x + 26f, groundY - 400f + bob, x, groundY - 362f + bob, c)
            if (inWindow()) g.text("SWING!", x, groundY - 440f, 52f, Colors.GOOD, outline = Colors.BLACK, outlineWidth = 6f)
        }
        val pose = if (input.avatar.valid) input.avatar else idle(k, time)
        val expr = when {
            state == State.POINT_OVER && pointWinner == k -> Expression.EXCITED
            state == State.POINT_OVER -> Expression.OOPS
            isReceiver -> Expression.FOCUSED
            else -> Expression.HAPPY
        }
        Draw.buddy(g, x, groundY, 300f, c, pose, expr, Prop.RACKET, input.dominant, time)
        if (swingAge[k] < 0.25f) {
            val a = 1f - swingAge[k] / 0.25f
            val dir = if (k == 0) 1f else -1f
            g.arc(x + dir * 40f, groundY - 190f, 120f, if (dir > 0) -PI_F * 0.6f else PI_F * 0.4f, PI_F * 0.9f, 10f, Colors.withAlpha(Colors.WHITE, 0.6f * a))
        }
    }

    private val idlePose = arrayOf(AvatarPose(), AvatarPose())
    private fun idle(k: Int, time: Double): AvatarPose {
        val p = idlePose[k]
        p.upper[0] = -2.2f; p.fore[0] = -1.8f; p.upper[1] = -0.9f + 0.1f * sin(time.toFloat() * 2); p.fore[1] = -0.3f
        p.valid = true
        return p
    }

    override fun demo(g: Gfx, r: Rect, t: Double) = demoSwing(g, r, t, Prop.RACKET, env.color(0), wide = true)

    companion object {
        val INFO = GameInfo(
            id = "tennis", title = "Christmas Tennis", tagline = "Swing your arm like a racket!",
            howTo = listOf(
                "Your snow buddy runs to the ball for you.",
                "When the ball comes to YOUR side, swing your arm as the ring closes.",
                "On time = fast, deep shot. Far too early = OUT. Far too late = NET.",
                "Serve by swinging when it's your turn. First to 7 points wins!",
            ),
            controls = listOf("Swing either arm" to "Hit the ball", "Watch the shrinking ring" to "Swing when it turns green"),
            gentleNotes = "Reduced movement and seated players: a smaller, slower arm swing counts.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "First to 7 points",
        )

        /** Shared instruction illustration: an arm swinging through a ball. */
        fun demoSwing(g: Gfx, r: Rect, t: Double, prop: Prop, color: Int, wide: Boolean) {
            val phase = (t % 1.6).toFloat() / 1.6f
            val p = AvatarPose()
            val swingK = clamp01((phase - 0.45f) / 0.2f)
            val ang = if (wide) -0.2f + 2.4f * swingK else 0.2f + 1.4f * swingK
            p.upper[0] = -2.0f; p.fore[0] = -1.9f
            p.upper[1] = -ang * 0.7f; p.fore[1] = -ang * 0.5f + 0.4f
            p.valid = true
            val cx = r.cx - r.width * 0.12f
            Draw.buddy(g, cx, r.bottom - 20f, r.height * 0.8f, color, p, Expression.FOCUSED, prop, 1, t)
            val bxp = r.right - r.width * (0.1f + 0.5f * phase.coerceAtMost(0.55f) / 0.55f)
            if (phase < 0.6f) g.fillCircle(bxp, r.bottom - r.height * 0.45f, r.height * 0.05f, 0xFFE6FF5C.toInt())
            else g.fillCircle(r.right - r.width * 0.1f * (phase - 0.6f) * 10f, r.bottom - r.height * (0.45f + (phase - 0.6f)), r.height * 0.05f, 0xFFE6FF5C.toInt())
            g.text("Swing!", r.cx + r.width * 0.25f, r.top + 40f, 40f, if (swingK in 0.01f..0.99f) Colors.GOOD else Colors.TEXT_DIM, Align.CENTER)
        }
    }
}
