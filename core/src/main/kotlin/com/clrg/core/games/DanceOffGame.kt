package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Songs
import com.clrg.core.audio.Sfx
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.LEFT
import com.clrg.core.gesture.PoseShape
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rect
import com.clrg.core.math.clamp
import com.clrg.core.math.clamp01
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 7. Elf Dance-Off - copy the elf's poses on the beat. Poses come from a small set that 2D tracking can tell apart
 * reliably (see [PoseShape]); similarity is normalised arm-angle matching and timing is judged against the music
 * beat. If either player uses reduced-movement or seated mode, both get the gentle pose set (same choreography).
 */
class DanceOffGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO

    class Cue(val beat: Float, val shape: PoseShape)
    enum class Grade(val label: String, val points: Int, val color: Int) {
        PERFECT("PERFECT!", 100, 0xFFFFD54F.toInt()), GREAT("Great!", 70, 0xFF69F0AE.toInt()),
        GOOD("Good", 40, 0xFF80D8FF.toInt()), MISS("Miss", 0, 0xFFFF8A80.toInt())
    }

    companion object {
        const val WINDOW_BEATS = 0.6f
        val INFO = GameInfo(
            id = "dance", title = "Elf Dance-Off", tagline = "Copy the elf - on the beat!",
            howTo = listOf(
                "Watch the elf and the pose cards sliding toward the middle.",
                "Strike the same pose with your arms as the card reaches NOW, on the beat.",
                "Your snow buddy copies you, so you can check your pose.",
                "Perfect poses on time score most. Highest score at the end of the song wins!",
            ),
            controls = listOf("Arm poses" to "Match the elf", "Hold it on the beat" to "Timing bonus"),
            gentleNotes = "Reduced movement / seated: a gentler pose set with nothing overhead and more tolerance.",
            music = MusicTrack.CALM, scoreUnit = "points", goal = "Highest dance score",
        )
    }

    private val bpm = Songs.dance.bpm.toFloat()
    private val spb = 60f / bpm
    private val gentle = env.players.any { it.mode != MovementMode.STANDARD }
    val shapes = if (gentle) PoseShape.GENTLE_SET else PoseShape.STANDARD_SET
    val cues = ArrayList<Cue>()
    val scores = IntArray(2)
    val combo = IntArray(2)
    val bestCombo = IntArray(2)
    val grades = Array(2) { IntArray(Grade.values().size) }
    private val best = FloatArray(2)
    private var judgeIndex = 0
    val lastGrade = arrayOfNulls<Grade>(2)
    private val lastGradeTime = FloatArray(2) { 99f }
    private val liveSim = FloatArray(2)
    private var screenW = 1920f
    private var endBeat = 0f
    private var lastBeatInt = -1

    private fun tolerance(k: Int) = PoseShape.toleranceFor(env.players[k].mode)

    override fun start() {
        val rng = env.rng(7)
        var last: PoseShape? = null
        fun pick(): PoseShape { var s: PoseShape; do { s = rng.pick(shapes) } while (s == last); last = s; return s }
        var b = 8f
        repeat(8) { cues.add(Cue(b, pick())); b += 4f }
        b -= 2f
        repeat(24) { b += 2f; cues.add(Cue(b, pick())) }
        endBeat = b + 4f
    }

    /** Beat of the music players are hearing (game clock minus audio output latency). */
    fun beat(): Float {
        val latency = env.audio?.outputLatencySec?.toFloat() ?: 0f
        return ((playTime.toFloat() - latency) / spb)
    }

    override fun onLiveChanged(live: Boolean) {
        val a = env.audio ?: return
        if (live) a.playMusic(MusicTrack.DANCE, restart = true, startBeat = (playTime / spb).toDouble()) else a.playMusic(MusicTrack.CALM)
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        val beat = beat()
        val bi = floor(beat).toInt()
        if (bi != lastBeatInt) { lastBeatInt = bi }
        for (k in 0..1) lastGradeTime[k] += dt
        if (judgeIndex >= cues.size) return
        val cue = cues[judgeIndex]
        for (k in 0..1) {
            val sim = if (inputs[k].tracked) cue.shape.similarity(inputs[k].avatar, tolerance(k)) else 0f
            liveSim[k] = sim
            val off = abs(beat - cue.beat)
            if (off <= WINDOW_BEATS) {
                val v = sim * (1f - 0.35f * off / WINDOW_BEATS)
                if (v > best[k]) best[k] = v
            }
        }
        if (beat > cue.beat + WINDOW_BEATS) {
            for (k in 0..1) {
                val g = when {
                    best[k] >= 0.8f -> Grade.PERFECT
                    best[k] >= 0.65f -> Grade.GREAT
                    best[k] >= 0.5f -> Grade.GOOD
                    else -> Grade.MISS
                }
                grades[k][g.ordinal]++
                combo[k] = if (g == Grade.MISS) 0 else combo[k] + 1
                bestCombo[k] = maxOf(bestCombo[k], combo[k])
                scores[k] += g.points + if (combo[k] >= 5 && g != Grade.MISS) 10 else 0
                lastGrade[k] = g
                lastGradeTime[k] = 0f
                val x = if (k == 0) screenW * 0.2f else screenW * 0.8f
                env.popups.add(g.label, x, 420f, g.color, if (g == Grade.PERFECT) 64f else 52f)
                if (g == Grade.PERFECT) { env.particles.burst(x, 520f, g.color, 18, kind = Particles.Kind.STAR, size = 12f); env.particles.ring(x, 520f, g.color, 130f) }
                best[k] = 0f
            }
            val any = (0..1).any { lastGrade[it] != Grade.MISS }
            env.sfx(if (any) Sfx.SPARKLE else Sfx.TICK, 0.5f)
            judgeIndex++
        }
    }

    override val isOver: Boolean get() = cues.isNotEmpty() && beat() >= endBeat

    // ---- CPU dancer: reads the pose card like a player, starts moving into it a little before the beat (with
    // timing error), moves its arms at a limited speed, and hits the shape with some angular error; now and
    // then it misses a pose entirely.
    override val cpuPosesAvatar = true
    private val botCue = IntArray(2) { -1 }
    private val botStart = FloatArray(2)
    private val botSkip = BooleanArray(2)
    private val botTarget = Array(2) { AvatarPose() }

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        inp.tracked = true
        inp.avatar.valid = true
        val cue = cues.getOrNull(judgeIndex) ?: return
        if (botCue[k] != judgeIndex) {
            botCue[k] = judgeIndex
            botStart[k] = cue.beat - com.clrg.core.math.lerp(0.8f, 0.5f, skill) + rng.gaussian() * com.clrg.core.math.lerp(0.45f, 0.12f, skill)
            botSkip[k] = rng.chance(com.clrg.core.math.lerp(0.1f, 0.01f, skill))
            val t = botTarget[k]
            cue.shape.toAvatar(t)
            val sd = Math.toRadians(com.clrg.core.math.lerp(26f, 7f, skill).toDouble()).toFloat()
            for (h in 0..1) { t.upper[h] += rng.gaussian() * sd; t.fore[h] += rng.gaussian() * sd }
        }
        if (botSkip[k] || beat() < botStart[k]) return
        val maxStep = com.clrg.core.math.lerp(5f, 10f, skill) * dt
        val t = botTarget[k]
        for (h in 0..1) {
            inp.avatar.upper[h] = approachAngle(inp.avatar.upper[h], t.upper[h], maxStep)
            inp.avatar.fore[h] = approachAngle(inp.avatar.fore[h], t.fore[h], maxStep)
        }
    }

    /** Moves angle [a] toward [b] along the shorter way by at most [step] radians. */
    private fun approachAngle(a: Float, b: Float, step: Float): Float {
        val twoPi = 2f * Math.PI.toFloat()
        var d = (b - a) % twoPi
        if (d > Math.PI) d -= twoPi
        if (d < -Math.PI) d += twoPi
        return if (abs(d) <= step) a + d else a + step * kotlin.math.sign(d)
    }

    override fun result() = MatchResult(info.id, scores.copyOf(), winnerOf(scores[0], scores[1]), info.scoreUnit,
        listOf("Perfect: ${grades[0][0]} vs ${grades[1][0]}", "Best combo: ${bestCombo[0]} vs ${bestCombo[1]}"))

    /** Elf pose: glides into each cue's shape during the beat before it. */
    private val elfPose = AvatarPose()
    private val tmpA = AvatarPose()
    private val tmpB = AvatarPose()
    private fun updateElf(beat: Float) {
        val next = cues.firstOrNull { it.beat >= beat - 0.5f }
        val prev = cues.lastOrNull { it.beat < beat - 0.5f }
        if (next == null) { (prev?.shape ?: PoseShape.T_POSE).toAvatar(elfPose); return }
        (prev?.shape ?: PoseShape.LOW_V).toAvatar(tmpA)
        next.shape.toAvatar(tmpB)
        val k = clamp01((beat - (next.beat - 1.2f)) / 0.9f)
        for (h in 0..1) {
            elfPose.upper[h] = lerpAngle(tmpA.upper[h], tmpB.upper[h], k)
            elfPose.fore[h] = lerpAngle(tmpA.fore[h], tmpB.fore[h], k)
        }
        elfPose.valid = true
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = b - a
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return a + d * t
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        val w = g.width
        val beat = if (playTime > 0) beat() else 0f
        val frac = beat - floor(beat)
        val pulse = if (env.settings.reducedEffects) 0.2f else (1f - frac).coerceIn(0f, 1f)
        g.gradientRect(0f, 0f, w, g.height, 0xFF2C1248.toInt(), 0xFF0B0716.toInt())
        // stage curtains
        for (side in 0..1) {
            val x0 = if (side == 0) 0f else w - 150f
            g.fillRectRadial(x0, 0f, x0 + 150f, 900f, if (side == 0) x0 + 150f else x0, 450f, 500f, 0xFFB71C1C.toInt(), 0xFF3E0710.toInt())
            for (f in 0..4) g.line(x0 + 15f + f * 30f, 0f, x0 + 15f + f * 30f, 900f, 4f, 0x33000000)
        }
        // mirror ball
        g.line(w / 2f, 0f, w / 2f, 60f, 3f, 0xFF90A4AE.toInt())
        g.fillCircleRadial(w / 2f, 92f, 34f, w / 2f - 10f, 80f, 48f, 0xFFECEFF1.toInt(), 0xFF546E7A.toInt())
        for (i in 0 until 6) {
            val a = (time * 1.4 + i * 1.05).toFloat()
            if (cos(a) > 0f) Draw.sparkle(g, w / 2f + sin(a) * 26f, 92f + (i - 2.5f) * 9f, 10f, 0xFFFFFFFF.toInt())
        }
        Draw.stringLights(g, 0f, w, 120f, time)
        // moving coloured spotlights that sweep with the beat
        if (!env.settings.reducedEffects) {
            val beamCols = intArrayOf(0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF40C4FF.toInt(), 0xFFE040FB.toInt())
            for (i in 0..3) {
                val x = w * (0.12f + i * 0.253f)
                val sweep = sin(time * 0.9 + i * 1.7).toFloat()
                val col = beamCols[(i + floor(beat).toInt()) % 4]
                Draw.spotBeam(g, x, 150f, x + sweep * w * 0.12f, 960f, 150f + 30f * pulse, Colors.withAlpha(col, 0.55f + 0.35f * pulse))
                g.fillCircleRadial(x, 150f, 18f, x, 154f, 22f, Colors.WHITE, col)
            }
        }
        // disco floor in perspective, tiles glow on the beat
        val rows = 4; val cols = 16
        val fy0 = 880f; val fy1 = 1080f
        g.gradientRect(0f, fy0, w, fy1, 0xFF1A0B2A.toInt(), 0xFF07040E.toInt())
        for (j in 0 until rows) for (i in 0 until cols) {
            val on = (i + j + floor(beat).toInt()) % 2 == 0
            val c = intArrayOf(0xFF7C4DFF.toInt(), 0xFFFF4081.toInt(), 0xFF00E5FF.toInt(), 0xFFFFD740.toInt())[(i + j) % 4]
            val y0 = fy0 + (fy1 - fy0) * j / rows; val y1 = fy0 + (fy1 - fy0) * (j + 1) / rows
            val s0 = 0.86f + 0.14f * j / rows; val s1 = 0.86f + 0.14f * (j + 1) / rows
            fun fx(t: Float, s: Float) = w / 2f + (t * w - w / 2f) * s
            val t0 = i.toFloat() / cols; val t1 = (i + 1f) / cols
            floorPts[0] = fx(t0, s0) + 2f; floorPts[1] = y0 + 2f; floorPts[2] = fx(t1, s0) - 2f; floorPts[3] = y0 + 2f
            floorPts[4] = fx(t1, s1) - 2f; floorPts[5] = y1 - 2f; floorPts[6] = fx(t0, s1) + 2f; floorPts[7] = y1 - 2f
            if (on) g.fillPolygonGradient(floorPts, 4, y0, y1, Colors.withAlpha(Colors.lighter(c, 0.3f), 0.45f + 0.4f * pulse), Colors.withAlpha(c, 0.25f + 0.3f * pulse))
            else g.fillPolygon(floorPts, 4, 0x26FFFFFF)
        }
        g.line(0f, fy0, w, fy0, 4f, 0x66FFD740)
        // timeline of upcoming poses
        val nowX = w / 2f
        val pxPerBeat = 125f
        g.fillRoundRect(40f, 170f, w - 40f, 330f, 26f, 0xAA0B0716.toInt())
        g.strokeRoundRect(40f, 170f, w - 40f, 330f, 26f, 2f, 0x44FFFFFF)
        for (c in cues) {
            val x = nowX + (c.beat - beat) * pxPerBeat
            if (x < -80f || x > w + 80f) continue
            val active = abs(c.beat - beat) <= WINDOW_BEATS
            val done = c.beat < beat - WINDOW_BEATS
            if (active) g.glow(x, 250f, 120f, 0x66B388FF)
            if (done) g.fillRoundRect(x - 62f, 186f, x + 62f, 314f, 18f, 0x44333333)
            else g.fillRoundRectGradient(x - 62f, 186f, x + 62f, 314f, 18f, if (active) 0xFF6A3FB0.toInt() else 0xFF3A2560.toInt(), if (active) 0xFF3A1F66.toInt() else 0xFF1E1236.toInt())
            drawShapeIcon(g, x, 262f, 100f, c.shape, if (done) 0x66FFFFFF else Colors.WHITE)
        }
        g.strokeRoundRect(nowX - 70f, 178f, nowX + 70f, 322f, 22f, 6f, Colors.withAlpha(Colors.GOLD, 0.6f + 0.4f * pulse))
        g.text("NOW", nowX, 352f, 30f, Colors.GOLD)
        val next = cues.getOrNull(judgeIndex)
        if (next != null) g.text(next.shape.name, nowX, 392f, 40f, Colors.WHITE)
        // beat counter
        for (i in 0..3) {
            val on = ((floor(beat).toInt() % 4) + 4) % 4 == i && beat >= 0
            g.fillCircle(nowX - 90f + i * 60f, 440f, if (on) 16f + 6f * pulse else 12f, if (on) Colors.GOLD else 0x55FFFFFF)
        }
        // elf
        updateElf(beat)
        Draw.elf(g, nowX, 960f, 470f, elfPose, time, pulse)
        // players
        for (k in 0..1) {
            val x = if (k == 0) w * 0.2f else w * 0.8f
            val inp = inputs[k]
            val expr = when (lastGrade[k]) { Grade.PERFECT, Grade.GREAT -> Expression.EXCITED; Grade.MISS -> Expression.OOPS; else -> Expression.HAPPY }
            Draw.buddy(g, x, 960f - pulse * 8f, 400f, env.color(k), if (inp.avatar.valid) inp.avatar else null, if (lastGradeTime[k] < 1f) expr else Expression.HAPPY, t = time)
            // live match meter
            val m = liveSim[k]
            g.fillRoundRect(x - 150f, 1000f, x + 150f, 1030f, 15f, 0xAA000000.toInt())
            g.fillRoundRect(x - 150f, 1000f, x - 150f + 300f * clamp(m, 0f, 1f), 1030f, 15f, if (m > 0.8f) Colors.GOLD else if (m > 0.5f) Colors.GOOD else env.color(k))
            g.text("pose match", x, 1055f, 24f, Colors.WHITE)
            if (combo[k] >= 3) g.text("Combo ${combo[k]}", x, 975f, 36f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 6f)
        }
        Hud.topBar(g, env, scores, "Elf Dance-Off", if (gentle) "Gentle pose set" else null, inputs)
    }

    private val floorPts = FloatArray(8)

    /** Small stick figure of a pose shape (for the cards). */
    fun drawShapeIcon(g: Gfx, cx: Float, cy: Float, size: Float, s: PoseShape, color: Int) {
        val u = size / 3.2f
        val sy = cy - u * 0.4f
        g.strokeCircle(cx, sy - u * 0.55f, u * 0.32f, 4f, color)
        g.line(cx, sy, cx, sy + u * 1.3f, 5f, color)
        g.line(cx - u * 0.35f, sy, cx + u * 0.35f, sy, 5f, color)
        fun arm(side: Int, upper: Float, fore: Float) {
            val x0 = cx + (if (side == LEFT) -0.35f else 0.35f) * u
            val ua = (upper * Math.PI / 180).toFloat(); val fa = (fore * Math.PI / 180).toFloat()
            val ex = x0 + cos(ua) * u * 0.62f; val ey = sy - sin(ua) * u * 0.62f
            val wx = ex + cos(fa) * u * 0.56f; val wy = ey - sin(fa) * u * 0.56f
            g.line(x0, sy, ex, ey, 5f, color); g.line(ex, ey, wx, wy, 5f, color)
            g.fillCircle(wx, wy, 6f, color)
        }
        arm(LEFT, s.leftUpper, s.leftFore)
        arm(RIGHT, s.rightUpper, s.rightFore)
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val idx = ((t / 1.6).toInt()) % shapes.size
        val p = AvatarPose()
        shapes[idx].toAvatar(p)
        Draw.elf(g, r.cx - r.width * 0.2f, r.bottom - 10f, r.height * 0.85f, p, t, 0f)
        Draw.buddy(g, r.cx + r.width * 0.22f, r.bottom - 10f, r.height * 0.8f, env.color(0), p, Expression.EXCITED, t = t)
        g.text(shapes[idx].name, r.cx, r.top + 30f, 36f, Colors.GOLD)
    }
}
