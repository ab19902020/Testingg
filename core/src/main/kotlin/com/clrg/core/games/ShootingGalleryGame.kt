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
import com.clrg.core.tracking.MovementMode
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
                "Make a finger gun and point it at the TV - your crosshair follows your hand.",
                "Raise your thumb, then drop it to fire (toy gun: flick it up). First shot: the bullseye sets your aim.",
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
            Kind.BOTTLE -> { env.sfx(Sfx.GLASS, 0.9f, (sx / 960f - 1f).coerceIn(-1f, 1f)); env.particles.burst(sx, sy, tg.color, 22, speed = 360f, size = 8f) }
            Kind.BALLOON -> { env.sfx(Sfx.BALLOON_POP, 0.9f, (sx / 960f - 1f).coerceIn(-1f, 1f)); env.particles.burst(sx, sy, tg.color, 16, speed = 300f, kind = Particles.Kind.CONFETTI, size = 10f) }
            Kind.STAR -> { env.sfx(Sfx.SPARKLE, 1f); env.sfx(Sfx.APPLAUSE, 0.4f); env.particles.burst(sx, sy, Colors.GOLD, 30, kind = Particles.Kind.STAR, size = 14f) }
            Kind.DUCK -> { env.sfx(Sfx.TIN_HIT, 0.8f, (sx / 960f - 1f).coerceIn(-1f, 1f)); env.particles.burst(sx, sy, Colors.GOLD, 12, speed = 260f, size = 7f) }
        }
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
        // back wall: deep red with gold stars
        g.gradientRect(0f, 0f, g.width, g.height, 0xFF4A0E1E.toInt(), 0xFF2A0712.toInt())
        for (i in 0 until 18) Draw.star(g, (i * 137f) % g.width, 180f + (i * 71f) % 300f, 10f, 0x55FFD54F, time.toFloat() * 0.3f + i)
        // awning stripes and bulbs
        for (i in 0 until 16) g.fillRect(i * g.width / 16f, 0f, (i + 1) * g.width / 16f, 120f, if (i % 2 == 0) 0xFFE53935.toInt() else Colors.WHITE)
        for (i in 0 until 16) g.fillCircle((i + 0.5f) * g.width / 16f, 128f, 18f, if (i % 2 == 0) 0xFFE53935.toInt() else Colors.WHITE)
        for (i in 0 until 24) {
            val on = sin(time * 4 + i).toFloat() > 0f
            g.fillCircle((i + 0.5f) * g.width / 24f, 158f, 7f, if (on) 0xFFFFF59D.toInt() else 0x66FFF59D)
        }
        // duck rails and water waves
        for ((i, ry) in rowY.withIndex()) {
            val y = ry + oy + 52f
            g.fillRect(0f, y, g.width, y + 26f, 0xFF1565C0.toInt())
            for (w in 0 until 40) g.fillCircle(w * 60f + ((time * (60 + i * 30)) % 60).toFloat(), y, 18f, 0xFF1E88E5.toInt())
        }
        // bottle shelf
        g.fillRect(0f, 270f + oy + 34f, g.width, 270f + oy + 58f, 0xFF6D4C41.toInt())
        // targets (balloons behind, ducks and bottles, stars in front)
        val order = listOf(Kind.BALLOON, Kind.BOTTLE, Kind.DUCK, Kind.STAR)
        for (kind in order) for (tg in targets) if (tg.kind == kind && tg.alive(playTime)) drawTarget(g, tg, tg.x(playTime) + ox, tg.y(playTime) + oy, time)
        // counter
        g.gradientRect(0f, g.height - 150f, g.width, g.height, 0xFF8D6E63.toInt(), 0xFF5D4037.toInt())
        g.fillRect(0f, g.height - 156f, g.width, g.height - 146f, Colors.GOLD)
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

    private fun drawTarget(g: Gfx, tg: Target, x: Float, y: Float, time: Double) {
        when (tg.kind) {
            Kind.DUCK -> {
                val dir = if (tg.vx >= 0) 1f else -1f
                g.fillOval(x - 46f, y - 20f, x + 46f, y + 30f, Colors.GOLD)
                g.fillCircle(x + dir * 30f, y - 34f, 24f, Colors.GOLD)
                g.fillCircle(x + dir * 38f, y - 40f, 5f, Colors.BLACK)
                Draw.tri(g, x + dir * 50f, y - 38f, x + dir * 50f, y - 26f, x + dir * 70f, y - 32f, 0xFFFF8F00.toInt())
                g.fillCircle(x, y + 2f, 16f, Colors.WHITE); g.fillCircle(x, y + 2f, 10f, 0xFFE53935.toInt())
                g.fillRect(x - 4f, y + 30f, x + 4f, y + 52f, 0xFF5D4037.toInt())
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
