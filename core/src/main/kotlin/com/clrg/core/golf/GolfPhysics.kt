package com.clrg.core.golf

import com.clrg.core.math.Rng
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Clubs: how far a full swing carries from a good lie (m), how far it rolls on, how wild an off-line swing goes. */
enum class Club(val label: String, val carry: Float, val roll: Float, val spreadDeg: Float, val loft: Float) {
    DRIVER("Driver", 225f, 0.12f, 9f, 0.12f),
    WOOD("3 Wood", 195f, 0.10f, 8f, 0.14f),
    IRON5("5 Iron", 160f, 0.07f, 6f, 0.17f),
    IRON9("9 Iron", 120f, 0.05f, 5f, 0.23f),
    WEDGE("Wedge", 80f, 0.03f, 4f, 0.32f),
    PUTTER("Putter", 0f, 0f, 0f, 0f),
}

enum class Outcome { STOPPED, WATER, OUT, HOLED }

/**
 * A computed shot, ready to animate: the ball flies from [start] to [land] (peaking at [apex] metres) in [flightSec],
 * then rolls along [roll] in [rollSec] to [end]. Putts have no flight, only a roll.
 */
class ShotPlan(
    val club: Club,
    val start: Pt,
    val land: Pt,
    val apex: Float,
    val flightSec: Float,
    val roll: List<Pt>,
    val rollSec: Float,
    val end: Pt,
    val outcome: Outcome,
    val endSurface: Surface,
    /** Clipped a tree on the way down. */
    val hitTree: Boolean = false,
) {
    val carry: Float get() = start.dist(land)
    val total: Float get() = start.dist(end)
    val totalSec: Float get() = flightSec + rollSec
}

object GolfPhysics {
    /** Speed (m/s) of a full-strength putt: rolls about 22 m on a flat green. */
    const val PUTT_VMAX = 4.9f
    const val GREEN_FRICTION = 0.55f
    /** How close (m) to the centre of the hole a slow ball drops in (a little forgiving). */
    const val CAPTURE_R = 0.11f
    /** Faster than this (m/s) and the ball rolls over the hole. */
    const val CAPTURE_SPEED = 1.7f
    /** Balls finishing this close (m) are tapped in automatically for one more stroke. */
    const val GIMME = 0.6f

    /** Direction for an aim angle (radians; 0 = straight up the hole, + = to the right). */
    fun dir(aim: Float) = Pt(sin(aim), cos(aim))
    fun aimAt(from: Pt, to: Pt): Float = atan2(to.x - from.x, to.y - from.y)

    /** How much of a club's distance a lie allows. */
    fun lieFactor(lie: Surface, club: Club): Float = when (lie) {
        Surface.TEE, Surface.FAIRWAY, Surface.GREEN -> 1f
        Surface.ROUGH -> if (club == Club.WEDGE) 0.92f else 0.82f
        Surface.SAND -> if (club == Club.WEDGE) 0.85f else 0.6f
        Surface.TREES -> 0.55f
        else -> 1f
    }

    /** Flat-green distance (m) a putt of [power] rolls - shown while the player draws the putter back. */
    fun puttDistance(power: Float): Float = (PUTT_VMAX * power).let { it * it / (2f * GREEN_FRICTION) }

    /** Putter power that rolls about [d] metres on the flat. */
    fun puttPowerFor(d: Float): Float = (kotlin.math.sqrt(2f * GREEN_FRICTION * d) / PUTT_VMAX).coerceIn(0f, 1f)

    /**
     * A full swing with [club] from [start] ([lie]) aimed at [aim], with swing [power] (0..1.15) and [offset] (-1..1,
     * hands off-line at impact). [rng] adds a hint of natural variation.
     */
    fun fullShot(hole: Hole, start: Pt, lie: Surface, club: Club, aim: Float, power: Float, offset: Float, rng: Rng?): ShotPlan {
        require(club != Club.PUTTER)
        val p = power.coerceIn(0f, 1.15f)
        val over = (p - 1f).coerceAtLeast(0f) / 0.15f
        val pw = if (p <= 1f) p else 1f + (p - 1f) * 0.35f
        var errDeg = offset.coerceIn(-1f, 1f) * club.spreadDeg
        if (rng != null) errDeg += rng.gaussian() * (0.5f + 2.5f * over)
        val a = aim + (errDeg * PI / 180.0).toFloat()
        val d = dir(a)
        val right = Pt(d.y, -d.x)
        var carry = club.carry * lieFactor(lie, club) * pw
        val windAlong = hole.wind.x * d.x + hole.wind.y * d.y
        val windCross = hole.wind.x * right.x + hole.wind.y * right.y
        carry *= 1f + 0.012f * windAlong
        val drift = windCross * 1.4f * (carry / 150f)
        var land = start + d * carry + right * drift
        // the ball can clip trees on its way down (the last 40% of the flight)
        var hitTree = false
        run {
            val n = 40
            for (i in (n * 0.6f).toInt()..n) {
                val q = start + (land - start) * (i.toFloat() / n)
                if (hole.trees.any { it.contains(q) }) { land = start + (land - start) * (maxOf(0, i - 2).toFloat() / n); hitTree = true; return@run }
            }
        }
        val apex = maxOf(4f, carry * club.loft)
        val flightSec = (1.1f + carry / 160f).coerceAtMost(2.6f)
        val landSurface = hole.surface(land)
        if (landSurface == Surface.WATER) return ShotPlan(club, start, land, apex, flightSec, emptyList(), 0f, land, Outcome.WATER, Surface.WATER, hitTree)
        if (landSurface == Surface.OUT) return ShotPlan(club, start, land, apex, flightSec, emptyList(), 0f, land, Outcome.OUT, Surface.OUT, hitTree)
        if (land.dist(hole.pin) <= 0.25f) return ShotPlan(club, start, land, apex, flightSec, emptyList(), 0f, hole.pin, Outcome.HOLED, Surface.GREEN, hitTree)
        val rollLen = when {
            hitTree || landSurface == Surface.SAND || landSurface == Surface.TREES -> 0f
            landSurface == Surface.GREEN -> carry * club.roll * 0.6f
            landSurface == Surface.ROUGH -> carry * club.roll * 0.3f
            else -> carry * club.roll
        }
        val roll = ArrayList<Pt>()
        var pos = land
        var outcome = Outcome.STOPPED
        var walked = 0f
        while (walked < rollLen) {
            val step = minOf(0.5f, rollLen - walked)
            val next = pos + d * step
            val s = hole.surface(next)
            if (s == Surface.WATER) { outcome = Outcome.WATER; pos = next; roll.add(pos); break }
            if (s == Surface.OUT) { outcome = Outcome.OUT; pos = next; roll.add(pos); break }
            pos = next; walked += step
            roll.add(pos)
            if (pos.dist(hole.pin) <= CAPTURE_R * 1.5f && rollLen - walked < 6f) { outcome = Outcome.HOLED; pos = hole.pin; roll.add(pos); break }
            if (s == Surface.SAND || s == Surface.TREES) break
        }
        val rollSec = if (roll.isEmpty()) 0f else (0.35f + walked / 25f).coerceAtMost(1.6f)
        return ShotPlan(club, start, land, apex, flightSec, roll, rollSec, pos, outcome, if (outcome == Outcome.HOLED) Surface.GREEN else hole.surface(pos), hitTree)
    }

    /** A putt (or a putter stroke from just off the green) aimed at [aim] with stroke [power] (0..1). */
    fun putt(hole: Hole, start: Pt, aim: Float, power: Float): ShotPlan {
        var vx = sin(aim) * PUTT_VMAX * power.coerceIn(0f, 1f)
        var vy = cos(aim) * PUTT_VMAX * power.coerceIn(0f, 1f)
        var x = start.x; var y = start.y
        val dt = 1f / 120f
        val pts = ArrayList<Pt>()
        var outcome = Outcome.STOPPED
        var t = 0f
        var i = 0
        while (t < 20f) {
            val here = Pt(x, y)
            val s = hole.surface(here)
            if (s == Surface.WATER) { outcome = Outcome.WATER; break }
            if (s == Surface.OUT) { outcome = Outcome.OUT; break }
            if (s == Surface.SAND || s == Surface.TREES) break
            val mu = when (s) { Surface.GREEN -> GREEN_FRICTION; Surface.FAIRWAY, Surface.TEE -> 1.4f; else -> 3.2f }
            val speed = hypot(vx, vy)
            if (here.dist(hole.pin) <= CAPTURE_R && speed <= CAPTURE_SPEED) { outcome = Outcome.HOLED; x = hole.pin.x; y = hole.pin.y; break }
            val slope = if (s == Surface.GREEN) hole.slope else Pt(0f, 0f)
            if (speed < 0.03f && slope.len() < mu * 0.8f) break
            if (speed > 1e-4f) {
                val dec = minOf(speed, mu * dt)
                vx -= vx / speed * dec; vy -= vy / speed * dec
            }
            vx += slope.x * dt; vy += slope.y * dt
            x += vx * dt; y += vy * dt
            t += dt
            if (i++ % 4 == 0) pts.add(Pt(x, y))
        }
        val end = Pt(x, y)
        pts.add(end)
        val rollSec = t.coerceIn(0.3f, 5f)
        return ShotPlan(Club.PUTTER, start, start, 0f, 0f, pts, rollSec, end, outcome, if (outcome == Outcome.HOLED) Surface.GREEN else hole.surface(end))
    }

    /** Ball position along a plan at animation time [t] (seconds), with its height above the ground (m). */
    fun positionAt(plan: ShotPlan, t: Float): Pair<Pt, Float> {
        if (t < plan.flightSec && plan.flightSec > 0f) {
            val u = t / plan.flightSec
            val p = plan.start + (plan.land - plan.start) * u
            return p to plan.apex * 4f * u * (1f - u)
        }
        if (plan.roll.isEmpty()) return plan.end to 0f
        val u = ((t - plan.flightSec) / plan.rollSec).coerceIn(0f, 1f)
        // putts are sampled in time already; other rolls slow down (ease out)
        val e = if (plan.club == Club.PUTTER) u else 1f - (1f - u) * (1f - u)
        val idx = (e * (plan.roll.size - 1)).toInt().coerceIn(0, plan.roll.size - 1)
        return plan.roll[idx] to 0f
    }
}
