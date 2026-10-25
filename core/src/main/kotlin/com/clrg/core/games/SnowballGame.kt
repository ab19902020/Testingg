package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.math.PI_F
import com.clrg.core.math.Rect
import com.clrg.core.math.clamp
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.render.Prop
import kotlin.math.abs
import kotlin.math.sin

/**
 * 2. Snowball Showdown - two snow forts, 60 seconds. Lean to move inside your area, throw overhand to launch a
 * snowball at where your opponent is standing, raise both hands for a short shield (3 per match).
 */
class SnowballGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    companion object {
        const val MATCH_SEC = 60.0
        const val THROW_COOLDOWN = 0.9f
        const val SHIELD_SEC = 1.6f
        const val SHIELDS = 3
        const val HIT_RADIUS = 0.05f
        const val ZONE_HALF = 0.13f
        val ZONE_CENTER = floatArrayOf(0.22f, 0.78f)

        val INFO = GameInfo(
            id = "snowball", title = "Snowball Showdown", tagline = "Throw, dodge and shield!",
            howTo = listOf(
                "THROW: bring your hand up by your shoulder and throw forward.",
                "Your snowball flies at the spot where your opponent is standing.",
                "DODGE: lean left or right to move inside your fort area.",
                "SHIELD: raise both hands high (3 shields each). Most hits in 60 seconds wins!",
            ),
            controls = listOf("Overhand throw" to "Launch a snowball", "Lean left / right" to "Dodge", "Both hands up" to "Shield"),
            gentleNotes = "Reduced movement and seated: a short forward push from chest height throws; small leans move you.",
            music = MusicTrack.GAME, scoreUnit = "hits", goal = "Most hits in 60 seconds",
        )
    }

    class Ball(val owner: Int, val x0: Float, val x1: Float, val flight: Float, var t: Float = 0f, var done: Boolean = false, var doneAt: Float = 0f)

    val scores = IntArray(2)
    /** Arena x (0..1) of each player. */
    val px = floatArrayOf(ZONE_CENTER[0], ZONE_CENTER[1])
    val cooldown = FloatArray(2)
    val shieldLeft = intArrayOf(SHIELDS, SHIELDS)
    val shieldTime = FloatArray(2)
    val stun = FloatArray(2)
    val dodges = IntArray(2)
    val throws = IntArray(2)
    val balls = ArrayList<Ball>()
    private val throwAnim = floatArrayOf(9f, 9f)
    private var screenW = 1920f
    private val groundY = 930f

    val timeLeft: Double get() = (MATCH_SEC - playTime).coerceAtLeast(0.0)

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        val timeUp = playTime >= MATCH_SEC
        for (k in 0..1) {
            val inp = inputs[k]
            if (timeUp) { inp.events.clear() } // buzzer: only snowballs already in the air still count
            cooldown[k] = (cooldown[k] - dt).coerceAtLeast(0f)
            shieldTime[k] = (shieldTime[k] - dt).coerceAtLeast(0f)
            stun[k] = (stun[k] - dt).coerceAtLeast(0f)
            throwAnim[k] += dt
            // movement: absolute position inside the fort area from the lean
            val target = ZONE_CENTER[k] + clamp(inp.lean, -1f, 1f) * ZONE_HALF
            px[k] += clamp(target - px[k], -0.75f * dt, 0.75f * dt)
            if (inp.take<GestureEvent.HandsUp>() != null && shieldLeft[k] > 0 && shieldTime[k] <= 0f) {
                shieldLeft[k]--
                shieldTime[k] = SHIELD_SEC
                env.sfx(Sfx.SHIELD, 0.8f, pan(k))
            }
            val th = inp.take<GestureEvent.Throw>()
            if (th != null) {
                if (cooldown[k] <= 0f && stun[k] <= 0f) {
                    val flight = lerp(1.15f, 0.85f, th.power)
                    balls.add(Ball(k, px[k] + if (k == 0) 0.03f else -0.03f, px[1 - k], flight))
                    cooldown[k] = THROW_COOLDOWN
                    throws[k]++
                    throwAnim[k] = 0f
                    env.sfx(Sfx.THROW, 0.8f, pan(k))
                } else if (cooldown[k] > 0f) {
                    env.popups.add("Reloading...", sx(px[k]), 380f, Colors.TEXT_DIM, 34f, 0.6f)
                }
            }
        }
        val it = balls.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.t += dt
            // a camera player's dodge or shield is seen a little late (camera, TV): decide once it could have arrived
            if (b.t >= b.flight + inputGrace(1 - b.owner) && !b.done) {
                b.done = true
                b.doneAt = b.t
                val target = 1 - b.owner
                val hitX = b.x1
                if (abs(px[target] - hitX) < HIT_RADIUS) {
                    if (shieldTime[target] > 0f) {
                        env.popups.add("BLOCKED!", sx(px[target]), 380f, Colors.ICE, 56f)
                        env.particles.ring(sx(px[target]), 560f, Colors.ICE, 200f)
                        env.particles.burst(sx(px[target]), 560f, Colors.ICE, 18, kind = Particles.Kind.PUFF)
                        env.sfx(Sfx.SHIELD, 0.6f, pan(target))
                        env.sfx(Sfx.SNOW_CRUNCH, 0.8f, pan(target))
                    } else {
                        scores[b.owner]++
                        stun[target] = 0.6f
                        env.particles.ring(sx(px[target]), 560f, env.color(b.owner), 150f)
                        env.popups.add("SPLAT! +1", sx(px[target]), 380f, env.color(b.owner), 60f)
                        env.particles.burst(sx(px[target]), 640f, Colors.SNOW, 30, kind = Particles.Kind.PUFF, size = 14f)
                        env.sfx(Sfx.SPLAT, 1f, pan(target))
                        env.shake.kick(8f)
                    }
                } else {
                    dodges[target]++
                    env.popups.add("Dodged!", sx(px[target]), 390f, env.color(target), 44f)
                    env.particles.burst(sx(hitX), groundY, Colors.SNOW, 14, kind = Particles.Kind.PUFF)
                    env.sfx(Sfx.SNOW_CRUNCH, 0.7f, pan(target))
                }
            }
            if (b.done && b.t > b.doneAt + 0.3f) it.remove()
        }
    }

    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f
    private fun sx(x: Float) = x * screenW

    // ---- CPU player: throws at a human pace, sees incoming snowballs after a reaction delay, then dodges or
    // shields (sometimes it doesn't notice at all), and wanders around its fort between throws.
    private class BotView(val react: Boolean, val delay: Float, val shield: Boolean)
    private val botViews = HashMap<Ball, BotView>()
    private val botLean = FloatArray(2)
    private val botHold = FloatArray(2)
    private val botNextThrow = FloatArray(2) { -1f }
    private val botNextWander = FloatArray(2)

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        val now = playTime.toFloat()
        if (cooldown[k] <= 0f && stun[k] <= 0f && playTime < MATCH_SEC) {
            if (botNextThrow[k] < 0f) botNextThrow[k] = now + lerp(1.3f, 0.35f, skill) + rng.nextFloat() * lerp(1.0f, 0.4f, skill)
            if (now >= botNextThrow[k]) {
                inp.events.add(GestureEvent.Throw(playTime, 1, 0f, clamp(lerp(0.4f, 0.85f, skill) + (rng.nextFloat() - 0.5f) * 0.2f, 0f, 1f)))
                botNextThrow[k] = -1f
            }
        }
        botViews.keys.removeAll { it.done }
        botHold[k] -= dt
        var threat: Ball? = null
        for (b in balls) {
            if (b.done || b.owner == k) continue
            val v = botViews.getOrPut(b) {
                BotView(rng.chance(lerp(0.5f, 0.92f, skill)), com.clrg.core.game.CpuTraits.reaction(skill) * rng.range(0.85f, 1.3f), rng.chance(lerp(0.05f, 0.2f, skill)))
            }
            if (v.react && b.t >= v.delay && abs(b.x1 - px[k]) < HIT_RADIUS * 1.6f) { threat = b; if (v.shield && shieldLeft[k] > 0 && shieldTime[k] <= 0f) { inp.events.add(GestureEvent.HandsUp(playTime)); botViews[b] = BotView(true, v.delay, false) } }
        }
        if (threat != null && shieldTime[k] <= 0f && botHold[k] <= 0f) {
            // step away from where the snowball will land, toward the side with more room
            val room = px[k] - ZONE_CENTER[k]
            botLean[k] = if (abs(room) > ZONE_HALF * 0.5f) -kotlin.math.sign(room) else if (px[k] >= threat.x1) 1f else -1f
            botHold[k] = 0.6f
        } else if (botHold[k] <= 0f && now >= botNextWander[k]) {
            botLean[k] = rng.range(-0.8f, 0.8f)
            botNextWander[k] = now + lerp(3.5f, 1.5f, skill) * rng.range(0.6f, 1.4f)
        }
        inp.lean = botLean[k]
    }

    override val isOver: Boolean get() = playTime >= MATCH_SEC && balls.none { !it.done }

    override fun result() = MatchResult(info.id, scores.copyOf(), winnerOf(scores[0], scores[1]), info.scoreUnit,
        listOf("Throws: ${throws[0]} vs ${throws[1]}", "Dodges: ${dodges[0]} vs ${dodges[1]}"))

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        val w = g.width
        Draw.nightSky(g, Rect(0f, 0f, w, g.height), time)
        Draw.aurora(g, Rect(0f, 0f, w, 520f), time, 0.7f)
        Draw.moon(g, w * 0.83f, 190f, 46f)
        Draw.pineRow(g, -20f, w, 610f, 0xFF0F2E25.toInt(), 110f, 4)
        // log cabin in the background: chimney smoke, glowing windows, light spilling onto the snow
        val hx0 = w * 0.38f; val hx1 = w * 0.62f
        g.fillRect(w * 0.555f, 250f, w * 0.585f, 330f, 0xFF5D4037.toInt())
        for (i in 0 until 6) {
            val k = ((time * 0.25 + i / 6.0) % 1.0).toFloat()
            g.glow(w * 0.57f + kotlin.math.sin(k * 6f + i) * 30f * k, 240f - k * 190f, 26f + 50f * k, Colors.withAlpha(0xFFB0BEC5.toInt(), 0.35f * (1f - k)))
        }
        g.fillRoundRectGradient(hx0, 360f, hx1, 640f, 6f, 0xFF7B5446.toInt(), 0xFF3E2723.toInt())
        for (i in 0 until 7) g.line(hx0 + 4f, 380f + i * 38f, hx1 - 4f, 380f + i * 38f, 4f, 0x33000000)
        pts4[0] = w * 0.35f; pts4[1] = 370f; pts4[2] = w * 0.65f; pts4[3] = 370f; pts4[4] = w * 0.5f; pts4[5] = 230f
        g.fillPolygonGradient(pts4, 3, 230f, 370f, 0xFF5D4037.toInt(), 0xFF2B1B16.toInt())
        pts4[0] = w * 0.36f; pts4[1] = 372f; pts4[2] = w * 0.64f; pts4[3] = 372f; pts4[4] = w * 0.5f; pts4[5] = 245f
        g.fillPolygonGradient(pts4, 3, 245f, 372f, Colors.WHITE, 0xFFC5D8F0.toInt())
        Draw.lightPool(g, w * 0.5f, 660f, w * 0.2f, 40f, 0x55FFB74D)
        for (i in 0..2) {
            val wx = w * (0.42f + i * 0.08f)
            if (i == 1) {
                g.glow(wx, 560f, 90f, 0x44FFB74D)
                g.fillRoundRectGradient(wx - 34f, 500f, wx + 34f, 640f, 6f, 0xFFFFE0A3.toInt(), 0xFFFFA94D.toInt())
                g.fillRoundRect(wx - 30f, 504f, wx + 30f, 640f, 6f, 0x88542E1F.toInt())
                g.fillCircle(wx + 18f, 575f, 5f, Colors.GOLD)
                continue
            }
            g.glow(wx, 470f, 85f, 0x66FFB74D)
            g.fillRoundRectGradient(wx - 35f, 430f, wx + 35f, 510f, 6f, 0xFFFFE6B0.toInt(), 0xFFFFA94D.toInt())
            g.line(wx, 430f, wx, 510f, 4f, 0xFF4E342E.toInt()); g.line(wx - 35f, 470f, wx + 35f, 470f, 4f, 0xFF4E342E.toInt())
            g.fillRoundRect(wx - 40f, 508f, wx + 40f, 518f, 4f, Colors.SNOW)
        }
        Draw.stringLights(g, w * 0.36f, w * 0.64f, 378f, time, sag = 18f, spacing = 50f)
        Draw.pineRow(g, 0f, w * 0.33f, 640f, 0xFF173F33.toInt(), 160f, 7)
        Draw.pineRow(g, w * 0.67f, w, 640f, 0xFF173F33.toInt(), 160f, 9)
        Draw.snowHills(g, Rect(0f, 0f, w, g.height), 690f)
        Draw.snowfall(g, Rect(0f, 0f, w, g.height), time, 50)
        // fort areas
        for (k in 0..1) {
            val c = env.color(k)
            val x0 = sx(ZONE_CENTER[k] - ZONE_HALF - 0.04f); val x1 = sx(ZONE_CENTER[k] + ZONE_HALF + 0.04f)
            g.fillOval(x0, groundY - 30f, x1, groundY + 40f, Colors.withAlpha(c, 0.18f))
        }
        // players
        for (k in 0..1) {
            val inp = inputs[k]
            val x = sx(px[k])
            val pose = if (inp.avatar.valid) inp.avatar else idle
            val expr = when { stun[k] > 0f -> Expression.OOPS; throwAnim[k] < 0.4f -> Expression.EXCITED; else -> Expression.FOCUSED }
            Draw.buddy(g, x, groundY, 400f, env.color(k), pose, expr, if (cooldown[k] <= 0f) Prop.SNOWBALL else Prop.NONE, inp.dominant, time, shielded = shieldTime[k] > 0f)
            if (stun[k] > 0f) g.fillCircle(x - 12f, groundY - 330f, 34f, 0xEEFFFFFF.toInt())
            // reload ring and shields
            Draw.progressRing(g, x, groundY - 470f, 28f, 1f - cooldown[k] / THROW_COOLDOWN, if (cooldown[k] <= 0f) Colors.GOOD else Colors.TEXT_DIM, 7f)
            if (cooldown[k] <= 0f) g.fillCircle(x, groundY - 470f, 13f, Colors.SNOW)
            for (s in 0 until SHIELDS) {
                val sxp = x - 50f + s * 50f
                val on = s < shieldLeft[k]
                if (on) {
                    g.glow(sxp, groundY + 80f, 30f, 0x5580D8FF)
                    g.fillRoundRectGradient(sxp - 16f, groundY + 60f, sxp + 16f, groundY + 100f, 10f, 0xFFDDF4FF.toInt(), 0xFF4FB3E8.toInt())
                } else g.fillRoundRect(sxp - 16f, groundY + 60f, sxp + 16f, groundY + 100f, 10f, 0x44FFFFFF)
            }
        }
        // fort walls in front of the players
        for (k in 0..1) {
            val cx = sx(ZONE_CENTER[k])
            val ww = screenW * (ZONE_HALF + 0.07f)
            Draw.softShadow(g, cx, groundY + 40f, ww * 1.1f, 26f, 0.4f)
            g.fillRoundRectGradient(cx - ww, groundY - 110f, cx + ww, groundY + 40f, 30f, 0xFFF4F9FF.toInt(), 0xFFA9C2E2.toInt())
            // packed-snow bricks
            for (row in 0 until 3) {
                val ry = groundY - 70f + row * 38f
                g.line(cx - ww + 12f, ry, cx + ww - 12f, ry, 3f, 0x337A9CC6)
                var bx = cx - ww + (if (row % 2 == 0) 70f else 30f)
                while (bx < cx + ww - 20f) { g.line(bx, ry, bx, ry + 38f, 3f, 0x2E7A9CC6); bx += 80f }
            }
            for (i in 0 until 6) {
                val bxx = cx - ww + 30f + i * (ww * 2 - 60f) / 5f
                g.fillCircleRadial(bxx, groundY - 110f, 30f, bxx - 10f, groundY - 122f, 42f, Colors.WHITE, 0xFFBACFEA.toInt())
            }
            // team pennant
            val px0 = if (k == 0) cx - ww + 20f else cx + ww - 20f
            g.line(px0, groundY - 140f, px0, groundY - 290f, 6f, 0xFF8D6E63.toInt())
            val dir = if (k == 0) 1f else -1f
            val flap = kotlin.math.sin((time * 4 + k).toFloat()) * 8f
            pts4[0] = px0; pts4[1] = groundY - 290f; pts4[2] = px0 + dir * 80f; pts4[3] = groundY - 265f + flap; pts4[4] = px0; pts4[5] = groundY - 240f
            g.fillPolygonGradient(pts4, 3, groundY - 290f, groundY - 240f, com.clrg.core.render.Colors.lighter(env.color(k), 0.3f), com.clrg.core.render.Colors.darker(env.color(k), 0.7f))
        }
        // snowballs in flight
        for (b in balls) {
            if (b.done || b.t >= b.flight) continue
            val u = (b.t / b.flight).coerceIn(0f, 1f)
            val x = sx(lerp(b.x0, b.x1, u))
            val y = groundY - 330f - 380f * 4f * u * (1f - u) + 150f * u
            g.fillOval(x - 14f, groundY + 6f, x + 14f, groundY + 16f, Colors.SHADOW)
            for (i in 1..4) {
                val uu = (u - i * 0.035f).coerceAtLeast(0f)
                g.fillCircle(sx(lerp(b.x0, b.x1, uu)), groundY - 330f - 380f * 4f * uu * (1f - uu) + 150f * uu, 16f - i * 3f, Colors.withAlpha(Colors.SNOW, 0.35f - i * 0.07f))
            }
            g.fillCircleRadial(x, y, 18f, x - 6f, y - 7f, 26f, Colors.WHITE, 0xFFAFC6E4.toInt())
            // landing marker so the target can see where to dodge from
            g.strokeCircle(sx(b.x1), groundY + 10f, 40f * (1f - u * 0.5f), 4f, Colors.withAlpha(env.color(b.owner), 0.5f + 0.5f * u))
        }
        Hud.topBar(g, env, scores, Hud.formatTime(timeLeft), "Most hits wins", inputs)
    }

    private val pts4 = FloatArray(8)
    private val idle = AvatarPose().apply { upper[0] = -2.3f; fore[0] = -2.0f; upper[1] = 0.4f; fore[1] = 1.6f; valid = true }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val phase = ((t % 2.0) / 2.0).toFloat()
        val p = AvatarPose()
        p.upper[0] = -2.3f; p.fore[0] = -2.0f
        if (phase < 0.45f) { p.upper[1] = 0.6f; p.fore[1] = 1.9f } else {
            val k = ((phase - 0.45f) / 0.2f).coerceIn(0f, 1f)
            p.upper[1] = 0.6f - 1.2f * k; p.fore[1] = 1.9f - 2.2f * k
        }
        p.valid = true
        val lean = sin(t * 1.5).toFloat() * 0.3f
        p.tilt = lean
        Draw.buddy(g, r.cx - r.width * 0.15f, r.bottom - 10f, r.height * 0.8f, env.color(0), p, Expression.EXCITED, if (phase < 0.6f) Prop.SNOWBALL else Prop.NONE, 1, t)
        if (phase > 0.6f) {
            val k = (phase - 0.6f) / 0.4f
            g.fillCircle(r.cx - r.width * 0.05f + r.width * 0.45f * k, r.top + r.height * (0.4f - 0.3f * sin(k * PI_F)), 14f, Colors.SNOW)
        }
    }
}
