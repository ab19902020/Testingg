package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.CpuTraits
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.LEFT
import com.clrg.core.gesture.PunchKind
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.math.clamp01
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.render.Prop
import com.clrg.core.render.SportsDraw
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * Boxing - 1v1, split screen. Each player sees their opponent face on (the opponent's boxer copies their real arms)
 * with their own gloves in front of them. Throw real punches: a jab or cross straight at the TV, a hook round from
 * the side, an uppercut from below. Fists up by your face blocks punches to the head; lean to the side or duck to
 * slip them. Punching wears you out a little, so wild flailing hits softer. Three rounds; three knockdowns is a
 * knockout, otherwise the most points (damage landed) wins.
 */
class BoxingGame(env: GameEnv) : MiniGame(env) {
    override val info = INFO
    override val cpuPosesAvatar = true

    /** A punch on its way: lands at [impactAt] (game time). */
    class Flying(val from: Int, val kind: PunchKind, val hand: Int, val power: Float, val head: Boolean, val thrownAt: Double, val impactAt: Double, val strength: Float = 1f)

    enum class Outcome { HIT, BLOCKED, SLIPPED }

    companion object {
        const val ROUNDS = 3
        const val ROUND_SEC = 30.0
        const val BREAK_SEC = 4.0
        const val MAX_HP = 100f
        const val GETUP_SEC = 3.0
        const val KNOCKDOWNS_TO_KO = 3
        /** Time from throwing a punch to it landing (s). */
        fun travel(kind: PunchKind) = when (kind) { PunchKind.STRAIGHT -> 0.12; PunchKind.HOOK -> 0.17; PunchKind.UPPERCUT -> 0.19 }
        fun damage(kind: PunchKind) = when (kind) { PunchKind.STRAIGHT -> 8f; PunchKind.HOOK -> 11f; PunchKind.UPPERCUT -> 13f }
        fun cost(kind: PunchKind) = when (kind) { PunchKind.STRAIGHT -> 10f; PunchKind.HOOK -> 14f; PunchKind.UPPERCUT -> 16f }

        val INFO = GameInfo(
            id = "boxing", title = "Boxing", tagline = "Jab, hook, uppercut - and keep your guard up!",
            howTo = listOf(
                "Throw real punches at the TV: straight jabs, hooks round from the side, uppercuts from below.",
                "Fists up by your face = guard: punches to the head are blocked. Body punches still get through.",
                "Lean left or right to slip a punch, or duck under it. Ducking doesn't beat an uppercut!",
                "Punching tires you out - wild flailing hits softer. Three rounds; three knockdowns is a KO.",
            ),
            controls = listOf("Punch at the TV" to "Jab / cross", "Swing round from the side" to "Hook", "Drive up from below" to "Uppercut", "Fists up by your face" to "Block", "Lean / duck" to "Slip"),
            gentleNotes = "Reduced movement and seated: shorter, slower punches count, and leaning a little slips a punch.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "Knock out or outpoint",
        )
    }

    val hp = FloatArray(2) { MAX_HP }
    val stamina = FloatArray(2) { 100f }
    val points = IntArray(2)
    val knockdowns = IntArray(2)
    val landed = IntArray(2)
    val blocked = IntArray(2)
    val slipped = IntArray(2)
    val thrown = IntArray(2)
    val flying = ArrayList<Flying>()
    /** When a downed boxer gets back up (game time), NaN = standing. */
    val downUntil = DoubleArray(2) { Double.NaN }
    var koWinner = -1
        private set
    private var endAt = Double.NaN
    private val hitFlash = FloatArray(2) { 9f }
    private val punchAnim = Array(2) { FloatArray(2) { 9f } }
    private val punchKind = Array(2) { arrayOf(PunchKind.STRAIGHT, PunchKind.STRAIGHT) }
    private val lastOutcome = arrayOfNulls<Outcome>(2)
    private val outcomeAge = FloatArray(2) { 9f }
    private var lastRound = -1
    private var screenW = 1920f
    val total = ROUNDS * ROUND_SEC + (ROUNDS - 1) * BREAK_SEC

    fun round(): Int = (playTime / (ROUND_SEC + BREAK_SEC)).toInt().coerceAtMost(ROUNDS - 1)
    fun inBreak(): Boolean = (playTime % (ROUND_SEC + BREAK_SEC)) > ROUND_SEC && round() < ROUNDS - 1
    private fun down(k: Int) = !downUntil[k].isNaN()

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        if (!endAt.isNaN()) return
        val r = round()
        if (r != lastRound) { lastRound = r; env.sfx(Sfx.RING) }
        for (k in 0..1) {
            hitFlash[k] += dt; outcomeAge[k] += dt
            for (h in 0..1) punchAnim[k][h] += dt
            // stamina comes back quickly when you stop throwing
            stamina[k] = (stamina[k] + dt * (if (inputs[k].guard) 24f else 18f)).coerceAtMost(100f)
            if (down(k) && playTime >= downUntil[k]) { downUntil[k] = Double.NaN; hp[k] = MAX_HP * 0.6f; env.sfx(Sfx.CROWD_CHEER, 0.55f) }
        }
        // punches thrown
        for (k in 0..1) {
            val inp = inputs[k]
            while (true) {
                val e = inp.take<GestureEvent.Punch>() ?: break
                if (inBreak() || down(k) || down(1 - k)) continue
                throwPunch(k, e, inp)
            }
        }
        // punches landing (a knockdown clears the rest)
        val due = flying.filter { playTime >= it.impactAt }
        flying.removeAll(due)
        for (f in due) resolve(f, inputs[1 - f.from])
        if (playTime >= total && koWinner < 0) endAt = playTime
    }

    private fun throwPunch(k: Int, e: GestureEvent.Punch, inp: PlayerInput) {
        val h = e.hand.coerceIn(0, 1)
        val made = madeAt(e)
        // tired arms: each punch costs energy, and a punch thrown with little energy left is weaker
        val strength = 0.4f + 0.6f * clamp01(stamina[k] / 70f)
        stamina[k] = (stamina[k] - cost(e.kind)).coerceAtLeast(0f)
        val power = e.power
        // body or head: where the fist ended up (below the chest is a body shot)
        val head = e.y > -0.25f || e.kind == PunchKind.UPPERCUT
        thrown[k]++
        punchAnim[k][h] = 0f; punchKind[k][h] = e.kind
        // the punch lands a short time after it was made, but never before now
        flying.add(Flying(k, e.kind, h, power, head, made, maxOf(playTime, made + travel(e.kind)), strength))
        env.sfx(Sfx.SWOOSH, 0.4f, if (k == 0) -0.4f else 0.4f)
    }

    /** What the defender does about it: guard (head shots), slip by leaning away, duck (not uppercuts). */
    fun outcome(f: Flying, def: PlayerInput, defIndex: Int): Outcome {
        val gentle = env.players[defIndex].mode != MovementMode.STANDARD
        val leanNeeded = if (gentle) 0.22f else 0.35f
        val ducking = def.avatar.rise < (if (gentle) -0.15f else -0.25f)
        // seen from the defender, a right hand comes from their left: lean away from it (either way slips a straight)
        val comesFrom = if (f.hand == RIGHT) -1f else 1f
        val leanAway = when (f.kind) {
            PunchKind.STRAIGHT -> abs(def.lean) > leanNeeded
            PunchKind.HOOK -> def.lean * comesFrom > leanNeeded
            PunchKind.UPPERCUT -> false
        }
        return when {
            f.head && def.guard -> Outcome.BLOCKED
            f.head && (leanAway || (ducking && f.kind != PunchKind.UPPERCUT)) -> Outcome.SLIPPED
            else -> Outcome.HIT
        }
    }

    private fun resolve(f: Flying, def: PlayerInput) {
        val d = 1 - f.from
        if (down(d) || down(f.from)) return
        val o = outcome(f, def, d)
        lastOutcome[d] = o; outcomeAge[d] = 0f
        val (px, py) = faceOf(f.from)
        when (o) {
            Outcome.BLOCKED -> {
                blocked[d]++
                hp[d] -= damage(f.kind) * 0.12f
                env.sfx(Sfx.BLOCK, 0.9f, pan(d))
                env.popups.add("BLOCKED", px, py - 120f, Colors.ICE, 46f)
            }
            Outcome.SLIPPED -> {
                slipped[d]++
                env.sfx(Sfx.SWOOSH, 0.8f, pan(d))
                env.sfx(Sfx.CROWD_OOH, 0.3f, pan(d))
                env.popups.add("SLIPPED!", px, py - 120f, Colors.GOOD, 46f)
            }
            Outcome.HIT -> {
                val dmg = damage(f.kind) * f.strength * (0.55f + 0.45f * f.power) * (if (f.head) 1f else 0.75f) * (if (f.kind == PunchKind.UPPERCUT && def.avatar.rise < -0.2f) 1.5f else 1f)
                hp[d] -= dmg
                points[f.from] += dmg.toInt().coerceAtLeast(1)
                landed[f.from]++
                hitFlash[d] = 0f
                env.sfx(if (f.kind == PunchKind.STRAIGHT) Sfx.PUNCH else Sfx.PUNCH_HEAVY, 1f, pan(d))
                if (f.kind == PunchKind.UPPERCUT && f.head) env.sfx(Sfx.CROWD_OOH, 0.35f, pan(d))
                env.shake.kick(if (f.kind == PunchKind.STRAIGHT) 6f else 10f)
                val label = when (f.kind) { PunchKind.STRAIGHT -> if (f.head) "JAB!" else "BODY!"; PunchKind.HOOK -> "HOOK!"; PunchKind.UPPERCUT -> "UPPERCUT!" }
                env.popups.add(label, px, py - 120f, env.color(f.from), 58f)
                env.particles.burst(px, py - 40f, Colors.WHITE, 14, speed = 300f, kind = Particles.Kind.STAR, size = 10f)
            }
        }
        if (hp[d] <= 0f) knockDown(d)
    }

    private fun knockDown(d: Int) {
        hp[d] = 0f
        knockdowns[d]++
        flying.clear()
        env.sfx(Sfx.KNOCKDOWN, 1f, pan(d))
        env.sfx(Sfx.CROWD_CHEER, 0.9f)
        if (knockdowns[d] >= KNOCKDOWNS_TO_KO) {
            env.sfx(Sfx.RING, 0.9f)
            koWinner = 1 - d
            endAt = playTime
            points[1 - d] += 30
            env.popups.add("K.O.!", screenW / 2f, 420f, env.color(1 - d), 120f, dur = 2.5f)
        } else {
            downUntil[d] = playTime + GETUP_SEC
            points[1 - d] += 15
            env.popups.add("KNOCKDOWN!", screenW / 2f, 420f, env.color(1 - d), 80f, dur = 1.8f)
        }
    }

    private fun pan(k: Int) = if (k == 0) -0.5f else 0.5f

    override val isOver: Boolean get() = !endAt.isNaN() && playTime - endAt > (if (koWinner >= 0) 2.5 else 0.5)

    override fun result(): MatchResult {
        val winner = if (koWinner >= 0) koWinner else winnerOf(points[0], points[1])
        return MatchResult(info.id, points.copyOf(), winner, info.scoreUnit, listOfNotNull(
            if (koWinner >= 0) "Knockout by ${env.name(koWinner)}!" else null,
            "Punches landed: ${landed[0]} vs ${landed[1]}",
            "Blocked: ${blocked[0]} vs ${blocked[1]}   Slipped: ${slipped[0]} vs ${slipped[1]}",
            "Knockdowns suffered: ${knockdowns[0]} vs ${knockdowns[1]}",
        ))
    }

    override fun status(): String? = when {
        down(0) || down(1) -> "Count: ${(downUntil[if (down(0)) 0 else 1] - playTime).toInt() + 1}"
        inBreak() -> "Round ${round() + 2} next"
        else -> null
    }

    // ------------------------------------------------------------------ computer boxer

    private val botNext = DoubleArray(2) { 1.5 }
    private val botGuardUntil = DoubleArray(2)
    private val botLean = FloatArray(2)
    private val botLeanUntil = DoubleArray(2)

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: Rng) {
        val o = 1 - k
        inp.tracked = true
        // a skilled boxer keeps the guard up more of the time and slips more
        if (playTime >= botGuardUntil[k]) {
            inp.guard = rng.chance(lerp(0.25f, 0.6f, skill))
            botGuardUntil[k] = playTime + rng.range(0.4f, 1.2f)
        }
        if (playTime >= botLeanUntil[k]) {
            botLean[k] = if (rng.chance(lerp(0.08f, 0.3f, skill))) (if (rng.chance(0.5f)) 0.6f else -0.6f) else 0f
            botLeanUntil[k] = playTime + rng.range(0.3f, 0.7f)
        }
        inp.lean += (botLean[k] - inp.lean) * 0.08f
        // punch when ready: rate and choice depend on skill; a tired bot waits
        if (playTime >= botNext[k] && !inBreak() && !down(k) && !down(o) && stamina[k] > 25f) {
            val kind = when (rng.nextInt(10)) { in 0..5 -> PunchKind.STRAIGHT; in 6..8 -> PunchKind.HOOK; else -> PunchKind.UPPERCUT }
            val h = if (rng.chance(0.5f)) LEFT else RIGHT
            val head = rng.chance(0.75f)
            inp.events.add(GestureEvent.Punch(0.0, h, kind, rng.range(0.4f, 1f) * lerp(0.7f, 1f, skill), if (h == RIGHT) 0.1f else -0.1f, if (head) 0.1f else -0.45f))
            botNext[k] = playTime + lerp(1.6f, 0.75f, skill) * rng.range(0.7f, 1.4f) + CpuTraits.reaction(skill) * 0.3f
        }
        poseBot(k, inp)
    }

    /** The computer boxer's arms: guard or a relaxed stance, with the punching arm extended for a moment. */
    private fun poseBot(k: Int, inp: PlayerInput) {
        val p = inp.avatar
        p.valid = true
        for (h in 0..1) {
            val side = if (h == RIGHT) 1f else -1f
            val a = punchAnim[k][h]
            val ext = if (a < 0.25f) sin(a / 0.25f * Math.PI.toFloat()) else 0f
            val guardUp = if (inp.guard) 1f else 0.6f
            // upper arm down and out, forearm up in front of the face; extending raises the upper arm forwards
            val baseUp = (-Math.PI / 2).toFloat() + side * 0.5f
            val baseFore = (Math.PI / 2).toFloat() + side * 0.35f * guardUp
            p.upper[h] = lerp(baseUp, if (h == RIGHT) -0.1f else (Math.PI.toFloat() + 0.1f), ext * 0.6f)
            p.fore[h] = lerp(baseFore, if (h == RIGHT) 0.2f else (Math.PI.toFloat() - 0.2f), ext)
        }
        p.rise = 0f
    }

    // ------------------------------------------------------------------ drawing

    /** Where player [k]'s boxer's face is drawn in the *other* player's half (for popups). */
    private fun faceOf(k: Int): Pair<Float, Float> {
        val half = 1 - k
        val cx = if (half == 0) screenW * 0.25f else screenW * 0.75f
        return cx to 470f
    }

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) renderHalf(g, k, inputs, time)
        Hud.divider(g)
        val left = (ROUND_SEC - (playTime % (ROUND_SEC + BREAK_SEC))).coerceAtLeast(0.0)
        Hud.topBar(g, env, points, if (inBreak()) "Break" else Hud.formatTime(left), "Round ${round() + 1} of $ROUNDS", inputs)
        if (inBreak()) Hud.banner(g, "Round ${round() + 2} - get ready!", 560f, Colors.GOLD, 70f)
    }

    /** Player [k]'s half: their opponent face on, their own gloves in front. */
    private fun renderHalf(g: Gfx, k: Int, inputs: Array<PlayerInput>, time: Double) {
        val o = 1 - k
        val r = Rect(if (k == 0) 0f else g.width / 2f, 0f, if (k == 0) g.width / 2f else g.width, g.height)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        // arena: dark crowd, flashes, spotlight, ropes, canvas
        g.gradientRect(r.left, 0f, r.right, 700f, 0xFF0A0F1E.toInt(), 0xFF1A2238.toInt())
        SportsDraw.crowd(g, r.left, r.right, 300f, 560f, time + k, rows = 4)
        g.fillRect(r.left, 300f, r.right, 560f, 0x88000000.toInt())
        val rng = Rng((time * 3).toLong() + k * 999)
        repeat(3) { if (rng.chance(0.35f)) g.glow(r.left + rng.nextFloat() * r.width, 320f + rng.nextFloat() * 200f, 30f, 0xCCFFFFFF.toInt()) }
        val floorTop = 640f
        g.gradientRect(r.left, floorTop, r.right, r.bottom, 0xFF90A4AE.toInt(), 0xFF5F7480.toInt())
        Draw.lightPool(g, r.cx, 820f, r.width * 0.45f, 120f, 0x44FFFFFF)
        for ((i, c) in listOf(0xFFE53935.toInt(), Colors.WHITE, 0xFF1E88E5.toInt()).withIndex()) {
            val y = 470f + i * 55f
            g.line(r.left, y, r.right, y, 7f, c)
        }
        g.fillRect(r.left + 30f, 420f, r.left + 60f, 700f, 0xFFB0BEC5.toInt())
        g.fillRect(r.right - 60f, 420f, r.right - 30f, 700f, 0xFFB0BEC5.toInt())
        // the opponent, facing this player: their own (mirror-view) pose flipped left to right
        val oInp = inputs[o]
        val pose = if (oInp.avatar.valid) oInp.avatar else idlePose
        val flinch = if (hitFlash[o] < 0.3f) (1f - hitFlash[o] / 0.3f) else 0f
        val leanX = -oInp.lean * 90f
        val bx = r.cx + leanX
        val by = 1010f + flinch * 10f
        val expr = when { hitFlash[o] < 0.5f -> Expression.OOPS; down(o) -> Expression.OOPS; lastOutcome[o] == Outcome.SLIPPED && outcomeAge[o] < 0.6f -> Expression.EXCITED; else -> Expression.FOCUSED }
        g.save()
        if (down(o)) {
            // knocked down: lying on the canvas
            g.translate(bx, by - 40f); g.rotate(1.45f); g.translate(-bx, -(by - 40f))
        } else {
            g.translate(bx, by); g.rotate(-flinch * 0.12f); g.translate(-bx, -by)
        }
        g.translate(bx, 0f); g.scale(-1f, 1f); g.translate(-bx, 0f)
        Draw.athlete(g, bx, by, 700f, env.color(o), pose, expr, Prop.GLOVES, t = time + o, look = o * 3 + 1)
        g.restore()
        if (down(o)) {
            val count = (GETUP_SEC - (downUntil[o] - playTime)).toInt() + 1
            g.text("$count...", r.cx, 380f, 110f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 10f)
        }
        // the opponent's punches coming at this player: a glove growing towards the camera
        for (f in flying) {
            if (f.from != o) continue
            val u = clamp01(((playTime - f.thrownAt) / (f.impactAt - f.thrownAt)).toFloat())
            val sideX = if (f.hand == RIGHT) -1f else 1f // their right hand is on our left
            val gx = r.cx + sideX * lerp(110f, 40f, u) + leanX
            val gy = lerp(520f, 600f, u) + if (f.kind == PunchKind.UPPERCUT) lerp(140f, -60f, u) else 0f
            SportsDraw.glove(g, gx, gy, lerp(130f, 420f, u * u), env.color(o))
        }
        // this player being hit: red flash
        if (hitFlash[k] < 0.35f) g.fillRect(r.left, r.top, r.right, r.bottom, Colors.withAlpha(0xFFFF1744.toInt(), 0.35f * (1f - hitFlash[k] / 0.35f)))
        // own gloves: follow the hands; a punch flies forwards (smaller) for a moment
        val me = inputs[k]
        val cpu = env.players[k].cpu
        for (h in 0..1) {
            val side = if (h == 0) -1f else 1f
            val hx = if (me.handVisible[h] && !cpu) me.handX[h] else side * 0.18f
            val hy = if (me.handVisible[h] && !cpu) me.handY[h] else if (me.guard) 0.25f else 0.05f
            var gx = r.cx + side * r.width * 0.12f + hx * r.width * 0.55f
            var gy = 1000f - (hy + 0.3f) * 380f
            var size = 240f
            val a = punchAnim[k][h]
            if (a < 0.3f) {
                val e = sin(a / 0.3f * Math.PI.toFloat())
                gx = lerp(gx, r.cx + (if (h == 0) -60f else 60f), e)
                gy = lerp(gy, 560f, e)
                size = lerp(240f, 120f, e)
            }
            SportsDraw.glove(g, gx, gy.coerceIn(560f, 1120f), size, env.color(k), if (h == 0) 0.3f else -0.3f)
        }
        // health and stamina bars for both, this player's at the bottom
        bar(g, Rect(r.left + 40f, 150f, r.right - 40f, 182f), hp[o] / MAX_HP, env.color(o), "${env.name(o)}  ${"●".repeat(knockdowns[o])}")
        bar(g, Rect(r.left + 40f, r.bottom - 64f, r.left + r.width * 0.62f, r.bottom - 34f), hp[k] / MAX_HP, env.color(k), "${env.name(k)}  ${"●".repeat(knockdowns[k])}")
        bar(g, Rect(r.left + r.width * 0.66f, r.bottom - 64f, r.right - 40f, r.bottom - 34f), stamina[k] / 100f, Colors.GOLD, "energy")
        if (me.guard && !env.players[k].cpu) g.text("GUARD", r.left + 110f, r.bottom - 110f, 34f, Colors.ICE, outline = Colors.BLACK, outlineWidth = 4f)
        if (stamina[k] < 30f && !env.players[k].cpu) g.text("TIRED - take a breath", r.right - r.width * 0.2f, r.bottom - 110f, 30f, Colors.WARN, outline = Colors.BLACK, outlineWidth = 4f)
        g.restore()
    }

    private fun bar(g: Gfx, r: Rect, frac: Float, color: Int, label: String) {
        g.fillRoundRect(r.left, r.top, r.right, r.bottom, r.height / 2f, 0xAA000000.toInt())
        val w = (r.width - 6f) * clamp01(frac)
        if (w > 2f) g.fillRoundRectGradient(r.left + 3f, r.top + 3f, r.left + 3f + w, r.bottom - 3f, (r.height - 6f) / 2f, Colors.lighter(color, 0.3f), Colors.darker(color, 0.75f))
        g.text(label, r.left + 16f, r.cy, r.height * 0.7f, Colors.WHITE, com.clrg.core.render.Align.LEFT, outline = Colors.BLACK, outlineWidth = 3f)
    }

    private val idlePose = AvatarPose().apply {
        valid = true
        upper[0] = (-Math.PI / 2).toFloat() - 0.5f; fore[0] = (Math.PI / 2).toFloat() - 0.3f
        upper[1] = (-Math.PI / 2).toFloat() + 0.5f; fore[1] = (Math.PI / 2).toFloat() + 0.3f
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        g.gradientRect(r.left, r.top, r.right, r.bottom, 0xFF1A2238.toInt(), 0xFF5F7480.toInt())
        val phase = ((t % 1.6) / 1.6).toFloat()
        val ext = if (phase in 0.3f..0.6f) sin((phase - 0.3f) / 0.3f * Math.PI.toFloat()) else 0f
        val p = AvatarPose().apply {
            valid = true
            upper[0] = idlePose.upper[0]; fore[0] = idlePose.fore[0]
            upper[1] = lerp(idlePose.upper[1], -0.1f, ext * 0.6f); fore[1] = lerp(idlePose.fore[1], 0.2f, ext)
        }
        Draw.athlete(g, r.cx, r.bottom + r.height * 0.05f, r.height * 1.05f, env.color(0), p, Expression.FOCUSED, Prop.GLOVES, t = t)
        if (ext > 0.3f) g.text("JAB!", r.right - r.width * 0.2f, r.top + 40f, 40f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 4f)
    }
}
