package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.AimCursor
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.ObjectKind
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.render.SportsDraw
import com.clrg.core.render.Scenery
import com.clrg.core.tracking.MovementMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Shooting Gallery - a fairground stall. Tin ducks paddle along two rows, bottles wobble on the shelf, balloons float
 * up and a gold star zips across now and then. Both players shoot at the same targets with finger guns (or toy
 * guns); each target goes to whoever hit it first. Six shots, then a quick reload. Most points in 60 seconds wins.
 */
class ShootingGalleryGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO
    override val usesFingers = true

    enum class Kind(val points: Int, val radius: Float) { DUCK(1, 46f), BOTTLE(2, 30f), BALLOON(1, 44f), STAR(5, 34f) }

    /** A target: appears at [born], moves along its path ([pos]); view units (0, 0 = screen centre). */
    class Target(val id: Int, val kind: Kind, val born: Double, val life: Double, val x0: Float, val y0: Float, val vx: Float, val vy: Float, val color: Int) {
        var hitBy = -1
        var hitAt = 0.0
        var brokeAt = 0.0
        fun alive(t: Double) = t >= born && t < born + life && !(hitBy >= 0 && t >= hitAt)
        fun x(t: Double) = x0 + vx * (t - born).toFloat()
        fun y(t: Double) = y0 + vy * (t - born).toFloat() + if (kind == Kind.BALLOON) 10f * sin((t - born).toFloat() * 3f + id) else 0f
    }

    companion object {
        const val GAME_SEC = 60.0
        const val INTRO_SEC = 3.5
        const val ROUNDS_IN_GUN = 6
        const val RELOAD_SEC = 1.0
        const val HIT_R = 40f
        const val HIT_R_GENTLE = 62f
        const val FAIRNESS_SEC = 0.3

        val INFO = GameInfo(
            id = "gallery", title = "Shooting Gallery", tagline = "Ducks, bottles, balloons - bang!",
            howTo = listOf(
                "Point one or two fingers at the TV, thumb up - your crosshair follows your hand.",
                "Drop your thumb to fire, raise it again for the next shot (toy gun: flick it up). First shot sets your aim.",
                "Ducks and balloons 1 point, bottles 2, the gold star 5. Whoever hits a target first gets it.",
                "Six shots, then a quick reload. Most points in 60 seconds wins!",
            ),
            controls = listOf("Point your hand" to "Aim", "Thumb down" to "Fire", "Toy gun: flick up" to "Fire"),
            gentleNotes = "Small hand movements cover the screen, targets are easier to hit, and holding the crosshair on one fires for you.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "Most points in 60 s", objectKind = ObjectKind.GUN,
        )
    }

    val targets = ArrayList<Target>()
    val points = IntArray(2)
    val hits = IntArray(2)
    val shotsFired = IntArray(2)
    val aim = arrayOf(AimCursor(), AimCursor())
    val loaded = BooleanArray(2)
    private val ammo = IntArray(2) { ROUNDS_IN_GUN }
    private val reloadUntil = DoubleArray(2) { -1.0 }
    private val dwell = FloatArray(2)
    private val muzzle = FloatArray(2) { 9f }
    private var screenW = 1920f
    private val rowY = floatArrayOf(-150f, 60f)

    override fun start() {
        val rng = env.rng(51)
        var id = 0
        val colors = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt())
        // ducks: two rows going opposite ways, a steady stream
        for (row in 0..1) {
            var t = INTRO_SEC - 2.0 + row * 0.6
            val dir = if (row == 0) 1f else -1f
            val speed = 210f + row * 60f
            while (t < INTRO_SEC + GAME_SEC) {
                targets.add(Target(id++, Kind.DUCK, t, 2200.0 / speed, -dir * 1100f, rowY[row], dir * speed, 0f, Colors.GOLD))
                t += rng.range(0.9f, 1.6f)
            }
        }
        // bottles appear on the shelf for a few seconds each
        var t = INTRO_SEC + 1.0
        while (t < INTRO_SEC + GAME_SEC) {
            targets.add(Target(id++, Kind.BOTTLE, t, rng.range(2.5f, 4f).toDouble(), rng.range(-700f, 700f), 270f, 0f, 0f, colors[rng.nextInt(colors.size)]))
            t += rng.range(0.7f, 1.3f)
        }
        // balloons float up
        t = INTRO_SEC + 0.5
        while (t < INTRO_SEC + GAME_SEC) {
            // float up from behind the counter and away under the awning (never up behind the scoreboard)
            val up = rng.range(150f, 230f)
            targets.add(Target(id++, Kind.BALLOON, t, ((620f + 330f) / up).toDouble(), rng.range(-800f, 800f), 620f, rng.range(-20f, 20f), -up, colors[rng.nextInt(colors.size)]))
            t += rng.range(1.2f, 2.2f)
        }
        // gold stars: rare and quick
        t = INTRO_SEC + 8.0
        while (t < INTRO_SEC + GAME_SEC - 3.0) {
            val dir = if (rng.chance(0.5f)) 1f else -1f
            targets.add(Target(id++, Kind.STAR, t, 2.1, -dir * 1050f, rng.range(-380f, -260f), dir * 1000f, 0f, Colors.GOLD))
            t += rng.range(7f, 11f)
        }
    }

    private fun hitRadius(k: Int) = if (env.players[k].mode == MovementMode.STANDARD) HIT_R else HIT_R_GENTLE

    fun targetUnder(k: Int, t: Double): Target? {
        val (ax, ay) = aim[k].at(t)
        var best: Target? = null; var bestD = Float.MAX_VALUE
        for (tg in targets) {
            if (t < tg.born || t >= tg.born + tg.life) continue
            if (tg.hitBy >= 0 && tg.hitAt <= t) continue
            val d = hypot(tg.x(t) - ax, tg.y(t) - ay)
            if (d < hitRadius(k) + tg.kind.radius && d < bestD) { best = tg; bestD = d }
        }
        return best
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            val inp = inputs[k]
            muzzle[k] += dt
            if (botDriven[k]) aim[k].set(botX[k], botY[k], playTime) else aim[k].update(inp, playTime, dt)
            botDriven[k] = false
            if (ammo[k] == 0 && playTime >= reloadUntil[k]) { ammo[k] = ROUNDS_IN_GUN; env.sfx(Sfx.RELOAD, 0.6f, if (k == 0) -0.4f else 0.4f) }
            while (true) { val e = inp.take<GestureEvent.Shot>() ?: break; fire(k, e) }
            if (env.players[k].mode != MovementMode.STANDARD && !env.players[k].cpu && loaded[k]) {
                dwell[k] = if (targetUnder(k, playTime) != null) dwell[k] + dt else 0f
                if (dwell[k] > 0.4f) { dwell[k] = 0f; fire(k, null) }
            }
            if (playTime >= INTRO_SEC - 0.2) loaded[k] = true
        }
    }

    private fun fire(k: Int, e: GestureEvent.Shot?) {
        if (!loaded[k]) {
            // the bullseye: this shot sets where "straight ahead" is
            if (e != null && !e.aimX.isNaN()) aim[k].recentre(e.aimX, e.aimY)
            loaded[k] = true
            env.sfx(Sfx.GALLERY_SHOT, 0.8f, if (k == 0) -0.4f else 0.4f)
            env.popups.add("Aim set!", screenW / 2f, 420f, env.color(k), 50f)
            return
        }
        if (ammo[k] == 0 || playTime > INTRO_SEC + GAME_SEC) { env.sfx(Sfx.EMPTY, 0.8f, if (k == 0) -0.4f else 0.4f); return }
        ammo[k]--
        if (ammo[k] == 0) reloadUntil[k] = playTime + RELOAD_SEC
        shotsFired[k]++
        muzzle[k] = 0f
        val at = if (e == null) playTime else madeAt(e).coerceIn(playTime - 0.4, playTime)
        val (ax, _) = aim[k].at(at)
        env.sfx(Sfx.GALLERY_SHOT, 0.8f, (ax / 960f).coerceIn(-1f, 1f))
        val tg = targetUnder(k, at) ?: return
        val other = tg.hitBy
        if (other >= 0 && other != k) {
            if (at >= tg.hitAt || playTime - tg.brokeAt > FAIRNESS_SEC) return
            points[other] -= tg.kind.points; hits[other]--
        }
        if (other < 0) tg.brokeAt = playTime
        tg.hitBy = k; tg.hitAt = at
        points[k] += tg.kind.points; hits[k]++
        val sx = tg.x(at) + screenW / 2f; val sy = tg.y(at) + 540f
        when (tg.kind) {
            Kind.BOTTLE -> { env.sfx(Sfx.GLASS, 0.9f, (sx / 960f - 1f).coerceIn(-1f, 1f)); env.particles.shatter(sx, sy, tg.color, 16, size = 11f, speed = 380f, dust = 0x66FFFFFF) }
            Kind.BALLOON -> { env.sfx(Sfx.BALLOON_POP, 0.9f, (sx / 960f - 1f).coerceIn(-1f, 1f)); env.particles.burst(sx, sy, tg.color, 16, speed = 300f, kind = Particles.Kind.CONFETTI, size = 10f) }
            Kind.STAR -> { env.sfx(Sfx.SPARKLE, 1f); env.sfx(Sfx.APPLAUSE, 0.4f); env.particles.burst(sx, sy, Colors.GOLD, 30, kind = Particles.Kind.STAR, size = 14f) }
            Kind.DUCK -> { env.sfx(Sfx.TIN_HIT, 0.8f, (sx / 960f - 1f).coerceIn(-1f, 1f)); env.particles.burst(sx, sy, Colors.GOLD, 12, speed = 260f, size = 7f) }
        }
        env.particles.impact(sx, sy, if (tg.kind == Kind.STAR) Colors.GOLD else tg.color, if (tg.kind == Kind.STAR) 0.9f else 0.45f)
        env.popups.add("+${tg.kind.points}", sx, sy - 50f, env.color(k), if (tg.kind == Kind.STAR) 60f else 44f)
    }

    override val isOver: Boolean get() = playTime >= INTRO_SEC + GAME_SEC + 0.8

    override fun result() = MatchResult(info.id, points.copyOf(), winnerOf(points[0], points[1]), info.scoreUnit, listOf(
        "Targets hit: ${hits[0]} vs ${hits[1]}",
        "Accuracy: ${pct(0)} vs ${pct(1)}",
    ))

    private fun pct(k: Int) = if (shotsFired[k] == 0) "-" else "${hits[k] * 100 / shotsFired[k]}%"

    // ------------------------------------------------------------------ computer shooter

    private val botX = FloatArray(2) { if (it == 0) -200f else 200f }
    private val botY = FloatArray(2)
    private val botDriven = BooleanArray(2)
    private val botTarget = IntArray(2) { -1 }
    private val botSince = DoubleArray(2)
    private val botErr = Array(2) { FloatArray(2) }
    private val botLastShot = DoubleArray(2) { -9.0 }

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: Rng) {
        val dt = 1f / 120f
        botDriven[k] = true
        val speed = lerp(1100f, 2600f, skill)
        if (!loaded[k]) {
            move(k, 0f, 0f, speed, dt)
            if (playTime > 1.0 + k * 0.3 && hypot(botX[k], botY[k]) < 15f) shoot(inp)
            return
        }
        val cur = targets.getOrNull(botTarget[k])?.takeIf { it.alive(playTime) && it.born + it.life - playTime > 0.3 }
        if (cur == null) {
            val pick = targets.filter { it.alive(playTime) && playTime - it.born > 0.4 && it.born + it.life - playTime > 0.6 }
                .maxByOrNull { it.kind.points * 400f - hypot(it.x(playTime) - botX[k], it.y(playTime) - botY[k]) }
            if (pick == null) { botTarget[k] = -1; return }
            botTarget[k] = pick.id; botSince[k] = playTime
            val sd = lerp(85f, 38f, skill)
            botErr[k][0] = rng.gaussian() * sd; botErr[k][1] = rng.gaussian() * sd
        }
        val tg = targets[botTarget[k]]
        val lead = 0.1
        move(k, tg.x(playTime + lead) + botErr[k][0], tg.y(playTime + lead) + botErr[k][1], speed, dt)
        val near = hypot(botX[k] - tg.x(playTime), botY[k] - tg.y(playTime)) < tg.kind.radius + hitRadius(k) * 0.6f
        // a person needs a moment to line up and between shots (and a hard computer should still be beatable)
        if (near && playTime - botSince[k] > lerp(0.7f, 0.4f, skill) && playTime - botLastShot[k] > lerp(1.0f, 0.55f, skill) && ammo[k] > 0) {
            shoot(inp); botLastShot[k] = playTime
            botTarget[k] = -1
            val sd = lerp(60f, 25f, skill)
            botErr[k][0] = rng.gaussian() * sd; botErr[k][1] = rng.gaussian() * sd
        }
    }

    private fun move(k: Int, tx: Float, ty: Float, speed: Float, dt: Float) {
        val dx = tx - botX[k]; val dy = ty - botY[k]; val d = hypot(dx, dy); val step = speed * dt
        if (d <= step) { botX[k] = tx; botY[k] = ty } else { botX[k] += dx / d * step; botY[k] += dy / d * step }
    }

    private fun shoot(inp: PlayerInput) = inp.events.add(GestureEvent.Shot(0.0, RIGHT, Float.NaN, Float.NaN, byThumb = true))

    // ------------------------------------------------------------------ drawing

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        val ox = g.width / 2f; val oy = 540f
        val tf = time.toFloat()
        // a fairground booth at night: painted backdrop, the duck rows between wooden waves, the shelf, then balloons
        backdrop(g, tf)
        for ((i, ry) in rowY.withIndex()) {
            val y = ry + oy
            wave(g, y + 4f, tf, i, front = false)
            for (tg in targets) if (tg.kind == Kind.DUCK && tg.alive(playTime) && abs(tg.y(playTime) - ry) < 1f) drawTarget(g, tg, tg.x(playTime) + ox, y, time)
            wave(g, y + 34f, tf, i, front = true)
        }
        g.layer("gallery.shelf", 0f, 270f + oy + 20f, g.width, 270f + oy + 110f) { lg -> shelf(lg, 270f + oy + 34f) }
        // balloons float in front of everything, so they are never hidden while rising
        for (tg in targets) if (tg.kind == Kind.BALLOON && tg.alive(playTime)) drawTarget(g, tg, tg.x(playTime) + ox, tg.y(playTime) + oy, time)
        for (tg in targets) if (tg.kind == Kind.BOTTLE && tg.alive(playTime)) drawTarget(g, tg, tg.x(playTime) + ox, tg.y(playTime) + oy, time)
        for (tg in targets) if (tg.kind == Kind.STAR && tg.alive(playTime)) drawTarget(g, tg, tg.x(playTime) + ox, tg.y(playTime) + oy, time)
        boothFrame(g, tf)
        // intro bullseye
        if (playTime < INTRO_SEC && !(loaded[0] && loaded[1])) {
            for ((r, c) in listOf(110f to Colors.WHITE, 80f to 0xFFE53935.toInt(), 50f to Colors.WHITE, 22f to 0xFFE53935.toInt())) g.fillCircle(ox, oy, r, c)
            g.text("Shoot the bullseye to set your aim", ox, oy + 170f, 44f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 6f)
        }
        // crosshairs and ammo
        for (k in 0..1) {
            val a = aim[k]
            val c = env.color(k)
            val ax = if (k == 0) 60f else g.width - 60f
            for (i in 0 until ROUNDS_IN_GUN) {
                val bx = ax + (if (k == 0) i * 28f else -i * 28f)
                g.fillRoundRect(bx - 8f, g.height - 110f, bx + 8f, g.height - 70f, 6f, if (i < ammo[k]) Colors.GOLD else 0x44FFFFFF)
            }
            if (ammo[k] == 0) g.text("RELOADING", if (k == 0) 200f else g.width - 200f, g.height - 40f, 30f, Colors.WARN)
            if (!a.seen) continue
            val x = a.x + ox; val y = a.y + oy
            val on = targetUnder(k, playTime) != null
            val r = hitRadius(k)
            g.strokeCircle(x, y, r, 6f, Colors.withAlpha(Colors.BLACK, 0.5f))
            g.strokeCircle(x, y, r, 4f, if (on) Colors.GOOD else c)
            g.line(x - r * 1.3f, y, x - r * 0.4f, y, 4f, c); g.line(x + r * 0.4f, y, x + r * 1.3f, y, 4f, c)
            g.line(x, y - r * 1.3f, x, y - r * 0.4f, 4f, c); g.line(x, y + r * 0.4f, x, y + r * 1.3f, 4f, c)
            g.text("P${k + 1}", x + r, y - r, 26f, c, outline = Colors.BLACK, outlineWidth = 4f)
            if (muzzle[k] < 0.08f) g.glow(x, y, 70f, 0xAAFFE082.toInt())
            if (dwell[k] > 0f) Draw.progressRing(g, x, y, r + 10f, dwell[k] / 0.4f, Colors.GOLD, 5f)
        }
        val left = (INTRO_SEC + GAME_SEC - playTime).coerceIn(0.0, GAME_SEC)
        Hud.topBar(g, env, points, Hud.formatTime(left), "Shooting Gallery", inputs)
    }

    /** Painted night sky with a ferris wheel, tent tops and strings of lights (mostly cached pictures). */
    private fun backdrop(g: Gfx, t: Float) {
        val w = g.width; val h = g.height
        g.layer("gallery.sky", 0f, 0f, w, h, res = 0.75f, grain = 0.15f) { lg ->
            lg.gradientRect(0f, 0f, w, h, 0xFF0E1640.toInt(), 0xFF3B1D58.toInt())
            for (i in 0 until 140) {
                val sx = (i * 197f + 53f) % w; val sy = 160f + (i * 113f) % 540f
                lg.fillCircle(sx, sy, 0.8f + 1.6f * Draw.hash01(i), Colors.withAlpha(Colors.WHITE, 0.25f + 0.5f * Draw.hash01(i * 3)))
            }
            lg.glow(w * 0.82f, 250f, 160f, 0x33FFF59D)
            lg.fillCircle(w * 0.82f, 250f, 52f, 0xFFFFF8E1.toInt())
            lg.fillCircle(w * 0.82f + 16f, 238f, 46f, 0xFF2A1B52.toInt()) // a crescent
            // ferris wheel supports
            val fx = w * 0.16f; val fy = 430f
            lg.line(fx - 120f, fy + 330f, fx, fy, 10f, 0x553D3470); lg.line(fx + 120f, fy + 330f, fx, fy, 10f, 0x553D3470)
            // striped tent tops along the horizon
            for ((cx, ht, wd) in listOf(Triple(w * 0.55f, 210f, 260f), Triple(w * 0.72f, 170f, 200f), Triple(w * 0.95f, 240f, 300f))) {
                val base = 680f
                for (sIdx in 0 until 8) {
                    val x0 = cx - wd / 2f + sIdx * wd / 8f; val x1 = x0 + wd / 8f
                    lg.fillPolygon(floatArrayOf(cx, base - ht, x0, base, x1, base), 3, if (sIdx % 2 == 0) 0x88C62828.toInt() else 0x88ECEFF1.toInt())
                }
                lg.fillCircle(cx, base - ht - 8f, 8f, 0xAAFFD54F.toInt())
            }
            lg.gradientRect(0f, 700f, w, h, 0x00000000, 0xAA12082A.toInt())
        }
        // a few stars twinkle
        g.save(); g.setBlend(com.clrg.core.render.Blend.ADD)
        for (i in 0 until 10) {
            val sx = (i * 389f + 91f) % w; val sy = 170f + (i * 151f) % 480f
            val tw = 0.5f + 0.5f * sin(t * 2f + i * 1.7f)
            g.glow(sx, sy, 10f + 6f * tw, Colors.withAlpha(Colors.WHITE, 0.4f * tw))
        }
        g.restore()
        // ferris wheel, slowly turning (the rim and spokes are one cached picture, rotated), gondolas hang level
        val fx = w * 0.16f; val fy = 430f; val fr = 230f
        g.save(); g.translate(fx, fy); g.rotate(t * 0.15f)
        g.layer("gallery.wheel", -fr - 10f, -fr - 10f, fr + 10f, fr + 10f) { lg ->
            lg.strokeCircle(0f, 0f, fr, 8f, 0x665E4B9A)
            lg.strokeCircle(0f, 0f, fr * 0.55f, 4f, 0x445E4B9A)
            for (i in 0 until 12) { val a = i * (2f * PI.toFloat() / 12f); lg.line(0f, 0f, cos(a) * fr, sin(a) * fr, 3f, 0x445E4B9A) }
            lg.fillCircle(0f, 0f, 14f, 0x885E4B9A.toInt())
        }
        g.restore()
        for (i in 0 until 12) {
            val a = t * 0.15f + i * (2f * PI.toFloat() / 12f)
            val gx = fx + cos(a) * fr; val gy = fy + sin(a) * fr
            g.fillRoundRect(gx - 14f, gy + 6f, gx + 14f, gy + 30f, 6f, if (i % 3 == 0) 0x99E53935.toInt() else if (i % 3 == 1) 0x991E88E5.toInt() else 0x99FDD835.toInt())
            val on = sin(t * 3f + i) > -0.2f
            g.fillCircle(gx, gy, 6f, if (on) 0xFFFFF59D.toInt() else 0x55FFF59D)
        }
        // strings of lights hanging across, chasing (three cached frames)
        Scenery.frames(g, "gallery.lights", 0f, 180f, w, 330f, t.toDouble(), frames = 3, fps = 4.0) { lg, f -> lightStrings(lg, w, f) }
    }

    private fun lightStrings(g: Gfx, w: Float, f: Int) {
        for (row in 0..1) {
            val y0 = 200f + row * 70f
            var px = -20f; var py = y0
            for (i in 0..24) {
                val x = i * w / 24f
                val y = y0 + 34f * sin(PI.toFloat() * ((i % 6) / 6f))
                if (i > 0) g.line(px, py, x, y, 2f, 0x88000000.toInt())
                val c = when ((i + row) % 4) { 0 -> 0xFFFF5252.toInt(); 1 -> 0xFFFFEB3B.toInt(); 2 -> 0xFF69F0AE.toInt(); else -> 0xFF40C4FF.toInt() }
                val lit = (f + i + row) % 3 != 0
                if (lit) g.glow(x, y + 8f, 22f, Colors.withAlpha(c, 0.45f))
                g.fillCircle(x, y + 8f, 7f, if (lit) c else Colors.withAlpha(c, 0.35f))
                px = x; py = y
            }
        }
    }

    /** One wooden wave cut-out across the booth (the ducks ride between a back and a front one). */
    private fun wave(g: Gfx, base: Float, t: Float, row: Int, front: Boolean) {
        val w = g.width
        val n = 49
        val pts = FloatArray((n + 2) * 2)
        val speed = (if (row == 0) 1.6f else -1.9f) * (if (front) 1f else 0.7f)
        val phase = if (front) 0f else 1.5f
        for (i in 0 until n) {
            val x = i * w / (n - 1f)
            pts[i * 2] = x
            pts[i * 2 + 1] = base + 12f * sin(x / 70f + t * speed + phase) + 5f * sin(x / 23f + t * speed * 1.7f)
        }
        pts[n * 2] = w; pts[n * 2 + 1] = base + 70f
        pts[n * 2 + 2] = 0f; pts[n * 2 + 3] = base + 70f
        val top = if (front) 0xFF42A5F5.toInt() else 0xFF1E5AA8.toInt()
        val bottom = if (front) 0xFF1565C0.toInt() else 0xFF0D2F6B.toInt()
        g.fillPolygonGradient(pts, n + 2, base - 18f, base + 70f, top, bottom)
        g.polyline(pts, n, 4f, if (front) 0xDDE3F2FD.toInt() else 0x8890CAF9.toInt())
        if (front) g.fillRect(0f, base + 52f, w, base + 70f, 0xFF6D4C41.toInt()) // the wooden rail under the front wave
    }

    /** Wooden shelf for the bottles, on brackets. */
    private fun shelf(g: Gfx, y: Float) {
        val w = g.width
        g.fillRect(0f, y + 24f, w, y + 34f, 0x55000000)
        g.gradientRect(0f, y, w, y + 24f, 0xFFA1887F.toInt(), 0xFF6D4C41.toInt())
        g.fillRect(0f, y, w, y + 3f, 0xFFD7CCC8.toInt())
        for (i in 0 until 7) {
            val bx = 160f + i * (w - 320f) / 6f
            Draw.tri(g, bx - 18f, y + 24f, bx + 18f, y + 24f, bx, y + 64f, 0xFF5D4037.toInt())
        }
    }

    /** The booth itself: striped valance with scallops and chasing bulbs, barber-pole posts, the counter and prizes. */
    private fun boothFrame(g: Gfx, t: Float) {
        val w = g.width; val h = g.height
        // posts with scrolling barber-pole stripes
        for (side in 0..1) {
            val l = if (side == 0) 0f else w - 56f
            g.save(); g.clipRect(l, 150f, l + 56f, h - 150f)
            g.gradientRect(l, 150f, l + 56f, h - 150f, 0xFFF5F5F5.toInt(), 0xFFD7CCC8.toInt())
            var yy = 110f - (t * 30f) % 80f
            while (yy < h) { g.line(l - 10f, yy, l + 66f, yy + 46f, 18f, 0xFFD32F2F.toInt()); yy += 80f }
            g.restore()
            g.fillRect(if (side == 0) l + 52f else l, 150f, if (side == 0) l + 56f else l + 4f, h - 150f, 0x55000000)
        }
        g.layer("gallery.valance", 0f, 0f, w, 200f) { lg -> valance(lg, w) }
        // chasing bulbs (four cached frames)
        Scenery.frames(g, "gallery.bulbs", 0f, 80f, w, 145f, t.toDouble(), frames = 4, fps = 6.0) { lg, f ->
            for (i in 0 until 32) {
                val x = (i + 0.5f) * w / 32f
                val lit = (f - i).mod(4) == 0
                if (lit) lg.glow(x, 112f, 26f, 0x88FFF59D.toInt())
                lg.fillCircle(x, 112f, 8f, if (lit) 0xFFFFFDE7.toInt() else 0xFFFFE082.toInt())
                lg.fillCircle(x - 2.5f, 109f, 2.5f, 0x88FFFFFF.toInt())
            }
        }
        g.layer("gallery.counter", 0f, h - 300f, w, h, grain = 0.2f) { lg -> counter(lg, w, h) }
    }

    private fun valance(g: Gfx, w: Float) {
        val n = 16
        for (i in 0 until n) g.fillRect(i * w / n, 0f, (i + 1) * w / n, 150f, if (i % 2 == 0) 0xFFD32F2F.toInt() else 0xFFFAFAFA.toInt())
        // folds in the canvas
        for (i in 0 until n) { val x = i * w / n; g.fillRect(x, 0f, x + 6f, 150f, 0x18000000); g.fillRect(x + w / n - 10f, 0f, x + w / n, 150f, 0x10000000) }
        g.gradientRect(0f, 60f, w, 150f, 0x00000000, 0x33000000)
        for (i in 0 until n) g.fillPie((i + 0.5f) * w / n, 150f, w / n / 2f, 0f, PI.toFloat(), if (i % 2 == 0) 0xFFD32F2F.toInt() else 0xFFFAFAFA.toInt())
        for (i in 0 until n) g.arc((i + 0.5f) * w / n, 150f, w / n / 2f - 2f, 0.2f, PI.toFloat() - 0.4f, 3f, 0x22000000)
        g.fillRect(0f, 146f, w, 152f, Colors.GOLD)
        g.fillRect(0f, 146f, w, 148f, 0x66FFFFFF)
        // a shadow the valance casts on the back of the booth
        g.gradientRect(0f, 152f, w, 200f, 0x55000000, 0x00000000)
    }

    private fun counter(g: Gfx, w: Float, h: Float) {
        // counter: wooden top with a gold lip, red-and-yellow front boards
        g.gradientRect(0f, h - 150f, w, h - 120f, 0xFFA1887F.toInt(), 0xFF795548.toInt())
        for (i in 0 until 8) g.line(0f, h - 146f + i * 3.5f, w, h - 145f + i * 3.5f, 1f, if (i % 2 == 0) 0x22000000 else 0x14FFFFFF)
        g.fillRect(0f, h - 156f, w, h - 148f, Colors.GOLD)
        g.fillRect(0f, h - 156f, w, h - 153f, 0x88FFFFFF.toInt())
        val boards = 12
        for (i in 0 until boards) {
            val l = i * w / boards
            g.gradientRect(l, h - 120f, l + w / boards, h, if (i % 2 == 0) 0xFFC62828.toInt() else 0xFFF9A825.toInt(), if (i % 2 == 0) 0xFF8E1B1B.toInt() else 0xFFC17900.toInt())
            g.fillRect(l, h - 120f, l + 3f, h, 0x44000000)
            g.fillCircle(l + w / boards / 2f, h - 60f, 14f, 0x33FFFFFF)
            Draw.star(g, l + w / boards / 2f, h - 60f, 10f, 0x66FFFFFF)
        }
        // prizes on the counter: a teddy at each end, clear of the bottles
        for (side in 0..1) {
            val x = if (side == 0) 140f else w - 140f
            val y = h - 150f
            val fur = if (side == 0) 0xFFBCAAA4.toInt() else 0xFFF48FB1.toInt()
            g.fillOval(x - 44f, y - 8f, x + 44f, y + 6f, 0x44000000)
            g.fillOval(x - 40f, y - 66f, x + 40f, y, fur)
            g.fillCircle(x, y - 92f, 32f, fur)
            g.fillCircleRadial(x, y - 92f, 32f, x - 12f, y - 104f, 44f, 0x33FFFFFF, 0x22000000)
            g.fillCircle(x - 26f, y - 118f, 13f, fur); g.fillCircle(x + 26f, y - 118f, 13f, fur)
            g.fillCircle(x - 26f, y - 118f, 7f, Colors.darker(fur, 0.8f)); g.fillCircle(x + 26f, y - 118f, 7f, Colors.darker(fur, 0.8f))
            g.fillOval(x - 14f, y - 88f, x + 14f, y - 68f, 0xFFFFF3E0.toInt())
            g.fillCircle(x - 11f, y - 98f, 4f, Colors.BLACK); g.fillCircle(x + 11f, y - 98f, 4f, Colors.BLACK)
            g.fillCircle(x - 10f, y - 99.5f, 1.4f, Colors.WHITE); g.fillCircle(x + 12f, y - 99.5f, 1.4f, Colors.WHITE)
            g.fillCircle(x, y - 84f, 4f, 0xFF4E342E.toInt())
            g.fillRoundRect(x - 22f, y - 62f, x + 22f, y - 52f, 5f, if (side == 0) 0xFF1E88E5.toInt() else 0xFF8E24AA.toInt())
        }
    }

    private fun drawTarget(g: Gfx, tg: Target, x: Float, y: Float, time: Double) {
        when (tg.kind) {
            Kind.DUCK -> {
                val dir = if (tg.vx >= 0) 1f else -1f
                val yy = y + 4f * sin(time.toFloat() * 5f + tg.id)
                g.fillRect(x - 4f, yy + 28f, x + 4f, yy + 60f, 0xFF5D4037.toInt())
                // body with a shaded underside, a wing and a tail flick
                Draw.tri(g, x - dir * 44f, yy - 4f, x - dir * 70f, yy - 26f, x - dir * 50f, yy + 12f, 0xFFFFB300.toInt())
                g.fillOval(x - 50f, yy - 22f, x + 50f, yy + 32f, 0xFFFFC107.toInt())
                g.fillOval(x - 46f, yy + 4f, x + 46f, yy + 32f, 0xFFFFA000.toInt())
                g.fillOval(x - dir * 34f - 22f, yy - 12f, x - dir * 34f + 22f, yy + 14f, 0xFFFFB300.toInt())
                g.fillOval(x - 30f, yy - 18f, x + 10f, yy - 6f, 0x66FFFFFF)
                // head, eye with a glint, beak
                g.fillCircle(x + dir * 32f, yy - 38f, 26f, 0xFFFFC107.toInt())
                g.fillCircle(x + dir * 24f, yy - 48f, 9f, 0x55FFFFFF)
                g.fillCircle(x + dir * 40f, yy - 44f, 7f, Colors.WHITE)
                g.fillCircle(x + dir * 41f, yy - 44f, 4.5f, Colors.BLACK)
                g.fillCircle(x + dir * 42.5f, yy - 46f, 1.6f, Colors.WHITE)
                Draw.tri(g, x + dir * 54f, yy - 42f, x + dir * 54f, yy - 28f, x + dir * 78f, yy - 34f, 0xFFFF6F00.toInt())
                // the target painted on its side
                g.fillCircle(x + dir * 2f, yy + 4f, 17f, Colors.WHITE); g.fillCircle(x + dir * 2f, yy + 4f, 11f, 0xFFE53935.toInt())
                g.fillCircle(x + dir * 2f, yy + 4f, 4f, Colors.WHITE)
            }
            Kind.BOTTLE -> {
                val wob = sin(time.toFloat() * 6f + tg.id) * 0.06f
                g.save(); g.translate(x, y + 30f); g.rotate(wob)
                g.fillRoundRect(-16f, -60f, 16f, 0f, 8f, Colors.withAlpha(tg.color, 0.9f))
                g.fillRect(-7f, -86f, 7f, -58f, Colors.withAlpha(tg.color, 0.9f))
                g.fillRect(-6f, -50f, 0f, -10f, 0x55FFFFFF)
                g.restore()
            }
            Kind.BALLOON -> {
                g.line(x, y + 44f, x + 6f * sin(time.toFloat() * 3f + tg.id), y + 110f, 2f, 0xAAFFFFFF.toInt())
                g.fillOval(x - 36f, y - 44f, x + 36f, y + 44f, tg.color)
                g.fillOval(x - 20f, y - 32f, x - 4f, y - 8f, 0x66FFFFFF)
            }
            Kind.STAR -> {
                g.glow(x, y, 80f, 0x88FFD54F.toInt())
                Draw.star(g, x, y, 38f, Colors.GOLD, time.toFloat() * 8f)
            }
        }
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        g.gradientRect(r.left, r.top, r.right, r.bottom, 0xFF4A0E1E.toInt(), 0xFF2A0712.toInt())
        for (i in 0 until 8) g.fillRect(r.left + i * r.width / 8f, r.top, r.left + (i + 1) * r.width / 8f, r.top + 30f, if (i % 2 == 0) 0xFFE53935.toInt() else Colors.WHITE)
        val phase = ((t % 2.0) / 2.0).toFloat()
        val dx = lerp(r.left - 40f, r.right + 40f, phase)
        val dy = r.cy - 10f
        if (phase < 0.55f) {
            g.fillOval(dx - 30f, dy - 12f, dx + 30f, dy + 20f, Colors.GOLD)
            g.fillCircle(dx + 20f, dy - 22f, 15f, Colors.GOLD)
        } else if (phase < 0.75f) g.text("+1", dx, dy - 20f, 40f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 4f)
        SportsDraw.fingerGun(g, r.cx, r.bottom - 40f, r.height * 0.4f, -1.2f, if (phase in 0.5f..0.7f) 1f else 0f)
    }
}
