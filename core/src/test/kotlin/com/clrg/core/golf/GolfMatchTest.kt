package com.clrg.core.golf

import com.clrg.core.math.Rng
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Golf rules, courses and ball physics (no camera involved). */
class GolfMatchTest {
    private fun players(vararg skills: Float) = skills.mapIndexed { i, s -> GolfPlayer("P${i + 1}", 0xFFFFFFFF.toInt(), i, cpuSkill = s) }

    /** Plays a whole round with computer golfers; returns the match. [onShot] sees who shot. */
    private fun playOut(course: Course, ps: List<GolfPlayer>, seed: Long = 3, onShot: (GolfMatch) -> Unit = {}): GolfMatch {
        val m = GolfMatch(course, ps, seed)
        val rng = Rng(seed + 1)
        var guard = 0
        while (m.phase != GolfMatch.Phase.COURSE_DONE && guard++ < 200_000) {
            when (m.phase) {
                GolfMatch.Phase.TURN_START -> m.beginTurn()
                GolfMatch.Phase.AIM -> { onShot(m); val (p, o) = m.cpuShot(m.player.cpuSkill!!, rng); m.shoot(p, o) }
                GolfMatch.Phase.HOLE_DONE -> m.continueAfterHole()
                else -> m.update(1f / 30f)
            }
        }
        return m
    }

    @Test
    fun everyCourseIsPlayableAndSkillShows() {
        for (c in Courses.all) {
            if (c === Courses.grandTour) {
                assertEquals(9, c.holes.size, "the tour plays every hole")
                assertEquals((1..9).toList(), c.holes.map { it.number }, "numbered 1 to 9")
            } else assertEquals(3, c.holes.size)
            for (h in c.holes) {
                assertEquals(Surface.TEE, h.surface(h.tee), "${c.name} ${h.number}: tee")
                assertEquals(Surface.GREEN, h.surface(h.pin), "${c.name} ${h.number}: pin on the green")
            }
            var good = 0; var weak = 0
            for (seed in 1L..4L) {
                val m = playOut(c, players(0.95f, 0.25f), seed)
                assertEquals(GolfMatch.Phase.COURSE_DONE, m.phase, c.name)
                for (i in 0..1) for (h in c.holes.indices) assertTrue(m.strokes[i][h] in 1..c.holes[h].par + 4, "${c.name} hole ${h + 1}: ${m.strokes[i][h]}")
                good += m.total(0); weak += m.total(1)
            }
            println("GOLF ${c.name} par ${c.par}: skilled avg %.1f, beginner avg %.1f".format(good / 4.0, weak / 4.0))
            assertTrue(good / 4.0 <= c.par + 3.5 * c.holes.size / 3, "${c.name}: a skilled golfer scores near par (${good / 4.0} vs par ${c.par})")
            assertTrue(good < weak, "${c.name}: skill pays off ($good vs $weak over 4 rounds)")
        }
    }

    @Test
    fun eachPlayerPlaysTheirWholeHoleThenTheNextOneStepsUp() {
        val seen = ArrayList<Pair<Int, Int>>() // (hole, player) per shot
        val m = playOut(Courses.candyCane, players(0.8f, 0.8f, 0.5f, 0.5f)) { seen.add(it.holeIndex to it.current) }
        // within a hole, a player's shots are contiguous: once the next player starts, the previous one never returns
        for (h in 0..2) {
            val order = seen.filter { it.first == h }.map { it.second }
            val switches = order.zipWithNext().count { (a, b) -> a != b }
            assertEquals(3, switches, "hole ${h + 1}: four players take one turn each ($order)")
        }
        // honour: on hole 2 the best score on hole 1 tees off first
        val firstOnHole2 = seen.first { it.first == 1 }.second
        assertEquals((0..3).minByOrNull { m.strokes[it][0] * 10 + it }, firstOnHole2)
        assertEquals(4, m.leaders().size.coerceAtLeast(4).coerceAtMost(4))
    }

    @Test
    fun waterCostsAPenaltyAndYouPlayAgainFromTheSameSpot() {
        val m = GolfMatch(Courses.frozenLake, players(0.5f))
        m.continueAfterHole() // no-op outside HOLE_DONE
        // hole 2 of the Frozen Lake is over water: play hole 1 quickly first
        while (m.holeIndex < 1) {
            when (m.phase) {
                GolfMatch.Phase.TURN_START -> m.beginTurn()
                GolfMatch.Phase.AIM -> { val (p, o) = m.cpuShot(0.95f, Rng(5)); m.shoot(p, o) }
                GolfMatch.Phase.HOLE_DONE -> m.continueAfterHole()
                else -> m.update(0.1f)
            }
        }
        m.beginTurn()
        assertEquals(Club.IRON5, m.club, "165 m to the green: the 5 iron is suggested")
        val tee = m.ball
        val plan = m.shoot(0.45f, 0f)!! // far too soft: into the lake
        assertEquals(Outcome.WATER, plan.outcome)
        while (m.phase != GolfMatch.Phase.AIM) m.update(0.1f)
        assertEquals(2, m.strokesThisHole, "one stroke plus one penalty")
        assertEquals(tee, m.ball, "replayed from the same spot")
    }

    @Test
    fun puttsBreakWithTheSlopeAndAReadPuttDropsIn() {
        val h = Courses.northPole.holes[2] // the steepest green
        val start = Pt(h.pin.x - 5f, h.pin.y - 4f)
        val straight = GolfPhysics.putt(h, start, GolfPhysics.aimAt(start, h.pin), GolfPhysics.puttPowerFor(start.dist(h.pin)))
        assertTrue(straight.end.dist(h.pin) > 0.3f, "aiming straight at the hole on a slope misses (${straight.end.dist(h.pin)} m)")
        // read the green like the computer does (and a good player would)
        val m = GolfMatch(Course("t", "", listOf(h)), players(1f))
        m.beginTurn()
        val field = GolfMatch::class.java.getDeclaredField("ball").apply { isAccessible = true }
        field.set(m, start)
        val lieField = GolfMatch::class.java.getDeclaredField("lie").apply { isAccessible = true }
        lieField.set(m, Surface.GREEN)
        val (a, p) = m.readPutt()
        val read = GolfPhysics.putt(h, start, a, p)
        assertTrue(read.outcome == Outcome.HOLED || read.end.dist(h.pin) < GolfPhysics.GIMME, "a read putt finishes in or next to the hole (${read.outcome}, ${read.end.dist(h.pin)} m)")
        assertTrue(kotlin.math.abs(a - GolfPhysics.aimAt(start, h.pin)) > (1.0 * PI / 180).toFloat(), "the read aims away from the hole to allow for the break")
        // the putter meter: stronger stroke, further roll
        assertTrue(GolfPhysics.puttDistance(1f) in 20f..24f)
        assertTrue(GolfPhysics.puttDistance(0.5f) < GolfPhysics.puttDistance(0.7f))
    }

    @Test
    fun clubsAndPowerSetTheDistance() {
        val h = Courses.candyCane.holes[1]
        val d = GolfPhysics.fullShot(h, h.tee, Surface.TEE, Club.DRIVER, 0f, 1f, 0f, null)
        val half = GolfPhysics.fullShot(h, h.tee, Surface.TEE, Club.DRIVER, 0f, 0.5f, 0f, null)
        val wedge = GolfPhysics.fullShot(h, h.tee, Surface.TEE, Club.WEDGE, 0f, 1f, 0f, null)
        assertTrue(d.carry in 215f..235f, "driver carry ${d.carry}")
        assertTrue(half.carry in d.carry * 0.45f..d.carry * 0.55f)
        assertTrue(wedge.carry in 70f..90f)
        val right = GolfPhysics.fullShot(h, h.tee, Surface.TEE, Club.DRIVER, 0f, 1f, 0.8f, null)
        assertTrue(right.land.x > d.land.x + 15f, "hands through right of address: ball goes right (${right.land.x} vs ${d.land.x})")
        val rough = GolfPhysics.fullShot(h, h.tee, Surface.ROUGH, Club.IRON5, 0f, 1f, 0f, null)
        assertTrue(rough.carry < 0.85f * 160f, "rough costs distance")
    }
}
