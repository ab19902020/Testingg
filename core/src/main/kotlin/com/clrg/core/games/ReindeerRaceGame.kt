package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.math.Rect
import com.clrg.core.math.clamp
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * 4. Reindeer Racing - pump your arms alternately to run, lean to steer inside your own lane, dodge obstacles and
 * grab carrots. Speed comes from the *rhythm* of your arm pumping and is capped, so bigger or wilder movements do
 * not automatically win. Reduced-movement players reach top speed with small, slower pumps.
 */
class ReindeerRaceGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    enum class ObKind { SNOWMAN, LOG, ICE }
    class Obstacle(val z: Float, val x: Float, val kind: ObKind)
    class Carrot(val z: Float, val x: Float)

    companion object {
        const val LENGTH = 520f
        const val MIN_SPEED = 3.5f
        const val MAX_SPEED = 19f
        const val HALF_WIDTH = 1.7f
        const val AFTER_FINISH_SEC = 12.0

        val INFO = GameInfo(
            id = "reindeer", title = "Reindeer Racing", tagline = "Pump your arms to gallop!",
            howTo = listOf(
                "Pump your arms up and down, one after the other, like running - no actual running!",
                "A steady rhythm is fastest; bigger movements are not faster.",
                "Lean left or right to steer around snowmen, logs and ice.",
                "Carrots give a speed boost. First reindeer over the finish line wins!",
            ),
            controls = listOf("Alternate arms up/down" to "Run", "Lean left / right" to "Steer"),
            gentleNotes = "Reduced movement / seated: small arm pumps at a gentle rhythm reach full speed.",
            music = MusicTrack.GAME, scoreUnit = "place", goal = "First to the finish line",
        )
    }

    val distance = FloatArray(2)
    val speed = FloatArray(2)
    val lateral = FloatArray(2)
    val stumble = FloatArray(2)
    val boost = FloatArray(2)
    val finishTime = doubleArrayOf(-1.0, -1.0)
    val bumps = IntArray(2)
    val carrots = IntArray(2)
    private val hitSet = Array(2) { HashSet<Int>() }
    private val carrotSet = Array(2) { HashSet<Int>() }
    val obstacles = ArrayList<Obstacle>()
    val carrotList = ArrayList<Carrot>()
    private var firstFinish = -1.0
    private val phase = FloatArray(2)
    private var screenW = 1920f

    override fun start() {
        val rng = env.rng(4)
        var z = 40f
        while (z < LENGTH - 30f) {
            val lane = rng.intRange(-1, 1) * 1.05f
            obstacles.add(Obstacle(z, lane, ObKind.values()[rng.nextInt(3)]))
            if (rng.chance(0.35f)) {
                var other = rng.intRange(-1, 1) * 1.05f
                if (other == lane) other = -lane
                if (other != lane) obstacles.add(Obstacle(z + 4f, other, ObKind.values()[rng.nextInt(3)]))
            }
            if (rng.chance(0.5f)) carrotList.add(Carrot(z + 14f, rng.intRange(-1, 1) * 1.05f))
            z += rng.range(24f, 36f)
        }
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            if (finishTime[k] >= 0) { speed[k] = (speed[k] - 8f * dt).coerceAtLeast(0f); distance[k] += speed[k] * dt; continue }
            val inp = inputs[k]
            stumble[k] = (stumble[k] - dt).coerceAtLeast(0f)
            boost[k] = (boost[k] - dt).coerceAtLeast(0f)
            var target = lerp(MIN_SPEED, MAX_SPEED, clamp(inp.pump, 0f, 1f))
            if (boost[k] > 0f) target *= 1.3f
            if (stumble[k] > 0f) target *= 0.3f
            val accel = if (target > speed[k]) 7f else 12f
            speed[k] += clamp(target - speed[k], -accel * dt, accel * dt)
            lateral[k] = clamp(lateral[k] + clamp(inp.lean, -1f, 1f) * 3.0f * dt, -HALF_WIDTH, HALF_WIDTH)
            val before = distance[k]
            distance[k] += speed[k] * dt
            phase[k] += speed[k] * dt / 2.4f
            for ((i, o) in obstacles.withIndex()) {
                if (i in hitSet[k] || o.z < before - 1f || o.z > distance[k] + 0.6f) continue
                if (abs(o.x - lateral[k]) < 0.6f && o.z in before..(distance[k] + 0.6f)) {
                    hitSet[k].add(i)
                    stumble[k] = 1.0f
                    bumps[k]++
                    env.sfx(Sfx.CRASH, 0.7f, pan(k))
                    env.popups.add("Bump!", laneCx(k), 640f, Colors.BAD, 50f)
                    env.particles.burst(laneCx(k), 820f, Colors.SNOW, 18, kind = Particles.Kind.PUFF)
                }
            }
            for ((i, c) in carrotList.withIndex()) {
                if (i in carrotSet[k] || c.z !in before..(distance[k] + 0.6f)) continue
                if (abs(c.x - lateral[k]) < 0.65f) {
                    carrotSet[k].add(i)
                    boost[k] = 2f
                    carrots[k]++
                    env.sfx(Sfx.PICKUP, 0.8f, pan(k))
                    env.popups.add("Carrot boost!", laneCx(k), 600f, Colors.WARM, 46f)
                }
            }
            if (distance[k] >= LENGTH) {
                finishTime[k] = playTime
                if (firstFinish < 0) firstFinish = playTime
                env.sfx(Sfx.CHEER, 0.9f, pan(k))
                env.particles.confetti(laneCx(k) - 300f, laneCx(k) + 300f, 200f, 60)
                env.popups.add(if (place(k) == 1) "1st!" else "2nd", laneCx(k), 420f, env.color(k), 90f, 1.6f)
            }
        }
    }

    /** 1 or 2 (both 1 on an exact tie). */
    fun place(k: Int): Int {
        val o = 1 - k
        val a = finishTime[k]; val b = finishTime[o]
        return when {
            a >= 0 && b >= 0 -> if (a < b) 1 else if (b < a) 2 else 1
            a >= 0 -> 1
            b >= 0 -> 2
            else -> if (distance[k] >= distance[o]) 1 else 2
        }
    }

    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f

    // ---- CPU player: pumps at a rhythm that drifts (and is sometimes lost for a moment), only sees obstacles
    // a limited time ahead minus its reaction time, sometimes misses one, and leans smoothly like a person.
    private val botPump = floatArrayOf(0.7f, 0.7f)
    private val botLost = FloatArray(2)
    private val botLean = FloatArray(2)
    private val botNotice = Array(2) { HashMap<Int, Boolean>() }
    private val botCarrot = Array(2) { HashMap<Int, Boolean>() }

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        val base = lerp(0.66f, 0.95f, skill)
        botPump[k] += (base + rng.gaussian() * lerp(0.35f, 0.12f, skill) - botPump[k]) * dt * 1.5f
        botLost[k] -= dt
        if (botLost[k] <= 0f && rng.chance(lerp(0.004f, 0.0005f, skill))) botLost[k] = rng.range(0.6f, 1.4f)
        inp.pump = clamp(botPump[k], 0f, 1f) * if (botLost[k] > 0f) 0.4f else 1f
        val look = (lerp(1.0f, 1.7f, skill) - com.clrg.core.game.CpuTraits.reaction(skill)) * maxOf(speed[k], 6f)
        val near = distance[k]
        var best = lateral[k]
        var bestCost = Float.MAX_VALUE
        for (l in floatArrayOf(-1.05f, 0f, 1.05f)) {
            var cost = abs(l - lateral[k]) * 0.5f
            for ((i, o) in obstacles.withIndex()) {
                if (o.z <= near || o.z > near + look) continue
                if (!botNotice[k].getOrPut(i) { rng.chance(lerp(0.75f, 0.97f, skill)) }) continue
                if (abs(o.x - l) < 0.75f) cost += 10f
            }
            for ((i, c) in carrotList.withIndex()) {
                if (c.z <= near || c.z > near + look) continue
                if (botCarrot[k].getOrPut(i) { rng.chance(lerp(0.3f, 0.8f, skill)) } && abs(c.x - l) < 0.5f) cost -= 0.6f
            }
            if (cost < bestCost) { bestCost = cost; best = l }
        }
        val want = clamp((best - lateral[k]) * 2.5f, -1f, 1f)
        botLean[k] += (want - botLean[k]) * minOf(1f, dt * 8f)
        inp.lean = botLean[k]
    }

    private fun laneCx(k: Int) = if (k == 0) screenW * 0.25f else screenW * 0.75f

    override val isOver: Boolean get() = (finishTime[0] >= 0 && finishTime[1] >= 0) || (firstFinish >= 0 && playTime - firstFinish > AFTER_FINISH_SEC) || playTime > 150.0

    override fun result(): MatchResult {
        val p0 = place(0); val p1 = place(1)
        val winner = if (p0 == p1) -1 else if (p0 < p1) 0 else 1
        fun t(k: Int) = if (finishTime[k] >= 0) "%.1f s".format(finishTime[k]) else "${distance[k].toInt()} m (did not finish)"
        return MatchResult(info.id, intArrayOf(if (p0 == 1) 1 else 0, if (p1 == 1) 1 else 0), winner, "wins",
            listOf("Times: ${t(0)} vs ${t(1)}", "Bumps: ${bumps[0]} vs ${bumps[1]}", "Carrots: ${carrots[0]} vs ${carrots[1]}"))
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) renderLane(g, k, inputs[k], time)
        Hud.divider(g)
        // shared progress bar
        val bx0 = g.width * 0.3f; val bx1 = g.width * 0.7f
        g.fillRoundRect(bx0, 132f, bx1, 150f, 9f, 0x66000000)
        for (k in 0..1) {
            val f = (distance[k] / LENGTH).coerceIn(0f, 1f)
            g.fillCircle(lerp(bx0, bx1, f), 141f, 16f, env.color(k))
            g.text("P${k + 1}", lerp(bx0, bx1, f), 141f, 18f, Colors.BLACK)
        }
        Draw.star(g, bx1 + 22f, 141f, 18f, Colors.GOLD)
        Hud.topBar(g, env, null, "Reindeer Race", "Pump arms to run - lean to steer", inputs)
    }

    private fun renderLane(g: Gfx, k: Int, inp: PlayerInput, time: Double) {
        val r = Rect(if (k == 0) 0f else screenW / 2f, 0f, if (k == 0) screenW / 2f else screenW, 1080f)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        val horizon = 360f
        Draw.nightSky(g, Rect(r.left, 0f, r.right, horizon), time)
        Draw.aurora(g, Rect(r.left, 0f, r.right, horizon), time, 0.7f)
        // distant mountains
        var mn = 0
        for (i in 0..12) { val u = i / 12f; mpts[mn++] = r.left + r.width * u; mpts[mn++] = horizon - 70f - 60f * kotlin.math.abs(sin(u * 7.3f + k)) - 30f * sin(u * 17f) }
        mpts[mn++] = r.right; mpts[mn++] = horizon; mpts[mn++] = r.left; mpts[mn++] = horizon
        g.fillPolygonGradient(mpts, mn / 2, horizon - 160f, horizon, 0xFF3E5F8F.toInt(), 0xFF1D3A63.toInt())
        Draw.pineRow(g, r.left, r.right, horizon + 6f, 0xFF173F33.toInt(), 90f, 21 + k)
        g.gradientRect(r.left, horizon, r.right, r.bottom, 0xFFC7D9F0.toInt(), 0xFFF7FBFF.toInt())
        val cx = r.cx
        val f = r.width * 0.55f
        val camH = 1.7f
        val camX = lateral[k] * 0.7f
        fun sy(z: Float) = horizon + camH * f / z
        fun sx(x: Float, z: Float) = cx + (x - camX) * f / z
        // road with candy-cane edge posts
        val zNear = 1.2f; val zFar = 80f
        val road = floatArrayOf(sx(-2.3f, zNear), sy(zNear), sx(2.3f, zNear), sy(zNear), sx(2.3f, zFar), sy(zFar), sx(-2.3f, zFar), sy(zFar))
        g.fillPolygonGradient(road, 4, sy(zFar), sy(zNear), 0xFF9FB8DA.toInt(), 0xFFDDE9F8.toInt())
        // groomed corduroy lines along the track
        for (lx in floatArrayOf(-1.6f, -0.55f, 0.55f, 1.6f)) g.line(sx(lx, zNear), sy(zNear), sx(lx, zFar), sy(zFar), 2f, 0x22FFFFFF)
        val d = distance[k]
        var mark = (kotlin.math.floor(d / 6f) * 6f) + 6f
        while (mark < d + zFar) {
            val z = mark - d
            if (z > zNear) {
                val s = f / z
                for (side in intArrayOf(-1, 1)) {
                    val x = sx(side * 2.4f, z)
                    g.fillRect(x - 0.06f * s, sy(z) - 0.9f * s, x + 0.06f * s, sy(z), if ((mark / 6f).toInt() % 2 == 0) Colors.CANDY else Colors.WHITE)
                    if ((mark / 6f).toInt() % 2 == 0) {
                        g.glow(x, sy(z) - 0.95f * s, 0.35f * s, 0x88FFC870.toInt())
                        g.fillCircle(x, sy(z) - 0.95f * s, 0.07f * s, 0xFFFFE9B0.toInt())
                    }
                }
                g.fillRect(sx(-0.5f, z), sy(z) - 0.02f * s, sx(0.5f, z), sy(z) + 0.02f * s, 0x22003366)
            }
            mark += 6f
        }
        // finish line
        val fz = LENGTH - d
        if (fz in zNear..zFar) {
            val s = f / fz
            for (i in 0 until 10) g.fillRect(sx(-2.3f + i * 0.46f, fz), sy(fz) - 0.05f * s, sx(-2.3f + (i + 1) * 0.46f, fz), sy(fz) + 0.05f * s, if (i % 2 == 0) Colors.BLACK else Colors.WHITE)
            g.fillRect(sx(-2.5f, fz) - 4f, sy(fz) - 2.6f * s, sx(-2.5f, fz) + 4f, sy(fz), 0xFF795548.toInt())
            g.fillRect(sx(2.5f, fz) - 4f, sy(fz) - 2.6f * s, sx(2.5f, fz) + 4f, sy(fz), 0xFF795548.toInt())
            g.fillRect(sx(-2.5f, fz), sy(fz) - 2.6f * s, sx(2.5f, fz), sy(fz) - 2.0f * s, Colors.CANDY)
            if (s > 30f) g.text("FINISH", (sx(-2.5f, fz) + sx(2.5f, fz)) / 2, sy(fz) - 2.3f * s, 0.45f * s, Colors.WHITE)
        }
        // obstacles and carrots, far to near (plus pines along the course for depth)
        val items = ArrayList<Pair<Float, () -> Unit>>()
        var tz = (kotlin.math.floor(d / 14f) * 14f) + 14f
        while (tz < d + zFar) {
            val z = tz - d
            if (z > zNear) {
                val s = f / z
                for (side in intArrayOf(-1, 1)) {
                    val tx = sx(side * (3.4f + 0.8f * Draw.hash01((tz.toInt() * 7 + side))), z)
                    items.add(z to { Draw.pineTree(g, tx, sy(z), 2.6f * s, 0xFF1F5A3F.toInt()) })
                }
            }
            tz += 14f
        }
        for ((i, o) in obstacles.withIndex()) {
            val z = o.z - d
            if (z !in zNear..zFar) continue
            val s = f / z
            val x = sx(o.x, z); val y = sy(z)
            val hit = i in hitSet[k]
            items.add(z to {
                when (o.kind) {
                    ObKind.SNOWMAN -> Draw.buddy(g, x, y, 1.3f * s, 0xFF90A4AE.toInt(), null, if (hit) Expression.OOPS else Expression.HAPPY)
                    ObKind.LOG -> {
                        Draw.softShadow(g, x, y, 0.65f * s, 0.08f * s, 0.4f)
                        g.fillRoundRectGradient(x - 0.55f * s, y - 0.3f * s, x + 0.55f * s, y, 0.15f * s, 0xFF8D6E63.toInt(), 0xFF4E342E.toInt())
                        g.fillRoundRect(x - 0.5f * s, y - 0.32f * s, x + 0.4f * s, y - 0.24f * s, 0.06f * s, 0xEEF4F8FF.toInt())
                        g.fillOval(x + 0.42f * s, y - 0.3f * s, x + 0.62f * s, y, 0xFFD7C2B0.toInt())
                        g.strokeCircle(x + 0.52f * s, y - 0.15f * s, 0.05f * s, 0.012f * s + 0.5f, 0xFF8D6E63.toInt())
                    }
                    ObKind.ICE -> {
                        g.save(); g.translate(x, y); g.scale(1f, 0.2f)
                        g.fillCircleRadial(0f, 0f, 0.6f * s, -0.2f * s, -0.2f * s, 0.8f * s, 0xEEE1F5FE.toInt(), 0xCC4FC3F7.toInt())
                        g.restore()
                        g.line(x - 0.3f * s, y, x + 0.2f * s, y - 0.05f * s, 0.02f * s + 1f, Colors.WHITE)
                        Draw.sparkle(g, x + 0.25f * s, y - 0.05f * s, 0.08f * s + 2f, Colors.WHITE)
                    }
                }
            })
        }
        for ((i, c) in carrotList.withIndex()) {
            val z = c.z - d
            if (z !in zNear..zFar || i in carrotSet[k]) continue
            val s = f / z
            val x = sx(c.x, z); val y = sy(z) - 0.4f * s
            items.add(z to {
                g.glow(x, y, 0.5f * s, 0x55FFB74D)
                Draw.tri(g, x - 0.1f * s, y - 0.2f * s, x + 0.1f * s, y - 0.2f * s, x, y + 0.3f * s, 0xFFFF8F00.toInt())
                g.line(x, y - 0.2f * s, x - 0.08f * s, y - 0.35f * s, 0.04f * s + 1f, Colors.PINE)
                g.line(x, y - 0.2f * s, x + 0.08f * s, y - 0.35f * s, 0.04f * s + 1f, Colors.PINE)
            })
        }
        items.sortByDescending { it.first }
        items.forEach { it.second() }
        // speed streaks during a carrot boost
        if (boost[k] > 0f) for (i in 0 until 10) {
            val u = Draw.hash01(i * 13 + k)
            val ph = ((time * 2.5 + u) % 1.0).toFloat()
            val lx = r.left + r.width * (0.1f + 0.8f * u)
            g.line(lx, horizon + 60f + ph * 600f, lx + (lx - cx) * 0.15f, horizon + 120f + ph * 600f, 4f, Colors.withAlpha(Colors.WARM, 0.5f * (1f - ph)))
        }
        // the player's reindeer, seen from behind
        val rx = sx(lateral[k], 3.2f)
        drawReindeerBack(g, rx, r.bottom - 60f, 330f, env.color(k), phase[k], stumble[k] > 0f, time)
        // speed meter
        val sp = speed[k] / MAX_SPEED
        g.fillRoundRect(r.left + 40f, 260f, r.left + 70f, 760f, 15f, 0x55000000)
        g.fillRoundRect(r.left + 40f, 760f - 500f * sp.coerceIn(0f, 1f), r.left + 70f, 760f, 15f, if (boost[k] > 0f) Colors.WARM else env.color(k))
        g.text("${(speed[k] * 3.6f).toInt()} km/h", r.left + 24f, 795f, 30f, Colors.BLACK, com.clrg.core.render.Align.LEFT, bold = true)
        if (finishTime[k] >= 0) g.text(if (place(k) == 1) "1st place!" else "2nd place", r.cx, 470f, 80f, env.color(k), outline = Colors.BLACK, outlineWidth = 8f)
        else if (inp.pump < 0.05f && playTime > 2) g.text("Pump your arms!", r.cx, 470f, 50f, Colors.CANDY, outline = Colors.WHITE, outlineWidth = 6f)
        Hud.laneLabel(g, Rect(r.left, 150f, r.right, 300f), env, k)
        g.restore()
    }

    private val mpts = FloatArray(40)

    private fun drawReindeerBack(g: Gfx, x: Float, y: Float, h: Float, color: Int, ph: Float, stumbling: Boolean, time: Double) {
        val body = 0xFF8D5A3B.toInt(); val dark = 0xFF5D3A24.toInt()
        val bob = sin(ph * 6.283f * 2) * h * 0.025f
        g.fillOval(x - h * 0.4f, y - h * 0.05f, x + h * 0.4f, y + h * 0.05f, Colors.SHADOW)
        for (i in 0..3) {
            val side = if (i % 2 == 0) -1f else 1f
            val lift = sin(ph * 6.283f + i * 1.57f).coerceAtLeast(0f) * h * 0.08f
            val lx = x + side * h * (if (i < 2) 0.14f else 0.2f)
            g.line(lx, y - h * 0.32f + bob, lx, y - lift, h * 0.07f, if (i < 2) dark else body)
            g.fillOval(lx - h * 0.04f, y - lift - h * 0.03f, lx + h * 0.04f, y - lift + h * 0.01f, 0xFF3E2723.toInt())
        }
        g.save(); if (stumbling) { g.translate(x, y - h * 0.4f); g.rotate(sin(time * 20).toFloat() * 0.15f); g.translate(-x, -(y - h * 0.4f)) }
        g.save(); g.translate(x, y - h * 0.44f + bob); g.scale(1f, 0.64f)
        g.fillCircleRadial(0f, 0f, h * 0.28f, -h * 0.1f, -h * 0.14f, h * 0.42f, 0xFFB07850.toInt(), 0xFF5D3A24.toInt())
        g.restore()
        g.fillRoundRectGradient(x - h * 0.22f, y - h * 0.6f + bob, x + h * 0.22f, y - h * 0.42f + bob, h * 0.05f, Colors.lighter(color, 0.3f), Colors.darker(color, 0.7f))
        g.line(x - h * 0.2f, y - h * 0.44f + bob, x + h * 0.2f, y - h * 0.44f + bob, h * 0.012f, Colors.GOLD)
        g.fillCircle(x, y - h * 0.32f + bob, h * 0.06f, Colors.SNOW)
        // rider (the player's snow buddy hat and scarf)
        g.fillCircle(x, y - h * 0.68f + bob, h * 0.11f, 0xFFF7FBFF.toInt())
        g.fillPie(x, y - h * 0.72f + bob, h * 0.11f, 3.1416f, 3.1416f, color)
        g.fillCircle(x, y - h * 0.84f + bob, h * 0.035f, Colors.WHITE)
        // head and antlers ahead of the body
        g.fillOval(x - h * 0.09f, y - h * 0.86f + bob, x + h * 0.09f, y - h * 0.7f + bob, body)
        val ant = 0xFFC8A27A.toInt()
        for (s in intArrayOf(-1, 1)) {
            g.line(x + s * h * 0.05f, y - h * 0.84f + bob, x + s * h * 0.2f, y - h * 1.02f + bob, h * 0.025f, ant)
            g.line(x + s * h * 0.13f, y - h * 0.93f + bob, x + s * h * 0.1f, y - h * 1.05f + bob, h * 0.02f, ant)
            g.line(x + s * h * 0.18f, y - h * 0.99f + bob, x + s * h * 0.28f, y - h * 1.04f + bob, h * 0.02f, ant)
        }
        g.restore()
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val p = com.clrg.core.gesture.AvatarPose()
        val s = sin(t * 2 * Math.PI * 1.2).toFloat()
        p.upper[0] = -1.9f + 0.9f * s; p.fore[0] = -0.4f + 1.4f * s
        p.upper[1] = -1.2f - 0.9f * s; p.fore[1] = -2.7f - 1.4f * s
        p.valid = true
        Draw.buddy(g, r.cx - r.width * 0.2f, r.bottom - 10f, r.height * 0.8f, env.color(0), p, Expression.EXCITED, t = t)
        Draw.reindeer(g, r.cx + r.width * 0.22f, r.bottom - 10f, r.height * 0.6f, env.color(0), (t * 1.5).toFloat())
    }
}
