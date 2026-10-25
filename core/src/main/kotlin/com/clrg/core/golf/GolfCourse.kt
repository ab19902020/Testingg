package com.clrg.core.golf

import kotlin.math.hypot

/** A point on a hole, in metres: x to the right of the tee, y towards the green. */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    fun len() = hypot(x, y)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/** An ellipse-shaped area (bunker, water, a stand of trees). */
class Blob(val x: Float, val y: Float, val rx: Float, val ry: Float) {
    fun contains(p: Pt, grow: Float = 0f): Boolean {
        val dx = (p.x - x) / (rx + grow); val dy = (p.y - y) / (ry + grow)
        return dx * dx + dy * dy <= 1f
    }
}

enum class Surface(val label: String) {
    TEE("Tee"), FAIRWAY("Fairway"), ROUGH("Rough"), SAND("Bunker"), GREEN("Green"), WATER("Water"), TREES("Trees"), OUT("Out of bounds")
}

/**
 * One hole. Everything is in metres in the hole's own frame: the tee at (0, 0), the green up the screen (+y).
 * The fairway is a centre line with a width, so doglegs are easy to describe.
 */
class Hole(
    val number: Int,
    val par: Int,
    val pin: Pt,
    val greenR: Float,
    /** Downhill direction and steepness of the green: the acceleration a rolling ball feels (m/s^2). */
    val slope: Pt,
    val fairway: List<Pt>,
    val fairwayWidth: Float,
    val bunkers: List<Blob> = emptyList(),
    val water: List<Blob> = emptyList(),
    val trees: List<Blob> = emptyList(),
    val xMin: Float, val xMax: Float, val yMax: Float,
    /** Wind in m/s (x, y). */
    val wind: Pt = Pt(0f, 0f),
) {
    val tee = Pt(0f, 0f)
    val yMin = -18f
    /** Straight-line length from tee to pin (m). */
    val length: Int get() = tee.dist(pin).toInt()

    fun surface(p: Pt): Surface {
        if (p.x < xMin || p.x > xMax || p.y < yMin || p.y > yMax) return Surface.OUT
        if (water.any { it.contains(p) }) return Surface.WATER
        if (bunkers.any { it.contains(p) }) return Surface.SAND
        if (p.dist(pin) <= greenR) return Surface.GREEN
        if (p.dist(tee) <= 6f) return Surface.TEE
        if (trees.any { it.contains(p) }) return Surface.TREES
        if (distToFairway(p) <= fairwayWidth / 2f) return Surface.FAIRWAY
        return Surface.ROUGH
    }

    fun distToFairway(p: Pt): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until fairway.size - 1) best = minOf(best, segDist(p, fairway[i], fairway[i + 1]))
        return best
    }

    /** The point on the fairway centre line about [ahead] metres (straight line) from [from], further along the hole. */
    fun fairwayPointAt(from: Pt, ahead: Float): Pt {
        var bestP = fairway.last()
        var bestErr = Float.MAX_VALUE
        // walk along the centre line in 2 m steps
        for (i in 0 until fairway.size - 1) {
            val a = fairway[i]; val b = fairway[i + 1]
            val n = (a.dist(b) / 2f).toInt().coerceAtLeast(1)
            for (j in 0..n) {
                val q = a + (b - a) * (j.toFloat() / n)
                if (q.y < from.y + 10f) continue
                val err = kotlin.math.abs(q.dist(from) - ahead)
                if (err < bestErr) { bestErr = err; bestP = q }
            }
        }
        return bestP
    }

    companion object {
        fun segDist(p: Pt, a: Pt, b: Pt): Float {
            val abx = b.x - a.x; val aby = b.y - a.y
            val l2 = abx * abx + aby * aby
            if (l2 <= 1e-6f) return p.dist(a)
            val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / l2).coerceIn(0f, 1f)
            return hypot(p.x - (a.x + abx * t), p.y - (a.y + aby * t))
        }
    }
}

class Course(val name: String, val tagline: String, val holes: List<Hole>) {
    val par: Int get() = holes.sumOf { it.par }

    /** The name shown: the Christmas name, or its summer counterpart in sports mode. */
    fun title(summer: Boolean): String = if (!summer) name else when (name) {
        "Candy Cane Links" -> "Meadow Links"
        "Frozen Lake Club" -> "Lakeside Club"
        "North Pole Peaks" -> "Highland Peaks"
        else -> name
    }
}

/** The three courses (three holes each). Original layouts. */
object Courses {
    private fun p(x: Number, y: Number) = Pt(x.toFloat(), y.toFloat())
    private fun b(x: Number, y: Number, rx: Number, ry: Number) = Blob(x.toFloat(), y.toFloat(), rx.toFloat(), ry.toFloat())

    val candyCane = Course(
        "Candy Cane Links", "Wide fairways - a gentle start",
        listOf(
            Hole(1, 3, p(0, 148), 14f, p(0.10, -0.06), listOf(p(0, 0), p(0, 148)), 30f,
                bunkers = listOf(b(-17, 140, 6, 4), b(17, 158, 5, 4)),
                trees = listOf(b(-48, 70, 10, 28), b(50, 95, 11, 30)),
                xMin = -72f, xMax = 72f, yMax = 182f, wind = p(1.2, 0.5)),
            Hole(2, 4, p(12, 328), 15f, p(-0.12, 0.05), listOf(p(0, 0), p(0, 180), p(12, 328)), 36f,
                bunkers = listOf(b(-24, 205, 7, 5), b(28, 318, 6, 5)),
                water = listOf(b(36, 236, 14, 11)),
                trees = listOf(b(-55, 120, 12, 40), b(60, 140, 12, 36), b(-45, 300, 10, 26)),
                xMin = -82f, xMax = 92f, yMax = 362f, wind = p(-1.8, 0.0)),
            Hole(3, 5, p(82, 452), 16f, p(0.08, 0.10), listOf(p(0, 0), p(0, 215), p(68, 395), p(82, 452)), 38f,
                bunkers = listOf(b(100, 436, 7, 6), b(58, 300, 8, 6), b(-22, 240, 7, 5)),
                trees = listOf(b(52, 190, 22, 34), b(-50, 380, 14, 40)),
                xMin = -72f, xMax = 152f, yMax = 488f, wind = p(0.8, -1.4)),
        ),
    )

    val frozenLake = Course(
        "Frozen Lake Club", "Water everywhere - choose your line",
        listOf(
            Hole(1, 4, p(-62, 316), 15f, p(0.14, 0.0), listOf(p(0, 0), p(0, 200), p(-62, 316)), 34f,
                bunkers = listOf(b(-32, 326, 6, 5), b(-84, 300, 6, 6)),
                water = listOf(b(-52, 178, 32, 52)),
                trees = listOf(b(48, 160, 14, 50), b(-100, 230, 14, 34)),
                xMin = -132f, xMax = 66f, yMax = 352f, wind = p(-1.5, 1.0)),
            Hole(2, 3, p(6, 166), 14f, p(-0.10, -0.12), listOf(p(4, 128), p(6, 166)), 26f,
                bunkers = listOf(b(-15, 176, 6, 5), b(22, 160, 5, 5)),
                water = listOf(b(0, 82, 62, 36)),
                trees = listOf(b(-62, 150, 10, 24), b(64, 30, 10, 26)),
                xMin = -82f, xMax = 82f, yMax = 198f, wind = p(2.0, -0.8)),
            Hole(3, 5, p(16, 470), 15f, p(0.06, -0.14), listOf(p(0, 0), p(-10, 230), p(16, 420), p(16, 470)), 34f,
                bunkers = listOf(b(-30, 250, 7, 6), b(-2, 478, 6, 5), b(34, 462, 5, 6)),
                water = listOf(b(62, 250, 28, 120), b(-58, 420, 18, 26)),
                trees = listOf(b(-70, 120, 14, 50)),
                xMin = -92f, xMax = 112f, yMax = 506f, wind = p(1.6, 1.2)),
        ),
    )

    val northPole = Course(
        "North Pole Peaks", "Narrow, tree-lined and tricky",
        listOf(
            Hole(1, 4, p(96, 362), 13f, p(-0.16, 0.06), listOf(p(0, 0), p(0, 208), p(80, 330), p(96, 362)), 28f,
                bunkers = listOf(b(-16, 226, 7, 6), b(112, 350, 6, 5), b(76, 372, 6, 4)),
                trees = listOf(b(58, 175, 30, 42), b(-42, 120, 12, 60), b(30, 290, 14, 18)),
                xMin = -62f, xMax = 152f, yMax = 396f, wind = p(-1.0, -1.8)),
            Hole(2, 5, p(10, 492), 14f, p(0.12, 0.12), listOf(p(0, 0), p(30, 180), p(-20, 340), p(10, 492)), 30f,
                bunkers = listOf(b(-42, 200, 7, 6), b(-2, 476, 6, 5), b(30, 486, 6, 5)),
                water = listOf(b(58, 322, 24, 30)),
                trees = listOf(b(-40, 90, 14, 40), b(70, 200, 14, 36), b(-70, 360, 14, 40), b(32, 410, 12, 18)),
                xMin = -102f, xMax = 122f, yMax = 526f, wind = p(2.2, 0.5)),
            Hole(3, 3, p(-6, 176), 11f, p(0.18, -0.16), listOf(p(-6, 150), p(-6, 176)), 18f,
                bunkers = listOf(b(-21, 182, 6, 5), b(9, 186, 6, 5), b(-6, 160, 9, 3.5), b(10, 168, 4, 6)),
                water = listOf(b(-46, 150, 20, 26)),
                trees = listOf(b(46, 100, 14, 40), b(-60, 60, 12, 30)),
                xMin = -82f, xMax = 72f, yMax = 206f, wind = p(-2.4, 1.0)),
        ),
    )

    val all = listOf(candyCane, frozenLake, northPole)
}
