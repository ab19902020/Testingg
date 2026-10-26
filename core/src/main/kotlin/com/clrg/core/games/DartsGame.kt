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
import com.clrg.core.render.Draw
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
            for (d in darts) if (d.player == k && playTime >= d.thrownAt + FLIGHT_SEC && landedSound.add(d)) dartLands(d)
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

    private val landedSound = HashSet<Dart>()

    /** The dart arrives: a thud into the board, a clatter off it, a cheer for the bull or a treble twenty. */
    private fun dartLands(d: Dart) {
        val pan = if (d.player == 0) -0.4f else 0.4f
        if (d.score == 0) { env.sfx(Sfx.DART_MISS, 0.8f, pan); return }
        env.sfx(Sfx.DART_HIT, 1f, pan)
        when {
            d.label == "BULL" -> { env.sfx(Sfx.BULLSEYE, 0.7f, pan); env.sfx(Sfx.CROWD_CHEER, 0.6f) }
            d.score >= 40 || d.label == "25" -> env.sfx(Sfx.APPLAUSE, 0.45f)
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
        env.sfx(Sfx.SWOOSH, 0.45f, if (k == 0) -0.5f else 0.5f, 1.4f)
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
        // the cabinet and board (one cached picture, the same in both lanes)
        val cx = r.cx; val cy = 540f + boardY
        g.save(); g.translate(r.left, 0f)
        g.layer("darts.board", 0f, 0f, r.width, g.height, grain = 0.22f) { lg -> board(lg, r.width / 2f, cy, r.width, lg.height) }
        g.restore()
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
        // round and dart, in a pill in the lane's top corner, with a dart icon per dart left this round
        val info = if (finished(k)) "Finished: ${totals[k]}" else "Round ${rnd + 1} of $ROUNDS"
        val pillX = if (k == 0) r.left + 24f else r.right - 24f - 300f
        g.fillRoundRect(pillX, 140f, pillX + 300f, 200f, 30f, 0xCC0B1A36.toInt())
        g.text(info, pillX + 150f, 158f, 28f, Colors.GOLD)
        if (!finished(k)) {
            val left = DARTS_PER_ROUND - thrown[k] % DARTS_PER_ROUND
            for (j in 0 until DARTS_PER_ROUND) drawDart(g, pillX + 115f + j * 34f, 172f, 0.42f, if (j < left) env.color(k) else 0x44FFFFFF)
        }
        if (lastAge[k] in 0f..1.6f && lastLabel[k].isNotEmpty()) {
            val bad = lastLabel[k].startsWith("Miss") || lastLabel[k].startsWith("Time")
            val w = g.measureText(lastLabel[k], 60f) + 70f
            g.fillRoundRect(r.cx - w / 2f, 940f, r.cx + w / 2f, 1030f, 45f, 0xCC0B1A36.toInt())
            g.text(lastLabel[k], r.cx, 985f, 60f, if (bad) Colors.BAD else if (lastLabel[k].startsWith("BULL")) Colors.GOLD else Colors.WHITE)
        } else if (!finished(k) && !env.players[k].cpu) g.text("Hold steady... then push to throw", r.cx, 1040f, 28f, Colors.TEXT_DIM, outline = Colors.BLACK, outlineWidth = 3f)
        g.restore()
    }

    /** Pub wall, a wooden cabinet with grain and a bevel, the board with sisal texture, its wire spider and numbers. */
    private fun board(g: Gfx, cx: Float, cy: Float, w: Float, h: Float) {
        val R = BOARD_MM * PX_PER_MM
        g.gradientRect(0f, 0f, w, h * 0.72f, 0xFF1F3A2E.toInt(), 0xFF152A21.toInt())
        g.gradientRect(0f, h * 0.72f, w, h, 0xFF4A3022.toInt(), 0xFF2A1A11.toInt())
        g.glow(cx, cy - R * 0.3f, R * 2.1f, 0x44FFE0B2)
        // the cabinet: shadow, walnut frame with grain, a bevelled inner edge, dark felt behind the board
        val cl = cx - R - 96f; val ct = cy - R - 96f; val cr = cx + R + 96f; val cb = cy + R + 96f
        g.fillRoundRect(cl + 14f, ct + 22f, cr + 14f, cb + 22f, 26f, 0x66000000)
        g.fillRoundRectGradient(cl, ct, cr, cb, 26f, 0xFF7A5038.toInt(), 0xFF3B2519.toInt())
        for (i in 0 until 60) {
            val y = ct + 8f + i * (cb - ct - 16f) / 60f
            val pp = FloatArray(20)
            for (j in 0 until 10) { pp[j * 2] = cl + 6f + j * (cr - cl - 12f) / 9f; pp[j * 2 + 1] = y + 3f * sin(j * 0.9f + i * 1.7f) + 2f * sin(j * 2.3f + i) }
            g.polyline(pp, 10, 1.3f, if (i % 4 == 0) 0x2A000000 else 0x12FFE0C0)
        }
        g.strokeRoundRect(cl, ct, cr, cb, 26f, 6f, 0xFF8D6E63.toInt())
        g.strokeRoundRect(cl + 3f, ct + 3f, cr - 3f, cb - 3f, 24f, 2f, 0x44FFE0C0)
        g.fillRoundRect(cl + 16f, ct + 16f, cr - 16f, cb - 16f, 18f, 0xFF2A1A11.toInt())
        g.fillRoundRectGradient(cl + 20f, ct + 20f, cr - 20f, cb - 20f, 16f, 0xFF20301F.toInt(), 0xFF101810.toInt())
        // the board: black surround, segments, rings
        g.fillCircle(cx + 8f, cy + 14f, R + 72f, 0x66000000)
        g.fillCircleRadial(cx, cy, R + 70f, cx - R * 0.3f, cy - R * 0.4f, R * 1.6f, 0xFF2B2B2B.toInt(), 0xFF111111.toInt())
        for (i in 0 until 20) {
            val start = (-90f - 9f + i * 18f) * (PI.toFloat() / 180f)
            val sweep = 18f * (PI.toFloat() / 180f)
            val dark = i % 2 == 0
            val single = if (dark) 0xFF1E1E1E.toInt() else 0xFFF0E1C0.toInt()
            val ring = if (dark) 0xFFC62828.toInt() else 0xFF2E7D32.toInt()
            g.fillPie(cx, cy, R, start, sweep, ring)
            g.fillPie(cx, cy, 162f * PX_PER_MM, start, sweep, single)
            g.fillPie(cx, cy, 107f * PX_PER_MM, start, sweep, ring)
            g.fillPie(cx, cy, 99f * PX_PER_MM, start, sweep, single)
        }
        g.fillCircle(cx, cy, 15.9f * PX_PER_MM, 0xFF2E7D32.toInt())
        g.fillCircle(cx, cy, 6.35f * PX_PER_MM, 0xFFC62828.toInt())
        // sisal: thousands of short fibres, slightly lighter and darker, and wear around the favourite numbers
        for (i in 0 until 2600) {
            val a = Draw.hash01(i * 7 + 1) * 2f * PI.toFloat()
            val rr = 16f * PX_PER_MM + Draw.hash01(i * 13 + 3) * (R - 16f * PX_PER_MM)
            val x = cx + cos(a) * rr; val y = cy + sin(a) * rr
            val l = 3f + 4f * Draw.hash01(i)
            g.line(x, y, x + cos(a) * l, y + sin(a) * l, 1f, if (i % 2 == 0) 0x16FFFFFF else 0x18000000)
        }
        for (i in 0 until 120) {
            val a = (-90f + (Draw.hash01(i * 3) - 0.5f) * 30f) * (PI.toFloat() / 180f)
            val rr = 99f * PX_PER_MM + Draw.hash01(i * 5 + 2) * 70f * PX_PER_MM
            g.fillCircle(cx + cos(a) * rr, cy + sin(a) * rr, 1.6f, 0x33000000)
        }
        // the wire spider: segment lines and ring wires catching the light
        for (i in 0 until 20) {
            val a = (-90f - 9f + i * 18f) * (PI.toFloat() / 180f)
            g.line(cx + cos(a) * 15.9f * PX_PER_MM + 1f, cy + sin(a) * 15.9f * PX_PER_MM + 1.5f, cx + cos(a) * R + 1f, cy + sin(a) * R + 1.5f, 1.8f, 0x55000000)
            g.line(cx + cos(a) * 15.9f * PX_PER_MM, cy + sin(a) * 15.9f * PX_PER_MM, cx + cos(a) * R, cy + sin(a) * R, 1.6f, 0xCCD7DDE2.toInt())
        }
        for (rr in floatArrayOf(6.35f, 15.9f, 99f, 107f, 162f, BOARD_MM)) {
            g.strokeCircle(cx + 1f, cy + 1.5f, rr * PX_PER_MM, 1.8f, 0x55000000)
            g.strokeCircle(cx, cy, rr * PX_PER_MM, 1.6f, 0xCCD7DDE2.toInt())
        }
        // the number ring: a wire band with silver numbers
        g.strokeCircle(cx, cy, R + 60f, 2.5f, 0x88B0BEC5.toInt())
        for (i in 0 until 20) {
            val na = (-90f + i * 18f) * (PI.toFloat() / 180f)
            g.text("${NUMBERS[i]}", cx + cos(na) * (R + 36f), cy + sin(na) * (R + 36f), 36f, 0xFFECEFF1.toInt(), outline = 0xFF111111.toInt(), outlineWidth = 4f)
        }
        // the light from above: a sheen over the top of the board, darker below
        g.fillCircleRadial(cx, cy, R + 70f, cx, cy - R * 0.9f, R * 1.4f, 0x2AFFFFFF, 0x00FFFFFF)
        g.fillCircleRadial(cx, cy, R + 70f, cx, cy + R * 1.2f, R * 1.3f, 0x30000000, 0x00000000)
        // brass lamp over the cabinet
        g.fillRoundRectGradient(cx - 120f, ct - 26f, cx + 120f, ct + 8f, 14f, 0xFFE6C77A.toInt(), 0xFF8C6A2A.toInt())
        g.line(cx - 110f, ct - 20f, cx + 110f, ct - 20f, 2f, 0x66FFFFFF)
        g.setBlend(com.clrg.core.render.Blend.ADD)
        g.glow(cx, ct + 10f, 160f, 0x44FFE6B0)
        g.setBlend(com.clrg.core.render.Blend.NORMAL)
    }

    private fun drawDart(g: Gfx, x: Float, y: Float, s: Float, color: Int) {
        // shadow on the board
        g.line(x + 6f * s, y + 6f * s, x + 22f * s, y + 54f * s, 6f * s, 0x44000000)
        // steel point, a knurled tungsten barrel, the shaft and a flight in the player's colour
        g.line(x, y, x + 5f * s, y + 13f * s, 2.5f * s, 0xFFCFD8DC.toInt())
        g.line(x + 5f * s, y + 13f * s, x + 10f * s, y + 28f * s, 6f * s, 0xFF78909C.toInt())
        for (j in 0 until 4) { val u = j / 4f; g.line(x + (5f + 5f * u) * s - 3f * s, y + (13f + 15f * u) * s + 1f * s, x + (5f + 5f * u) * s + 3f * s, y + (13f + 15f * u) * s - 1f * s, 1f * s, 0x66263238) }
        g.line(x + 4.5f * s, y + 14f * s, x + 9f * s, y + 27f * s, 1.5f * s, 0x99FFFFFF.toInt())
        g.line(x + 10f * s, y + 28f * s, x + 16f * s, y + 44f * s, 4f * s, 0xFF263238.toInt())
        Draw.tri(g, x + 12f * s, y + 38f * s, x + 28f * s, y + 60f * s, x + 4f * s, y + 58f * s, color)
        Draw.tri(g, x + 12f * s, y + 38f * s, x + 16f * s, y + 59f * s, x + 4f * s, y + 58f * s, Colors.darker(color, 0.7f))
        g.fillCircle(x, y, 2.5f * s, 0xFF263238.toInt())
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
