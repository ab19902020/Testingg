package com.clrg.core.games

import com.clrg.core.game.CpuLevel
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameSession
import com.clrg.core.game.MatchResult
import com.clrg.core.game.Phase
import com.clrg.core.game.PlayerInfo
import com.clrg.core.gesture.PlayerControls
import com.clrg.core.party.Settings
import com.clrg.core.render.PlayerColors
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import com.clrg.core.tracking.PlayerTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Computer opponents for solo play. Matches here are played CPU against CPU through the real match flow
 * (GameSession), which is the only way to measure them without people. This shows that every game is playable
 * against every level and that the levels are ordered; it says nothing about how the CPU compares with a real
 * person in front of the camera.
 */
class CpuOpponentTest {
    private fun players(a: Float, b: Float, aCpu: Boolean = true) = listOf(
        PlayerInfo(0, -10, "A", PlayerColors.get(0), Hand.RIGHT, MovementMode.STANDARD, cpu = aCpu, cpuSkill = a),
        PlayerInfo(1, -11, "B", PlayerColors.get(1), Hand.RIGHT, MovementMode.STANDARD, cpu = true, cpuSkill = b),
    )

    /** Plays one whole match (instructions -> countdown -> play -> finish) and returns the result. */
    private fun play(id: String, a: Float, b: Float, seed: Long): Pair<MatchResult, GameSession> {
        val env = GameEnv(players(a, b), Settings(), seed)
        val session = GameSession(GameRegistry.byId(id)!!.create(env), env)
        val controls = arrayOf(PlayerControls(0), PlayerControls(1))
        val slots = PlayerTracker(2).slots
        var t = 0.0
        while (session.phase != Phase.DONE) {
            session.update(1.0 / 60.0, controls, slots)
            t += 1.0 / 60.0
            if (t > 900.0) fail("$id did not finish (skills $a vs $b, seed $seed)")
        }
        return session.game.result() to session
    }

    @Test
    fun everyGameIsPlayableAgainstEveryLevel() {
        for (id in GameRegistry.ids) for (lv in CpuLevel.values()) {
            val (r, session) = play(id, lv.skill, lv.skill, 42)
            assertTrue(r.winner in -1..1, "$id ${lv.label}: valid result")
            assertTrue(session.game.playTime > 5.0, "$id ${lv.label}: a real match was played")
            assertTrue(r.scores.any { it > 0 } || id == "reindeer" || id == "slalom", "$id ${lv.label}: the CPU actually scores (${r.scores.toList()})")
        }
    }

    /**
     * Wins of [hi] against [lo] over several seeded matches, both orders of sides. Ties count half.
     */
    private fun winShare(id: String, hi: Float, lo: Float, seeds: Int): Double {
        var w = 0.0
        var n = 0
        for (s in 0 until seeds) {
            val (r1, _) = play(id, hi, lo, 1000L + s)
            w += when (r1.winner) { 0 -> 1.0; -1 -> 0.5; else -> 0.0 }
            val (r2, _) = play(id, lo, hi, 2000L + s)
            w += when (r2.winner) { 1 -> 1.0; -1 -> 0.5; else -> 0.0 }
            n += 2
        }
        return w / n
    }

    @Test
    fun harderLevelsWinMoreOften() {
        val easy = CpuLevel.EASY.skill
        val medium = CpuLevel.MEDIUM.skill
        val hard = CpuLevel.HARD.skill
        val rows = ArrayList<String>()
        val problems = ArrayList<String>()
        for (id in GameRegistry.ids) {
            val hardVsEasy = winShare(id, hard, easy, 4)
            val mediumVsEasy = winShare(id, medium, easy, 4)
            val hardVsMedium = winShare(id, hard, medium, 4)
            rows += "%-12s hard>easy %.2f  medium>easy %.2f  hard>medium %.2f".format(id, hardVsEasy, mediumVsEasy, hardVsMedium)
            if (hardVsEasy < 0.75) problems += "$id: Hard should clearly beat Easy, won ${"%.2f".format(hardVsEasy)}"
            if (mediumVsEasy < 0.55) problems += "$id: Medium should beat Easy more often than not, won ${"%.2f".format(mediumVsEasy)}"
            if (hardVsMedium < 0.5) problems += "$id: Hard should not lose to Medium on balance, won ${"%.2f".format(hardVsMedium)}"
        }
        println("[cpu] win shares over 8 matches each:\n" + rows.joinToString("\n"))
        assertTrue(problems.isEmpty(), problems.joinToString("; "))
    }

    @Test
    fun cpuPlayerNeverCausesATrackingPauseButTheHumanDoes() {
        val env = GameEnv(players(0f, CpuLevel.MEDIUM.skill, aCpu = false), Settings(), 7)
        val session = GameSession(GameRegistry.byId("catch")!!.create(env), env)
        val controls = arrayOf(PlayerControls(0), PlayerControls(1))
        val tracker = PlayerTracker(2).apply { solo = true }
        assertTrue(session.cpu[1] && !session.cpu[0])
        assertTrue(session.ready[1], "the CPU is ready straight away")
        session.startNow()
        repeat(60) { session.update(1.0 / 60.0, controls, tracker.slots) }
        // the camera player has never been seen: the match waits for them, and only for them
        assertEquals(Phase.TRACKING_PAUSED, session.phase)
        assertEquals(listOf(0), session.missing.toList())
    }

    @Test
    fun cpuCharacterIsAnimated() {
        val env = GameEnv(players(CpuLevel.HARD.skill, CpuLevel.HARD.skill), Settings(), 3)
        val session = GameSession(GameRegistry.byId("tennis")!!.create(env), env)
        val controls = arrayOf(PlayerControls(0), PlayerControls(1))
        val slots = PlayerTracker(2).slots
        val seen = HashSet<Int>()
        repeat(60 * 20) {
            session.update(1.0 / 60.0, controls, slots)
            val a = session.inputs[1].avatar
            assertTrue(a.valid)
            seen.add((a.upper[1] * 10).toInt())
        }
        assertTrue(seen.size > 8, "the CPU's arm moves (swings), not a frozen pose: ${seen.size} distinct angles")
    }
}
