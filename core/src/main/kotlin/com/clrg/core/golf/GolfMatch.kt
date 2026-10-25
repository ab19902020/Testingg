package com.clrg.core.golf

import com.clrg.core.math.Rng
import com.clrg.core.math.lerp
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import kotlin.math.PI

/** Someone playing golf: a family member at the camera, or a computer golfer ([cpuSkill] 0..1). */
class GolfPlayer(
    val name: String,
    val color: Int,
    val profileId: Int,
    val cpuSkill: Float? = null,
    val hand: Hand? = null,
    val mode: MovementMode = MovementMode.STANDARD,
) {
    val isCpu: Boolean get() = cpuSkill != null
}

/**
 * Stroke-play golf for 1-4 players, taking turns: each player plays a whole hole (tee shot, approach, putts) while
 * the others watch, so only one person needs to be in front of the camera at a time. Lowest total wins.
 *
 * Pure game logic (no drawing, no tracking): the screen feeds it club choices, aim and swings.
 */
class GolfMatch(val course: Course, val players: List<GolfPlayer>, seed: Long = 1) {
    enum class Phase { TURN_START, AIM, FLIGHT, RESULT, HOLE_DONE, COURSE_DONE }

    init { require(players.size in 1..4) }

    private val rng = Rng(seed)
    var phase = Phase.TURN_START
        private set
    var holeIndex = 0
        private set
    /** Turn order for the current hole (indices into [players]). */
    var order: List<Int> = players.indices.toList()
        private set
    private var orderPos = 0
    val current: Int get() = order[orderPos.coerceAtMost(order.size - 1)]
    val player: GolfPlayer get() = players[current]
    val hole: Hole get() = course.holes[holeIndex]

    /** Strokes per player per hole (0 = not played yet). */
    val strokes = Array(players.size) { IntArray(course.holes.size) }

    // the current player's ball
    var ball = Pt(0f, 0f)
        private set
    var lie = Surface.TEE
        private set
    var strokesThisHole = 0
        private set
    var club = Club.DRIVER
        private set
    /** Aim (radians; 0 = straight up the hole, + = right). */
    var aim = 0f
        private set
    private var aimTouched = false
    var plan: ShotPlan? = null
        private set
    var animTime = 0f
        private set
    var message = ""
        private set
    var messageTime = 0f
        private set
    private var resultHold = 0f
    private var holeOver = false
    /** Seconds spent in the current phase. */
    var phaseTime = 0f
        private set

    /** The flag is reached in at most par + 4 strokes, then the player picks up. */
    val maxStrokes: Int get() = hole.par + 4

    fun distanceToPin(): Float = ball.dist(hole.pin)

    // ------------------------------------------------------------------ turns

    /** The current player steps up: their ball goes on the tee of the current hole. */
    fun beginTurn() {
        if (phase != Phase.TURN_START) return
        ball = hole.tee; lie = Surface.TEE; strokesThisHole = 0; holeOver = false
        chooseDefaults()
        setPhase(Phase.AIM)
    }

    private fun setPhase(p: Phase) { phase = p; phaseTime = 0f }

    /** After the scorecard: the next hole, or the end of the round. */
    fun continueAfterHole() {
        if (phase != Phase.HOLE_DONE) return
        if (holeIndex + 1 >= course.holes.size) { setPhase(Phase.COURSE_DONE); return }
        // honour: the best score on the last hole plays first
        val last = holeIndex
        holeIndex++
        order = players.indices.sortedWith(compareBy({ strokes[it][last] }, { it }))
        orderPos = 0
        setPhase(Phase.TURN_START)
    }

    // ------------------------------------------------------------------ aiming

    fun suggestedClub(): Club {
        if (lie == Surface.GREEN) return Club.PUTTER
        val d = distanceToPin()
        // just off the green on short grass: the putter is the easy option
        if (lie == Surface.FAIRWAY && d < hole.greenR + 5f) return Club.PUTTER
        val choices = if (lie == Surface.TEE) listOf(Club.WEDGE, Club.IRON9, Club.IRON5, Club.WOOD, Club.DRIVER) else listOf(Club.WEDGE, Club.IRON9, Club.IRON5, Club.WOOD)
        return choices.firstOrNull { it.carry * GolfPhysics.lieFactor(lie, it) >= d * 0.95f } ?: choices.last()
    }

    /** Where the default aim points for [c]: the pin when it is in range, otherwise the fairway at that distance. */
    fun targetFor(c: Club): Pt {
        if (c == Club.PUTTER) return hole.pin
        val reach = c.carry * GolfPhysics.lieFactor(lie, c)
        return if (distanceToPin() <= reach * 1.08f) hole.pin else hole.fairwayPointAt(ball, reach)
    }

    private fun chooseDefaults() {
        club = suggestedClub()
        aim = GolfPhysics.aimAt(ball, targetFor(club))
        aimTouched = false
    }

    fun selectClub(c: Club) {
        if (phase != Phase.AIM) return
        club = c
        if (!aimTouched) aim = GolfPhysics.aimAt(ball, targetFor(c))
    }

    /** Turns the aim by [radians] (+ = to the right), within 60 degrees of the pin direction. */
    fun nudgeAim(radians: Float) {
        if (phase != Phase.AIM) return
        val toPin = GolfPhysics.aimAt(ball, hole.pin)
        aim = (aim + radians).coerceIn(toPin - LIMIT, toPin + LIMIT)
        aimTouched = true
    }

    fun resetAim() { aim = GolfPhysics.aimAt(ball, targetFor(club)); aimTouched = false }

    /** Roughly where a full-power shot with the current club would land (for the aiming guide). */
    fun reachNow(power: Float = 1f): Float = if (club == Club.PUTTER) GolfPhysics.puttDistance(power) else club.carry * GolfPhysics.lieFactor(lie, club) * power

    // ------------------------------------------------------------------ shots

    /** Takes the shot with the current club and aim: [power] 0..1.15 from the swing, [offset] -1..1. */
    fun shoot(power: Float, offset: Float): ShotPlan? {
        if (phase != Phase.AIM) return null
        val p = if (club == Club.PUTTER) GolfPhysics.putt(hole, ball, aim, power)
        else GolfPhysics.fullShot(hole, ball, lie, club, aim, power, offset, rng)
        plan = p
        strokesThisHole++
        animTime = 0f
        setPhase(Phase.FLIGHT)
        return p
    }

    fun update(dt: Float) {
        phaseTime += dt
        messageTime += dt
        when (phase) {
            Phase.FLIGHT -> {
                val p = plan ?: return
                animTime += dt
                if (animTime >= p.totalSec + 0.25f) settle(p)
            }
            Phase.RESULT -> {
                resultHold -= dt
                if (resultHold <= 0f) {
                    if (holeOver) finishTurn() else { chooseDefaults(); setPhase(Phase.AIM) }
                }
            }
            else -> Unit
        }
    }

    private fun say(text: String) { message = text; messageTime = 0f }

    private fun settle(p: ShotPlan) {
        when (p.outcome) {
            Outcome.WATER -> { strokesThisHole++; say("Splash! One penalty stroke - play again from the same spot") }
            Outcome.OUT -> { strokesThisHole++; say("Out of bounds! One penalty stroke - play again") }
            Outcome.HOLED -> { ball = hole.pin; holeOut(); return }
            Outcome.STOPPED -> {
                ball = p.end
                lie = p.endSurface
                val left = ball.dist(hole.pin)
                if (lie == Surface.GREEN && left <= GolfPhysics.GIMME) {
                    strokesThisHole++
                    ball = hole.pin
                    holeOut(tapIn = true)
                    return
                }
                say(when {
                    p.hitTree -> "Clipped a tree!"
                    lie == Surface.GREEN && p.club != Club.PUTTER -> "On the green! ${fmt(left)} to the hole"
                    lie == Surface.GREEN -> "${fmt(left)} left"
                    lie == Surface.SAND -> "In the bunker"
                    lie == Surface.TREES -> "In the trees"
                    lie == Surface.ROUGH -> "In the rough - ${p.total.toInt()} m"
                    else -> "Fairway - ${p.total.toInt()} m"
                })
            }
        }
        if (strokesThisHole >= maxStrokes) {
            strokes[current][holeIndex] = maxStrokes
            say("Picked up - ${maxStrokes} strokes")
            holeOver = true
        }
        resultHold = 1.9f
        setPhase(Phase.RESULT)
    }

    private fun holeOut(tapIn: Boolean = false) {
        val n = minOf(strokesThisHole, maxStrokes)
        strokesThisHole = n
        strokes[current][holeIndex] = n
        holeOver = true
        say((if (tapIn) "Tapped in. " else "") + scoreName(n, hole.par))
        resultHold = 2.4f
        setPhase(Phase.RESULT)
    }

    private fun finishTurn() {
        orderPos++
        if (orderPos >= order.size) { setPhase(Phase.HOLE_DONE); return }
        setPhase(Phase.TURN_START)
    }

    // ------------------------------------------------------------------ scores

    fun total(i: Int): Int = strokes[i].sum()
    fun parPlayed(i: Int): Int = course.holes.indices.filter { strokes[i][it] > 0 }.sumOf { course.holes[it].par }
    fun toPar(i: Int): Int = total(i) - parPlayed(i)

    /** Players with the lowest total (more than one means a tie). */
    fun leaders(): List<Int> {
        val best = players.indices.minOf { total(it) }
        return players.indices.filter { total(it) == best }
    }

    // ------------------------------------------------------------------ computer golfer

    /**
     * The computer's shot for the current position: it picks the suggested club and aim like a person would, then
     * swings with human-like errors in strength and direction (smaller at higher skill). Putts are read by trying
     * lines and strengths on the real green (as a good golfer reads it), then struck with an error.
     */
    fun cpuShot(skill: Float, r: Rng): Pair<Float, Float> {
        selectClub(suggestedClub())
        resetAim()
        if (club == Club.PUTTER) {
            val (bestAim, bestPower) = readPutt()
            aim = bestAim + r.gaussian() * lerp(0.05f, 0.012f, skill)
            return (bestPower * (1f + r.gaussian() * lerp(0.16f, 0.05f, skill))).coerceIn(0.03f, 1f) to 0f
        }
        val reach = club.carry * GolfPhysics.lieFactor(lie, club)
        val need = (ball.dist(targetFor(club)) / reach).coerceIn(0.15f, 1f)
        aim += r.gaussian() * lerp(0.05f, 0.012f, skill)
        val power = (need + r.gaussian() * lerp(0.1f, 0.03f, skill)).coerceIn(0.1f, 1.1f)
        val offset = (r.gaussian() * lerp(0.45f, 0.12f, skill)).coerceIn(-1f, 1f)
        return power to offset
    }

    /** The aim and strength that leave a putt closest to (or in) the hole. */
    fun readPutt(): Pair<Float, Float> {
        val base = GolfPhysics.aimAt(ball, hole.pin)
        val d = distanceToPin()
        var best = base to GolfPhysics.puttPowerFor(d)
        var bestErr = Float.MAX_VALUE
        for (ai in -12..12) {
            val a = base + ai * (PI / 180.0).toFloat()
            var pw = 0.04f
            while (pw <= 1f) {
                val pl = GolfPhysics.putt(hole, ball, a, pw)
                val err = if (pl.outcome == Outcome.HOLED) -1f else if (pl.outcome != Outcome.STOPPED) 1e6f else pl.end.dist(hole.pin)
                if (err < bestErr) { bestErr = err; best = a to pw }
                pw += 0.02f
            }
        }
        return best
    }

    companion object {
        val LIMIT = (60.0 * PI / 180.0).toFloat()

        fun scoreName(strokes: Int, par: Int): String = when {
            strokes == 1 -> "HOLE IN ONE!"
            strokes - par <= -3 -> "Albatross!"
            strokes - par == -2 -> "Eagle!"
            strokes - par == -1 -> "Birdie!"
            strokes == par -> "Par"
            strokes - par == 1 -> "Bogey"
            strokes - par == 2 -> "Double bogey"
            else -> "$strokes strokes"
        }

        fun fmt(m: Float): String = if (m < 10f) "%.1f m".format(m) else "${m.toInt()} m"

        fun toParText(v: Int): String = when { v == 0 -> "E"; v > 0 -> "+$v"; else -> "$v" }
    }
}
