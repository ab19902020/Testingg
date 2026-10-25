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
import com.clrg.core.render.Cam3
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
            // cosmetic ski tracks: a sample every 0.8 m
            if (y[k] - trailLastY[k] >= 0.8f) {
                trailLastY[k] = y[k]
                val n = trailN[k]
                if (n < TRAIL) { trailX[k][n] = x[k]; trailY[k][n] = y[k]; trailN[k] = n + 1 }
                else { System.arraycopy(trailX[k], 1, trailX[k], 0, TRAIL - 1); System.arraycopy(trailY[k], 1, trailY[k], 0, TRAIL - 1); trailX[k][TRAIL - 1] = x[k]; trailY[k][TRAIL - 1] = y[k] }
            }
            for ((i, t) in things.withIndex()) {
                if (t.y < before || t.y > y[k]) continue
                if (i in done[k]) continue
                done[k].add(i)
                val dx = abs(x[k] - t.x)
                when (t.kind) {
                    Kind.GATE -> if (dx <= t.halfWidth) {
                        gates[k]++; env.sfx(Sfx.GATE, 0.7f, pan(k))
                    } else {
                        missed[k]++; env.sfx(Sfx.MISS, 0.5f, pan(k)); env.sfx(Sfx.SNOW_CRUNCH, 0.6f, pan(k))
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
                env.sfx(Sfx.JINGLE, 0.7f, pan(k))
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

    private val TRAIL = 40
    private val trailX = Array(2) { FloatArray(TRAIL) }
    private val trailY = Array(2) { FloatArray(TRAIL) }
    private val trailN = IntArray(2)
    private val trailLastY = FloatArray(2)
    private val lanePts = FloatArray(TRAIL * 2)

    // ------------------------------------------------------------------ 3D view: behind each sleigh, down the hill

    /** Half the piste's width in metres (course x of 1 is the piste edge). */
    private val halfW = 7f
    private val cams = arrayOf(Cam3(), Cam3())
    private val quad = FloatArray(8)

    /** Height of the snow at distance [yy] down the course: a rolling slope. */
    private fun zAt(yy: Float): Float = 0.9f * sin(yy * 0.11f) + 0.55f * sin(yy * 0.047f + 1f)

    private fun hash01(i: Int): Float { var h = i * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xFFFF) / 65535f }

    private class Obj(var d: Float, var kind: Int, var x: Float, var y: Float, var s: Float, var i: Int)
    private val objs = ArrayList<Obj>()
    private val pool = ArrayList<Obj>()
    private fun obj(d: Float, kind: Int, x: Float, y: Float, s: Float, i: Int) {
        val o = if (pool.isNotEmpty()) pool.removeAt(pool.size - 1) else Obj(0f, 0, 0f, 0f, 0f, 0)
        o.d = d; o.kind = kind; o.x = x; o.y = y; o.s = s; o.i = i
        objs.add(o)
    }

    private fun fogMix(c: Int, d: Float): Int = Colors.lerp(c, 0xFFDCE7F6.toInt(), ((d - 18f) / 70f).coerceIn(0f, 0.85f))

    private fun renderLane(g: Gfx, k: Int, time: Double) {
        val r = Rect(if (k == 0) 0f else screenW / 2f, 120f, if (k == 0) screenW / 2f else screenW, 1080f)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        val cam = cams[k]
        val ys = y[k]
        val xs = x[k] * halfW
        val bounce = if (crash[k] > 0f) 0f else sin(time.toFloat() * 13f) * 0.03f * speed[k] / 15f
        cam.viewport(r, 58f)
        cam.cy = r.top + r.height * 0.42f
        cam.lookAt(xs * 0.8f, ys - 7.5f, zAt(ys) + 3.0f + bounce, xs * 0.45f, ys + 14f, zAt(ys + 14f) + 0.2f)
        // sky, low sun glow and mountains
        val hz = cam.horizonY()
        g.gradientRect(r.left, r.top, r.right, hz + 10f, 0xFF7FB2E5.toInt(), 0xFFE9F0FA.toInt())
        g.glow(r.left + r.width * 0.72f, hz - 70f, 260f, 0x55FFE3B3)
        val mt = FloatArray(2 * 12)
        for (layer in 0..1) {
            val base = hz + 6f
            val amp = if (layer == 0) 150f else 90f
            for (i in 0 until 10) {
                val px = r.left - 40f + (r.width + 80f) * i / 9f
                mt[i * 2] = px
                mt[i * 2 + 1] = base - amp * (0.45f + 0.55f * hash01(i * 7 + layer * 31 + k * 0)) - (if (i % 2 == 0) 0f else amp * 0.25f)
            }
            mt[20] = r.right + 40f; mt[21] = base; mt[22] = r.left - 40f; mt[23] = base
            g.fillPolygonGradient(mt, 12, base - amp, base, if (layer == 0) 0xFFB7C9E2.toInt() else 0xFF9DB4D3.toInt(), 0xFFDCE7F6.toInt())
            if (layer == 0) for (i in 0 until 10 step 2) {
                // snow caps
                val px = mt[i * 2]; val py = mt[i * 2 + 1]
                Draw.tri(g, px - 46f, py + 40f, px + 46f, py + 40f, px, py, 0xEEFFFFFF.toInt())
            }
        }
        // the slope, far to near, in strips
        val far = 85f
        var yy = ys + far
        var prevL = 0f; var prevR = 0f; var prevY = 0f; var prevOk = false
        var pbl = 0f; var pbr = 0f
        while (yy > ys - 7f) {
            val step = if (yy - ys > 40f) 4f else if (yy - ys > 15f) 2f else 1f
            val z = zAt(yy)
            val okL = cam.project(-halfW, yy, z); val lx = cam.px; val ly = cam.py
            val okR = cam.project(halfW, yy, z); val rx = cam.px
            val okBL = cam.project(-halfW - 14f, yy, z + 1.3f); val blx = cam.px; val bly = cam.py
            val okBR = cam.project(halfW + 14f, yy, z + 1.3f); val brx = cam.px; val bry = cam.py
            if (okL && okR && okBL && okBR && prevOk) {
                val d = cam.pd
                val slope = (zAt(yy + 1f) - zAt(yy - 1f)) / 2f
                val light = (1f - slope * 1.6f).coerceIn(0.78f, 1.08f)
                val stripe = ((yy / 1.6f).toInt() and 1) == 0
                val snow = if (stripe) 0xFFF4F8FF.toInt() else 0xFFE6EEF9.toInt()
                val c = fogMix(Colors.lerp(Colors.darker(snow, 0.86f), snow, light - 0.08f), d)
                quad[0] = lx; quad[1] = ly; quad[2] = rx; quad[3] = ly; quad[4] = prevR; quad[5] = prevY; quad[6] = prevL; quad[7] = prevY
                g.fillPolygon(quad, 4, c)
                val bank = fogMix(0xFFD3E0F2.toInt(), d)
                quad[0] = blx; quad[1] = bly; quad[2] = lx; quad[3] = ly; quad[4] = prevL; quad[5] = prevY; quad[6] = pbl; quad[7] = prevY - (ly - bly)
                g.fillPolygon(quad, 4, bank)
                quad[0] = rx; quad[1] = ly; quad[2] = brx; quad[3] = bry; quad[4] = pbr; quad[5] = prevY - (ly - bry); quad[6] = prevR; quad[7] = prevY
                g.fillPolygon(quad, 4, bank)
                // fence posts marking the piste edge
                if (((yy / 4f).toInt()) % 2 == 0 && d < 45f) {
                    g.line(lx, ly, lx, ly - 900f / d, maxOf(1.5f, 60f / d), 0xFFE53935.toInt())
                    g.line(rx, ly, rx, ly - 900f / d, maxOf(1.5f, 60f / d), 0xFFE53935.toInt())
                }
            }
            prevL = lx; prevR = rx; prevY = ly; prevOk = okL && okR && okBL && okBR; pbl = blx; pbr = brx
            yy -= step
        }
        // ski tracks
        val n = trailN[k]
        if (n >= 2) for (off in floatArrayOf(-0.35f, 0.35f)) {
            var m = 0
            for (i in 0 until n) {
                if (!cam.project(trailX[k][i] * halfW + off, trailY[k][i], zAt(trailY[k][i]) + 0.02f)) continue
                lanePts[m * 2] = cam.px; lanePts[m * 2 + 1] = cam.py; m++
            }
            if (m >= 2) g.polyline(lanePts, m, 4f, 0x335C7FB0)
        }
        // everything standing on the snow, sorted far to near
        pool.addAll(objs); objs.clear()
        for ((i, t) in things.withIndex()) {
            if (t.y < ys - 6f || t.y > ys + far) continue
            if (t.kind == Kind.PRESENT && i in done[k]) continue
            obj(t.y - ys, t.kind.ordinal, t.x * halfW, t.y, 1f, i)
        }
        // the forest either side
        var row = kotlin.math.floor((ys - 6f) / 3.2f).toInt()
        while (row * 3.2f < ys + far) {
            for (side in 0..1) {
                val h = hash01(row * 2 + side)
                val sgn = if (side == 0) -1f else 1f
                obj(row * 3.2f - ys, 10, sgn * (halfW + 2f + h * 9f), row * 3.2f + hash01(row * 5 + side) * 2f, 3.5f + 3f * hash01(row * 11 + side), row)
                if (h > 0.4f) obj(row * 3.2f - ys + 1.5f, 10, sgn * (halfW + 6f + h * 12f), row * 3.2f + 1.5f, 4f + 3f * h, row + 7)
            }
            row++
        }
        // the finish arch
        if (LENGTH - ys in -6f..far) obj(LENGTH - ys, 20, 0f, LENGTH, 1f, 0)
        objs.sortByDescending { it.d }
        for (o in objs) {
            // things right beside the camera would fill the view: fade them out as they pass
            val camD = o.d + 7.5f
            if (camD < 4.5f) continue
            val a = ((camD - 4.5f) / 3.5f).coerceIn(0f, 1f)
            if (a < 1f) { g.save(); g.setAlpha(g.getAlpha() * a) }
            drawObj(g, cam, k, o, time)
            if (a < 1f) g.restore()
        }
        // the sleigh, from behind
        if (cam.project(xs, ys, zAt(ys))) {
            val sx = cam.px; val sy = cam.py
            val st = steer[k]
            val size = cam.focal * 1.55f / cam.pd
            if (kotlin.math.abs(st) > 0.35f && crash[k] <= 0f) for (i in 0 until 6) {
                val ph = ((time * 3.2 + i / 6.0) % 1.0).toFloat()
                g.glow(sx + (if (st > 0) -1f else 1f) * (size * 0.35f + size * 0.5f * ph), sy - size * 0.1f - size * 0.4f * ph, size * (0.12f + 0.18f * ph), Colors.withAlpha(Colors.WHITE, (kotlin.math.abs(st) - 0.3f) * (1f - ph)))
            }
            Draw.sleighBack(g, sx, sy, size, env.color(k), -st * 0.22f, time + k, tucking[k])
            if (crash[k] > 0f) for (j in 0..2) Draw.star(g, sx + kotlin.math.cos(time.toFloat() * 6f + j * 2.1f) * size * 0.35f, sy - size * 0.9f, size * 0.07f, Colors.GOLD, time.toFloat() * 5f)
        }
        // falling snow
        if (!env.settings.reducedEffects) for (i in 0 until 46) {
            val fx = r.left + ((hash01(i * 13 + k) * r.width + time.toFloat() * (12f + 20f * hash01(i)) + sin(time.toFloat() * 0.8f + i) * 18f) % r.width + r.width) % r.width
            val fy = r.top + ((hash01(i * 29) * r.height + time.toFloat() * (70f + 90f * hash01(i * 3)) + speed[k] * 6f * time.toFloat() * 0f) % r.height)
            g.fillCircle(fx, fy, 2f + 3.5f * hash01(i * 7), 0xCCFFFFFF.toInt())
        }
        // speed lines when tucked
        if (tucking[k] || (steeringOnly && speed[k] > 10f)) for (i in 0 until 8) {
            val ph = ((time * 2.5 + i * 0.137) % 1.0).toFloat()
            val ang = i * 0.785f + 0.3f
            val cx = r.cx; val cy = cam.cy + 80f
            val r0 = 260f + 420f * ph
            g.line(cx + kotlin.math.cos(ang) * r0, cy + kotlin.math.sin(ang) * r0 * 0.7f, cx + kotlin.math.cos(ang) * (r0 + 90f), cy + kotlin.math.sin(ang) * (r0 + 90f) * 0.7f, 3f, Colors.withAlpha(Colors.WHITE, 0.5f * (1f - ph)))
        }
        // lane HUD: time, progress and place
        val f = (y[k] / LENGTH).coerceIn(0f, 1f)
        g.fillRoundRect(r.right - 46f, 200f, r.right - 26f, 1000f, 10f, 0x55000000)
        g.fillRoundRect(r.right - 46f, 1000f - 800f * f, r.right - 26f, 1000f, 10f, env.color(k))
        g.fillCircle(r.right - 36f, 1000f - 800f * f, 14f, Colors.WHITE)
        val timeText = if (finish[k] >= 0) "%.1f s".format(finish[k]) else "%.1f s".format(total(k, playTime))
        val tw = g.measureText(timeText, 46f) + 50f
        g.fillRoundRect(r.cx - tw / 2f, 200f, r.cx + tw / 2f, 262f, 31f, 0xAA0B1A36.toInt())
        g.text(timeText, r.cx, 231f, 46f, Colors.WHITE)
        if (finish[k] >= 0) g.text(if (place(k) == 1) "1st!" else "2nd", r.cx, 560f, 110f, env.color(k), outline = Colors.BLACK, outlineWidth = 8f)
        Hud.laneLabel(g, Rect(r.left, 130f, r.right, 260f), env, k)
        g.restore()
    }

    private fun drawObj(g: Gfx, cam: Cam3, k: Int, o: Obj, time: Double) {
        val z = zAt(o.y)
        if (!cam.project(o.x, o.y, z)) return
        val bx = cam.px; val by = cam.py; val d = cam.pd
        val pxm = cam.focal / d
        when (o.kind) {
            10, Kind.TREE.ordinal -> {
                val h = (if (o.kind == 10) o.s else 4.2f) * pxm
                Draw.softShadow(g, bx + h * 0.12f, by, h * 0.32f, h * 0.06f, 0.3f)
                Draw.pineTree(g, bx, by, h, fogMix(if (o.kind == 10) 0xFF2E7D4F.toInt() else 0xFF2F8A55.toInt(), d))
            }
            Kind.ROCK.ordinal -> {
                val w = 1.4f * pxm
                Draw.softShadow(g, bx + w * 0.1f, by, w * 0.7f, w * 0.15f, 0.35f)
                g.fillCircleRadial(bx, by - w * 0.25f, w * 0.55f, bx - w * 0.2f, by - w * 0.5f, w * 0.9f, fogMix(0xFFB0BEC5.toInt(), d), fogMix(0xFF455A64.toInt(), d))
                g.fillOval(bx - w * 0.42f, by - w * 0.78f, bx + w * 0.3f, by - w * 0.46f, fogMix(0xEEF4F8FF.toInt(), d))
            }
            Kind.PRESENT.ordinal -> {
                val sz = 0.9f * pxm
                val hover = sin(time * 3 + o.i).toFloat() * sz * 0.08f
                g.glow(bx, by - sz * 0.6f, sz * 1.2f, 0x55FFD54F)
                Draw.softShadow(g, bx, by, sz * 0.5f, sz * 0.12f, 0.35f)
                Draw.present(g, bx, by - sz * 0.55f + hover, sz, Colors.CANDY, Colors.GOLD, sin(time * 2 + o.i).toFloat() * 0.15f)
            }
            Kind.GATE.ordinal -> {
                val t = things[o.i]
                val passed = o.i in done[k]
                for (side in intArrayOf(-1, 1)) {
                    val px = o.x + side * t.halfWidth * halfW
                    if (!cam.project(px, o.y, z)) continue
                    val gx = cam.px; val gy = cam.py; val pd = cam.pd
                    val m = cam.focal / pd
                    val c = if (side < 0) 0xFFE53935.toInt() else 0xFF1E88E5.toInt()
                    val col = if (passed) Colors.withAlpha(c, 0.5f) else fogMix(c, pd)
                    Draw.softShadow(g, gx + 0.3f * m, gy, 0.5f * m, 0.12f * m, 0.35f)
                    g.line(gx, gy, gx, gy - 1.9f * m, maxOf(2f, 0.09f * m), fogMix(0xFF37474F.toInt(), pd))
                    val flap = sin(time * 6 + o.i + side).toFloat() * 0.12f * m
                    val fx = gx - side * 0.75f * m
                    quad[0] = gx; quad[1] = gy - 1.9f * m; quad[2] = fx; quad[3] = gy - 1.85f * m + flap
                    quad[4] = fx; quad[5] = gy - 1.25f * m + flap; quad[6] = gx; quad[7] = gy - 1.2f * m
                    g.fillPolygonGradient(quad, 4, gy - 1.9f * m, gy - 1.2f * m, Colors.lighter(col, 0.3f), Colors.darker(col, 0.75f))
                    if (passed) Draw.star(g, gx, gy - 2.15f * m, 0.12f * m, Colors.withAlpha(Colors.GOLD, 0.7f), 0f)
                }
            }
            20 -> {
                // finish arch across the piste
                val zz = z
                val postH = 4.2f
                val okL = cam.project(-halfW - 0.6f, o.y, zz); val lx = cam.px; val ly = cam.py; val lm = cam.focal / cam.pd
                val okR = cam.project(halfW + 0.6f, o.y, zz); val rx = cam.px
                if (!okL || !okR) return
                g.line(lx, ly, lx, ly - postH * lm, maxOf(3f, 0.25f * lm), 0xFF5D4037.toInt())
                g.line(rx, ly, rx, ly - postH * lm, maxOf(3f, 0.25f * lm), 0xFF5D4037.toInt())
                val top = ly - postH * lm; val bot = ly - (postH - 1.1f) * lm
                g.fillRect(lx, top, rx, bot, Colors.CANDY)
                val cells = 16
                for (i in 0 until cells) if (i % 2 == 0) g.fillRect(lx + (rx - lx) * i / cells, bot, lx + (rx - lx) * (i + 1) / cells, bot + 0.25f * lm, Colors.WHITE)
                g.text("FINISH", (lx + rx) / 2f, (top + bot) / 2f, minOf(120f, 0.8f * lm), Colors.WHITE, outline = 0xFF7F0000.toInt(), outlineWidth = maxOf(2f, 0.06f * lm))
                // chequered line on the snow
                for (i in 0 until 12) {
                    val x0 = -halfW + 2f * halfW * i / 12f; val x1 = -halfW + 2f * halfW * (i + 1) / 12f
                    if (!cam.project(x0, o.y, zz)) continue
                    val ax = cam.px; val ay = cam.py
                    if (!cam.project(x1, o.y + 0.6f, zz)) continue
                    g.fillRect(ax, minOf(ay, cam.py), cam.px, maxOf(ay, cam.py) + 2f, if (i % 2 == 0) 0xFF263238.toInt() else Colors.WHITE)
                }
            }
        }
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val lean = sin(t * 1.4).toFloat()
        val p = com.clrg.core.gesture.AvatarPose().apply { valid = true; tilt = lean * 0.35f; upper[0] = -2.2f; fore[0] = -1.9f; upper[1] = -0.95f; fore[1] = -1.25f }
        Draw.buddy(g, r.cx - r.width * 0.2f, r.bottom - 10f, r.height * 0.8f, env.color(1), p, com.clrg.core.render.Expression.EXCITED, t = t)
        Draw.sleighTop(g, r.cx + r.width * 0.22f + lean * r.width * 0.12f, r.cy, r.height * 0.4f, env.color(1), 0f)
    }
}
