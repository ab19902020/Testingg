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
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 3. Present Catch - Santa's sleigh flies over each player's lane dropping presents. Catch them with your hands as
 * they fall (anywhere on the way down). Gold presents and candy canes are worth more, a star gives magnet mittens
 * (a bigger catch zone for a few seconds), and coal must be dodged. Catches in a row raise a multiplier; the last
 * seconds are a double-points Present Rush. Both lanes get exactly the same drops.
 *
 * Catches are judged against where a present was *as the player saw it*: a camera player's hands reach the game a
 * little late and the TV shows the picture a little late, so the falling present is looked up that far back.
 */
class PresentCatchGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    enum class Kind(val points: Int) { PRESENT(10), GOLD(25), CANDY(15), STAR(5), COAL(-15) }

    /** One drop from the sleigh: where it starts (reach units across the lane), how it falls and sways. */
    class Drop(val spawn: Double, val x0: Float, val kind: Kind, val color: Int, val speed: Float, val sway: Float, val phase: Float, val drift: Float)

    class Live(val d: Drop) {
        var state = FALLING
        var t = 0f
        var hitX = 0f
        var hitY = 0f
        var hand = -1
        var points = 0
        companion object { const val FALLING = 0; const val CAUGHT = 1; const val MISSED = 2; const val COAL_HIT = 3 }
    }

    companion object {
        const val MATCH_SEC = 60.0
        const val RUSH_SEC = 12.0
        /** Drops appear this high above the shoulders (reach units) and are gone below [FLOOR]. */
        const val TOP = 1.45f
        const val FLOOR = -1.12f
        const val CATCH_R = 0.27f
        const val MAGNET_SEC = 6f
        val BOX_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt(), 0xFF00ACC1.toInt())

        val INFO = GameInfo(
            id = "catch", title = "Present Catch", tagline = "Santa's dropping presents - catch them!",
            howTo = listOf(
                "Santa's sleigh flies over and drops presents. Catch them with your hands as they fall.",
                "Gold presents and candy canes score more. Catch a STAR for magnet mittens!",
                "COAL glows red - keep your hands away from it.",
                "Catch in a row to raise your multiplier. The last 12 seconds are a double-points PRESENT RUSH!",
            ),
            controls = listOf("Move your hands under the presents" to "Catch", "Keep away from coal" to "Avoid penalties", "Catch a star" to "Magnet mittens"),
            gentleNotes = "Presents fall within each player's own calibrated reach; seated and reduced-movement players get a narrower lane.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "Highest score in 60 seconds",
        )
    }

    val scores = IntArray(2)
    val caught = IntArray(2)
    val coalHits = IntArray(2)
    val missed = IntArray(2)
    val streak = IntArray(2)
    val bestStreak = IntArray(2)
    /** Seconds of magnet mittens left. */
    val magnet = FloatArray(2)
    private val schedule = ArrayList<Drop>()
    private val live = Array(2) { ArrayList<Live>() }
    private val nextIndex = IntArray(2)
    private var screenW = 1920f
    private val catchAnim = FloatArray(2) { 9f }

    val rush: Boolean get() = playTime >= MATCH_SEC - RUSH_SEC
    fun multiplier(k: Int): Int = 1 + min(3, streak[k] / 5)

    /** Seated and reduced-movement players get a narrower lane (the same drops, closer in). */
    private fun spread(k: Int) = if (env.players[k].mode == MovementMode.STANDARD) 1f else 0.8f

    /** Position (reach units) of drop [d], [age] seconds after it left the sleigh. */
    fun position(d: Drop, age: Float, k: Int): Pair<Float, Float> {
        val x = (d.x0 + d.drift * age + d.sway * sin(d.phase + age * 2.2f)) * spread(k)
        return x to TOP - d.speed * age
    }

    override fun start() {
        val rng = env.rng(3)
        var t = 1.2
        var lastX = 0f
        val stars = doubleArrayOf(20.5, 39.5)
        while (t < MATCH_SEC - 1.6) {
            val progress = (t / MATCH_SEC).toFloat()
            val inRush = t >= MATCH_SEC - RUSH_SEC
            var x0 = rng.range(-0.95f, 0.95f)
            if (abs(x0 - lastX) < 0.45f) x0 = if (lastX > 0f) rng.range(-0.95f, -0.15f) else rng.range(0.15f, 0.95f)
            lastX = x0
            val r = rng.nextFloat()
            var kind = when {
                t > 6.0 && r < (if (inRush) 0.12f else 0.17f) -> Kind.COAL
                r > 0.92f -> Kind.GOLD
                r > 0.84f -> Kind.CANDY
                else -> Kind.PRESENT
            }
            if (stars.any { kotlin.math.abs(it - t) < 0.6 }) kind = Kind.STAR
            val speed = lerp(0.62f, 0.95f, progress) * (if (kind == Kind.CANDY) 1.4f else 1f) * rng.range(0.92f, 1.08f)
            val fall = (TOP - FLOOR) / speed
            var drift = rng.range(-0.1f, 0.1f)
            if (abs(x0 + drift * fall) > 1.0f) drift = -drift
            schedule.add(Drop(t, x0, kind, BOX_COLORS[rng.nextInt(BOX_COLORS.size)], speed, rng.range(0.02f, 0.11f), rng.range(0f, 6.28f), drift))
            val gap = if (inRush) 0.42f else lerp(1.15f, 0.62f, progress / 0.8f)
            t += gap * rng.range(0.85f, 1.15f)
        }
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            catchAnim[k] += dt
            magnet[k] = (magnet[k] - dt).coerceAtLeast(0f)
            while (nextIndex[k] < schedule.size && schedule[nextIndex[k]].spawn <= playTime) live[k].add(Live(schedule[nextIndex[k]++]))
            val inp = inputs[k]
            val lag = viewLag(k).toFloat()
            val r = CATCH_R * (if (magnet[k] > 0f) 1.8f else 1f)
            val it = live[k].iterator()
            while (it.hasNext()) {
                val li = it.next()
                val d = li.d
                val age = (playTime - d.spawn).toFloat()
                if (li.state != Live.FALLING) {
                    li.t += dt
                    if (li.t > 1.2f) it.remove()
                    continue
                }
                // on the ground: a present that got away
                if (position(d, age, k).second < FLOOR) {
                    li.state = Live.MISSED; li.t = 0f
                    if (d.kind != Kind.COAL) { streak[k] = 0; missed[k]++; env.sfx(Sfx.SNOW_CRUNCH, 0.7f, pan(k)); env.sfx(Sfx.MISS, 0.25f, pan(k)) }
                    continue
                }
                // where it was as the player saw it when their hands were where they are now
                val (sx, sy) = position(d, (age - lag).coerceAtLeast(0f), k)
                val hand = (0..1).firstOrNull { h -> inp.handVisible[h] && hypot(inp.handX[h] - sx, inp.handY[h] - sy) < r } ?: continue
                li.hand = hand; li.hitX = inp.handX[hand]; li.hitY = inp.handY[hand]; li.t = 0f
                val (px, py) = toScreen(k, li.hitX, li.hitY)
                if (d.kind == Kind.COAL) {
                    li.state = Live.COAL_HIT
                    scores[k] = (scores[k] + Kind.COAL.points).coerceAtLeast(0)
                    coalHits[k]++
                    streak[k] = 0
                    env.popups.add("COAL -15", px, py - 70f, Colors.BAD, 54f)
                    env.particles.burst(px, py, 0xFF424242.toInt(), 18, kind = Particles.Kind.PUFF, size = 13f)
                    env.shake.kick(7f)
                    env.sfx(Sfx.BAD, 0.9f, pan(k))
                    continue
                }
                li.state = Live.CAUGHT
                streak[k]++
                bestStreak[k] = maxOf(bestStreak[k], streak[k])
                val pts = d.kind.points * multiplier(k) * (if (rush) 2 else 1)
                li.points = pts
                scores[k] += pts
                caught[k]++
                catchAnim[k] = 0f
                val col = when (d.kind) { Kind.GOLD, Kind.STAR -> Colors.GOLD; Kind.CANDY -> Colors.CANDY; else -> d.color }
                if (d.kind == Kind.STAR) {
                    magnet[k] = MAGNET_SEC
                    env.popups.add("MAGNET MITTENS!", px, py - 110f, Colors.GOLD, 56f)
                    env.sfx(Sfx.SPARKLE, 1f, pan(k))
                }
                val mult = multiplier(k) * (if (rush) 2 else 1)
                env.popups.add("+$pts" + if (mult > 1) " x$mult" else "", px, py - 60f, if (d.kind == Kind.GOLD) Colors.GOLD else env.color(k), if (mult > 1) 58f else 50f)
                env.particles.burst(px, py, col, if (d.kind == Kind.GOLD) 30 else 20, kind = Particles.Kind.STAR, size = 12f)
                env.particles.ring(px, py, col, 110f)
                env.sfx(Sfx.CATCH, 0.9f, pan(k), 1f + 0.04f * min(10, streak[k]))
                if (streak[k] > 0 && streak[k] % 5 == 0 && streak[k] <= 15) env.sfx(Sfx.JINGLE, 0.7f, pan(k))
                if (streak[k] > 0 && streak[k] % 5 == 0 && streak[k] <= 15) env.popups.add("COMBO x${multiplier(k)}!", laneRect(k).cx, 330f, Colors.GOLD, 64f, 1.3f)
            }
        }
    }

    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f

    // ---- CPU player: notices each new drop after a human reaction delay (sometimes not at all), works out where it
    // will be when it gets down to a comfortable height, and moves the hand on that side there at a human speed; it
    // keeps its hands clear of coal it has noticed (now and then it grabs one by mistake).
    private class BotView(val go: Boolean, val noticeAt: Double)
    private val botViews = Array(2) { HashMap<Live, BotView>() }

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        val views = botViews[k]
        views.keys.removeAll { it.state != Live.FALLING }
        val speed = com.clrg.core.game.CpuTraits.handSpeed(skill)
        val falling = live[k].filter { it.state == Live.FALLING }
        for (li in falling) views.getOrPut(li) {
            val coal = li.d.kind == Kind.COAL
            val go = if (coal) rng.chance(lerp(0.1f, 0.0f, skill)) else rng.chance(lerp(0.7f, 0.97f, skill))
            BotView(go, li.d.spawn + com.clrg.core.game.CpuTraits.reaction(skill) * rng.range(0.85f, 1.3f))
        }
        val wanted = falling.filter { val v = views[it]!!; v.go && playTime >= v.noticeAt }
        val coal = falling.filter { it.d.kind == Kind.COAL && views[it]?.let { v -> !v.go && playTime >= v.noticeAt } == true }
        for (h in 0..1) {
            // each hand covers its own side of the lane; the soonest drop there
            val mine = wanted.filter { val x = position(it.d, (playTime - it.d.spawn).toFloat(), k).first; if (h == 0) x <= 0.05f else x > -0.05f }
                .minByOrNull { (position(it.d, 0f, k).second - 0.1f) / it.d.speed + (it.d.spawn - playTime).toFloat() }
            val tx: Float; val ty: Float
            if (mine != null) {
                val age = (playTime - mine.d.spawn).toFloat()
                // meet it at chest height (or where it is now if it is already lower)
                val meetAge = maxOf(age, (TOP - 0.1f) / mine.d.speed)
                val p = position(mine.d, meetAge, k)
                tx = p.first; ty = p.second
            } else { tx = if (h == 0) -0.3f else 0.3f; ty = -0.2f }
            val ox = inp.handX[h]; val oy = inp.handY[h]
            com.clrg.core.game.CpuTraits.moveHand(inp, h, tx, ty, speed, dt)
            for (c in coal) {
                val (cx, cy) = position(c.d, (playTime - c.d.spawn).toFloat(), k)
                if (hypot(inp.handX[h] - cx, inp.handY[h] - cy) < CATCH_R + 0.12f) {
                    val dd = hypot(ox - cx, oy - cy).coerceAtLeast(1e-3f)
                    inp.handX[h] = ox + (ox - cx) / dd * speed * dt
                    inp.handY[h] = oy + (oy - cy) / dd * speed * dt
                }
            }
        }
    }

    // ---- drawing

    private fun laneRect(k: Int) = Rect(if (k == 0) 0f else screenW / 2f, 120f, if (k == 0) screenW / 2f else screenW, 1080f)
    private fun reachPx(k: Int) = minOf(laneRect(k).width * 0.36f, 330f)
    private fun shoulder(k: Int): Pair<Float, Float> = laneRect(k).cx to 705f

    fun toScreen(k: Int, rx: Float, ry: Float): Pair<Float, Float> {
        val (cx, cy) = shoulder(k)
        val r = reachPx(k)
        return cx + rx * r to cy - ry * r
    }

    /** Santa's sleigh glides from one drop to the next, so every present visibly comes from the sack. */
    private fun sleighX(k: Int): Pair<Float, Float> {
        val i = schedule.indexOfFirst { it.spawn > playTime }
        if (i < 0) return (schedule.lastOrNull()?.x0 ?: 0f) * spread(k) to 1f
        val next = schedule[i]
        val prev = if (i > 0) schedule[i - 1] else null
        val from = prev?.x0 ?: 0f
        val t0 = prev?.spawn ?: 0.0
        val u = ((playTime - t0) / (next.spawn - t0)).toFloat().coerceIn(0f, 1f)
        val e = u * u * (3 - 2 * u)
        return (from + (next.x0 - from) * e) * spread(k) to if (next.x0 >= from) 1f else -1f
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) {
            val r = laneRect(k)
            g.save(); g.clipRect(r.left, 0f, r.right, g.height)
            scenery(g, k, r, time)
            val (gx, gy) = toScreen(k, 0f, FLOOR)
            // drop shadows on the snow show where each present is heading
            for (li in live[k]) if (li.state == Live.FALLING) {
                val (x, y) = position(li.d, (playTime - li.d.spawn).toFloat(), k)
                val (sx, _) = toScreen(k, x, y)
                val near = ((y - FLOOR) / (TOP - FLOOR)).coerceIn(0f, 1f)
                g.fillOval(sx - 34f + 14f * near, gy - 8f, sx + 34f - 14f * near, gy + 8f, Colors.withAlpha(if (li.d.kind == Kind.COAL) 0xFF5A0000.toInt() else 0xFF1B2A44.toInt(), 0.35f - 0.2f * near))
            }
            // the sleigh
            val (slx, dir) = sleighX(k)
            sleigh(g, toScreen(k, slx, TOP + 0.17f).first, toScreen(k, 0f, TOP + 0.17f).second, dir, time)
            // the player (mittens follow the hands), with their catch zones
            val inp = inputs[k]
            val hands = FloatArray(4)
            for (h in 0..1) {
                val (hx, hy) = toScreen(k, if (inp.handVisible[h]) inp.handX[h] else (if (h == 0) -0.35f else 0.35f), if (inp.handVisible[h]) inp.handY[h] else -0.7f)
                hands[h * 2] = hx; hands[h * 2 + 1] = hy
            }
            val rz = CATCH_R * reachPx(k) * (if (magnet[k] > 0f) 1.8f else 1f)
            val zone = if (magnet[k] > 0f) Colors.GOLD else env.color(k)
            for (h in 0..1) if (inp.handVisible[h]) {
                g.glow(hands[h * 2], hands[h * 2 + 1], rz * 1.4f, Colors.withAlpha(zone, if (magnet[k] > 0f) 0.4f else 0.22f))
                g.strokeCircle(hands[h * 2], hands[h * 2 + 1], rz, 4f, Colors.withAlpha(zone, 0.55f))
            }
            val expr = if (catchAnim[k] < 0.5f) Expression.EXCITED else Expression.HAPPY
            Draw.lightPool(g, shoulder(k).first, 1062f, 220f, 40f, Colors.withAlpha(env.color(k), 0.3f))
            Draw.buddy(g, shoulder(k).first, 1075f, 540f, env.color(k), inp.avatar, expr, t = time, handTargets = hands, mittenScale = 1.7f)
            // presents, candy, stars and coal
            for (li in live[k]) drawDrop(g, k, li, time)
            laneHud(g, k, r)
            g.restore()
        }
        Hud.divider(g)
        Hud.topBar(g, env, scores, Hud.formatTime(timeLeft), if (rush) "PRESENT RUSH - double points!" else "Catch the presents - not the coal", inputs)
        if (rush && playTime < MATCH_SEC - RUSH_SEC + 1.6) Hud.banner(g, "PRESENT RUSH!", 470f, Colors.GOLD, 96f)
    }

    private fun scenery(g: Gfx, k: Int, r: Rect, time: Double) {
        val full = Rect(r.left, 0f, r.right, g0h)
        Draw.nightSky(g, full, time + k * 3)
        Draw.aurora(g, Rect(r.left, 0f, r.right, 520f), time + k * 5, 0.9f)
        Draw.moon(g, if (k == 0) r.left + 110f else r.right - 110f, 230f, 44f)
        Draw.pineRow(g, r.left, r.right, 930f, 0xFF0F2A2A.toInt(), 110f, 31 + k)
        // a little village: snowy roofs and lit windows
        var i = 0
        var x = r.left + 30f
        while (x < r.right - 60f) {
            val w = 120f + (i * 37 % 50); val h = 70f + (i * 53 % 40)
            val base = 990f
            g.fillRect(x, base - h, x + w, base, 0xFF3E2A2A.toInt())
            pts[0] = x - 12f; pts[1] = base - h; pts[2] = x + w / 2f; pts[3] = base - h - 50f; pts[4] = x + w + 12f; pts[5] = base - h
            g.fillPolygon(pts, 3, 0xFF2B1B1B.toInt())
            pts[0] = x - 14f; pts[1] = base - h + 2f; pts[2] = x + w / 2f; pts[3] = base - h - 52f; pts[4] = x + w + 14f; pts[5] = base - h + 2f
            g.polyline(pts, 3, 10f, Colors.SNOW)
            val lit = sin(time * 0.7 + i * 1.9) > -0.6
            g.fillRect(x + w * 0.2f, base - h * 0.62f, x + w * 0.42f, base - h * 0.3f, if (lit) 0xFFFFD27A.toInt() else 0xFF5A4636.toInt())
            g.fillRect(x + w * 0.58f, base - h * 0.62f, x + w * 0.8f, base - h * 0.3f, if (lit) 0xFFFFE0A0.toInt() else 0xFF5A4636.toInt())
            if (lit) g.glow(x + w * 0.5f, base - h * 0.45f, w * 0.5f, 0x22FFC870)
            x += w + 40f + (i * 29 % 30); i++
        }
        Draw.snowHills(g, Rect(r.left, 0f, r.right, g0h), 1000f)
        if (!env.settings.reducedEffects) Draw.snowfall(g, full, time + k, 26)
    }

    private val g0h = 1080f
    private val pts = FloatArray(8)

    private fun drawDrop(g: Gfx, k: Int, li: Live, time: Double) {
        val d = li.d
        val age = (playTime - d.spawn).toFloat()
        val size = when (d.kind) { Kind.GOLD -> 86f; Kind.CANDY -> 70f; Kind.STAR -> 44f; Kind.COAL -> 78f; else -> 78f }
        when (li.state) {
            Live.FALLING -> {
                val (x, y) = position(d, age, k)
                val (sx, sy) = toScreen(k, x, y)
                val wob = sin(time.toFloat() * 3f + d.phase) * 0.15f
                item(g, d, sx, sy, size, wob, time, 1f)
            }
            Live.CAUGHT -> {
                // pops into the mitten
                val (hx, hy) = toScreen(k, li.hitX, li.hitY)
                val u = (li.t / 0.25f).coerceIn(0f, 1f)
                if (u < 1f) item(g, d, hx, hy - 30f * (1f - u), size * (1f - 0.7f * u), 0f, time, 1f - u)
                if (li.t < 0.9f) {
                    g.save(); g.setAlpha(g.getAlpha() * (1f - li.t / 0.9f))
                    g.glow(hx, hy, 90f, Colors.withAlpha(if (d.kind == Kind.GOLD || d.kind == Kind.STAR) Colors.GOLD else Colors.WHITE, 0.6f))
                    g.restore()
                }
            }
            Live.MISSED -> if (d.kind != Kind.COAL) {
                val (x, _) = position(d, age, k)
                val (sx, sy) = toScreen(k, x, FLOOR)
                item(g, d, sx, sy + 10f, size * 0.9f, 0.6f, time, (1f - li.t / 1.2f).coerceIn(0f, 1f))
            }
            Live.COAL_HIT -> {
                val (hx, hy) = toScreen(k, li.hitX, li.hitY)
                item(g, d, hx, hy - 20f + li.t * 120f, size, li.t * 6f, time, (1f - li.t / 1.2f).coerceIn(0f, 1f))
            }
        }
    }

    private fun item(g: Gfx, d: Drop, x: Float, y: Float, size: Float, rot: Float, time: Double, alpha: Float) {
        if (alpha <= 0.01f) return
        g.save(); g.setAlpha(g.getAlpha() * alpha)
        when (d.kind) {
            Kind.PRESENT -> Draw.present(g, x, y, size, d.color, Colors.GOLD, rot)
            Kind.GOLD -> { g.glow(x, y, size * 1.3f, 0x77FFD54F); Draw.present(g, x, y, size, Colors.GOLD, Colors.CANDY, rot); Draw.sparkle(g, x + size * 0.4f, y - size * 0.4f, 14f, Colors.WHITE) }
            Kind.CANDY -> candyCane(g, x, y, size, rot)
            Kind.STAR -> {
                g.glow(x, y, size * 2.6f, 0x88FFE082.toInt())
                Draw.star(g, x, y, size, Colors.GOLD, time.toFloat() * 2f)
                Draw.sparkle(g, x - size, y + size * 0.6f, 12f, Colors.WHITE)
            }
            Kind.COAL -> {
                g.glow(x, y, size * 1.25f, 0x66FF1744)
                Draw.coal(g, x, y, size, rot)
                g.line(x - size * 0.32f, y - size * 0.32f, x + size * 0.32f, y + size * 0.32f, 7f, 0xDDFF5252.toInt())
                g.line(x + size * 0.32f, y - size * 0.32f, x - size * 0.32f, y + size * 0.32f, 7f, 0xDDFF5252.toInt())
            }
        }
        g.restore()
    }

    private fun candyCane(g: Gfx, x: Float, y: Float, size: Float, rot: Float) {
        g.save(); g.translate(x, y); g.rotate(rot - 0.5f)
        val h = size * 0.5f
        val w = size * 0.13f
        g.line(0f, h, 0f, -h * 0.4f, w * 1.3f, 0x66000000)
        g.line(0f, h, 0f, -h * 0.4f, w, Colors.WHITE)
        g.arc(-h * 0.3f, -h * 0.4f, h * 0.3f, -kotlin.math.PI.toFloat(), kotlin.math.PI.toFloat(), w, Colors.WHITE)
        var s = -h * 0.3f
        while (s < h) { g.line(-w * 0.5f, s, w * 0.5f, s + w * 0.7f, w * 0.45f, Colors.CANDY); s += w * 1.4f }
        g.restore()
    }

    /** Santa, his sack and two reindeer, flying across the top of the lane. */
    private fun sleigh(g: Gfx, x: Float, y: Float, dir: Float, time: Double) {
        val bob = sin(time * 3.0).toFloat() * 5f
        g.save(); g.translate(x, y + bob); g.scale(dir, 1f)
        g.glow(0f, 10f, 120f, 0x22FFFFFF)
        // reindeer ahead
        for (i in 0..1) {
            val rx = 95f + i * 60f; val ry = -6f + (if (i == 0) 0f else -4f)
            val leg = sin(time.toFloat() * 9f + i) * 6f
            g.line(rx - 14f, ry + 8f, rx - 18f + leg, ry + 22f, 4f, 0xFF5D4037.toInt())
            g.line(rx + 12f, ry + 8f, rx + 16f - leg, ry + 22f, 4f, 0xFF5D4037.toInt())
            g.fillOval(rx - 22f, ry - 8f, rx + 20f, ry + 12f, 0xFF8D6E63.toInt())
            g.fillCircle(rx + 24f, ry - 12f, 9f, 0xFF8D6E63.toInt())
            g.line(rx + 22f, ry - 20f, rx + 16f, ry - 34f, 3f, 0xFF4E342E.toInt())
            g.line(rx + 28f, ry - 20f, rx + 34f, ry - 34f, 3f, 0xFF4E342E.toInt())
            if (i == 1) g.fillCircle(rx + 32f, ry - 11f, 4f, 0xFFFF1744.toInt())
            g.line(rx - 20f, ry, rx - 60f, 4f, 1.5f, 0xAAE0C080.toInt())
        }
        // sack, sleigh body, runner, Santa
        g.fillCircle(-38f, -18f, 26f, 0xFF8D6E63.toInt())
        g.fillRoundRectGradient(-70f, -10f, 40f, 22f, 12f, 0xFFE53935.toInt(), 0xFF8E1B1B.toInt())
        g.line(-70f, 30f, 46f, 30f, 4f, Colors.GOLD)
        g.arc(46f, 22f, 8f, -1.6f, 3.2f, 4f, Colors.GOLD)
        g.fillCircle(10f, -22f, 15f, 0xFFE53935.toInt())
        g.fillCircle(14f, -36f, 10f, 0xFFFFCCBC.toInt())
        g.fillOval(6f, -32f, 24f, -18f, Colors.WHITE)
        pts[0] = 6f; pts[1] = -42f; pts[2] = 24f; pts[3] = -42f; pts[4] = 4f; pts[5] = -60f
        g.fillPolygon(pts, 3, 0xFFE53935.toInt())
        g.fillCircle(4f, -60f, 4f, Colors.WHITE)
        g.restore()
    }

    private fun laneHud(g: Gfx, k: Int, r: Rect) {
        val m = multiplier(k) * (if (rush) 2 else 1)
        val y = 300f
        // on the outer edge of the lane, clear of the sleigh and the falling presents
        val cx = if (k == 0) r.left + 150f else r.right - 150f
        if (streak[k] > 0) {
            val text = "Streak ${streak[k]}" + if (m > 1) "  x$m" else ""
            val w = g.measureText(text, 32f) + 44f
            g.fillRoundRect(cx - w / 2f, y - 25f, cx + w / 2f, y + 25f, 25f, if (m > 1) 0xCC6A4A00.toInt() else 0x99000000.toInt())
            g.text(text, cx, y, 32f, if (m > 1) Colors.GOLD else Colors.WHITE)
        }
        if (magnet[k] > 0f) {
            val w = 220f
            g.fillRoundRect(cx - w / 2f, y + 40f, cx + w / 2f, y + 64f, 12f, 0x66000000)
            g.fillRoundRect(cx - w / 2f, y + 40f, cx - w / 2f + w * (magnet[k] / MAGNET_SEC), y + 64f, 12f, Colors.GOLD)
            g.text("Magnet mittens", cx, y + 90f, 26f, Colors.GOLD)
        }
    }

    val timeLeft: Double get() = (MATCH_SEC - playTime).coerceAtLeast(0.0)
    override val isOver: Boolean get() = playTime >= MATCH_SEC

    override fun result() = MatchResult(info.id, scores.copyOf(), winnerOf(scores[0], scores[1]), info.scoreUnit,
        listOf("Presents caught: ${caught[0]} vs ${caught[1]}", "Best streak: ${bestStreak[0]} vs ${bestStreak[1]}", "Coal touched: ${coalHits[0]} vs ${coalHits[1]}"))

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val phase = ((t % 2.4) / 2.4).toFloat()
        val left = ((t / 2.4).toInt() % 2) == 0
        val px = r.cx + (if (left) -1f else 1f) * r.width * 0.28f
        val fall = r.top + r.height * 0.05f + phase * r.height * 0.55f
        val hands = floatArrayOf(r.cx - r.width * 0.12f, r.bottom - r.height * 0.25f, r.cx + r.width * 0.12f, r.bottom - r.height * 0.25f)
        val h = if (left) 0 else 1
        val k = (phase / 0.7f).coerceIn(0f, 1f)
        hands[h * 2] = lerp(hands[h * 2], px, k); hands[h * 2 + 1] = lerp(hands[h * 2 + 1], r.top + r.height * 0.45f, k)
        Draw.buddy(g, r.cx, r.bottom + r.height * 0.15f, r.height * 1.1f, env.color(0), null, Expression.EXCITED, t = t, handTargets = hands, mittenScale = 1.5f)
        if (fall < r.top + r.height * 0.45f) Draw.present(g, px, fall, r.height * 0.14f, Colors.CANDY, Colors.GOLD, cos(t.toFloat() * 3f) * 0.1f)
        else Draw.sparkle(g, px, r.top + r.height * 0.42f, 22f, Colors.GOLD)
        Draw.coal(g, r.left + r.width * 0.12f, r.top + r.height * 0.2f, r.height * 0.11f)
    }
}
