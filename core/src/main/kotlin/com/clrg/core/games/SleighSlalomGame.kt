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
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * 8. Sleigh Slalom - two simultaneous downhill runs in split screen. Lean to steer through every pair of flags,
 * collect presents (-0.5 s each), avoid trees and rocks. Tuck (hands together in front of your chest) to go faster.
 * Steering-only mode (accessibility setting, or automatically when a player uses reduced/seated mode) removes the
 * tuck for both players and gives everyone the same automatic speed.
 */
class SleighSlalomGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    enum class Kind { GATE, PRESENT, TREE, ROCK }
    class Thing(val y: Float, val x: Float, val kind: Kind, val halfWidth: Float = 0f)

    companion object {
        const val LENGTH = 430f
        const val MISS_PENALTY = 2.0
        const val PRESENT_BONUS = 0.5
        /** Metres of slope a player can see ahead of the sleigh on screen. */
        const val VISIBLE_AHEAD = 24f
        const val AFTER_FINISH_SEC = 15.0
        val INFO = GameInfo(
            id = "slalom", title = "Sleigh Slalom", tagline = "Lean to carve through the gates!",
            howTo = listOf(
                "Lean left or right to steer your sleigh down the hill.",
                "Pass between every pair of flags. A missed gate adds 2 seconds.",
                "Presents take 0.5 seconds off. Trees and rocks stop you for a moment.",
                "Tuck - hands together in front of your chest - to go faster. Fastest time wins!",
            ),
            controls = listOf("Lean left / right" to "Steer", "Hands together in front" to "Tuck for speed"),
            gentleNotes = "Steering-only mode: no tuck needed, speed is automatic for both players.",
            music = MusicTrack.GAME, scoreUnit = "wins", goal = "Fastest time",
        )
    }

    val steeringOnly = env.settings.steeringOnlySlalom || env.players.any { it.mode != MovementMode.STANDARD }
    val things = ArrayList<Thing>()
    val y = FloatArray(2)
    val x = FloatArray(2)
    val speed = FloatArray(2)
    val crash = FloatArray(2)
    val gates = IntArray(2)
    val missed = IntArray(2)
    val presents = IntArray(2)
    val finish = doubleArrayOf(-1.0, -1.0)
    private val done = Array(2) { HashSet<Int>() }
    private var firstFinish = -1.0
    private var screenW = 1920f
    private val tucking = BooleanArray(2)
    private val steer = FloatArray(2)

    override fun start() {
        val rng = env.rng(8)
        var gy = 30f
        var side = if (rng.chance(0.5f)) 1f else -1f
        while (gy < LENGTH - 20f) {
            val gx = side * rng.range(0.15f, 0.5f)
            things.add(Thing(gy, gx, Kind.GATE, 0.27f))
            // a present on the racing line or a hazard beside it
            if (rng.chance(0.55f)) things.add(Thing(gy + 11f, gx * -0.5f + rng.range(-0.15f, 0.15f), Kind.PRESENT))
            if (rng.chance(0.6f)) things.add(Thing(gy + rng.range(6f, 16f), -side * rng.range(0.55f, 0.85f), if (rng.chance(0.5f)) Kind.TREE else Kind.ROCK))
            things.add(Thing(gy + rng.range(0f, 20f), side * rng.range(0.85f, 0.98f), Kind.TREE))
            side = -side
            gy += rng.range(22f, 28f)
        }
        things.sortBy { it.y }
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            if (finish[k] >= 0) { speed[k] = (speed[k] - 10f * dt).coerceAtLeast(0f); y[k] += speed[k] * dt; continue }
            val inp = inputs[k]
            crash[k] = (crash[k] - dt).coerceAtLeast(0f)
            tucking[k] = !steeringOnly && inp.tuck
            steer[k] = clamp(inp.lean, -1f, 1f)
            val base = lerp(9f, 15f, (y[k] / LENGTH).coerceIn(0f, 1f))
            val target = when {
                crash[k] > 0f -> 0f
                steeringOnly -> base * 1.12f
                tucking[k] -> base * 1.25f
                else -> base
            }
            speed[k] += clamp(target - speed[k], -20f * dt, 5f * dt)
            x[k] = clamp(x[k] + clamp(inp.lean, -1f, 1f) * 1.25f * dt * (0.6f + 0.4f * speed[k] / 15f), -1f, 1f)
            val before = y[k]
            y[k] += speed[k] * dt
            for ((i, t) in things.withIndex()) {
                if (t.y < before || t.y > y[k]) continue
                if (i in done[k]) continue
                done[k].add(i)
                val dx = abs(x[k] - t.x)
                when (t.kind) {
                    Kind.GATE -> if (dx <= t.halfWidth) {
                        gates[k]++; env.sfx(Sfx.GATE, 0.7f, pan(k))
                    } else {
                        missed[k]++; env.sfx(Sfx.MISS, 0.6f, pan(k))
                        env.popups.add("Missed gate +2s", laneCx(k), 520f, Colors.BAD, 46f)
                    }
                    Kind.PRESENT -> if (dx < 0.13f) {
                        presents[k]++; env.sfx(Sfx.PICKUP, 0.8f, pan(k))
                        env.popups.add("-0.5s", laneCx(k), 520f, Colors.GOLD, 50f)
                        env.particles.burst(laneCx(k), sleighY, Colors.GOLD, 14, kind = Particles.Kind.STAR)
                    } else done[k].remove(i) // passed by: stays visible, can no longer be collected
                    Kind.TREE, Kind.ROCK -> if (dx < 0.12f) {
                        crash[k] = 1f; speed[k] *= 0.2f
                        env.sfx(Sfx.CRASH, 0.8f, pan(k)); env.shake.kick(6f)
                        env.popups.add("Crash!", laneCx(k), 520f, Colors.BAD, 52f)
                        env.particles.burst(laneCx(k), sleighY, Colors.SNOW, 22, kind = Particles.Kind.PUFF)
                    }
                }
            }
            if (y[k] >= LENGTH) {
                finish[k] = total(k, playTime)
                if (firstFinish < 0) firstFinish = playTime
                env.sfx(Sfx.CHEER, 0.9f, pan(k))
            }
        }
    }

    // ---- CPU player: sees as far down the slope as a player does on screen (minus what passes during its
    // reaction time), aims at each gate with a human error (occasionally badly), dodges hazards it noticed, picks
    // up presents on better levels, and tucks on the straights.
    private val botAim = Array(2) { HashMap<Int, Float>() }
    private val botNotice = Array(2) { HashMap<Int, Boolean>() }
    private val botLean = FloatArray(2)
    private val botTuckPref = FloatArray(2)
    private val botTuckNext = DoubleArray(2)

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        val look = VISIBLE_AHEAD - com.clrg.core.game.CpuTraits.reaction(skill) * maxOf(speed[k], 8f)
        var target = x[k]
        val gi = things.indexOfFirst { it.kind == Kind.GATE && it.y > y[k] }
        if (gi >= 0 && things[gi].y < y[k] + look) {
            val err = botAim[k].getOrPut(gi) {
                if (rng.chance(lerp(0.88f, 0.985f, skill))) rng.gaussian() * lerp(0.16f, 0.05f, skill) else rng.range(0.35f, 0.55f) * if (rng.chance(0.5f)) 1f else -1f
            }
            target = things[gi].x + err
        }
        for ((i, t) in things.withIndex()) {
            if (t.y <= y[k] || t.y > y[k] + look) continue
            when (t.kind) {
                Kind.TREE, Kind.ROCK -> if (abs(t.x - target) < 0.2f && botNotice[k].getOrPut(i) { rng.chance(lerp(0.8f, 0.98f, skill)) }) {
                    target = t.x + if (target >= t.x) 0.25f else -0.25f
                }
                Kind.PRESENT -> if (abs(t.x - target) < 0.25f && botNotice[k].getOrPut(i) { rng.chance(lerp(0.2f, 0.7f, skill)) }) target = t.x
                else -> Unit
            }
        }
        val want = clamp((target - x[k]) * 4f, -1f, 1f)
        botLean[k] += (want - botLean[k]) * minOf(1f, dt * 8f)
        inp.lean = botLean[k]
        if (playTime >= botTuckNext[k]) { botTuckPref[k] = if (rng.chance(lerp(0.35f, 0.9f, skill))) 1f else 0f; botTuckNext[k] = playTime + rng.range(0.8f, 1.6f) }
        inp.tuck = botTuckPref[k] > 0f && abs(want) < 0.45f
    }

    private fun total(k: Int, time: Double) = time + missed[k] * MISS_PENALTY - presents[k] * PRESENT_BONUS
    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f
    private fun laneCx(k: Int) = if (k == 0) screenW * 0.25f else screenW * 0.75f
    private val sleighY = 420f

    fun place(k: Int): Int {
        val a = finish[k]; val b = finish[1 - k]
        return when {
            a >= 0 && b >= 0 -> if (a <= b) 1 else 2
            a >= 0 -> 1
            b >= 0 -> 2
            else -> if (y[k] >= y[1 - k]) 1 else 2
        }
    }

    override val isOver: Boolean get() = (finish[0] >= 0 && finish[1] >= 0) || (firstFinish >= 0 && playTime - firstFinish > AFTER_FINISH_SEC) || playTime > 150.0

    override fun result(): MatchResult {
        val p0 = place(0); val p1 = place(1)
        val tie = finish[0] >= 0 && finish[0] == finish[1]
        fun t(k: Int) = if (finish[k] >= 0) "%.1f s".format(finish[k]) else "did not finish"
        return MatchResult(info.id, intArrayOf(if (p0 == 1 && !tie) 1 else 0, if (p1 == 1 && !tie) 1 else 0), if (tie) -1 else if (p0 < p1) 0 else 1, info.scoreUnit,
            listOf("Final times: ${t(0)} vs ${t(1)}", "Gates: ${gates[0]} vs ${gates[1]} (missed ${missed[0]} vs ${missed[1]})", "Presents: ${presents[0]} vs ${presents[1]}"))
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) renderLane(g, k, time)
        Hud.divider(g)
        Hud.topBar(g, env, null, "Sleigh Slalom", if (steeringOnly) "Steering-only mode" else "Lean to steer - tuck for speed", inputs)
    }

    private fun renderLane(g: Gfx, k: Int, time: Double) {
        val r = Rect(if (k == 0) 0f else screenW / 2f, 120f, if (k == 0) screenW / 2f else screenW, 1080f)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        g.fillRect(r.left, r.top, r.right, r.bottom, 0xFFEAF2FC.toInt())
        val half = r.width * 0.42f
        val pxPerM = 26f
        fun sx(xx: Float) = r.cx + xx * half
        fun sy(yy: Float) = sleighY + (yy - y[k]) * pxPerM
        // slope texture: soft ridges scrolling up
        var ry = (kotlin.math.floor((y[k] - 20f) / 8f) * 8f)
        while (sy(ry) < r.bottom + 40f) {
            val yy = sy(ry)
            if (yy > r.top - 20f) g.line(r.left, yy, r.right, yy + 30f, 3f, 0x11003366)
            ry += 8f
        }
        g.fillRect(r.left, r.top, sx(-1.08f), r.bottom, 0xFFD5E3F5.toInt())
        g.fillRect(sx(1.08f), r.top, r.right, r.bottom, 0xFFD5E3F5.toInt())
        // finish line
        val fy = sy(LENGTH)
        if (fy in r.top..r.bottom) {
            for (i in 0 until 12) g.fillRect(sx(-1f + i / 6f), fy - 10f, sx(-1f + (i + 1) / 6f), fy + 10f, if (i % 2 == 0) Colors.BLACK else Colors.WHITE)
            g.text("FINISH", r.cx, fy - 40f, 50f, Colors.CANDY)
        }
        for ((i, t) in things.withIndex()) {
            val yy = sy(t.y)
            if (yy < r.top - 80f || yy > r.bottom + 80f) continue
            val xx = sx(t.x)
            when (t.kind) {
                Kind.GATE -> {
                    val passed = i in done[k]
                    val c1 = if (passed) 0x88E57373.toInt() else 0xFFE53935.toInt()
                    val c2 = if (passed) 0x8864B5F6.toInt() else 0xFF1E88E5.toInt()
                    for ((side, c) in listOf(-1 to c1, 1 to c2)) {
                        val px = sx(t.x + side * t.halfWidth)
                        g.line(px, yy, px, yy - 100f, 9f, 0xFF455A64.toInt())
                        Draw.tri(g, px, yy - 100f, px, yy - 55f, px - side * 62f, yy - 78f, c)
                    }
                    g.line(sx(t.x - t.halfWidth), yy, sx(t.x + t.halfWidth), yy, 3f, 0x33000000)
                }
                Kind.PRESENT -> if (i !in done[k]) Draw.present(g, xx, yy - 20f, 46f, Colors.CANDY, Colors.GOLD, sin(time * 3 + i).toFloat() * 0.2f)
                Kind.TREE -> { g.fillCircle(xx + 8f, yy + 8f, 46f, 0x33000000); for (a in 0 until 6) { val ang = a * 1.047f; g.fillCircle(xx + kotlin.math.cos(ang) * 24f, yy + kotlin.math.sin(ang) * 24f, 26f, Colors.PINE_DARK) }; g.fillCircle(xx, yy, 30f, Colors.PINE); g.fillCircle(xx - 8f, yy - 8f, 12f, Colors.withAlpha(Colors.SNOW, 0.8f)) }
                Kind.ROCK -> { g.fillOval(xx - 34f, yy - 22f, xx + 34f, yy + 22f, 0xFF78909C.toInt()); g.fillOval(xx - 22f, yy - 22f, xx + 14f, yy - 2f, 0xFFCFD8DC.toInt()) }
            }
        }
        // sleigh with trail
        val shx = sx(x[k])
        for (i in 1..10) g.fillCircle(shx, sleighY - i * 18f, 7f, 0x22003366)
        if (tucking[k] || (steeringOnly && speed[k] > 10f)) for (i in 0..2) g.line(shx - 30f + i * 30f, sleighY - 60f, shx - 30f + i * 30f, sleighY - 120f - speed[k] * 3f, 3f, 0x5590CAF9)
        Draw.sleighTop(g, shx, sleighY, 110f, env.color(k), -steer[k] * 0.35f)
        if (crash[k] > 0f) Draw.star(g, shx + 30f, sleighY - 60f, 20f, Colors.GOLD, time.toFloat() * 5f)
        // progress bar
        val f = (y[k] / LENGTH).coerceIn(0f, 1f)
        g.fillRoundRect(r.right - 46f, 200f, r.right - 26f, 1000f, 10f, 0x44000000)
        g.fillRoundRect(r.right - 46f, 200f, r.right - 26f, 200f + 800f * f, 10f, env.color(k))
        g.text(if (finish[k] >= 0) "%.1f s".format(finish[k]) else "%.1f s".format(total(k, playTime)), r.cx, 230f, 44f, Colors.BLACK, bold = true)
        if (finish[k] >= 0) g.text(if (place(k) == 1) "1st!" else "2nd", r.cx, 640f, 100f, env.color(k), outline = Colors.BLACK, outlineWidth = 8f)
        Hud.laneLabel(g, Rect(r.left, 130f, r.right, 260f), env, k)
        g.restore()
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val lean = sin(t * 1.4).toFloat()
        val p = com.clrg.core.gesture.AvatarPose().apply { valid = true; tilt = lean * 0.35f; upper[0] = -2.2f; fore[0] = -1.9f; upper[1] = -0.95f; fore[1] = -1.25f }
        Draw.buddy(g, r.cx - r.width * 0.2f, r.bottom - 10f, r.height * 0.8f, env.color(1), p, com.clrg.core.render.Expression.EXCITED, t = t)
        Draw.sleighTop(g, r.cx + r.width * 0.22f + lean * r.width * 0.12f, r.cy, r.height * 0.4f, env.color(1), 0f)
    }
}
