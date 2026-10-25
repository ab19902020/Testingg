package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.LEFT
import com.clrg.core.math.Rect
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Prop
import kotlin.math.max
import kotlin.math.sin

/**
 * 6. Table Tennis - faster and shorter than tennis. Every incoming ball is marked FOREHAND or BACKHAND; using the
 * matching compact stroke gives a solid return, the wrong one pops the ball up and lets your opponent SMASH.
 */
class TableTennisGame(env: GameEnv) : RallyGame(env) {
    override val info = INFO
    override val compactSwings = true

    override val cfg = Config(
        gravity = 3.6f, netH = 0.07f, hitH = 0.15f, window = 0.3f,
        slowFlight = 0.85f, fastFlight = 0.55f, speedupPerHit = 0.02f, minFlightFactor = 0.75f,
        pointsToWin = 11, serveEvery = 2, restitution = 0.8f, friction = 0.92f,
        homeX = 1.15f, moveSpeed = 3.6f, racketReach = 0.07f, autoServeSec = 8f,
        windowShrinkPerHit = 0.065f, minWindowFactor = 0.4f,
    )

    /** Stroke required from each player for their next incoming ball: true = forehand. */
    val needForehand = booleanArrayOf(true, true)
    /** True when the last return was weak, giving the receiver a smash chance. */
    var smashChance = false
        private set
    var smashes = 0
        private set
    private var sideStreak = 0
    private val tableY = 690f
    private fun unit() = screenW * 0.27f
    override fun toScreenX(x: Float) = screenW / 2f + x * unit()
    override fun toScreenY(h: Float) = tableY - h * unit()

    private fun chooseSide(player: Int) {
        var fh = rng.chance(0.5f)
        if (sideStreak >= 2 && fh == needForehand[player]) fh = !fh // no more than 3 in a row
        sideStreak = if (fh == needForehand[player]) sideStreak + 1 else 0
        needForehand[player] = fh
    }

    override fun onServe(player: Int, auto: Boolean) {
        smashChance = false
        lastSmash = false
        chooseSide(1 - player)
    }

    override fun shotModifier(player: Int, s: GestureEvent.Swing, quality: Float): ShotModifier {
        val correct = s.forehand == needForehand[player]
        return when {
            smashChance && correct && quality > 0.5f -> { smashes++; lastSmash = true; env.shake.kick(10f); ShotModifier(0.62f, 0.15f, "SMASH!") }
            correct -> { lastSmash = false; ShotModifier(1f, 0f, null) }
            else -> { lastSmash = false; ShotModifier(1.3f, -0.2f, if (needForehand[player]) "Weak - that was FOREHAND" else "Weak - that was BACKHAND") }
        }
    }

    override fun onGoodHit(player: Int, quality: Float, m: ShotModifier) {
        super.onGoodHit(player, quality, m)
        smashChance = m.timeFactor > 1.2f
        if (m.label == "SMASH!") env.sfx(Sfx.CRASH, 0.6f)
        chooseSide(1 - player)
    }

    override fun onPoint(winner: Int, reason: PointReason) { smashChance = false }

    override fun botForehand(k: Int) = needForehand[k]
    override fun botWrongStrokeChance(skill: Float) = com.clrg.core.math.lerp(0.22f, 0.03f, skill)
    override fun incomingPressure() = if (lastSmash) 1.6f else 1f
    private var lastSmash = false

    override fun status(): String? = when (state) {
        State.SERVE -> "${env.name(server)}: swing to serve"
        State.POINT_OVER -> "${env.name(pointWinner)} scores - ${pointReason.text}"
        else -> if (smashChance && receiver >= 0) "${env.name(receiver)}: SMASH chance!" else null
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        val w = g.width
        // cosy hall: warm wall, windows with snow, garlands
        g.gradientRect(0f, 0f, w, 860f, 0xFF4A2433.toInt(), 0xFF26121F.toInt())
        // wainscot panels along the wall
        g.gradientRect(0f, 640f, w, 860f, 0xFF3A1A12.toInt(), 0xFF2A120C.toInt())
        g.line(0f, 640f, w, 640f, 6f, 0xFF7A4A2A.toInt())
        for (i in 0 until 10) { val x = i * w / 10f + w / 20f; g.strokeRoundRect(x - w / 26f, 670f, x + w / 26f, 830f, 8f, 3f, 0x33FFD9A0) }
        for (i in 0 until 4) {
            val wx = w * (0.14f + i * 0.24f)
            g.fillRectRadial(wx - 120f, 170f, wx + 120f, 430f, wx, 430f, 300f, 0xFF1E3A66.toInt(), 0xFF0A1428.toInt())
            Draw.snowfall(g, Rect(wx - 120f, 170f, wx + 120f, 430f), time + i, 12)
            g.fillRect(wx - 120f, 400f, wx + 120f, 430f, 0xFFE8F1FA.toInt())
            g.strokeRoundRect(wx - 120f, 170f, wx + 120f, 430f, 20f, 10f, 0xFF6D4C41.toInt())
            g.line(wx, 170f, wx, 430f, 6f, 0xFF6D4C41.toInt())
            g.line(wx - 120f, 300f, wx + 120f, 300f, 6f, 0xFF6D4C41.toInt())
            Draw.wreathSmall(g, wx, 160f, 30f, time + i)
        }
        Draw.stringLights(g, 0f, w, 120f, time, sag = 40f)
        g.gradientRect(0f, 860f, w, g.height, 0xFF6D4C41.toInt(), 0xFF3E2723.toInt())
        for (i in 0 until 16) g.line(i * w / 16f, 860f, i * w / 16f - 60f, g.height, 3f, 0x33000000)
        // the lamp over the table
        val lampX = w / 2f
        g.line(lampX, 0f, lampX, 170f, 4f, 0xFF2A1A20.toInt())
        Draw.spotBeam(g, lampX, 220f, lampX, tableY, (toScreenX(1f) - toScreenX(-1f)) * 1.1f, 0x22FFE7B0)
        lampShade[0] = lampX - 110f; lampShade[1] = 225f; lampShade[2] = lampX + 110f; lampShade[3] = 225f; lampShade[4] = lampX + 40f; lampShade[5] = 170f; lampShade[6] = lampX - 40f; lampShade[7] = 170f
        g.fillPolygonGradient(lampShade, 4, 170f, 225f, 0xFF2E7D32.toInt(), 0xFF1B4D20.toInt())
        g.fillOval(lampX - 60f, 215f, lampX + 60f, 240f, 0xFFFFF3D6.toInt())
        g.glow(lampX, 235f, 120f, 0x55FFE7B0)
        Draw.lightPool(g, lampX, 905f, (toScreenX(1f) - toScreenX(-1f)) * 0.75f, 70f, 0x44FFE7B0)
        // table
        val l = toScreenX(-1f); val r = toScreenX(1f)
        for (lx in floatArrayOf(l + 60f, r - 60f)) g.fillRect(lx - 10f, tableY, lx + 10f, 880f, 0xFF263238.toInt())
        // table top seen slightly from above so it reads as a table, not a plank
        g.fillOval(l - 20f, 870f, r + 20f, 905f, 0x55000000)
        val top = floatArrayOf(l + 40f, tableY - 70f, r - 40f, tableY - 70f, r, tableY, l, tableY)
        g.fillPolygonGradient(top, 4, tableY - 70f, tableY, 0xFF1E88E5.toInt(), 0xFF1257A8.toInt())
        // lamp highlight on the table surface
        g.fillCircleRadial(w / 2f, tableY - 40f, (r - l) * 0.32f, w / 2f, tableY - 40f, (r - l) * 0.32f, 0x30FFFFFF, 0x00FFFFFF)
        g.polyline(top, 4, 5f, Colors.WHITE, closed = true)
        g.line(l + 20f, tableY - 35f, r - 20f, tableY - 35f, 3f, 0x99FFFFFF.toInt())
        g.fillRoundRectGradient(l, tableY, r, tableY + 24f, 4f, 0xFF0D47A1.toInt(), 0xFF08306B.toInt())
        val nh = cfg.netH * unit() * 1.3f
        val net = floatArrayOf(w / 2f + 20f, tableY - 70f, w / 2f - 20f, tableY, w / 2f - 20f, tableY - nh, w / 2f + 20f, tableY - 70f - nh)
        g.fillPolygon(net, 4, 0x66FFFFFF)
        for (i in 1..4) { val f = i / 5f; g.line(w / 2f + 20f - 40f * f, tableY - 70f + 70f * f, w / 2f + 20f - 40f * f, tableY - 70f + 70f * f - nh, 2f, 0x88FFFFFF.toInt()) }
        g.line(w / 2f + 20f, tableY - 70f - nh, w / 2f - 20f, tableY - nh, 6f, Colors.GOLD)
        // receiver highlight + FH/BH arrow
        val rcv = receiver
        for (k in 0..1) drawPlayer(g, k, inputs[k], time, k == rcv && state == State.RALLY)
        // ball
        if (ballVisible) {
            val sx = toScreenX(bx); val sy = toScreenY(bh)
            for (i in 1..4) g.fillCircle(sx - vx * unit() * 0.012f * i, sy + vh * unit() * 0.012f * i, 11f - i * 2f, 0x33FFFFFF)
            g.glow(sx, sy, 40f, 0x66FFF3E0)
            g.fillCircle(sx, sy, 13f, 0xFFFFF3E0.toInt())
            if (rcv >= 0 && !hitTime.isNaN()) {
                val f = approachFraction()
                g.strokeCircle(sx, sy, 18f + 100f * (1f - f), 6f, if (inWindow()) Colors.GOOD else Colors.withAlpha(env.color(rcv), 0.9f))
            }
        }
        Hud.topBar(g, env, scores, "First to ${cfg.pointsToWin}", status(), inputs)
        if (state == State.SERVE) {
            val sxp = toScreenX(px[server])
            g.text("SWING TO SERVE", sxp, 300f, 44f, env.color(server), outline = Colors.BLACK, outlineWidth = 6f)
        }
    }

    private fun drawPlayer(g: Gfx, k: Int, input: PlayerInput, time: Double, isReceiver: Boolean) {
        val x = toScreenX(px[k])
        val c = env.color(k)
        if (isReceiver) {
            g.fillOval(x - 100f, 885f, x + 100f, 925f, Colors.withAlpha(c, 0.55f))
            // which stroke is needed: arrow on the player's forehand/backhand side
            val fh = needForehand[k]
            val dominantRight = input.dominant != LEFT
            val sideRight = if (fh) dominantRight else !dominantRight
            val ax = x + (if (sideRight) 150f else -150f)
            val label = if (fh) "FOREHAND" else "BACKHAND"
            val col = if (fh) Colors.GOLD else Colors.ICE
            g.fillRoundRect(ax - 110f, 330f, ax + 110f, 390f, 30f, 0xCC000000.toInt())
            g.text(label, ax, 360f, 34f, col)
            val dir = if (sideRight) 1f else -1f
            Draw.tri(g, ax + dir * 120f, 345f, ax + dir * 120f, 375f, ax + dir * 150f, 360f, col)
            if (smashChance) g.text("SMASH IT!", x, 290f, 46f, Colors.BAD, outline = Colors.BLACK, outlineWidth = 6f)
            if (inWindow()) g.text("SWING!", x, 250f, 52f, Colors.GOOD, outline = Colors.BLACK, outlineWidth = 6f)
        }
        val pose = if (input.avatar.valid) input.avatar else idle
        val expr = if (state == State.POINT_OVER) (if (pointWinner == k) Expression.EXCITED else Expression.OOPS) else if (isReceiver) Expression.FOCUSED else Expression.HAPPY
        Draw.buddy(g, x, 900f, 330f, c, pose, expr, Prop.PADDLE, input.dominant, time)
        if (swingAge[k] < 0.2f) {
            val a = 1f - swingAge[k] / 0.2f
            g.strokeCircle(x + (if (k == 0) 70f else -70f), 640f, 60f + 40f * (1f - a), 6f, Colors.withAlpha(Colors.WHITE, 0.6f * a))
        }
    }

    private val lampShade = FloatArray(8)

    private val idle = AvatarPose().apply { upper[0] = -2.2f; fore[0] = -1.6f; upper[1] = -0.8f; fore[1] = 0.2f; valid = true }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val phase = ((t % 2.4) / 2.4).toFloat()
        val fh = phase < 0.5f
        val p = AvatarPose()
        val k = ((if (fh) phase else phase - 0.5f) / 0.5f)
        val swing = ((k - 0.4f) / 0.25f).coerceIn(0f, 1f)
        p.upper[0] = -2.1f; p.fore[0] = -1.8f
        // forehand: hand travels from its own side across; backhand: the other way (compact)
        val a = if (fh) -0.3f + 1.6f * swing else 1.3f - 1.6f * swing
        p.upper[1] = -0.6f + a * 0.3f; p.fore[1] = a
        p.valid = true
        Draw.buddy(g, r.cx, r.bottom - 10f, r.height * 0.8f, env.color(1), p, Expression.FOCUSED, Prop.PADDLE, 1, t)
        g.text(if (fh) "FOREHAND" else "BACKHAND", r.cx, r.top + 36f, 40f, if (fh) Colors.GOLD else Colors.ICE)
    }

    override fun result() = super.result().let { res ->
        com.clrg.core.game.MatchResult(res.gameId, res.scores, res.winner, res.scoreUnit, res.details + "Smashes: $smashes")
    }

    companion object {
        val INFO = GameInfo(
            id = "tabletennis", title = "Table Tennis", tagline = "Quick little forehands and backhands!",
            howTo = listOf(
                "Faster than tennis - keep your swings small and quick.",
                "Each ball shows FOREHAND or BACKHAND next to you.",
                "Forehand: playing hand sweeps across from its own side. Backhand: the other way.",
                "Wrong stroke = weak pop-up, and your opponent can SMASH it. First to 11!",
            ),
            controls = listOf("Small forehand swing" to "FOREHAND balls", "Small backhand swing" to "BACKHAND balls"),
            gentleNotes = "Reduced movement and seated: tiny wrist-and-forearm strokes are enough.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "First to 11 points",
        )
    }
}
