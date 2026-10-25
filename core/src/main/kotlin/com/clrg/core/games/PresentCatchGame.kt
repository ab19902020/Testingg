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
import com.clrg.core.math.easeOutCubic
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.tracking.MovementMode
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 3. Present Catch - each player has their own lane. Presents drift down to spots around you (left, right, high,
 * overhead); touch them with either hand while their ring is showing. Coal is marked with a red X: avoid it.
 * Both lanes get exactly the same sequence (same seed).
 */
class PresentCatchGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    enum class Kind { PRESENT, GOLD, COAL }
    class Spot(val x: Float, val y: Float)
    class Item(val spawn: Double, val spot: Int, val kind: Kind, val color: Int)
    class LiveItem(val item: Item, var state: Int = 0 /* 0 falling, 1 hover, 2 gone */, var t: Float = 0f, var caught: Boolean = false, var touchedCoal: Boolean = false)

    companion object {
        const val MATCH_SEC = 60.0
        const val FALL = 1.3f
        const val HOVER = 1.4f
        const val CATCH_R = 0.3f
        val STANDARD_SPOTS = listOf(Spot(-0.95f, 0.05f), Spot(-0.7f, 0.65f), Spot(0f, 1.0f), Spot(0.7f, 0.65f), Spot(0.95f, 0.05f), Spot(-0.65f, -0.4f), Spot(0.65f, -0.4f))
        val SEATED_SPOTS = listOf(Spot(-0.9f, 0.05f), Spot(-0.65f, 0.6f), Spot(0f, 0.9f), Spot(0.65f, 0.6f), Spot(0.9f, 0.05f), Spot(-0.6f, -0.2f), Spot(0.6f, -0.2f))
        val BOX_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt())

        val INFO = GameInfo(
            id = "catch", title = "Present Catch", tagline = "Reach out and grab the presents!",
            howTo = listOf(
                "Presents float down to spots around you: left, right, high and overhead.",
                "Touch a present with either hand while its ring is showing.",
                "Gold presents are worth extra. COAL has a red X - keep your hands away!",
                "Both lanes get the same presents. Highest score after 60 seconds wins.",
            ),
            controls = listOf("Reach left / right / up" to "Catch presents", "Keep away from coal" to "Avoid penalties"),
            gentleNotes = "Spots are placed inside your own calibrated reach; seated players get no low spots.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "Highest score in 60 seconds",
        )
    }

    val scores = IntArray(2)
    val caught = IntArray(2)
    val coalHits = IntArray(2)
    val streak = IntArray(2)
    val bestStreak = IntArray(2)
    private val schedule = ArrayList<Item>()
    private val live = Array(2) { ArrayList<LiveItem>() }
    private var nextIndex = IntArray(2)
    private var screenW = 1920f
    private val catchAnim = FloatArray(2) { 9f }

    private fun spotsFor(k: Int) = if (env.players[k].mode == MovementMode.SEATED) SEATED_SPOTS else STANDARD_SPOTS

    override fun start() {
        val rng = env.rng(3)
        var t = 1.0
        var lastSpot = -1
        while (t < MATCH_SEC - 2.0) {
            val progress = t / MATCH_SEC
            val gap = lerp(1.7f, 0.85f, progress.toFloat())
            var spot = rng.nextInt(STANDARD_SPOTS.size)
            if (spot == lastSpot) spot = (spot + 1 + rng.nextInt(STANDARD_SPOTS.size - 1)) % STANDARD_SPOTS.size
            lastSpot = spot
            val r = rng.nextFloat()
            val kind = when { progress > 0.15 && r < 0.18f -> Kind.COAL; r > 0.9f -> Kind.GOLD; else -> Kind.PRESENT }
            schedule.add(Item(t, spot, kind, BOX_COLORS[rng.nextInt(BOX_COLORS.size)]))
            // later in the match, sometimes two at once (different spots)
            if (progress > 0.5 && rng.chance(0.3f)) {
                val s2 = (spot + 2 + rng.nextInt(3)) % STANDARD_SPOTS.size
                schedule.add(Item(t + 0.15, s2, if (rng.chance(0.25f)) Kind.COAL else Kind.PRESENT, BOX_COLORS[rng.nextInt(BOX_COLORS.size)]))
            }
            t += gap
        }
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            catchAnim[k] += dt
            while (nextIndex[k] < schedule.size && schedule[nextIndex[k]].spawn <= playTime) live[k].add(LiveItem(schedule[nextIndex[k]++]))
            val spots = spotsFor(k)
            val inp = inputs[k]
            val it = live[k].iterator()
            while (it.hasNext()) {
                val li = it.next()
                li.t += dt
                if (li.state == 0 && li.t >= FALL) { li.state = 1; li.t = 0f }
                if (li.state == 1 && li.t >= HOVER) {
                    li.state = 2; li.t = 0f
                    if (!li.caught && li.item.kind != Kind.COAL) { streak[k] = 0; env.sfx(Sfx.MISS, 0.4f, pan(k)) }
                }
                if (li.state == 2 && li.t > 0.6f) { it.remove(); continue }
                if (li.caught || li.touchedCoal || li.state == 2) continue
                val catchable = li.state == 1 || (li.state == 0 && li.t > FALL * 0.7f)
                if (!catchable) continue
                val sp = spots[li.item.spot]
                val (sx, sy) = itemReach(li, sp)
                val touched = (0..1).any { h -> inp.handVisible[h] && hypot(inp.handX[h] - sx, inp.handY[h] - sy) < CATCH_R }
                if (!touched) continue
                val (px, py) = toScreen(k, sx, sy)
                when (li.item.kind) {
                    Kind.COAL -> {
                        li.touchedCoal = true
                        scores[k] = (scores[k] - 15).coerceAtLeast(0)
                        coalHits[k]++
                        streak[k] = 0
                        env.popups.add("COAL -15", px, py - 60f, Colors.BAD, 52f)
                        env.particles.burst(px, py, 0xFF424242.toInt(), 16, kind = Particles.Kind.PUFF)
                        env.sfx(Sfx.BAD, 0.9f, pan(k))
                    }
                    else -> {
                        li.caught = true
                        streak[k]++
                        bestStreak[k] = maxOf(bestStreak[k], streak[k])
                        val base = if (li.item.kind == Kind.GOLD) 25 else 10
                        val bonus = if (streak[k] >= 5) 5 else 0
                        scores[k] += base + bonus
                        caught[k]++
                        catchAnim[k] = 0f
                        env.popups.add("+${base + bonus}" + if (bonus > 0) " x${streak[k]}" else "", px, py - 60f, if (li.item.kind == Kind.GOLD) Colors.GOLD else env.color(k), 52f)
                        env.particles.burst(px, py, if (li.item.kind == Kind.GOLD) Colors.GOLD else li.item.color, 22, kind = Particles.Kind.STAR, size = 12f)
                        env.particles.ring(px, py, if (li.item.kind == Kind.GOLD) Colors.GOLD else env.color(k), 100f)
                        env.sfx(Sfx.CATCH, 0.9f, pan(k))
                    }
                }
            }
        }
    }

    /** Current item position in reach space (falls in from above the spot). */
    private fun itemReach(li: LiveItem, sp: Spot): Pair<Float, Float> = when (li.state) {
        0 -> sp.x to sp.y + (1f - easeOutCubic(li.t / FALL)) * 1.6f
        1 -> sp.x to sp.y + 0.03f * sin(li.t * 6f)
        else -> sp.x to sp.y - li.t * 3f
    }

    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f

    // ---- CPU player: notices each new present after a reaction delay (and sometimes not at all), moves a hand
    // there at a human speed, waits for it, then returns to a ready position in front of the chest. It steers its
    // hands around coal it has noticed, but now and then grabs coal by mistake.
    private class BotView(val go: Boolean, val noticeAt: Double)
    private val botViews = HashMap<LiveItem, BotView>()

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        val spots = spotsFor(k)
        botViews.keys.removeAll { it.state == 2 }
        val wanted = live[k].filter { li ->
            val v = botViews.getOrPut(li) {
                val isCoal = li.item.kind == Kind.COAL
                val go = if (isCoal) rng.chance(lerp(0.12f, 0.0f, skill)) else rng.chance(lerp(0.72f, 0.97f, skill))
                BotView(go, li.item.spawn + com.clrg.core.game.CpuTraits.reaction(skill) * rng.range(0.85f, 1.3f))
            }
            v.go && playTime >= v.noticeAt && !li.caught && !li.touchedCoal && li.state < 2
        }.sortedBy { it.item.spawn }
        val speed = com.clrg.core.game.CpuTraits.handSpeed(skill)
        // coal the CPU has noticed (and isn't mistakenly going for) is kept away from
        val avoid = live[k].filter { li ->
            li.item.kind == Kind.COAL && !li.touchedCoal && li.state < 2 && botViews[li]?.let { !it.go && playTime >= it.noticeAt } == true
        }.map { spots[it.item.spot] }
        for (h in 0..1) {
            // each hand covers its own side; the overhead spot goes to whichever hand is free
            val target = wanted.firstOrNull { val x = spots[it.item.spot].x; if (h == 0) x < -0.01f else x > 0.01f }
                ?: wanted.firstOrNull { kotlin.math.abs(spots[it.item.spot].x) <= 0.01f && wanted.none { o -> o !== it && (if (h == 0) spots[o.item.spot].x < -0.01f else spots[o.item.spot].x > 0.01f) } }
            val (tx, ty) = if (target != null) spots[target.item.spot].let { it.x to it.y } else (if (h == 0) -0.25f else 0.25f) to -0.1f
            val ox = inp.handX[h]
            val oy = inp.handY[h]
            com.clrg.core.game.CpuTraits.moveHand(inp, h, tx, ty, speed, dt)
            val c = avoid.firstOrNull { hypot(inp.handX[h] - it.x, inp.handY[h] - it.y) < CATCH_R + 0.08f }
            if (c != null) {
                // back off from the coal instead of moving through it
                val d = hypot(ox - c.x, oy - c.y).coerceAtLeast(1e-3f)
                inp.handX[h] = ox + (ox - c.x) / d * speed * dt
                inp.handY[h] = oy + (oy - c.y) / d * speed * dt
            }
        }
    }

    private fun laneRect(k: Int) = Rect(if (k == 0) 0f else screenW / 2f, 120f, if (k == 0) screenW / 2f else screenW, 1080f)
    private fun reachPx(k: Int) = minOf(laneRect(k).width * 0.36f, 330f)
    private fun shoulder(k: Int): Pair<Float, Float> = laneRect(k).cx to 650f

    fun toScreen(k: Int, rx: Float, ry: Float): Pair<Float, Float> {
        val (cx, cy) = shoulder(k)
        val r = reachPx(k)
        return cx + rx * r to cy - ry * r
    }

    val timeLeft: Double get() = (MATCH_SEC - playTime).coerceAtLeast(0.0)
    override val isOver: Boolean get() = playTime >= MATCH_SEC

    override fun result() = MatchResult(info.id, scores.copyOf(), winnerOf(scores[0], scores[1]), info.scoreUnit,
        listOf("Presents caught: ${caught[0]} vs ${caught[1]}", "Best streak: ${bestStreak[0]} vs ${bestStreak[1]}", "Coal touched: ${coalHits[0]} vs ${coalHits[1]}"))

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) {
            val r = laneRect(k)
            g.save(); g.clipRect(r.left, 0f, r.right, g.height)
            Draw.nightSky(g, Rect(r.left, 0f, r.right, g.height), time + k * 3)
            Draw.aurora(g, Rect(r.left, 0f, r.right, 520f), time + k * 5, 0.9f)
            Draw.moon(g, if (k == 0) r.left + 120f else r.right - 120f, 220f, 46f)
            Draw.pineRow(g, r.left, r.right, 860f, 0xFF0F2A2A.toInt(), 110f, 31 + k)
            Draw.pineRow(g, r.left, r.right, 900f, 0xFF173F33.toInt(), 140f, 11 + k)
            Draw.snowHills(g, Rect(r.left, 0f, r.right, g.height), 960f)
            Draw.snowfall(g, Rect(r.left, 0f, r.right, g.height), time + k, 30)
            val spots = spotsFor(k)
            val inp = inputs[k]
            // faint spot markers so players learn where things land
            for (sp in spots) {
                val (sx, sy) = toScreen(k, sp.x, sp.y)
                g.strokeCircle(sx, sy, CATCH_R * reachPx(k), 3f, 0x22FFFFFF)
            }
            val (cx, cy) = shoulder(k)
            val hands = FloatArray(4)
            for (h in 0..1) {
                val (hx, hy) = toScreen(k, if (inp.handVisible[h]) inp.handX[h] else (if (h == 0) -0.35f else 0.35f), if (inp.handVisible[h]) inp.handY[h] else -0.7f)
                hands[h * 2] = hx; hands[h * 2 + 1] = hy
            }
            val expr = if (catchAnim[k] < 0.5f) Expression.EXCITED else Expression.HAPPY
            Draw.lightPool(g, cx, cy + 430f, 230f, 50f, Colors.withAlpha(env.color(k), 0.3f))
            Draw.buddy(g, cx, cy + 420f, 600f, env.color(k), inp.avatar, expr, t = time, handTargets = hands, mittenScale = 1.6f)
            for (li in live[k]) {
                val sp = spots[li.item.spot]
                val (rx, ry) = itemReach(li, sp)
                val (sx, sy) = toScreen(k, rx, ry)
                val alpha = if (li.state == 2) (1f - li.t / 0.6f).coerceIn(0f, 1f) else 1f
                if (li.caught && li.state != 0) continue
                g.save(); g.setAlpha(alpha)
                if (li.state == 1 && !li.touchedCoal) Hud.timerRing(g, sx, sy, CATCH_R * reachPx(k), 1f - li.t / HOVER, if (li.item.kind == Kind.COAL) Colors.BAD else env.color(k))
                when (li.item.kind) {
                    Kind.COAL -> Draw.coal(g, sx, sy, 84f, li.t)
                    Kind.GOLD -> { g.glow(sx, sy, 80f, 0x66FFD54F); Draw.present(g, sx, sy, 80f, Colors.GOLD, Colors.CANDY, sin(time * 3 + li.item.spawn).toFloat() * 0.1f) }
                    Kind.PRESENT -> Draw.present(g, sx, sy, 80f, li.item.color, Colors.GOLD, sin(time * 3 + li.item.spawn).toFloat() * 0.1f)
                }
                g.restore()
            }
            if (streak[k] >= 3) g.text("Streak ${streak[k]}", r.cx, 1035f, 44f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 6f)
            g.restore()
        }
        Hud.divider(g)
        Hud.topBar(g, env, scores, Hud.formatTime(timeLeft), "Catch presents - avoid coal", inputs)
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val phase = ((t % 3.0) / 3.0).toFloat()
        val target = if (phase < 0.5f) floatArrayOf(r.cx - r.width * 0.3f, r.top + r.height * 0.25f) else floatArrayOf(r.cx + r.width * 0.3f, r.top + r.height * 0.15f)
        val hands = floatArrayOf(r.cx - r.width * 0.12f, r.bottom - r.height * 0.2f, r.cx + r.width * 0.12f, r.bottom - r.height * 0.2f)
        val k = ((phase % 0.5f) / 0.3f).coerceIn(0f, 1f)
        val h = if (phase < 0.5f) 0 else 1
        hands[h * 2] = lerp(hands[h * 2], target[0], k); hands[h * 2 + 1] = lerp(hands[h * 2 + 1], target[1], k)
        Draw.buddy(g, r.cx, r.bottom + r.height * 0.15f, r.height * 1.1f, env.color(0), null, Expression.EXCITED, t = t, handTargets = hands, mittenScale = 1.5f)
        if (k < 1f) Draw.present(g, target[0], target[1], r.height * 0.14f, Colors.CANDY, Colors.GOLD)
        Draw.coal(g, r.left + r.width * 0.1f, r.top + r.height * 0.15f, r.height * 0.12f)
    }
}
