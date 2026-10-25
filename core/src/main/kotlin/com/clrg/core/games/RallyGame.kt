package com.clrg.core.games

import com.clrg.core.audio.Sfx
import com.clrg.core.game.GameEnv
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.math.clamp
import com.clrg.core.math.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Shared rally simulation for Christmas Tennis and Table Tennis, in "court units": the net is at x = 0, player 1's
 * baseline at x = -1, player 2's at x = +1; h is height above the playing surface.
 *
 * Aiming rule (shown in the instructions): the ball is returned automatically toward the opponent; *when* you swing
 * sets the shot. On time = deep, fast shot. A little early = deeper, a little late = shorter. Far too early = long
 * (OUT), far too late = into the NET. No swing = miss.
 *
 * Built for real swings seen by a camera: the perfect moment is known as soon as the opponent hits (so a swing before
 * the bounce counts as a volley), a backswing or an early swing is never "used up" - every swing around the ball's
 * arrival is a candidate and the one closest to the perfect moment is played - and while a ball is arriving the
 * receiver's swing detection is made more sensitive ([PlayerInput.expectSwing]).
 *
 * Timing is judged by when the swing was *made*: a camera player's swing arrives a little after they make it
 * (camera, tracking) and they react to a picture that is a little old (phone display, TV), so the swing is compared
 * with where the ball was at that moment, the ball is returned from there, and a miss is only called once a swing
 * that was made in time could no longer be on its way ([inputGrace]).
 */
abstract class RallyGame(env: GameEnv) : MiniGame(env) {
    class Config(
        val gravity: Float,
        val netH: Float,
        val hitH: Float,
        /** Half-width of the timing window (seconds) around the perfect hit moment. */
        val window: Float,
        val slowFlight: Float,
        val fastFlight: Float,
        val speedupPerHit: Float,
        val minFlightFactor: Float,
        val pointsToWin: Int,
        /** Serve passes to the other player every N points. */
        val serveEvery: Int,
        val restitution: Float,
        val friction: Float,
        val homeX: Float,
        val moveSpeed: Float,
        val racketReach: Float,
        val autoServeSec: Float,
        /** How fast the timing window tightens per hit in a rally, and its floor. */
        val windowShrinkPerHit: Float = 0.025f,
        val minWindowFactor: Float = 0.6f,
        /** Furthest a character runs from the net (court units), so they stay on screen. */
        val maxCourtX: Float = 1.3f,
    )

    enum class State { SERVE, RALLY, POINT_OVER, MATCH_OVER }
    enum class PointReason(val text: String) { OUT("Out!"), NET("Into the net!"), MISSED("Missed!"), WINNER("Winner!") }

    protected abstract val cfg: Config

    val scores = IntArray(2)
    var state = State.SERVE
        protected set
    var server = 0
        protected set
    var stateTime = 0f
        protected set
    /** Player who must hit next (-1 none). */
    var receiver = -1
        protected set
    var lastHitter = -1
        protected set
    var rally = 0
        protected set
    var longestRally = 0
        protected set
    var pointWinner = -1
        protected set
    var pointReason = PointReason.MISSED
        protected set

    // ball
    var bx = 0f; var bh = 0f; var vx = 0f; var vh = 0f
    var bounced = false
        protected set
    var ballVisible = false
        protected set
    protected var netShot = false
    /** Play time at which the receiver's perfect hit happens (NaN when not yet known). */
    var hitTime = Double.NaN
        protected set
    var hitX = 0f
        protected set
    /** Characters' court positions. */
    val px = FloatArray(2)
    /** Seconds since each player's last swing (for animation). */
    val swingAge = floatArrayOf(9f, 9f)
    val swingForehand = booleanArrayOf(true, true)
    private var pointsPlayed = 0
    protected val rng = env.rng(11)

    /** Swings made around the incoming ball's arrival: the closest to the perfect moment is played. */
    private class Candidate(val at: Double, val swing: GestureEvent.Swing)
    private val candidates = ArrayList<Candidate>()
    private var tooEarlyShown = false
    /** Swings seen per player (any time) and swings that hit a ball - for the end-of-match summary and tests. */
    val swingsSeen = IntArray(2)
    val hitsMade = IntArray(2)
    /** For each hit: how far from the perfect moment the swing was made (s, + = late). */
    val timingErrors = Array(2) { ArrayList<Float>() }

    // recent ball positions (one per simulation step) so a swing can be judged against where the ball was
    private val histT = DoubleArray(HISTORY)
    private val histX = FloatArray(HISTORY)
    private val histH = FloatArray(HISTORY)
    private var histHead = -1
    private var histSize = 0

    private fun recordBall() {
        histHead = (histHead + 1) % HISTORY
        histT[histHead] = playTime; histX[histHead] = bx; histH[histHead] = bh
        if (histSize < HISTORY) histSize++
    }

    /** Index into the history of the newest ball position at or before play time [t] (-1 if none). */
    private fun ballIndexAt(t: Double): Int {
        for (k in 0 until histSize) {
            val i = ((histHead - k) % HISTORY + HISTORY) % HISTORY
            if (histT[i] <= t + 1e-9) return i
        }
        return -1
    }

    override fun start() {
        px[0] = -cfg.homeX; px[1] = cfg.homeX
        server = 0
        beginServe()
    }

    protected fun dirToward(player: Int) = if (player == 1) 1f else -1f

    protected open fun beginServe() {
        state = State.SERVE
        stateTime = 0f
        receiver = -1
        rally = 0
        ballVisible = false
        hitTime = Double.NaN
        netShot = false
        lastHitter = -1
    }

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        if (dt > 0f) lastDt = dt
        stateTime += dt
        for (k in 0..1) swingAge[k] += dt
        // collect swings (any other gesture is ignored by this game): every swing of this step, not just one
        val swings = Array(2) { ArrayList<GestureEvent.Swing>(2) }
        for (k in 0..1) while (true) { swings[k].add(inputs[k].take<GestureEvent.Swing>() ?: break) }
        for (k in 0..1) swings[k].lastOrNull()?.let { swingAge[k] = 0f; swingForehand[k] = it.forehand; swingsSeen[k] += swings[k].size }
        for (k in 0..1) inputs[k].expectSwing = expectation(k)

        when (state) {
            State.SERVE -> {
                val s = swings[server].lastOrNull()
                val auto = stateTime > cfg.autoServeSec
                if ((s != null && stateTime > 0.6f) || auto) serve(s?.power ?: 0.3f, auto)
                moveCharacters(dt)
            }
            State.RALLY -> {
                stepBall(dt)
                recordBall()
                val r = receiver
                if (r >= 0 && !hitTime.isNaN()) {
                    val w = window()
                    for (s in swings[r]) {
                        val at = madeAt(s)
                        val e = ((at - hitTime) / w).toFloat()
                        if (e < EARLIEST) {
                            if (!tooEarlyShown) { tooEarlyShown = true; onTooEarly(r) }
                        } else if (e <= LATEST) candidates.add(Candidate(at, s))
                    }
                    decide(r, w)
                    // no swing that was made in time can still be on its way: missed
                    val eLatest = ((playTime - inputGrace(r) - hitTime) / w).toFloat()
                    if (eLatest > LATEST && candidates.isEmpty() && state == State.RALLY && receiver == r) endPoint(1 - r, PointReason.MISSED)
                }
                moveCharacters(dt)
            }
            State.POINT_OVER -> {
                stepBall(dt)
                moveCharacters(dt)
                if (stateTime > 1.7f) {
                    if (scores.any { it >= cfg.pointsToWin }) { state = State.MATCH_OVER } else {
                        pointsPlayed++
                        if (pointsPlayed % cfg.serveEvery == 0) server = 1 - server
                        beginServe()
                    }
                }
            }
            State.MATCH_OVER -> Unit
        }
    }

    protected open fun onTooEarly(player: Int) = popup("Too early - wait for it!", player, 40f)

    /**
     * Plays the best swing so far once no closer one can still be on its way: a swing made after the perfect moment
     * is played straight away (any later one would be worse); an early one waits until a swing as far after the
     * perfect moment could have arrived - but never long after the ball reached the player.
     */
    private fun decide(r: Int, w: Float) {
        if (candidates.isEmpty()) return
        var best = candidates[0]
        for (c in candidates) if (kotlin.math.abs(c.at - hitTime) < kotlin.math.abs(best.at - hitTime)) best = c
        val waitUntil = if (best.at >= hitTime) playTime else minOf(hitTime + (hitTime - best.at), hitTime + HOLD_FOR_BETTER) + inputGrace(r)
        if (playTime + 1e-9 < waitUntil) return
        candidates.clear()
        hit(r, ((best.at - hitTime) / w).toFloat(), best.swing, best.at)
    }

    /** 0..1: how much player [k] should be swinging right now (a ball arriving at them, or their serve). */
    private fun expectation(k: Int): Float = when {
        state == State.SERVE && server == k && stateTime > 0.6f -> 0.6f
        state == State.RALLY && receiver == k && !hitTime.isNaN() -> {
            val left = (hitTime - playTime).toFloat()
            if (left < -window() * LATEST) 0f else (1f - (left - 0.8f) / 0.4f).coerceIn(0f, 1f)
        }
        else -> 0f
    }

    /** Last rendered virtual screen width (layout depends on the display aspect ratio). */
    protected var screenW = 1920f

    /** Court x -> virtual screen x. */
    abstract fun toScreenX(x: Float): Float

    /** Height above the playing surface -> virtual screen y. */
    abstract fun toScreenY(h: Float): Float

    protected fun popup(text: String, player: Int, size: Float = 50f) =
        env.popups.add(text, toScreenX(px[player]).coerceIn(240f, screenW - 240f), toScreenY(0.55f), env.color(player), size)

    private fun serve(power: Float, auto: Boolean) {
        val target = 1 - server
        val q = if (auto) 0.2f else 0.75f
        val startX = px[server] + dirToward(target) * cfg.racketReach
        launch(server, startX, cfg.hitH * 1.6f, dirToward(target) * lerp(0.55f, 0.8f, power), flightTime(q, power) * 1.08f, net = false)
        env.sfx(Sfx.HIT, 0.9f, if (server == 0) -0.5f else 0.5f)
        swingAge[server] = 0f
        onServe(server, auto)
    }

    protected open fun onServe(player: Int, auto: Boolean) {}

    protected fun flightTime(quality: Float, power: Float): Float {
        val base = lerp(cfg.slowFlight, cfg.fastFlight, clamp(0.55f * quality + 0.45f * power, 0f, 1f))
        return base * max(cfg.minFlightFactor, 1f - cfg.speedupPerHit * rally)
    }

    /** Launches the ball from [fromX] so it lands at [landX] after [t] seconds (raising the arc to clear the net). */
    protected fun launch(hitter: Int, fromX: Float, fromH: Float, landX: Float, t: Float, net: Boolean) {
        var tt = t
        var nvx = (landX - fromX) / tt
        var nvh = (cfg.gravity * tt * tt / 2f - fromH) / tt
        if (!net && sign(fromX) != sign(landX)) {
            repeat(12) {
                val tn = (0f - fromX) / nvx
                val hn = fromH + nvh * tn - cfg.gravity * tn * tn / 2f
                if (hn >= cfg.netH * 1.35f) return@repeat
                tt *= 1.08f
                nvx = (landX - fromX) / tt
                nvh = (cfg.gravity * tt * tt / 2f - fromH) / tt
            }
        }
        bx = fromX; bh = fromH; vx = nvx; vh = nvh
        bounced = false
        ballVisible = true
        netShot = net
        lastHitter = hitter
        receiver = 1 - hitter
        hitTime = Double.NaN
        state = State.RALLY
        stateTime = 0f
        candidates.clear()
        tooEarlyShown = false
        predictHitTime()
    }

    /**
     * From the moment a ball is struck: where and when the receiver's perfect hit will be (after the bounce, as the
     * ball comes down to hitting height). Nothing for a ball heading into the net or out.
     */
    private fun predictHitTime() {
        if (netShot) return
        // fly the ball forward with the very steps the game will use, up to its bounce
        val g = cfg.gravity
        val dt = lastDt
        var x = bx; var h = bh; var v = vh
        var t = 0f
        while (t < 6f) {
            val ox = x
            x += vx * dt
            v -= g * dt
            h += v * dt
            t += dt
            if (sign(ox) != sign(x) && ox != 0f && h < cfg.netH) return // into the net
            if (h <= 0f && v < 0f) break
        }
        val rcv = receiver
        if (rcv < 0 || sign(x) != dirToward(rcv) || abs(x) > 1.03f) return
        // then exactly what happens at the bounce (see computeHitTime)
        val vhb = -v * cfg.restitution
        val vxb = vx * cfg.friction
        val disc = vhb * vhb - 2f * g * cfg.hitH
        val tau = if (disc >= 0f) (vhb + sqrt(disc)) / g else vhb / g
        hitTime = playTime + t + tau
        hitX = x + vxb * tau
    }

    /** Length of the last simulation step (the game runs at a fixed rate). */
    private var lastDt = 1f / 120f

    private fun stepBall(dt: Float) {
        if (!ballVisible) return
        val oldX = bx
        bx += vx * dt
        vh -= cfg.gravity * dt
        bh += vh * dt
        // net
        if (sign(oldX) != sign(bx) && oldX != 0f && state == State.RALLY) {
            if (netShot || bh < cfg.netH) {
                bx = -sign(vx) * 0.02f
                vx = -vx * 0.12f
                bh = minOf(bh, cfg.netH * 0.8f)
                vh = 0f
                env.sfx(Sfx.BOUNCE, 0.6f)
                endPoint(1 - lastHitter, PointReason.NET)
                return
            }
        }
        if (bh <= 0f && vh < 0f) {
            bh = 0f
            if (state == State.RALLY && !bounced) {
                val rx = receiver
                val inSide = rx >= 0 && sign(bx) == dirToward(rx) && abs(bx) <= 1.03f
                if (!inSide) {
                    vh = -vh * cfg.restitution * 0.6f
                    env.sfx(Sfx.BOUNCE, 0.5f)
                    endPoint(1 - lastHitter, PointReason.OUT)
                    return
                }
                bounced = true
                vh = -vh * cfg.restitution
                vx *= cfg.friction
                env.sfx(Sfx.BOUNCE, 0.7f, bx * 0.6f)
                computeHitTime()
            } else {
                vh = -vh * cfg.restitution * 0.6f
                vx *= 0.8f
                if (abs(vh) < 0.05f) { vh = 0f; vx *= 0.9f }
            }
        }
    }

    /** After the bounce: when the ball descends to hitting height in front of the receiver. */
    private fun computeHitTime() {
        val g = cfg.gravity
        val disc = vh * vh - 2f * g * cfg.hitH
        val tau = if (disc >= 0f) (vh + sqrt(disc)) / g else vh / g
        hitTime = playTime + tau
        hitX = bx + vx * tau
    }

    private fun hit(player: Int, e: Float, s: GestureEvent.Swing, at: Double) {
        val q = clamp(1f - abs(e) / SWEET, 0f, 1f)
        rally++
        hitsMade[player]++
        timingErrors[player].add((at - hitTime).toFloat())
        longestRally = max(longestRally, rally)
        val target = 1 - player
        val modifier = shotModifier(player, s, q)
        // return the ball from where it was when the swing was made (it may have flown on while the swing arrived),
        // but no earlier than just before it reached the player
        val from = max(at, hitTime - 0.12)
        val i = if (from < playTime) ballIndexAt(from) else -1
        val fromX = if (i >= 0) histX[i] else bx
        val fromH = if (i >= 0) histH[i] else bh
        when {
            e < EARLY_OUT -> {
                launch(player, fromX, max(fromH, cfg.hitH), dirToward(target) * 1.2f, flightTime(q, s.power) * 0.9f, net = false)
                popup("Too early!", player)
            }
            e > LATE_NET -> {
                launch(player, fromX, max(fromH, cfg.hitH * 0.7f), dirToward(target) * 0.3f, 0.5f, net = true)
                popup("Too late!", player)
            }
            else -> {
                // early = deeper, late = shorter; a little early or late is still a good, safe return
                val landFrac = clamp(0.62f - 0.25f * e + modifier.depthBonus, 0.3f, 0.95f)
                launch(player, fromX, max(fromH, cfg.hitH), dirToward(target) * landFrac, flightTime(q, s.power) * modifier.timeFactor, net = false)
                onGoodHit(player, q, modifier)
            }
        }
        env.sfx(Sfx.HIT, 0.7f + 0.3f * q, if (player == 0) -0.5f else 0.5f, 0.9f + 0.25f * q)
        if (q > 0.85f) env.shake.kick(6f)
    }

    class ShotModifier(val timeFactor: Float = 1f, val depthBonus: Float = 0f, val label: String? = null)

    /** Hook for game-specific shot rules (table tennis forehand/backhand, smashes). */
    protected open fun shotModifier(player: Int, s: GestureEvent.Swing, quality: Float): ShotModifier = ShotModifier()

    protected open fun onGoodHit(player: Int, quality: Float, m: ShotModifier) {
        val label = m.label ?: when {
            quality > 0.8f -> "PERFECT!"
            quality > 0.5f -> "Great!"
            else -> "Good"
        }
        popup(label, player, if (quality > 0.8f) 60f else 46f)
    }

    protected fun endPoint(winner: Int, reason: PointReason) {
        if (state != State.RALLY) return
        scores[winner]++
        pointWinner = winner
        pointReason = reason
        state = State.POINT_OVER
        stateTime = 0f
        receiver = -1
        hitTime = Double.NaN
        candidates.clear()
        env.sfx(Sfx.POINT)
        onPoint(winner, reason)
    }

    protected open fun onPoint(winner: Int, reason: PointReason) {}

    private fun moveCharacters(dt: Float) {
        for (k in 0..1) {
            val d = dirToward(k) // side sign of player k
            val target = when {
                state == State.RALLY && receiver == k && !hitTime.isNaN() -> hitX - (-d) * cfg.racketReach
                state == State.RALLY && receiver == k -> predictedLanding(k)
                else -> d * cfg.homeX
            }
            val clampT = clamp(target * d, 0.35f, cfg.maxCourtX) * d
            px[k] += clamp(clampT - px[k], -cfg.moveSpeed * dt, cfg.moveSpeed * dt)
        }
    }

    /** Before the bounce: head roughly toward where the ball will come down. */
    private fun predictedLanding(k: Int): Float {
        val g = cfg.gravity
        val disc = vh * vh + 2f * g * bh
        val t = (vh + sqrt(max(0f, disc))) / g
        return clamp(bx + vx * t * 1.4f, -1.4f, 1.4f) + dirToward(k) * cfg.racketReach
    }

    // ---- CPU player -------------------------------------------------------------------------------------
    // It sees what a human sees: the ball, and the shrinking ring that shows when the perfect moment is. Its swing
    // timing has a human-like random error that grows as rallies get faster, it sometimes doesn't swing at all, and
    // in table tennis it sometimes picks the wrong stroke.
    private val botPlanFor = DoubleArray(2) { Double.NaN }
    private val botSwingAt = DoubleArray(2)
    private val botWillSwing = BooleanArray(2)
    private val botSwung = BooleanArray(2)
    private val botForehandPlan = BooleanArray(2)
    private val botServeAt = FloatArray(2) { Float.NaN }

    /** Which stroke the incoming ball needs (table tennis overrides). */
    protected open fun botForehand(k: Int) = true

    /** Chance that the CPU picks the wrong stroke (table tennis overrides). */
    protected open fun botWrongStrokeChance(skill: Float) = 0f

    /** Extra timing difficulty of the incoming ball (table tennis: after a smash). */
    protected open fun incomingPressure() = 1f

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val power = clamp(lerp(0.35f, 0.8f, skill) + (rng.nextFloat() - 0.5f) * 0.2f, 0.1f, 1f)
        if (state == State.SERVE && server == k) {
            if (botServeAt[k].isNaN()) botServeAt[k] = lerp(1.6f, 0.9f, skill) + rng.nextFloat() * 0.8f
            if (stateTime >= botServeAt[k]) {
                inp.events.add(GestureEvent.Swing(playTime, 1, -1f, 0f, power, true))
                botServeAt[k] = Float.MAX_VALUE // one serve swing per serve
            }
        } else if (state != State.SERVE) botServeAt[k] = Float.NaN
        if (state == State.RALLY && receiver == k && !hitTime.isNaN()) {
            if (botPlanFor[k] != hitTime) {
                botPlanFor[k] = hitTime
                val sigma = lerp(0.15f, 0.045f, skill) * (1f + 0.07f * rally) * incomingPressure()
                botSwingAt[k] = hitTime + rng.gaussian() * sigma
                botWillSwing[k] = !rng.chance(lerp(0.06f, 0.01f, skill))
                botForehandPlan[k] = botForehand(k) != rng.chance(botWrongStrokeChance(skill))
                botSwung[k] = false
            }
            if (botWillSwing[k] && !botSwung[k] && playTime >= botSwingAt[k]) {
                inp.events.add(GestureEvent.Swing(playTime, 1, -1f, 0f, power, botForehandPlan[k]))
                botSwung[k] = true
            }
        }
    }

    override val isOver: Boolean get() = state == State.MATCH_OVER

    companion object {
        /** Ball positions kept for judging late-arriving swings: 1 s at 120 steps per second. */
        private const val HISTORY = 120
        /** Swings this many timing windows before the perfect moment are ignored ("too early - wait for it"). */
        const val EARLIEST = -2.2f
        /** ... and swings later than this are not a hit at all. */
        const val LATEST = 1.35f
        /** Played but sails long (OUT) / into the net. */
        const val EARLY_OUT = -1.6f
        const val LATE_NET = 1.0f
        /** Timing (in windows) over which shot quality falls from perfect to nothing. */
        const val SWEET = 1.3f
        /** How long after the perfect moment an early swing waits for a better one (s). */
        const val HOLD_FOR_BETTER = 0.1
    }

    override fun result(): MatchResult = MatchResult(
        info.id, scores.copyOf(), winnerOf(scores[0], scores[1]), info.scoreUnit,
        listOf("Longest rally: $longestRally hits"),
    )

    /** 0..1 fraction of the approach until the perfect moment (for the shrinking timing ring). */
    fun approachFraction(): Float {
        if (hitTime.isNaN()) return 0f
        val left = (hitTime - playTime).toFloat()
        return clamp(1f - left / 1.0f, 0f, 1f)
    }

    /** Timing window for the current shot: tightens as a rally gets longer, so rallies build pressure and end. */
    fun window(): Float = cfg.window * max(cfg.minWindowFactor, 1f - cfg.windowShrinkPerHit * rally)

    fun inWindow(): Boolean = !hitTime.isNaN() && abs(playTime - hitTime) <= window() * 0.75f
}
