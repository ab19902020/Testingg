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
import com.clrg.core.render.SportsDraw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Prop
import com.clrg.core.render.Scenery
import kotlin.math.max
import kotlin.math.sin

/** 1. Christmas Tennis - side-on festive court, first to seven points. */
class TennisGame(env: GameEnv) : RallyGame(env) {
    override val info: GameInfo get() = if (env.sporty) SPORTS_INFO else INFO

    override val cfg = Config(
        // a big court and unhurried shots: plenty of time to see the ball coming and swing
        gravity = 2.8f, netH = 0.11f, hitH = 0.2f, window = 0.36f,
        slowFlight = 1.5f, fastFlight = 1.0f, speedupPerHit = 0.03f, minFlightFactor = 0.68f,
        pointsToWin = 7, serveEvery = 1, restitution = 0.72f, friction = 0.9f,
        homeX = 0.95f, moveSpeed = 3.2f, racketReach = 0.09f, autoServeSec = 8f,
        // rallies build pressure: the timing window tightens with every hit, so points end in a minute or less
        windowShrinkPerHit = 0.06f, minWindowFactor = 0.4f, maxCourtX = 1.2f,
    )

    private val groundY = 850f
    /** Court depth on screen: back and front edges above/below [groundY]. */
    private val back = 88f
    private val front = 74f
    private fun unit() = screenW * 0.355f
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
        val l = toScreenX(-1f); val r = toScreenX(1f)
        if (env.sporty) {
            // summer centre court: blue sky, a roofed stand packed with people, glowing sponsor boards
            Scenery.daySky(g, "tennis", 0f, 0f, w, 470f)
            Scenery.clouds(g, 0f, 0f, w, 330f, time, count = 5)
            g.layer("tennis.roof", 0f, 300f, w, 400f) { lg -> stadiumRoof(lg, w) }
            Scenery.crowd(g, "tennis", 0f, 380f, w, 690f, time, rows = 5, shade = 0.38f, blur = 1.5f)
        } else {
            Scenery.nightSky(g, "tennisC", Rect(0f, 0f, w, g.height), time)
            Draw.aurora(g, Rect(0f, 0f, w, 600f), time, 0.8f)
            g.layer("tennisC.back", 0f, 140f, w, g.height) { lg ->
                Draw.moon(lg, w * 0.5f, 210f, 44f)
                Draw.pineRow(lg, -30f, w, 610f, 0xFF0F2E25.toInt(), 110f, 5)
                Draw.pineRow(lg, 0f, w, 640f, 0xFF143A2E.toInt(), 150f, 3)
                Draw.snowHills(lg, Rect(0f, 0f, w, lg.height), 700f)
                Draw.floodlight(lg, w * 0.04f, 760f, 260f, toScreenX(-0.45f), groundY + 40f)
                Draw.floodlight(lg, w * 0.96f, 760f, 260f, toScreenX(0.45f), groundY + 40f)
            }
            // a crowd of snowmen behind the court, bobbing (three cached poses)
            Scenery.frames(g, "tennisC.crowd", 0f, 600f, w, 770f, time, fps = 4.0) { lg, f ->
                for (i in 0 until 9) {
                    val cx = w * (0.08f + i * 0.105f)
                    val bob = sin(f * 2.1f + i) * 4f
                    Draw.buddy(lg, cx, 742f + bob, 110f, com.clrg.core.render.PlayerColors.get(i + 2), null, Expression.HAPPY, t = 1.0 + i + f)
                }
            }
            Draw.stringLights(g, 0f, w, 10f, time)
        }
        // the court (cached): surround, playing surface, lines, the light falling on it
        g.layer(if (env.sporty) "tennis.court" else "tennisC.court", 0f, 680f, w, g.height, grain = 0.15f) { lg -> court(lg, w, l, r) }
        // highlight the side that must hit next
        val rcv = receiver
        if (state == State.RALLY && rcv >= 0) {
            val a = 0.15f + 0.1f * sin((time * 8).toFloat())
            val x0 = if (rcv == 0) l - 85f else w / 2f
            val x1 = if (rcv == 0) w / 2f else r + 85f
            g.fillRect(x0, groundY - back, x1, groundY + front, Colors.withAlpha(env.color(rcv), a))
        }
        // characters (behind the net for depth)
        for (k in 0..1) drawPlayer(g, k, inputs[k], time)
        // net in perspective: spans the court from back to front, with mesh and tinsel along the top
        val nh = cfg.netH * unit() * 1.4f
        val bxN = w / 2f; val backY = groundY - back; val frontY = groundY + front
        g.layer(if (env.sporty) "tennis.net" else "tennisC.net", bxN - 40f, backY - nh - 30f, bxN + 40f, frontY + 12f) { lg ->
            val net = floatArrayOf(bxN + 12f, backY, bxN - 12f, frontY, bxN - 12f, frontY - nh, bxN + 12f, backY - nh)
            lg.fillPolygon(net, 4, 0x55FFFFFF)
            for (i in 1 until 12) {
                val f = i / 12f
                lg.line(bxN + 12f - 24f * f, backY + (frontY - backY) * f, bxN + 12f - 24f * f, backY + (frontY - backY) * f - nh, 1.5f, 0x66FFFFFF)
            }
            for (i in 1 until 6) {
                val f = i / 6f
                lg.line(bxN + 12f, backY - nh * f, bxN - 12f, frontY - nh * f, 1.5f, 0x66FFFFFF)
            }
            lg.line(bxN + 14f, backY - nh - 16f, bxN + 14f, backY + 4f, 10f, 0xFF90A4AE.toInt())
            lg.line(bxN - 10f, frontY - nh - 16f, bxN - 10f, frontY + 4f, 10f, 0xFF90A4AE.toInt())
            lg.line(bxN + 12f, backY - nh - 16f, bxN + 12f, backY + 4f, 7f, 0xFFECEFF1.toInt())
            lg.line(bxN - 12f, frontY - nh - 16f, bxN - 12f, frontY + 4f, 7f, 0xFFECEFF1.toInt())
            if (env.sporty) { lg.line(bxN + 12f, backY - nh, bxN - 12f, frontY - nh, 10f, Colors.WHITE); lg.line(bxN + 12f, backY - nh + 3f, bxN - 12f, frontY - nh + 3f, 2f, 0x33000000) }
            else {
                lg.line(bxN + 12f, backY - nh, bxN - 12f, frontY - nh, 12f, Colors.GOLD)
                for (i in 0..8) { val f = i / 8f; lg.fillCircle(bxN + 12f - 24f * f, backY + (frontY - backY) * f - nh, 6f, if (i % 2 == 0) Colors.CANDY else Colors.PINE) }
            }
        }
        // ball, shadow, timing ring
        if (ballVisible) {
            val sx = toScreenX(bx)
            val sy = toScreenY(bh)
            val shadowW = max(8f, 26f - bh * 30f)
            g.fillOval(sx - shadowW, groundY - 6f, sx + shadowW, groundY + 6f, Colors.SHADOW)
            if (state == State.RALLY && rcv >= 0 && !hitTime.isNaN() && hitTime - playTime < 1.1) {
                val f = approachFraction()
                val ringR = 22f + 130f * (1f - f)
                val c = if (inWindow()) Colors.GOOD else env.color(rcv)
                g.strokeCircle(sx, sy, ringR, 7f, Colors.withAlpha(c, 0.9f))
            }
            for (i in 1..5) g.fillCircle(sx - vx * unit() * 0.018f * i, sy + vh * unit() * 0.018f * i, 16f - i * 2.2f, Colors.withAlpha(0xFFE6FF5C.toInt(), 0.32f - i * 0.05f))
            g.glow(sx, sy, 52f, 0x66FFF59D)
            g.fillCircleRadial(sx, sy, 17f, sx - 5f, sy - 6f, 22f, 0xFFF7FFB0.toInt(), 0xFFB5CC2E.toInt())
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

    /** The stadium roof over the far stand: steel underside, a row of lights, its shadow on the top rows. */
    private fun stadiumRoof(g: Gfx, w: Float) {
        g.gradientRect(0f, 300f, w, 360f, 0xFF5B6B80.toInt(), 0xFF2C3645.toInt())
        g.fillRect(0f, 356f, w, 366f, 0xFF1E2530.toInt())
        var x = 0f
        while (x < w) { g.line(x, 304f, x + 40f, 356f, 3f, 0x55212A36); g.line(x + 40f, 356f, x + 80f, 304f, 3f, 0x55212A36); x += 80f }
        for (i in 0 until 12) {
            val lx = (i + 0.5f) * w / 12f
            g.fillRoundRect(lx - 22f, 358f, lx + 22f, 372f, 5f, 0xFF263040.toInt())
            g.fillOval(lx - 16f, 366f, lx + 16f, 376f, 0xFFFFFDE7.toInt())
        }
        g.gradientRect(0f, 366f, w, 400f, 0x66000000, 0x00000000)
    }

    /** Court surround, the playing surface with its lines, sponsor boards (sports) and the floodlights' pool of light. */
    private fun court(g: Gfx, w: Float, l: Float, r: Float) {
        val apron = floatArrayOf(l - 150f, groundY + front + 40f, r + 150f, groundY + front + 40f, r + 10f, groundY - back - 28f, l - 10f, groundY - back - 28f)
        if (env.sporty) {
            // sponsor boards with a glowing face along the back of the court
            g.gradientRect(0f, 690f, w, 752f, 0xFF173A24.toInt(), 0xFF0B2414.toInt())
            for (i in 0 until 6) {
                val bx = w * (0.02f + i * 0.165f)
                val c = if (i % 2 == 0) 0xFF1565C0.toInt() else 0xFF2E7D32.toInt()
                g.fillRoundRectGradient(bx, 700f, bx + w * 0.14f, 742f, 6f, Colors.lighter(c, 0.25f), Colors.darker(c, 0.7f))
                g.line(bx + 8f, 703f, bx + w * 0.14f - 8f, 703f, 2f, 0x55FFFFFF)
                g.text(if (i % 2 == 0) "LIVING ROOM OPEN" else "ACE!", bx + w * 0.07f, 721f, 24f, Colors.WHITE)
            }
            g.gradientRect(0f, 752f, w, g.height, 0xFF3A9160.toInt(), 0xFF256B42.toInt())
            for (i in 0 until 14) if (i % 2 == 0) g.fillRect(i * w / 14f, 752f, (i + 1) * w / 14f, g.height, 0x0CFFFFFF)
            g.fillPolygonGradient(apron, 4, groundY - back - 28f, groundY + front + 40f, 0xFF3C9A5F.toInt(), 0xFF2B7A48.toInt())
        } else g.fillPolygonGradient(apron, 4, groundY - back - 28f, groundY + front + 40f, 0xFF2E6F8E.toInt(), 0xFF1B4F6B.toInt())
        val pts = floatArrayOf(l - 85f, groundY + front, r + 85f, groundY + front, r - 36f, groundY - back, l + 36f, groundY - back)
        if (env.sporty) g.fillPolygonGradient(pts, 4, groundY - back, groundY + front, 0xFF2F6DB5.toInt(), 0xFF1F4F8A.toInt())
        else g.fillPolygonGradient(pts, 4, groundY - back, groundY + front, 0xFFD24B43.toInt(), 0xFF962A2A.toInt())
        Draw.lightPool(g, w / 2f, groundY - 5f, (r - l) * 0.55f, 85f, 0x33FFF6D8)
        g.polyline(pts, 4, 6f, Colors.WHITE, closed = true)
        g.line(l - 36f, groundY - back * 0.57f, r + 36f, groundY - back * 0.57f, 3f, 0x66FFFFFF)
        g.line(l - 63f, groundY + front * 0.57f, r + 63f, groundY + front * 0.57f, 3f, 0x66FFFFFF)
        g.line(w / 2f, groundY - back, w / 2f, groundY + front, 4f, 0x99FFFFFF.toInt())
        for (x in floatArrayOf(-0.55f, 0.55f)) g.line(toScreenX(x) - 12f, groundY - back, toScreenX(x) + 12f, groundY + front, 4f, 0x88FFFFFF.toInt())
        g.gradientRect(0f, g.height - 160f, w, g.height, 0x00000000, 0x33000000)
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
        Draw.person(g, env.sporty, x, groundY, 320f, c, pose, expr, Prop.RACKET, input.dominant, time, look = k * 3 + 1)
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

    override fun demo(g: Gfx, r: Rect, t: Double) = demoSwing(g, r, t, Prop.RACKET, env.color(0), wide = true, sporty = env.sporty)

    companion object {
        val INFO = GameInfo(
            id = "tennis", title = "Christmas Tennis", tagline = "Swing your arm like a racket!",
            howTo = listOf(
                "Your snow buddy runs to the ball for you - you just swing.",
                "When the ball comes to YOUR side, swing your arm as the ring closes. Swing like a racket: back, then through.",
                "The swing nearest the right moment counts. On time = fast, deep shot. Way too early = OUT, way too late = NET.",
                "Rallies get faster and the timing tighter. Serve by swinging. First to 7 points wins!",
            ),
            controls = listOf("Swing either arm (or your held object)" to "Hit the ball", "Watch the shrinking ring" to "Swing when it turns green"),
            gentleNotes = "Reduced movement and seated players: a smaller, slower arm swing counts.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "First to 7 points", objectKind = com.clrg.core.game.ObjectKind.RACKET,
        )

        /** Shared instruction illustration: an arm swinging through a ball. */
        val SPORTS_INFO = GameInfo(
            id = "tennis_s", title = "Tennis", tagline = "Swing your arm like a racket!",
            howTo = listOf(
                "Your player runs to the ball for you - you just swing.",
                "When the ball comes to YOUR side, swing your arm as the ring closes. Swing like a racket: back, then through.",
                "The swing nearest the right moment counts. On time = fast, deep shot. Way too early = OUT, way too late = NET.",
                "Rallies get faster and the timing tighter. Serve by swinging. First to 7 points wins!",
            ),
            controls = INFO.controls, gentleNotes = INFO.gentleNotes,
            music = MusicTrack.GAME, scoreUnit = "points", goal = "First to 7 points", objectKind = com.clrg.core.game.ObjectKind.RACKET,
        )

        fun demoSwing(g: Gfx, r: Rect, t: Double, prop: Prop, color: Int, wide: Boolean, sporty: Boolean = false) {
            val phase = (t % 1.6).toFloat() / 1.6f
            val p = AvatarPose()
            val swingK = clamp01((phase - 0.45f) / 0.2f)
            val ang = if (wide) -0.2f + 2.4f * swingK else 0.2f + 1.4f * swingK
            p.upper[0] = -2.0f; p.fore[0] = -1.9f
            p.upper[1] = -ang * 0.7f; p.fore[1] = -ang * 0.5f + 0.4f
            p.valid = true
            val cx = r.cx - r.width * 0.12f
            Draw.person(g, sporty, cx, r.bottom - 20f, r.height * 0.8f, color, p, Expression.FOCUSED, prop, 1, t)
            val bxp = r.right - r.width * (0.1f + 0.5f * phase.coerceAtMost(0.55f) / 0.55f)
            if (phase < 0.6f) g.fillCircle(bxp, r.bottom - r.height * 0.45f, r.height * 0.05f, 0xFFE6FF5C.toInt())
            else g.fillCircle(r.right - r.width * 0.1f * (phase - 0.6f) * 10f, r.bottom - r.height * (0.45f + (phase - 0.6f)), r.height * 0.05f, 0xFFE6FF5C.toInt())
            g.text("Swing!", r.cx + r.width * 0.25f, r.top + 40f, 40f, if (swingK in 0.01f..0.99f) Colors.GOOD else Colors.TEXT_DIM, Align.CENTER)
        }
    }
}
