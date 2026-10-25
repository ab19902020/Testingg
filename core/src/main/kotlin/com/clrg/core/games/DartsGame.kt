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
import com.clrg.core.gesture.PunchKind
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Darts - split screen, a board each, throwing at the same time. Aim with your throwing hand (the sight follows it),
 * hold steady, then throw: a quick push of the hand towards the TV. The dart flies to where you were aiming before
 * the throw; a weak, floppy throw drops low. Four rounds of three darts; trebles and doubles count three and two
 * times, the bull is 50. Highest total wins.
 */
class DartsGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    /** A dart in the board: position in mm from the bull (x right, y up) and its score. */
    class Dart(val player: Int, val round: Int, val x: Float, val y: Float, val score: Int, val label: String, val thrownAt: Double)

    companion object {
        const val ROUNDS = 4
        const val DARTS_PER_ROUND = 3
        /** Board radius (mm) and how many view units one mm is drawn. */
        const val BOARD_MM = 170f
        const val PX_PER_MM = 2.25f
        const val FLIGHT_SEC = 0.35
        const val DART_TIME_LIMIT = 25.0
        val NUMBERS = intArrayOf(20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5)

        val INFO = GameInfo(
            id = "darts", title = "Darts", tagline = "Aim, hold steady, throw!",
            howTo = listOf(
                "Raise your throwing hand towards the TV: the sight follows it.",
                "Hold it steady over where you want the dart, then throw - a quick push towards the TV.",
                "The dart goes where you were aiming before the throw. A weak, floppy throw drops low.",
                "Four rounds of three darts. Treble = 3x, double = 2x, bull = 50. Highest total wins!",
            ),
            controls = listOf("Throwing hand" to "Aim", "Hold steady" to "Lock the aim", "Quick push forwards" to "Throw"),
            gentleNotes = "Reduced movement and seated: a short push throws, and the sight needs less hand movement.",
            music = MusicTrack.CALM, scoreUnit = "points", goal = "Highest total from 12 darts",
        )

        /** Score of a dart at ([x], [y]) mm from the bull: points and a label like "T20", "D5", "BULL". */
        fun score(x: Float, y: Float): Pair<Int, String> {
            val r = hypot(x, y)
            if (r <= 6.35f) return 50 to "BULL"
            if (r <= 15.9f) return 25 to "25"
            if (r > BOARD_MM) return 0 to "Miss"
            // angle clockwise from straight up; each number covers 18 degrees, 20 centred at the top
            var a = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            if (a < 0f) a += 360f
            val n = NUMBERS[(((a + 9f) / 18f).toInt()) % 20]
            return when {
                r in 99f..107f -> 3 * n to "T$n"
                r in 162f..170f -> 2 * n to "D$n"
                else -> n to "$n"
            }
        }
    }

    val totals = IntArray(2)
    val darts = ArrayList<Dart>()
    val thrown = IntArray(2)
    val aim = arrayOf(AimCursor(), AimCursor()).onEach { it.halfWidth = 480f }
    private val lastLabel = arrayOf("", "")
    private val lastAge = FloatArray(2) { 9f }
    private val clock = DoubleArray(2)
    private val doneAt = DoubleArray(2) { Double.NaN }

    fun round(k: Int) = (thrown[k] / DARTS_PER_ROUND).coerceAtMost(ROUNDS - 1)
    fun finished(k: Int) = thrown[k] >= ROUNDS * DARTS_PER_ROUND

    /** Board centre on screen (view units in the lane: 0, 0 = lane centre). */
    private val boardY = -10f

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            val inp = inputs[k]
            lastAge[k] += dt
            inp.gunHand = inp.dominant
            if (botDriven[k]) aim[k].set(botX[k], botY[k], playTime) else aim[k].update(inp, playTime, dt)
            botDriven[k] = false
            if (finished(k)) { if (doneAt[k].isNaN()) doneAt[k] = playTime; inp.events.clear(); continue }
            clock[k] += dt
            while (true) {
                val e = inp.events.firstOrNull { (it is GestureEvent.Punch && it.kind == PunchKind.STRAIGHT && it.hand == inp.dominant) || it is GestureEvent.Throw } ?: break
                inp.events.remove(e)
                val power = when (e) { is GestureEvent.Punch -> e.power; is GestureEvent.Throw -> e.power; else -> 0.6f }
                throwDart(k, e, power)
                if (finished(k)) break
            }
            if (clock[k] > DART_TIME_LIMIT) { thrown[k]++; clock[k] = 0.0; lastLabel[k] = "Time - 0"; lastAge[k] = 0f }
        }
    }

    /** Where the player was holding steady just before [t] (the throw itself moves the hand). */
    private fun steadyAim(k: Int, t: Double): Pair<Float, Float> {
        var probe = t - 0.15
        while (probe > t - 0.8) {
            val (x1, y1) = aim[k].at(probe)
            val (x0, y0) = aim[k].at(probe - 0.12)
            if (hypot(x1 - x0, y1 - y0) < 18f) return x1 to y1
            probe -= 0.04
        }
        return aim[k].at(t - 0.3)
    }

    private fun throwDart(k: Int, e: GestureEvent, power: Float) {
        val at = madeAt(e).coerceIn(playTime - 0.5, playTime)
        val (sx, sy) = steadyAim(k, at)
        // sight -> board mm; a floppy throw drops
        val drop = ((0.45f - power).coerceAtLeast(0f) / 0.45f) * 70f
        val mx = sx / PX_PER_MM
        val my = -(sy - boardY) / PX_PER_MM - drop
        val (pts, label) = score(mx, my)
        darts.add(Dart(k, round(k), mx, my, pts, label, playTime))
        thrown[k]++
        totals[k] += pts
        clock[k] = 0.0
        lastLabel[k] = if (pts == 0) "Miss" else if (label == "BULL") "BULLSEYE! 50" else "$label = $pts"
        lastAge[k] = -FLIGHT_SEC.toFloat()
        env.sfx(Sfx.SWOOSH, 0.6f, if (k == 0) -0.5f else 0.5f)
    }

    override val isOver: Boolean get() = (0..1).all { finished(it) } && darts.all { playTime >= it.thrownAt + FLIGHT_SEC + 1.0 }

    override fun result() = MatchResult(info.id, totals.copyOf(), winnerOf(totals[0], totals[1]), info.scoreUnit, listOf(
        "Best dart: ${best(0)} vs ${best(1)}",
        "Trebles: ${darts.count { it.player == 0 && it.label.startsWith("T") }} vs ${darts.count { it.player == 1 && it.label.startsWith("T") }}",
    ))

    private fun best(k: Int) = darts.filter { it.player == k }.maxByOrNull { it.score }?.let { "${it.label} (${it.score})" } ?: "-"

    // ------------------------------------------------------------------ computer thrower

    private val botX = FloatArray(2)
    private val botY = FloatArray(2)
    private val botDriven = BooleanArray(2)
    private val botThrowAt = DoubleArray(2) { -1.0 }
    private val botTarget = Array(2) { FloatArray(2) }

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: Rng) {
        botDriven[k] = true
        if (finished(k)) return
        if (botThrowAt[k] < 0) {
            // go for treble 20 (a weaker player plays safe at the middle of the board)
            val sd = lerp(40f, 11f, skill)
            val (tx, ty) = if (skill > 0.5f) 0f to 103f else 0f to 40f
            botTarget[k][0] = tx + rng.gaussian() * sd; botTarget[k][1] = ty + rng.gaussian() * sd
            botThrowAt[k] = playTime + lerp(2.6f, 1.4f, skill) * rng.range(0.8f, 1.3f)
        }
        val wantX = botTarget[k][0] * PX_PER_MM
        val wantY = boardY - botTarget[k][1] * PX_PER_MM
        botX[k] += (wantX - botX[k]) * 0.06f
        botY[k] += (wantY - botY[k]) * 0.06f
        if (playTime >= botThrowAt[k]) {
            inp.events.add(GestureEvent.Punch(0.0, inp.dominant, PunchKind.STRAIGHT, lerp(0.6f, 0.9f, skill), 0f, 0f))
            botThrowAt[k] = -1.0
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        for (k in 0..1) renderLane(g, k, inputs[k], time)
        Hud.divider(g)
        Hud.topBar(g, env, totals, "Darts", null, inputs)
    }

    private fun renderLane(g: Gfx, k: Int, inp: PlayerInput, time: Double) {
        val r = Rect(if (k == 0) 0f else g.width / 2f, 0f, if (k == 0) g.width / 2f else g.width, g.height)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        // pub wall: wood panelling, a lamp over the board
        g.gradientRect(r.left, 0f, r.right, g.height, 0xFF3E2A20.toInt(), 0xFF22160F.toInt())
        for (i in 0 until 8) g.line(r.left + i * r.width / 8f, 0f, r.left + i * r.width / 8f, g.height, 3f, 0x22000000)
        val cx = r.cx; val cy = 540f + boardY
        g.glow(cx, cy, 620f, 0x33FFE0B2)
        // board: black surround, segments, rings
        val R = BOARD_MM * PX_PER_MM
        g.fillCircle(cx, cy, R + 70f, 0xFF1B1B1B.toInt())
        for (i in 0 until 20) {
            val start = (-90f - 9f + i * 18f) * (PI.toFloat() / 180f)
            val sweep = 18f * (PI.toFloat() / 180f)
            val dark = i % 2 == 0
            val single = if (dark) 0xFF212121.toInt() else 0xFFF3E5C8.toInt()
            val ring = if (dark) 0xFFD32F2F.toInt() else 0xFF2E7D32.toInt()
            g.fillPie(cx, cy, R, start, sweep, ring)
            g.fillPie(cx, cy, 162f * PX_PER_MM, start, sweep, single)
            g.fillPie(cx, cy, 107f * PX_PER_MM, start, sweep, ring)
            g.fillPie(cx, cy, 99f * PX_PER_MM, start, sweep, single)
            val na = start + sweep / 2f
            g.text("${NUMBERS[i]}", cx + cos(na) * (R + 38f), cy + sin(na) * (R + 38f), 34f, Colors.WHITE)
        }
        g.fillCircle(cx, cy, 15.9f * PX_PER_MM, 0xFF2E7D32.toInt())
        g.fillCircle(cx, cy, 6.35f * PX_PER_MM, 0xFFD32F2F.toInt())
        g.strokeCircle(cx, cy, R, 2f, 0x66B0BEC5)
        // darts: this round's in the board; one flying in
        val rnd = round(k)
        for (d in darts) {
            if (d.player != k) continue
            val u = ((playTime - d.thrownAt) / FLIGHT_SEC).toFloat()
            val dx = cx + d.x * PX_PER_MM; val dy = cy - d.y * PX_PER_MM
            if (u < 1f) {
                // flying: comes in large from the front and shrinks onto the board
                val s = lerp(4f, 1f, u.coerceAtLeast(0f))
                drawDart(g, lerp(r.cx, dx, u), lerp(1000f, dy, u), s, env.color(k))
            } else if (d.round == rnd || (finished(k) && d.round == ROUNDS - 1)) {
                drawDart(g, dx, dy, 1f, env.color(k))
            }
        }
        // sight
        val a = aim[k]
        if (a.seen && !finished(k)) {
            val x = a.x + r.cx; val y = a.y + 540f
            val c = env.color(k)
            g.strokeCircle(x, y, 22f, 6f, Colors.withAlpha(Colors.BLACK, 0.6f))
            g.strokeCircle(x, y, 22f, 3f, c)
            g.line(x - 34f, y, x + 34f, y, 3f, c); g.line(x, y - 34f, x, y + 34f, 3f, c)
        }
        // HUD in the lane
        g.text(if (finished(k)) "Finished: ${totals[k]}" else "Round ${rnd + 1} of $ROUNDS - dart ${thrown[k] % DARTS_PER_ROUND + 1}", r.cx, 160f, 32f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 4f)
        if (lastAge[k] in 0f..1.6f && lastLabel[k].isNotEmpty()) g.text(lastLabel[k], r.cx, 980f, 64f, if (lastLabel[k].startsWith("Miss") || lastLabel[k].startsWith("Time")) Colors.BAD else Colors.WHITE, outline = Colors.BLACK, outlineWidth = 7f)
        else if (!finished(k) && !env.players[k].cpu) g.text("Hold steady... then push to throw", r.cx, 1000f, 30f, Colors.TEXT_DIM, outline = Colors.BLACK, outlineWidth = 3f)
        g.restore()
    }

    private fun drawDart(g: Gfx, x: Float, y: Float, s: Float, color: Int) {
        g.line(x, y, x + 10f * s, y + 26f * s, 5f * s, 0xFFB0BEC5.toInt())
        g.line(x + 10f * s, y + 26f * s, x + 16f * s, y + 44f * s, 7f * s, 0xFF455A64.toInt())
        com.clrg.core.render.Draw.tri(g, x + 12f * s, y + 40f * s, x + 26f * s, y + 58f * s, x + 4f * s, y + 56f * s, color)
        g.fillCircle(x, y, 3f * s, 0xFF263238.toInt())
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        g.gradientRect(r.left, r.top, r.right, r.bottom, 0xFF3E2A20.toInt(), 0xFF22160F.toInt())
        val cx = r.cx; val cy = r.cy
        val R = r.height * 0.42f
        for (i in 0 until 20) {
            val start = (-99f + i * 18f) * (PI.toFloat() / 180f)
            g.fillPie(cx, cy, R, start, 18f * (PI.toFloat() / 180f), if (i % 2 == 0) 0xFFD32F2F.toInt() else 0xFF2E7D32.toInt())
            g.fillPie(cx, cy, R * 0.92f, start, 18f * (PI.toFloat() / 180f), if (i % 2 == 0) 0xFF212121.toInt() else 0xFFF3E5C8.toInt())
        }
        g.fillCircle(cx, cy, R * 0.1f, 0xFFD32F2F.toInt())
        val phase = ((t % 1.8) / 1.8).toFloat()
        val u = ((phase - 0.4f) / 0.3f).coerceIn(0f, 1f)
        if (phase > 0.4f) drawDart(g, lerp(r.right, cx + 6f, u), lerp(r.bottom, cy - 4f, u), lerp(2.2f, 0.8f, u), env.color(0))
        g.text(if (phase < 0.4f) "hold steady..." else "throw!", r.cx, r.top + 26f, 28f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 4f)
    }
}
