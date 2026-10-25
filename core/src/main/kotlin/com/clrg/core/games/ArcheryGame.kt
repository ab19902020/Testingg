package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.AimCursor
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.LEFT
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.math.clamp01
import com.clrg.core.math.lerp
import com.clrg.core.render.Align
import com.clrg.core.render.Cam3
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.render.SportsDraw
import com.clrg.core.tracking.MovementMode
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Archery - split screen, each player shooting down their own lane at their own pace. Point your bow hand at the
 * TV to aim, touch your hands together to load an arrow, pull back towards your face (further = more power) and let
 * go. The sight is set for a full draw: draw less and the arrow drops. Three ends of three arrows at 20, 35 and
 * 50 metres, with wind on the longer ones. Most points wins (10 for the gold centre).
 */
class ArcheryGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO
    override val usesFingers = true

    /** An arrow in flight or stuck in the target: impact point on the target plane (m, from its centre). */
    class Arrow(val player: Int, val end: Int, val shotAt: Double, val flight: Double, val ix: Float, val iy: Float, val score: Int, val power: Float)

    companion object {
        const val ENDS = 3
        const val ARROWS_PER_END = 3
        val DISTANCE = floatArrayOf(20f, 35f, 50f)
        /** Crosswind per end (m/s^2 of sideways push), signed per game. */
        val WIND = floatArrayOf(0f, 1.6f, 2.8f)
        /** Target face radius (m): bigger than a real one so it reads well on a TV. */
        const val FACE_R = 0.9f
        const val FULL_SPEED = 62f
        const val GRAVITY = 5.5f
        const val ARROW_TIME_LIMIT = 30.0

        val INFO = GameInfo(
            id = "archery", title = "Archery", tagline = "Draw, aim, release - hit the gold!",
            howTo = listOf(
                "Point your bow hand (left if you're right-handed) at the TV: the sight follows it.",
                "Touch your hands together to load an arrow, then pull your other hand back to your face.",
                "Let go: open your fingers (or let your hand fly back). A full draw flies where you aim; less drops.",
                "Three ends of three arrows: 20 m, 35 m, then 50 m with wind. Gold = 10 points.",
            ),
            controls = listOf("Bow hand at the TV" to "Aim", "Hands together" to "Load an arrow", "Pull back to your face" to "Draw", "Open hand / let it fly back" to "Release"),
            gentleNotes = "Reduced movement and seated: a shorter pull is a full draw, and holding a good draw still for a second releases it.",
            music = MusicTrack.CALM, scoreUnit = "points", goal = "Most points from 9 arrows",
        )

        fun ringScore(r: Float): Int {
            if (r > FACE_R) return 0
            return (10 - (r / (FACE_R / 10f)).toInt()).coerceIn(1, 10)
        }
    }

    val scores = IntArray(2)
    val arrows = ArrayList<Arrow>()
    val shots = IntArray(2)
    val tens = IntArray(2)
    val aim = arrayOf(AimCursor(), AimCursor()).onEach { it.halfWidth = 480f }
    private val windSign = if (env.rng(77).chance(0.5f)) 1f else -1f
    private val arrowClock = DoubleArray(2)
    private val doneAt = DoubleArray(2) { Double.NaN }
    private val lastScore = IntArray(2) { -1 }
    private val lastScoreAge = FloatArray(2) { 9f }
    private var screenW = 1920f
    private val cam = Cam3()
    private val tmp = FloatArray(2)

    fun end(k: Int) = (shots[k] / ARROWS_PER_END).coerceAtMost(ENDS - 1)
    fun finished(k: Int) = shots[k] >= ENDS * ARROWS_PER_END
    fun wind(k: Int) = WIND[end(k)] * windSign

    /** Lane camera for distance [d] (the target centre at 1.3 m height), logic in view units (half-width screen). */
    private fun laneCam(d: Float): Cam3 = cam.apply {
        lookAt(0f, 0f, 1.5f, 0f, d, 1.3f)
        viewport(Rect(-480f, -540f, 480f, 540f), 14f)
    }

    /** The point on the target plane (m from the centre) the crosshair at ([sx], [sy]) points at, for distance [d]. */
    fun targetPoint(sx: Float, sy: Float, d: Float): Pair<Float, Float> {
        val c = laneCam(d)
        // ray through the screen point, intersected with the plane y = d
        val dirX = c.fx + (sx / c.focal) * c.rx - (sy / c.focal) * c.ux
        val dirY = c.fy + (sx / c.focal) * c.ry - (sy / c.focal) * c.uy
        val dirZ = c.fz + (sx / c.focal) * c.rz - (sy / c.focal) * c.uz
        val t = (d - c.ey) / dirY
        return (c.ex + dirX * t) to (c.ez + dirZ * t - 1.3f)
    }

    /** Where an arrow aimed at target point ([px], [py]) with [power] lands, for player [k] (extra drop and wind). */
    fun impact(k: Int, px: Float, py: Float, power: Float): Triple<Float, Float, Double> {
        val d = DISTANCE[end(k)]
        val vFull = FULL_SPEED
        val v = vFull * (0.5f + 0.5f * power.coerceIn(0f, 1f))
        val tFull = d / vFull
        val t = d / v
        // the sight is set for a full draw: only the extra drop of a slower arrow counts
        val drop = 0.5f * GRAVITY * (t * t - tFull * tFull)
        val drift = 0.5f * wind(k) * t * t
        return Triple(px + drift, py - drop, t.toDouble())
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (a in arrows) if (playTime >= a.shotAt + a.flight && landedSound.add(a)) arrowLands(a)
        for (k in 0..1) {
            val inp = inputs[k]
            lastScoreAge[k] += dt
            if (inp.bowState == 2 && lastBowState[k] != 2) env.sfx(Sfx.BOW_DRAW, 0.5f, if (k == 0) -0.5f else 0.5f)
            lastBowState[k] = inp.bowState
            // aim with the bow hand (the gun-hand aim of the bow hand)
            inp.gunHand = inp.bowHand
            if (botDriven[k]) aim[k].set(botX[k], botY[k], playTime) else aim[k].update(inp, playTime, dt)
            botDriven[k] = false
            if (finished(k)) { if (doneAt[k].isNaN()) doneAt[k] = playTime; inp.events.clear(); continue }
            arrowClock[k] += dt
            while (true) {
                val e = inp.take<GestureEvent.Release>() ?: break
                shoot(k, e)
            }
            if (arrowClock[k] > ARROW_TIME_LIMIT) {
                // too long on one arrow: it counts as a miss so the match keeps moving
                shots[k]++; arrowClock[k] = 0.0; lastScore[k] = 0; lastScoreAge[k] = 0f
                env.popups.add("Time! 0", laneX(k), 400f, Colors.WARN, 48f)
            }
        }
    }

    private fun laneX(k: Int) = if (k == 0) screenW * 0.25f else screenW * 0.75f

    private val landedSound = HashSet<Arrow>()
    private val lastBowState = IntArray(2)

    /** The arrow arrives: a thud in the boss (or the grass), and applause for the gold. */
    private fun arrowLands(a: Arrow) {
        val pan = if (a.player == 0) -0.5f else 0.5f
        if (a.score == 0) env.sfx(Sfx.GOLF_LAND, 0.6f, pan) else env.sfx(Sfx.THUNK, 0.9f, pan)
        if (a.score == 10) { env.sfx(Sfx.BULLSEYE, 0.8f, pan); env.sfx(Sfx.APPLAUSE, 0.5f, pan) }
        else if (a.score >= 8) env.sfx(Sfx.APPLAUSE, 0.3f, pan)
    }

    private fun shoot(k: Int, e: GestureEvent.Release) {
        val at = madeAt(e).coerceIn(playTime - 0.4, playTime)
        val (sx, sy) = aim[k].at(at)
        val d = DISTANCE[end(k)]
        val (px, py) = targetPoint(sx, sy, d)
        val (ix, iy, flight) = impact(k, px, py, e.power)
        val score = ringScore(hypot(ix, iy))
        arrows.add(Arrow(k, end(k), playTime, flight, ix, iy, score, e.power))
        shots[k]++
        scores[k] += score
        if (score == 10) tens[k]++
        arrowClock[k] = 0.0
        lastScore[k] = score; lastScoreAge[k] = -flight.toFloat()
        env.sfx(Sfx.TWANG, 0.9f, if (k == 0) -0.5f else 0.5f)
        env.sfx(Sfx.ARROW_FLY, 0.5f, if (k == 0) -0.3f else 0.3f)
    }

    override val isOver: Boolean get() = (0..1).all { finished(it) } && arrows.all { playTime >= it.shotAt + it.flight + 0.8 }

    override fun result() = MatchResult(info.id, scores.copyOf(), winnerOf(scores[0], scores[1]), info.scoreUnit, listOf(
        "Gold (10s): ${tens[0]} vs ${tens[1]}",
        "Best arrow: ${arrows.filter { it.player == 0 }.maxOfOrNull { it.score } ?: 0} vs ${arrows.filter { it.player == 1 }.maxOfOrNull { it.score } ?: 0}",
    ))

    override fun status(): String? = null

    // ------------------------------------------------------------------ computer archer

    private val botX = FloatArray(2)
    private val botY = FloatArray(2)
    private val botDriven = BooleanArray(2)
    private val botReleaseAt = DoubleArray(2) { -1.0 }
    private val botErrX = FloatArray(2)
    private val botErrY = FloatArray(2)

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: Rng) {
        botDriven[k] = true
        if (finished(k)) return
        val d = DISTANCE[end(k)]
        if (botReleaseAt[k] < 0) {
            // line up: aim at the centre, allowing for the wind as a person would (roughly)
            val sd = lerp(0.35f, 0.08f, skill) * (d / 35f)
            botErrX[k] = rng.gaussian() * sd; botErrY[k] = rng.gaussian() * sd
            botReleaseAt[k] = playTime + lerp(3.5f, 2.0f, skill) * rng.range(0.8f, 1.3f)
        }
        val windGuess = -0.5f * wind(k) * (d / FULL_SPEED) * (d / FULL_SPEED) * lerp(0.4f, 0.95f, skill)
        val c = laneCam(d)
        val wantX = windGuess + botErrX[k]; val wantY = 1.3f + botErrY[k]
        if (c.project(wantX, d, wantY)) {
            val sway = sin(playTime.toFloat() * 2.3f + k) * lerp(18f, 5f, skill)
            botX[k] += (c.px + sway - botX[k]) * 0.08f
            botY[k] += (c.py - botY[k]) * 0.08f
        }
        inp.bowState = if (playTime < botReleaseAt[k] - 1.2) 1 else 2
        inp.bowDraw = clamp01(1f - ((botReleaseAt[k] - playTime) / 1.2).toFloat())
        if (playTime >= botReleaseAt[k]) {
            inp.events.add(GestureEvent.Release(0.0, LEFT, lerp(0.85f, 1f, skill) * rng.range(0.92f, 1f), Float.NaN, Float.NaN))
            botReleaseAt[k] = -1.0
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) renderLane(g, k, inputs[k], time)
        Hud.divider(g)
        Hud.topBar(g, env, scores, "Archery", null, inputs)
    }

    private fun renderLane(g: Gfx, k: Int, inp: PlayerInput, time: Double) {
        val r = Rect(if (k == 0) 0f else g.width / 2f, 0f, if (k == 0) g.width / 2f else g.width, g.height)
        val ox = r.cx; val oy = 540f
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        val e = end(k)
        val d = DISTANCE[e]
        val c = laneCam(d)
        val horizon = c.horizonY() + oy
        SportsDraw.daySky(g, r.left, 0f, r.right, horizon, time + k * 5, sunX = r.left + r.width * 0.8f)
        SportsDraw.hills(g, r.left, r.right, horizon, 5 + k)
        g.gradientRect(r.left, horizon, r.right, r.bottom, SportsDraw.GRASS_FAR, SportsDraw.GRASS_NEAR)
        // lane edges and distance markers
        val pts = FloatArray(8)
        for (side in floatArrayOf(-1f, 1f)) {
            if (c.project(side * 3f, 4f, 0f)) { pts[0] = c.px + ox; pts[1] = c.py + oy }
            if (c.project(side * 3f, d + 8f, 0f)) { pts[2] = c.px + ox; pts[3] = c.py + oy }
            g.line(pts[0], pts[1], pts[2], pts[3], 3f, 0x66FFFFFF)
        }
        // target stand and boss
        if (c.project(0f, d + 0.3f, 0f)) {
            val by = c.py + oy
            if (c.project(0f, d + 0.3f, 2.3f)) {
                val top = c.py + oy
                val half = (c.focal * 1.25f / c.pd)
                g.fillRect(ox - half * 0.9f, top, ox - half * 0.8f, by, 0xFF6D4C41.toInt())
                g.fillRect(ox + half * 0.8f, top, ox + half * 0.9f, by, 0xFF6D4C41.toInt())
                g.fillRoundRect(ox - half, top, ox + half, top + (by - top) * 0.92f, half * 0.1f, 0xFFD7B56D.toInt())
            }
        }
        // target face: ten rings, gold in the middle
        if (c.project(0f, d, 1.3f)) {
            val cx = c.px + ox; val cy = c.py + oy
            val pr = c.focal * FACE_R / c.pd
            val ringCol = intArrayOf(Colors.WHITE, Colors.WHITE, 0xFF212121.toInt(), 0xFF212121.toInt(), 0xFF1E88E5.toInt(), 0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFFE53935.toInt(), 0xFFFFD600.toInt(), 0xFFFFD600.toInt())
            for (i in 0 until 10) {
                val rr = pr * (10 - i) / 10f
                g.fillCircle(cx, cy, rr, ringCol[i])
                g.strokeCircle(cx, cy, rr, 1.5f, if (i in 2..3) 0x88FFFFFF.toInt() else 0x55000000)
            }
            g.line(cx - 6f, cy, cx + 6f, cy, 2f, 0xFF212121.toInt()); g.line(cx, cy - 6f, cx, cy + 6f, 2f, 0xFF212121.toInt())
            // arrows already in this end's target
            for (a in arrows) {
                if (a.player != k || a.end != e) continue
                val u = ((playTime - a.shotAt) / a.flight).toFloat()
                if (u < 1f) continue
                val ax = cx + a.ix / FACE_R * pr; val ay = cy - a.iy / FACE_R * pr
                g.fillCircle(ax, ay, 6f, 0xFF263238.toInt())
                g.line(ax, ay, ax - 10f, ay + 22f, 5f, 0xFF5D4037.toInt())
                g.fillCircle(ax - 12f, ay + 26f, 7f, env.color(k))
            }
        }
        // arrows in flight: from the bow to the impact point, shrinking with distance
        for (a in arrows) {
            if (a.player != k) continue
            val u = ((playTime - a.shotAt) / a.flight).toFloat()
            if (u < 0f || u >= 1f) continue
            val dd = DISTANCE[a.end]
            val y = 2f + (dd - 2f) * u
            val x = a.ix * u
            val z = 1.4f + (a.iy + 1.3f - 1.4f) * u + 0.6f * 4f * u * (1f - u) * (dd / 35f)
            if (c.project(x, y, z)) {
                val s = (c.focal * 0.09f / c.pd).coerceIn(3f, 30f)
                g.fillCircle(c.px + ox, c.py + oy, s, env.color(k))
                g.line(c.px + ox, c.py + oy, c.px + ox, c.py + oy + s * 3f, s * 0.5f, 0xFF5D4037.toInt())
            }
        }
        // wind sock / indicator
        val w = wind(k)
        if (w != 0f) {
            val wx = r.left + 90f; val wy = 230f
            g.fillRoundRect(wx - 70f, wy - 34f, wx + 70f, wy + 34f, 16f, 0xAA000000.toInt())
            val len = 40f * kotlin.math.abs(w) / 2.8f
            val dir = if (w > 0) 1f else -1f
            g.line(wx - dir * len, wy, wx + dir * len, wy, 6f, Colors.ICE)
            com.clrg.core.render.Draw.tri(g, wx + dir * (len + 14f), wy, wx + dir * len, wy - 10f, wx + dir * len, wy + 10f, Colors.ICE)
            g.text("wind", wx, wy + 52f, 24f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 3f)
        }
        // the bow and the sight
        drawBow(g, k, inp, r)
        val a = aim[k]
        if (a.seen && !finished(k)) {
            val x = a.x + ox; val y = a.y + oy
            val ready = inp.bowState == 2
            val col = if (ready && inp.bowDraw > 0.9f) Colors.GOOD else env.color(k)
            g.strokeCircle(x, y, 26f, 5f, Colors.withAlpha(Colors.BLACK, 0.5f))
            g.strokeCircle(x, y, 26f, 3f, col)
            g.line(x - 40f, y, x - 12f, y, 3f, col); g.line(x + 12f, y, x + 40f, y, 3f, col)
            g.line(x, y - 40f, x, y - 12f, 3f, col); g.line(x, y + 12f, x, y + 40f, 3f, col)
        }
        // what to do now
        val tip = when {
            finished(k) -> "Done! ${scores[k]} points"
            env.players[k].cpu -> ""
            inp.bowState == 0 -> "Touch your hands together to load an arrow"
            inp.bowState == 1 -> "Pull back to your face"
            inp.bowDraw < 0.9f -> "Pull further for full power..."
            else -> if (inp.fingersSeen[1 - inp.bowHand]) "Open your hand to let go!" else "Let go!"
        }
        if (tip.isNotEmpty()) g.text(tip, r.cx, 222f, 34f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 5f)
        // end, arrows left and the last score
        g.text("End ${e + 1} of $ENDS - ${d.toInt()} m", r.cx, 168f, 34f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 4f)
        for (i in 0 until ARROWS_PER_END) {
            val used = i < shots[k] % ARROWS_PER_END || finished(k)
            g.fillRoundRect(r.right - 60f - i * 26f, 200f, r.right - 46f - i * 26f, 250f, 4f, if (used) 0x44FFFFFF else env.color(k))
        }
        if (lastScore[k] >= 0 && lastScoreAge[k] in 0f..1.8f) {
            val s = lastScore[k]
            val label = when (s) { 10 -> "10 - GOLD!"; 0 -> "Miss"; else -> s.toString() }
            g.text(label, r.cx, 320f, 80f, if (s >= 9) Colors.GOLD else if (s == 0) Colors.BAD else Colors.WHITE, outline = Colors.BLACK, outlineWidth = 8f)
        }
        Hud.laneLabel(g, Rect(r.left, 960f, r.right, 1080f), env, k)
        g.restore()
    }

    /**
     * First-person bow: held upright in front of you, the arrow pointing up the lane at the sight, the string
     * coming back towards you (down the screen) as far as you draw.
     */
    private fun drawBow(g: Gfx, k: Int, inp: PlayerInput, r: Rect) {
        val a = aim[k]
        val bx = r.cx - 210f + (if (a.seen) a.x * 0.25f else 0f)
        val by = 760f + (if (a.seen) a.y * 0.15f else 0f)
        val h = 520f
        val pull = if (inp.bowState == 2) inp.bowDraw else 0f
        // limbs: a recurve seen from behind, bending a little more as it is drawn
        val pts = FloatArray(18)
        for (i in 0..8) {
            val t = i / 8f
            val yy = by - h / 2f + h * t
            val bend = sin(t * Math.PI.toFloat()) * (34f + 26f * pull) - (if (i == 0 || i == 8) 10f else 0f)
            pts[i * 2] = bx - bend; pts[i * 2 + 1] = yy
        }
        g.polyline(pts, 9, 22f, 0xFF3E2723.toInt())
        g.polyline(pts, 9, 12f, 0xFF8D6E63.toInt())
        g.fillRoundRect(bx - 52f, by - 50f, bx - 18f, by + 50f, 12f, 0xFF263238.toInt()) // grip
        // nock point: comes towards you (down and out) with the draw
        val nx = bx + 40f + pull * 70f
        val ny = by + 40f + pull * 250f
        g.line(pts[0], pts[1], nx, ny, 3f, 0xFFECEFF1.toInt())
        g.line(pts[16], pts[17], nx, ny, 3f, 0xFFECEFF1.toInt())
        if (inp.bowState >= 1) {
            // the arrow from the string to the rest, pointing at the sight
            val tipX = bx - 30f; val tipY = by - 70f
            g.line(nx, ny, tipX, tipY, 9f, 0xFF4E342E.toInt())
            g.line(nx, ny, tipX, tipY, 5f, 0xFF8D6E63.toInt())
            com.clrg.core.render.Draw.tri(g, tipX - 10f, tipY + 6f, tipX + 8f, tipY + 6f, tipX - 4f, tipY - 26f, 0xFFB0BEC5.toInt())
            g.fillRoundRect(nx - 16f, ny - 34f, nx + 16f, ny + 4f, 6f, env.color(k)) // fletching
        }
        // power meter
        val mx = r.right - 50f
        g.fillRoundRect(mx - 14f, 560f, mx + 14f, 860f, 14f, 0xAA000000.toInt())
        val fill = 296f * pull
        if (fill > 2f) g.fillRoundRect(mx - 10f, 856f - fill, mx + 10f, 856f, 10f, if (pull > 0.9f) Colors.GOOD else Colors.GOLD)
        g.line(mx - 18f, 856f - 296f * 0.9f, mx + 18f, 856f - 296f * 0.9f, 3f, Colors.WHITE)
        g.text("power", mx, 885f, 22f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 3f)
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        g.gradientRect(r.left, r.top, r.right, r.bottom, SportsDraw.SKY_TOP, SportsDraw.GRASS_FAR)
        val phase = ((t % 2.6) / 2.6).toFloat()
        // target on the right
        val tx = r.right - r.width * 0.2f; val ty = r.cy - 10f
        val cols = intArrayOf(Colors.WHITE, 0xFF212121.toInt(), 0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFFFFD600.toInt())
        for (i in 0 until 5) g.fillCircle(tx, ty, r.height * 0.22f * (5 - i) / 5f, cols[i])
        // archer: bow arm out, drawing arm pulls back, then releases
        val p = com.clrg.core.gesture.AvatarPose().apply {
            valid = true
            upper[LEFT] = 0f; fore[LEFT] = 0f // bow arm towards the target (drawn mirrored: points right)
            val draw = clamp01((phase - 0.15f) / 0.4f)
            val released = phase > 0.65f
            upper[RIGHT] = if (released) 0.3f else lerp(-0.2f, 0.25f, draw)
            fore[RIGHT] = if (released) 0.9f else lerp(0f, 3.0f, draw)
        }
        com.clrg.core.render.Draw.athlete(g, r.left + r.width * 0.3f, r.bottom + 10f, r.height * 0.95f, env.color(1), p, com.clrg.core.render.Expression.FOCUSED, t = t)
        if (phase > 0.65f) {
            val u = clamp01((phase - 0.65f) / 0.15f)
            val ax = lerp(r.left + r.width * 0.45f, tx, u)
            g.line(ax - 30f, ty, ax, ty, 4f, 0xFF5D4037.toInt())
        }
        g.text(if (phase < 0.15f) "hands together" else if (phase < 0.65f) "pull back..." else "release!", r.cx, r.top + 30f, 30f, Colors.WHITE, Align.CENTER, outline = Colors.BLACK, outlineWidth = 4f)
    }

}
