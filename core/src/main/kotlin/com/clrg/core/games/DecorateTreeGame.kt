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
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.tracking.MovementMode
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 10. Decorate the Tree - hold a hand over an ornament in your tray until the ring fills to pick it up, then hold
 * it over the glowing branch spot until the ring fills to hang it. Dwell-based: no grabbing or pinching needed.
 * Both trees get the same spot sequence; the last spot is the star on top. First to finish wins.
 */
class DecorateTreeGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    class Spot(val x: Float, val y: Float)
    class Slot(val x: Float, val y: Float, var color: Int, var refill: Float = 0f)

    companion object {
        const val SPOTS = 10
        const val DWELL_PICK = 0.6f
        const val DWELL_PLACE = 0.7f
        const val PICK_R = 0.2f
        const val PLACE_R = 0.22f
        const val TIME_LIMIT = 150.0
        val COLORS = intArrayOf(0xFFE53935.toInt(), 0xFFFFD54F.toInt(), 0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFF26A69A.toInt(), 0xFFFF7043.toInt())
        val INFO = GameInfo(
            id = "tree", title = "Decorate the Tree", tagline = "Pick, carry and hang the baubles!",
            howTo = listOf(
                "Hold a hand still over an ornament in your tray until the ring fills: you pick it up.",
                "Carry it to the glowing spot on your tree and hold still until the ring fills.",
                "Ten decorations - the last one is the star on top.",
                "First to finish their tree wins!",
            ),
            controls = listOf("Hold a hand over an ornament" to "Pick it up", "Hold it over the glowing spot" to "Hang it"),
            gentleNotes = "All spots sit inside each player's calibrated reach; seated players get no low spots.",
            music = MusicTrack.MENU, scoreUnit = "decorations", goal = "First to finish the tree",
        )
    }

    private val sequence = ArrayList<Spot>()
    val placed = IntArray(2)
    val holding = IntArray(2) { -1 }   // colour index held, -1 none
    private val holdingHand = IntArray(2) { -1 }
    private val dwell = FloatArray(2)
    private val dwellTarget = IntArray(2) { -2 } // slot index, or -1 for tree spot
    private val dwellHand = IntArray(2) { -1 }
    private val trays = Array(2) { listOf(Slot(-1.0f, 0.55f, 0), Slot(-1.05f, 0.05f, 0), Slot(-0.95f, -0.4f, 0), Slot(1.0f, 0.55f, 0), Slot(1.05f, 0.05f, 0), Slot(0.95f, -0.4f, 0)) }
    private val placedColors = Array(2) { ArrayList<Int>() }
    val finishTime = doubleArrayOf(-1.0, -1.0)
    private var screenW = 1920f

    override fun start() {
        val rng = env.rng(10)
        val pool = listOf(
            Spot(-0.35f, -0.35f), Spot(0.3f, -0.4f), Spot(-0.5f, -0.1f), Spot(0.05f, -0.2f), Spot(0.45f, -0.05f),
            Spot(-0.3f, 0.2f), Spot(0.25f, 0.25f), Spot(-0.15f, 0.5f), Spot(0.15f, 0.62f),
        ).toMutableList()
        rng.shuffle(pool)
        sequence.addAll(pool.take(SPOTS - 1))
        sequence.add(Spot(0f, 0.95f)) // star on top
        for (k in 0..1) trays[k].forEachIndexed { i, s -> s.color = i % COLORS.size }
    }

    private fun spotY(k: Int, s: Spot) = if (env.players[k].mode == MovementMode.SEATED) 0.2f + (s.y + 0.4f) * 0.55f else s.y

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            for (s in trays[k]) if (s.refill > 0f) { s.refill -= dt; if (s.refill <= 0f) s.refill = 0f }
            if (finishTime[k] >= 0) continue
            val inp = inputs[k]
            var target = -2
            var hand = -1
            if (holding[k] < 0) {
                for (h in 0..1) {
                    if (!inp.handVisible[h]) continue
                    trays[k].forEachIndexed { i, s -> if (s.refill <= 0f && hypot(inp.handX[h] - s.x, inp.handY[h] - s.y) < PICK_R) { target = i; hand = h } }
                }
            } else {
                val h = holdingHand[k]
                val sp = sequence[placed[k]]
                if (inp.handVisible[h] && hypot(inp.handX[h] - sp.x, inp.handY[h] - spotY(k, sp)) < PLACE_R) { target = -1; hand = h }
            }
            if (target != -2 && target == dwellTarget[k] && hand == dwellHand[k]) {
                dwell[k] += dt
            } else {
                dwell[k] = if (target == -2) (dwell[k] - dt * 2f).coerceAtLeast(0f) else 0f
                if (target != -2 || dwell[k] <= 0f) { dwellTarget[k] = target; dwellHand[k] = hand }
            }
            if (target >= 0 && dwell[k] >= DWELL_PICK) {
                val s = trays[k][target]
                holding[k] = s.color
                holdingHand[k] = hand
                s.refill = 1.2f
                s.color = (s.color + 3) % COLORS.size
                dwell[k] = 0f; dwellTarget[k] = -2
                env.sfx(Sfx.PICKUP, 0.8f, pan(k))
            } else if (target == -1 && dwell[k] >= DWELL_PLACE) {
                placedColors[k].add(holding[k])
                placed[k]++
                holding[k] = -1; holdingHand[k] = -1
                dwell[k] = 0f; dwellTarget[k] = -2
                val (px, py) = toScreen(k, sequence[placed[k] - 1].x, spotY(k, sequence[placed[k] - 1]))
                env.particles.burst(px, py, Colors.GOLD, 20, kind = Particles.Kind.STAR, size = 12f)
                env.popups.add("${placed[k]}/$SPOTS", px, py - 60f, env.color(k), 46f)
                env.sfx(Sfx.PLACE, 0.9f, pan(k))
                if (placed[k] >= SPOTS) {
                    finishTime[k] = playTime
                    env.sfx(Sfx.CHEER, 1f, pan(k))
                    env.particles.confetti(laneRect(k).left, laneRect(k).right, 150f, 80)
                }
            }
        }
    }

    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f

    // ---- CPU player: looks for an ornament, reaches for it, holds still (with a small human wobble that can
    // break the hold on lower levels), carries it to the glowing spot, and pauses a moment between steps.
    private val botPauseUntil = DoubleArray(2)
    private val botPhase = IntArray(2) { -9 }
    private val botWobX = FloatArray(2)
    private val botWobY = FloatArray(2)
    private val botHand = IntArray(2) { 1 }

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        val speed = com.clrg.core.game.CpuTraits.handSpeed(skill) * com.clrg.core.math.lerp(0.6f, 0.85f, skill)
        // a new step (picking or placing) starts with a short look-and-think pause
        val phase = placed[k] * 2 + if (holding[k] >= 0) 1 else 0
        if (phase != botPhase[k]) {
            botPhase[k] = phase
            botPauseUntil[k] = playTime + com.clrg.core.math.lerp(1.5f, 0.3f, skill) * rng.range(0.7f, 1.3f)
        }
        // slowly wandering hand tremor
        val wob = com.clrg.core.math.lerp(0.1f, 0.03f, skill)
        botWobX[k] = (botWobX[k] + rng.gaussian() * wob * 0.15f) * 0.985f
        botWobY[k] = (botWobY[k] + rng.gaussian() * wob * 0.15f) * 0.985f
        val rest = floatArrayOf(-0.4f, -0.75f, 0.4f, -0.75f)
        var tx = Float.NaN; var ty = 0f
        if (finishTime[k] < 0 && playTime >= botPauseUntil[k]) {
            if (holding[k] < 0) {
                val slot = trays[k].filter { it.refill <= 0f }.minByOrNull { kotlin.math.hypot(it.x - inp.handX[botHand[k]], it.y - inp.handY[botHand[k]]) }
                if (slot != null) { botHand[k] = if (slot.x < 0) 0 else 1; tx = slot.x; ty = slot.y }
            } else {
                botHand[k] = holdingHand[k].coerceAtLeast(0)
                val sp = sequence[placed[k]]
                tx = sp.x; ty = spotY(k, sp)
            }
        }
        for (h in 0..1) {
            val (gx, gy) = if (h == botHand[k] && !tx.isNaN()) (tx + botWobX[k]) to (ty + botWobY[k]) else rest[h * 2] to rest[h * 2 + 1]
            com.clrg.core.game.CpuTraits.moveHand(inp, h, gx, gy, speed, dt)
        }
    }

    private fun laneRect(k: Int) = Rect(if (k == 0) 0f else screenW / 2f, 120f, if (k == 0) screenW / 2f else screenW, 1080f)
    fun toScreen(k: Int, rx: Float, ry: Float): Pair<Float, Float> {
        val r = laneRect(k)
        val sx = minOf(r.width * 0.38f, 360f)
        return r.cx + rx * sx to 640f - ry * 380f
    }

    override val isOver: Boolean get() = finishTime.any { it >= 0 } || playTime >= TIME_LIMIT

    override fun result(): MatchResult {
        val w = when {
            finishTime[0] >= 0 && finishTime[1] >= 0 -> if (finishTime[0] < finishTime[1]) 0 else if (finishTime[1] < finishTime[0]) 1 else -1
            finishTime[0] >= 0 -> 0
            finishTime[1] >= 0 -> 1
            else -> winnerOf(placed[0], placed[1])
        }
        val details = (0..1).map { k -> if (finishTime[k] >= 0) "%.1f s".format(finishTime[k]) else "${placed[k]} of $SPOTS" }
        return MatchResult(info.id, placed.copyOf(), w, info.scoreUnit, listOf("Finished: ${details[0]} vs ${details[1]}"))
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) renderLane(g, k, inputs[k], time)
        Hud.divider(g)
        Hud.topBar(g, env, placed, "Decorate the Tree", "First to $SPOTS wins - ${Hud.formatTime(TIME_LIMIT - playTime)} left", inputs)
    }

    private fun renderLane(g: Gfx, k: Int, inp: PlayerInput, time: Double) {
        val r = laneRect(k)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        // cosy living room: wall, window, fireplace glow, floor
        g.gradientRect(r.left, r.top, r.right, r.bottom, 0xFF4A2C2A.toInt(), 0xFF2B1716.toInt())
        for (i in 0 until 8) g.fillRect(r.left + i * r.width / 8f, r.top, r.left + i * r.width / 8f + 6f, 900f, 0x14000000)
        g.fillRect(r.left, 900f, r.right, r.bottom, 0xFF6D4C41.toInt())
        g.glow(r.cx, 900f, r.width * 0.6f, 0x33FFB74D)
        val (treeX, _) = toScreen(k, 0f, 0f)
        Draw.christmasTree(g, treeX, 960f, 760f, placed[k] >= SPOTS, time)
        // placed ornaments
        for (i in 0 until placed[k]) {
            val sp = sequence[i]
            val (px, py) = toScreen(k, sp.x, spotY(k, sp))
            if (i == SPOTS - 1) { g.glow(px, py, 90f, 0x88FFD54F.toInt()); Draw.star(g, px, py, 50f, Colors.GOLD, sin(time.toFloat()) * 0.1f) }
            else Draw.ornament(g, px, py, 26f, COLORS[placedColors[k][i]])
        }
        // next spot
        if (placed[k] < SPOTS && finishTime[k] < 0) {
            val sp = sequence[placed[k]]
            val (px, py) = toScreen(k, sp.x, spotY(k, sp))
            val pulse = 0.5f + 0.5f * sin(time.toFloat() * 5f)
            g.glow(px, py, 80f + 20f * pulse, 0x66FFFFFF)
            g.strokeCircle(px, py, PLACE_R * 380f, 5f, Colors.withAlpha(Colors.WHITE, 0.6f + 0.4f * pulse))
            if (dwellTarget[k] == -1 && dwell[k] > 0f) Draw.progressRing(g, px, py, PLACE_R * 380f + 14f, dwell[k] / DWELL_PLACE, env.color(k), 12f)
            g.text(if (placed[k] == SPOTS - 1) "Star!" else if (holding[k] >= 0) "Hang it here" else "Next spot", px, py + PLACE_R * 380f + 34f, 30f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 5f)
        }
        // trays
        for ((i, s) in trays[k].withIndex()) {
            val (sx, sy) = toScreen(k, s.x, s.y)
            g.fillRoundRect(sx - 64f, sy + 30f, sx + 64f, sy + 50f, 10f, 0xFF8D6E63.toInt())
            if (s.refill <= 0f) {
                if (placed[k] == SPOTS - 1) Draw.star(g, sx, sy, 34f, Colors.GOLD) else Draw.ornament(g, sx, sy, 30f, COLORS[s.color])
                if (dwellTarget[k] == i && dwell[k] > 0f) Draw.progressRing(g, sx, sy, 52f, dwell[k] / DWELL_PICK, env.color(k), 10f)
            }
        }
        // mittens (cursors)
        for (h in 0..1) {
            if (!inp.handVisible[h]) continue
            val (hx, hy) = toScreen(k, inp.handX[h], inp.handY[h])
            g.fillCircle(hx, hy, 34f, Colors.withAlpha(env.color(k), 0.9f))
            g.strokeCircle(hx, hy, 34f, 4f, Colors.WHITE)
            if (holding[k] >= 0 && holdingHand[k] == h) {
                if (placed[k] == SPOTS - 1) Draw.star(g, hx, hy - 10f, 30f, Colors.GOLD) else Draw.ornament(g, hx, hy - 10f, 24f, COLORS[holding[k]])
            }
        }
        if (finishTime[k] >= 0) g.text("Finished!", r.cx, 300f, 90f, env.color(k), outline = Colors.BLACK, outlineWidth = 8f)
        Hud.laneLabel(g, Rect(r.left, 130f, r.right, 260f), env, k)
        g.restore()
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val phase = ((t % 3.0) / 3.0).toFloat()
        Draw.christmasTree(g, r.cx + r.width * 0.15f, r.bottom - 10f, r.height * 0.9f, false, t)
        val trayX = r.left + r.width * 0.15f; val trayY = r.cy
        val spotX = r.cx + r.width * 0.15f; val spotY = r.cy - r.height * 0.05f
        Draw.ornament(g, trayX, trayY, 18f, Colors.CANDY)
        g.strokeCircle(spotX, spotY, 26f, 4f, Colors.WHITE)
        val (hx, hy, prog) = when {
            phase < 0.3f -> Triple(trayX, trayY, phase / 0.3f)
            phase < 0.55f -> { val k = (phase - 0.3f) / 0.25f; Triple(trayX + (spotX - trayX) * k, trayY + (spotY - trayY) * k, 0f) }
            else -> Triple(spotX, spotY, ((phase - 0.55f) / 0.3f).coerceAtMost(1f))
        }
        g.fillCircle(hx, hy, 22f, env.color(0))
        if (prog > 0f) Draw.progressRing(g, hx, hy, 36f, prog, Colors.GOLD, 7f)
        if (phase in 0.3f..0.85f) Draw.ornament(g, hx, hy - 6f, 14f, Colors.CANDY)
        if (phase > 0.85f) Draw.ornament(g, spotX, spotY, 18f, Colors.CANDY)
    }
}
