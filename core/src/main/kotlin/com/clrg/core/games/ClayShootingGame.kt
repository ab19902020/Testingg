package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.AimCursor
import com.clrg.core.game.CpuTraits
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.math.clamp01
import com.clrg.core.math.lerp
import com.clrg.core.render.Cam3
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.render.SportsDraw
import com.clrg.core.tracking.MovementMode
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Clay Shooting - both players aim at the same 3D sky. Make a finger gun (or hold a toy gun) and point at the TV;
 * drop your thumb to fire (with a toy gun: a quick upward kick). Each clay goes to whoever hits it first - shots are
 * judged by the moment they were made, so the camera's delay favours nobody. Three rounds: singles, doubles, then
 * a flurry where the most clays out of the sky wins. Gold clays are worth 3.
 */
class ClayShootingGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO
    override val usesFingers = true

    /** One clay: launched at [launch] from ([x0], [y0], [z0]) with velocity ([vx], [vy], [vz]); world metres. */
    class Clay(val id: Int, val launch: Double, val x0: Float, val y0: Float, val z0: Float, val vx: Float, val vy: Float, val vz: Float, val gold: Boolean, val round: Int) {
        var hitBy = -1
        var hitAt = 0.0
        var brokeAt = 0.0
        var awarded = false

        /** Position [t] seconds after launch (linear air drag, gravity): x, y, z into [out]. */
        fun pos(t: Float, out: FloatArray) {
            val e = (1f - exp(-DRAG * t)) / DRAG
            out[0] = x0 + vx * e
            out[1] = y0 + vy * e
            out[2] = z0 + (vz + G / DRAG) * e - G / DRAG * t
        }
    }

    companion object {
        const val G = 9.8f
        const val DRAG = 0.18f
        /** Drawn clay radius (m): bigger than a real clay so it reads on a TV across the room. */
        const val CLAY_R = 0.6f
        const val INTRO_SEC = 4.0
        const val SHELLS = 2
        const val RELOAD_SEC = 0.45
        /** A shot hits a clay within this many view units of the crosshair (plus the clay's own size). */
        const val HIT_R = 62f
        const val HIT_R_GENTLE = 86f
        /** A later-arriving shot made earlier than the one that broke a clay takes it over within this time. */
        const val FAIRNESS_SEC = 0.3

        val INFO = GameInfo(
            id = "clays", title = "Clay Shooting", tagline = "Pull! Shoot them out of the sky",
            howTo = listOf(
                "Make a finger gun and point it at the TV - your crosshair follows your hand.",
                "Raise your thumb, then drop it to fire. Holding a toy gun? Flick it up to fire.",
                "First shot of the game: hit the target in the middle (that sets your aim).",
                "Each clay goes to whoever hits it first. Gold clays are worth 3. Two shells, quick reload.",
            ),
            controls = listOf("Point your hand" to "Aim", "Thumb down" to "Fire", "Toy gun: flick up" to "Fire"),
            gentleNotes = "Small hand movements cover the screen, the aim is more forgiving, and holding the crosshair on a clay fires for you.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "Most clays out of the sky", objectKind = com.clrg.core.game.ObjectKind.GUN,
        )

        /** The fixed shooting-stand camera; logic uses view units (0, 0 = screen centre, 1080 high). */
        fun camera(): Cam3 = Cam3().apply {
            lookAt(0f, 0f, 1.6f, 0f, 40f, 8.5f)
            viewport(Rect(-960f, -540f, 960f, 540f), 55f)
        }
    }

    private val cam = camera()
    val clays = ArrayList<Clay>()
    val points = IntArray(2)
    val hits = IntArray(2)
    val shotsFired = IntArray(2)
    val golds = IntArray(2)
    val aim = arrayOf(AimCursor(), AimCursor())
    /** Players who took their centring shot (or ran out of intro time). */
    val loaded = BooleanArray(2)
    private val shells = IntArray(2) { SHELLS }
    private val lastShot = DoubleArray(2) { -9.0 }
    private val reloadClock = DoubleArray(2)
    private val muzzle = FloatArray(2) { 9f }
    private val assistX = FloatArray(2)
    private val assistY = FloatArray(2)
    private val dwell = FloatArray(2)
    private var endTime = 0.0
    private var screenW = 1920f
    private val tmp = FloatArray(3)
    private val roundStarts = DoubleArray(3)
    private var nextLaunch = 0
    private val launchedSound = HashSet<Int>()

    // computer / scripted players: crosshair and target choice
    private val botX = FloatArray(2)
    private val botY = FloatArray(2)
    private val botDriven = BooleanArray(2)
    private val botTarget = IntArray(2) { -1 }
    private val botSeenAt = DoubleArray(2)
    private val botErrX = FloatArray(2)
    private val botErrY = FloatArray(2)

    override fun start() {
        val rng = env.rng(31)
        var t = INTRO_SEC
        var id = 0
        // round 1: singles from alternating houses
        roundStarts[0] = t
        for (i in 0 until 8) { clays.add(launchFrom(id++, t, i % 3, rng, gold = false, round = 0)); t += 3.0 }
        // round 2: doubles - two clays at once, crossing
        t += 1.5
        roundStarts[1] = t
        for (i in 0 until 5) {
            clays.add(launchFrom(id++, t, 0, rng, false, 1))
            clays.add(launchFrom(id++, t + 0.25, 1, rng, false, 1))
            t += 4.0
        }
        // round 3: flurry - several in the air at once, a few gold
        t += 1.5
        roundStarts[2] = t
        val flurryEnd = t + 20.0
        while (t < flurryEnd) {
            clays.add(launchFrom(id++, t, rng.nextInt(4), rng, gold = rng.chance(0.15f), round = 2))
            t += rng.range(0.55f, 0.95f)
        }
        endTime = t + 3.5
    }

    /** House 0 = left, 1 = right, 2 = far (going away), 3 = high tower on the left. */
    private fun launchFrom(id: Int, t: Double, house: Int, rng: Rng, gold: Boolean, round: Int): Clay = when (house) {
        0 -> Clay(id, t, -18f, 32f, 1.4f, rng.range(13f, 17f), rng.range(-2f, 3f), rng.range(9f, 12f), gold, round)
        1 -> Clay(id, t, 18f, 32f, 1.4f, -rng.range(13f, 17f), rng.range(-2f, 3f), rng.range(9f, 12f), gold, round)
        2 -> Clay(id, t, rng.range(-1.5f, 1.5f), 18f, 1.0f, rng.range(-4f, 4f), rng.range(14f, 18f), rng.range(8.5f, 11f), gold, round)
        else -> Clay(id, t, -11f, 24f, 7.5f, rng.range(9f, 13f), rng.range(2f, 6f), rng.range(4f, 7f), gold, round)
    }

    /** Is the clay in the air (launched, not broken, above ground) at game time [t]? */
    private fun flying(c: Clay, t: Double): Boolean {
        if (t < c.launch) return false
        if (c.hitBy >= 0 && t >= c.hitAt) return false
        c.pos((t - c.launch).toFloat(), tmp)
        return tmp[2] > 0f && t - c.launch < 6.0
    }

    /** Screen position (view units) and radius of the clay at time [t]; null when not visible. */
    fun screenOf(c: Clay, t: Double): FloatArray? {
        c.pos((t - c.launch).toFloat(), tmp)
        if (!cam.project(tmp[0], tmp[1], tmp[2])) return null
        return floatArrayOf(cam.px, cam.py, cam.focal * CLAY_R / cam.pd)
    }

    fun round(): Int = when { playTime >= roundStarts[2] - 1.0 -> 2; playTime >= roundStarts[1] - 1.0 -> 1; else -> 0 }

    private fun hitRadius(k: Int) = if (env.players[k].mode == MovementMode.STANDARD) HIT_R else HIT_R_GENTLE

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        // launches (sound once per clay)
        while (nextLaunch < clays.size && clays[nextLaunch].launch <= playTime) {
            val c = clays[nextLaunch++]
            if (launchedSound.add(c.id)) env.sfx(Sfx.LAUNCH, 0.6f, (c.x0 / 24f).coerceIn(-1f, 1f))
        }
        for (k in 0..1) {
            val inp = inputs[k]
            muzzle[k] += dt
            // crosshair: the bot's, or the player's hand
            if (botDriven[k]) aim[k].set(botX[k], botY[k], playTime) else aim[k].update(inp, playTime, dt)
            botDriven[k] = false
            assist(k, dt)
            // reload: one shell back every RELOAD_SEC after the last shot
            if (shells[k] < SHELLS && playTime - lastShot[k] >= RELOAD_SEC) {
                reloadClock[k] += dt
                if (reloadClock[k] >= RELOAD_SEC) { shells[k]++; reloadClock[k] = 0.0 }
            }
            while (true) {
                val e = inp.take<GestureEvent.Shot>() ?: break
                fire(k, e)
            }
            // gentle modes: keeping the crosshair on a clay fires for you
            if (env.players[k].mode != MovementMode.STANDARD && !env.players[k].cpu) {
                val on = targetUnder(k, playTime) != null
                dwell[k] = if (on) dwell[k] + dt else 0f
                if (dwell[k] > 0.4f) { dwell[k] = 0f; fire(k, null) }
            }
            // intro: nobody waits forever for a centring shot
            if (playTime >= INTRO_SEC - 0.2 && !loaded[k]) loaded[k] = true
        }
        // awards become final once nobody can claim the clay with an earlier shot
        for (c in clays) if (c.hitBy >= 0 && !c.awarded && playTime - c.brokeAt >= FAIRNESS_SEC) {
            c.awarded = true
        }
    }

    /** Gentle pull of the crosshair onto a nearby clay (stronger for reduced-movement players). */
    private fun assist(k: Int, dt: Float) {
        val strength = if (env.players[k].mode == MovementMode.STANDARD) 0.3f else 0.55f
        val radius = hitRadius(k) * 1.6f
        var wantX = 0f; var wantY = 0f
        var best = radius
        val hx = aim[k].handX; val hy = aim[k].handY
        for (c in clays) {
            if (!flying(c, playTime)) continue
            val s = screenOf(c, playTime) ?: continue
            val d = hypot(s[0] - hx, s[1] - hy)
            if (d < best) { best = d; wantX = (s[0] - hx) * strength; wantY = (s[1] - hy) * strength }
        }
        val a = clamp01(dt * 10f)
        assistX[k] += (wantX - assistX[k]) * a
        assistY[k] += (wantY - assistY[k]) * a
        aim[k].assist(assistX[k], assistY[k])
    }

    /** The clay under player [k]'s crosshair at time [t] (nearest within reach of the shot), or null. */
    private fun targetUnder(k: Int, t: Double): Clay? {
        val (ax, ay) = aim[k].at(t)
        var best: Clay? = null
        var bestD = Float.MAX_VALUE
        for (c in clays) {
            if (c.launch > t || t - c.launch > 6.0) continue
            // still flying at t (a clay broken by the other player *after* t still counts: we may have been first)
            c.pos((t - c.launch).toFloat(), tmp)
            if (tmp[2] <= 0f) continue
            if (c.hitBy >= 0 && c.hitAt <= t) continue
            val s = screenOf(c, t) ?: continue
            val d = hypot(s[0] - ax, s[1] - ay)
            if (d < hitRadius(k) + s[2] && d < bestD) { best = c; bestD = d }
        }
        return best
    }

    private fun fire(k: Int, e: GestureEvent.Shot?) {
        // the intro target: the first shot sets where "straight ahead" is for this player (no shell used)
        if (!loaded[k]) {
            if (e != null && !e.aimX.isNaN()) aim[k].recentre(e.aimX, e.aimY)
            loaded[k] = true
            muzzle[k] = 0f
            env.sfx(Sfx.BANG, 0.8f, pan(k))
            env.popups.add("Aim set!", screenW / 2f, 400f, env.color(k), 54f)
            env.particles.ring(screenW / 2f, 540f, env.color(k), 160f)
            return
        }
        if (shells[k] <= 0) { env.sfx(Sfx.TICK, 0.6f, pan(k)); return }
        shells[k]--
        lastShot[k] = playTime
        reloadClock[k] = 0.0
        shotsFired[k]++
        muzzle[k] = 0f
        // when was the shot made (as the player saw the game)? judge the sky as it was then
        val at = if (e == null) playTime else madeAt(e).coerceIn(playTime - 0.4, playTime)
        val (ax, ay) = aim[k].at(at)
        env.sfx(Sfx.BANG, 0.8f, (ax / 960f).coerceIn(-1f, 1f))
        env.shake.kick(4f)
        val c = targetUnder(k, at) ?: return
        val other = c.hitBy
        if (other >= 0 && other != k) {
            // the other player broke it, but this shot was made earlier: it is ours
            if (at >= c.hitAt || playTime - c.brokeAt > FAIRNESS_SEC) return
            points[other] -= value(c); hits[other]--; if (c.gold) golds[other]--
            env.popups.add("${env.name(k)} was first!", screenW / 2f + (if (k == 0) -300f else 300f), 260f, env.color(k), 44f)
        }
        c.hitBy = k; c.hitAt = at
        if (other < 0) c.brokeAt = playTime
        points[k] += value(c); hits[k]++; if (c.gold) golds[k]++
        val s = screenOf(c, at) ?: return
        val sx = s[0] + screenW / 2f; val sy = s[1] + 540f
        env.sfx(Sfx.SHATTER, 0.9f, (s[0] / 960f).coerceIn(-1f, 1f))
        env.particles.burst(sx, sy, if (c.gold) Colors.GOLD else 0xFFFF7043.toInt(), 26, speed = 380f, size = 8f, gravity = 500f)
        env.particles.burst(sx, sy, 0xCCFFFFFF.toInt(), 8, speed = 120f, kind = Particles.Kind.PUFF, size = 22f, gravity = 0f)
        env.popups.add(if (c.gold) "+3 GOLD!" else "+1", sx, sy - 40f, env.color(k), if (c.gold) 56f else 46f)
    }

    private fun value(c: Clay) = if (c.gold) 3 else 1
    private fun pan(k: Int) = if (k == 0) -0.5f else 0.5f

    override val isOver: Boolean get() = endTime > 0.0 && playTime >= endTime

    override fun result() = MatchResult(info.id, points.copyOf(), winnerOf(points[0], points[1]), info.scoreUnit, listOf(
        "Clays hit: ${hits[0]} vs ${hits[1]}",
        "Gold clays: ${golds[0]} vs ${golds[1]}",
        "Accuracy: ${pct(0)} vs ${pct(1)}",
    ))

    private fun pct(k: Int) = if (shotsFired[k] == 0) "-" else "${hits[k] * 100 / shotsFired[k]}%"

    override fun status(): String? = when {
        playTime < INTRO_SEC -> "Shoot the target to set your aim"
        else -> null
    }

    // ------------------------------------------------------------------ computer player

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: Rng) {
        val dt = 1f / 120f
        botDriven[k] = true
        val speed = lerp(1300f, 3200f, skill)
        // intro: shoot the centre target once
        if (!loaded[k]) {
            moveBot(k, 0f, 0f, speed, dt)
            if (playTime > 1.2 + k * 0.4 && hypot(botX[k], botY[k]) < 20f) shoot(inp)
            return
        }
        // pick the nearest flying clay that nobody has broken
        val cur = botTarget[k].takeIf { id -> id >= 0 && clays.getOrNull(id)?.let { flying(it, playTime) } == true } ?: -1
        if (cur < 0) {
            val pick = clays.filter { flying(it, playTime) && playTime - it.launch > CpuTraits.reaction(skill) * 1.2f }
                .minByOrNull { c -> screenOf(c, playTime)?.let { hypot(it[0] - botX[k], it[1] - botY[k]) } ?: 9e9f }
            if (pick != null) {
                botTarget[k] = pick.id; botSeenAt[k] = playTime
                val sd = lerp(70f, 18f, skill)
                botErrX[k] = rng.gaussian() * sd; botErrY[k] = rng.gaussian() * sd
            } else { botTarget[k] = -1; moveBot(k, if (k == 0) -260f else 260f, -60f, speed * 0.4f, dt); return }
        }
        val c = clays[botTarget[k]]
        // lead the clay a little (where it will be when the hand gets there)
        val s = screenOf(c, playTime + 0.12) ?: return
        val tx = s[0] + botErrX[k]; val ty = s[1] + botErrY[k]
        moveBot(k, tx, ty, speed, dt)
        val near = hypot(botX[k] - s[0], botY[k] - s[1]) < hitRadius(k) * 0.9f
        val settled = playTime - botSeenAt[k] > lerp(0.5f, 0.22f, skill)
        if (near && settled && shells[k] > 0 && playTime - lastShot[k] > lerp(0.5f, 0.25f, skill)) {
            shoot(inp)
            // after a shot, a fresh aim error (a near miss is corrected, like a person would)
            val sd = lerp(50f, 12f, skill)
            botErrX[k] = rng.gaussian() * sd; botErrY[k] = rng.gaussian() * sd
        }
    }

    private fun moveBot(k: Int, tx: Float, ty: Float, speed: Float, dt: Float) {
        val dx = tx - botX[k]; val dy = ty - botY[k]
        val d = hypot(dx, dy)
        val step = speed * dt
        if (d <= step) { botX[k] = tx; botY[k] = ty } else { botX[k] += dx / d * step; botY[k] += dy / d * step }
    }

    private fun shoot(inp: PlayerInput) {
        inp.events.add(GestureEvent.Shot(0.0, RIGHT, Float.NaN, Float.NaN, byThumb = true))
    }

    // ------------------------------------------------------------------ drawing

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        val ox = g.width / 2f; val oy = 540f
        val horizon = cam.horizonY() + oy
        SportsDraw.daySky(g, 0f, 0f, g.width, horizon, time)
        SportsDraw.hills(g, 0f, g.width, horizon, 3)
        // field: mown stripes running away from the stand
        g.gradientRect(0f, horizon, g.width, g.height, SportsDraw.GRASS_FAR, SportsDraw.GRASS_NEAR)
        val pts = FloatArray(8)
        var i = 0
        var y0 = 4f
        while (y0 < 160f) {
            val y1 = y0 * 1.35f
            if (i % 2 == 0 && cam.project(-200f, y0, 0f)) {
                pts[0] = cam.px + ox; pts[1] = cam.py + oy
                cam.project(200f, y0, 0f); pts[2] = cam.px + ox; pts[3] = cam.py + oy
                cam.project(200f, y1, 0f); pts[4] = cam.px + ox; pts[5] = cam.py + oy
                cam.project(-200f, y1, 0f); pts[6] = cam.px + ox; pts[7] = cam.py + oy
                g.fillPolygon(pts, 4, 0x14FFFFFF)
            }
            y0 = y1; i++
        }
        // trap houses
        // trap houses (left, right, far) and the high tower, back to front
        SportsDraw.box(g, cam, ox, oy, -11.6f, 23.6f, 0f, -10.4f, 24.8f, 7.5f, 0xFFA1887F.toInt(), 0xFF8D6E63.toInt(), 0xFF6D4C41.toInt())
        SportsDraw.box(g, cam, ox, oy, -20f, 31f, 0f, -16f, 34f, 1.4f, 0xFF8D6E63.toInt(), 0xFF6D4C41.toInt(), 0xFF5D4037.toInt())
        SportsDraw.box(g, cam, ox, oy, 16f, 31f, 0f, 20f, 34f, 1.4f, 0xFF8D6E63.toInt(), 0xFF6D4C41.toInt(), 0xFF5D4037.toInt())
        SportsDraw.box(g, cam, ox, oy, -2f, 16.5f, 0f, 2f, 18.5f, 1.0f, 0xFF8D6E63.toInt(), 0xFF6D4C41.toInt(), 0xFF5D4037.toInt())
        if (cam.project(-11f, 24.2f, 7.5f)) { val fx = cam.px + ox; val fy = cam.py + oy; g.line(fx, fy, fx, fy - 70f, 4f, 0xFF5D4037.toInt()); g.fillRect(fx, fy - 70f, fx + 44f, fy - 44f, 0xFFE53935.toInt()) } // tower flag
        // intro target
        if (playTime < INTRO_SEC && !(loaded[0] && loaded[1])) {
            val pulse = 1f + 0.05f * sin(time.toFloat() * 6f)
            for ((r, c) in listOf(120f to Colors.WHITE, 95f to 0xFFE53935.toInt(), 70f to Colors.WHITE, 45f to 0xFFE53935.toInt(), 20f to Colors.GOLD))
                g.fillCircle(ox, oy, r * pulse, c)
            g.text("Shoot the target to set your aim", ox, oy + 190f, 46f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 6f)
            for (k in 0..1) {
                val x = ox + (if (k == 0) -260f else 260f)
                g.text(if (loaded[k]) "${env.name(k)}: ready" else env.name(k), x, oy + 250f, 36f, if (loaded[k]) Colors.GOOD else env.color(k), outline = Colors.BLACK, outlineWidth = 5f)
            }
        }
        // clays (far first)
        val drawList = clays.filter { flying(it, playTime) }.sortedByDescending { c -> c.pos((playTime - c.launch).toFloat(), tmp); tmp[1] }
        for (c in drawList) drawClay(g, c, ox, oy, time)
        // foreground: the shooting stand rail
        g.fillRect(0f, g.height - 70f, g.width, g.height - 52f, 0xFF8D6E63.toInt())
        g.fillRect(0f, g.height - 52f, g.width, g.height, 0xFF5D4037.toInt())
        var px = 60f
        while (px < g.width) { g.fillRect(px - 9f, g.height - 120f, px + 9f, g.height, 0xFF6D4C41.toInt()); px += 260f }
        // guns and crosshairs
        for (k in 0..1) drawGun(g, k, inputs[k], ox, oy)
        for (k in 0..1) drawCrosshair(g, k, inputs[k], ox, oy, time)
        val label = when (round()) { 0 -> "Round 1: Singles"; 1 -> "Round 2: Doubles"; else -> "Round 3: Flurry!" }
        Hud.topBar(g, env, points, label, null, inputs)
        // round banner as each starts
        for (r in 1..2) {
            val since = playTime - (roundStarts[r] - 1.2)
            if (since in 0.0..1.6) Hud.banner(g, if (r == 1) "Doubles - two at once!" else "Flurry - shoot everything!", 330f, Colors.GOLD, 64f)
        }
    }

    private fun drawClay(g: Gfx, c: Clay, ox: Float, oy: Float, time: Double) {
        val t = (playTime - c.launch).toFloat()
        // short streak behind the clay
        for (j in 4 downTo 1) {
            c.pos((t - j * 0.035f).coerceAtLeast(0f), tmp)
            if (!cam.project(tmp[0], tmp[1], tmp[2])) continue
            val r = cam.focal * CLAY_R / cam.pd
            g.fillCircle(cam.px + ox, cam.py + oy, r * (1f - j * 0.15f), Colors.withAlpha(if (c.gold) Colors.GOLD else 0xFFFFAB91.toInt(), 0.18f))
        }
        c.pos(t, tmp)
        if (!cam.project(tmp[0], tmp[1], tmp[2])) return
        val x = cam.px + ox; val y = cam.py + oy
        val r = (cam.focal * CLAY_R / cam.pd).coerceAtLeast(5f)
        val body = if (c.gold) Colors.GOLD else 0xFFFF6E40.toInt()
        g.glow(x, y, r * 3.2f, Colors.withAlpha(body, 0.45f))
        val tilt = 0.45f + 0.15f * sin(time.toFloat() * 9f + c.id)
        // dark rim first so the clay stands out against bright sky and white clouds
        g.fillOval(x - r - 3f, y - r * tilt - 3f, x + r + 3f, y + r * tilt + 3f, 0xCC3E2723.toInt())
        g.fillOval(x - r, y - r * tilt, x + r, y + r * tilt, body)
        g.fillOval(x - r * 0.55f, y - r * tilt * 0.75f, x + r * 0.55f, y + r * tilt * 0.2f, Colors.lighter(body, 0.35f))
        if (c.gold) com.clrg.core.render.Draw.star(g, x, y - r * 1.6f, r * 0.6f, Colors.GOLD, time.toFloat() * 3f)
    }

    private fun drawGun(g: Gfx, k: Int, inp: PlayerInput, ox: Float, oy: Float) {
        val a = aim[k]
        if (!a.seen) return
        // a first-person barrel from the bottom corner towards the crosshair, kicking back on each shot
        val bx = if (k == 0) g.width * 0.28f else g.width * 0.72f
        val by = g.height + 30f
        val ang = atan2(a.y + oy - by, a.x + ox - bx)
        val kick = if (muzzle[k] < 0.15f) (1f - muzzle[k] / 0.15f) * 40f else 0f
        g.save(); g.translate(bx, by); g.rotate(ang)
        g.fillRoundRect(-kick + 40f, -24f, -kick + 330f, 24f, 14f, 0xFF37474F.toInt())
        g.fillRoundRect(-kick + 40f, -24f, -kick + 330f, -6f, 10f, 0xFF607D8B.toInt())
        g.fillRoundRect(-kick - 80f, -38f, -kick + 120f, 38f, 18f, 0xFF6D4C41.toInt())
        g.fillRect(-kick + 300f, -30f, -kick + 318f, 30f, env.color(k))
        if (muzzle[k] < 0.08f) g.glow(-kick + 360f, 0f, 90f, 0xCCFFE082.toInt())
        g.restore()
    }

    private fun drawCrosshair(g: Gfx, k: Int, inp: PlayerInput, ox: Float, oy: Float, time: Double) {
        val a = aim[k]
        val c = env.color(k)
        if (!a.seen) {
            val x = if (k == 0) g.width * 0.25f else g.width * 0.75f
            val msg = if (env.players[k].cpu) "" else "${env.name(k)}: point your finger gun at the TV"
            if (msg.isNotEmpty()) g.text(msg, x, g.height - 150f, 34f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 5f)
            return
        }
        val x = a.x + ox; val y = a.y + oy
        val r = hitRadius(k)
        val on = targetUnder(k, playTime) != null
        g.strokeCircle(x, y, r, if (on) 6f else 4f, Colors.withAlpha(Colors.BLACK, 0.5f))
        g.strokeCircle(x, y, r, if (on) 4f else 3f, if (on) Colors.GOOD else c)
        for (q in 0 until 4) {
            val dx = floatArrayOf(1f, -1f, 0f, 0f)[q]; val dy = floatArrayOf(0f, 0f, 1f, -1f)[q]
            g.line(x + dx * r * 0.45f, y + dy * r * 0.45f, x + dx * r * 1.25f, y + dy * r * 1.25f, 7f, Colors.withAlpha(Colors.BLACK, 0.5f))
            g.line(x + dx * r * 0.45f, y + dy * r * 0.45f, x + dx * r * 1.25f, y + dy * r * 1.25f, 4f, c)
        }
        g.fillCircle(x, y, 3f, c)
        // name and shells
        g.text("P${k + 1}", x + r * 0.9f, y - r * 0.9f, 28f, c, outline = Colors.BLACK, outlineWidth = 4f)
        for (s in 0 until SHELLS) {
            val sx = x - r * 0.9f + s * 22f; val sy = y + r * 1.15f
            val full = s < shells[k]
            g.fillRoundRect(sx - 7f, sy - 14f, sx + 7f, sy + 14f, 4f, if (full) 0xFFE53935.toInt() else 0x55FFFFFF)
            if (full) g.fillRect(sx - 7f, sy + 6f, sx + 7f, sy + 14f, Colors.GOLD)
        }
        // gentle-mode auto fire progress
        if (dwell[k] > 0f) com.clrg.core.render.Draw.progressRing(g, x, y, r + 10f, dwell[k] / 0.4f, Colors.GOLD, 5f)
        // with a finger gun: show the thumb state as a reminder
        if (!env.players[k].cpu && inp.fingersSeen[inp.gunHand]) {
            val ready = inp.cocked[inp.gunHand]
            g.text(if (ready) "thumb up - ready" else "raise thumb", x, y + r * 1.6f, 24f, if (ready) Colors.GOOD else Colors.WARN, outline = Colors.BLACK, outlineWidth = 4f)
        }
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val horizon = r.top + r.height * 0.62f
        SportsDraw.daySky(g, r.left, r.top, r.right, horizon, t)
        g.gradientRect(r.left, horizon, r.right, r.bottom, SportsDraw.GRASS_FAR, SportsDraw.GRASS_NEAR)
        val phase = ((t % 2.4) / 2.4).toFloat()
        // the clay flies across; the thumb drops at 55%; it shatters
        val cx = lerp(r.left + 30f, r.right - 30f, phase)
        val cy = horizon - r.height * 0.5f * 4f * phase * (1f - phase) - 10f
        val tx = lerp(r.left + 30f, r.right - 30f, 0.55f)
        val ty = horizon - r.height * 0.5f * 4f * 0.55f * 0.45f - 10f
        if (phase < 0.56f) {
            g.fillOval(cx - 16f, cy - 7f, cx + 16f, cy + 7f, 0xFFFF6E40.toInt())
        } else if (phase < 0.75f) {
            val k = (phase - 0.56f) / 0.19f
            for (j in 0 until 8) {
                val a = j * 0.785f
                g.fillCircle(tx + kotlin.math.cos(a) * 60f * k, ty + kotlin.math.sin(a) * 60f * k + 40f * k * k, 5f, 0xFFFF6E40.toInt())
            }
        }
        val aimX = lerp(r.cx - 60f, tx, clamp01(phase / 0.5f))
        val aimY = lerp(r.cy, ty, clamp01(phase / 0.5f))
        g.strokeCircle(aimX, aimY, 34f, 4f, Colors.GOLD)
        val thumb = if (phase in 0.5f..0.7f) 1f else 0f
        SportsDraw.fingerGun(g, r.cx + r.width * 0.12f, r.bottom - r.height * 0.18f, r.height * 0.42f, -0.5f, thumb)
        g.text(if (thumb > 0f) "BANG!" else "thumb up...", r.left + r.width * 0.28f, r.bottom - 30f, 28f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 4f)
    }
}
