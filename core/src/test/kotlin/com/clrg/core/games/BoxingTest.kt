package com.clrg.core.games

import com.clrg.core.game.CpuLevel
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.LEFT
import com.clrg.core.gesture.PunchKind
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boxing rules at the game-input level (scripted punches, guard, leaning). Says nothing about real camera tracking. */
class BoxingTest {
    private fun driver() = GameDriver({ BoxingGame(it) }, seed = 3).apply { run(0.5) }

    private fun punch(d: GameDriver<BoxingGame>, k: Int, kind: PunchKind = PunchKind.STRAIGHT, hand: Int = RIGHT, head: Boolean = true, power: Float = 0.8f) {
        d.inputs[k].events.add(GestureEvent.Punch(0.0, hand, kind, power, 0.05f, if (head) 0.1f else -0.45f))
    }

    @Test
    fun aCleanPunchLands() {
        val d = driver()
        punch(d, 0)
        d.run(0.4)
        assertEquals(1, d.game.landed[0])
        assertTrue(d.game.hp[1] < BoxingGame.MAX_HP)
        assertTrue(d.game.points[0] > 0)
    }

    @Test
    fun guardBlocksTheHeadButNotTheBody() {
        val d = driver()
        d.inputs[1].guard = true
        punch(d, 0, head = true)
        d.run(0.4)
        assertEquals(1, d.game.blocked[1], "head punch blocked")
        assertEquals(0, d.game.landed[0])
        punch(d, 0, head = false)
        d.run(0.4)
        assertEquals(1, d.game.landed[0], "body punch goes under the guard")
    }

    @Test
    fun leaningSlipsAndDuckingDoesNotBeatTheUppercut() {
        val d = driver()
        d.inputs[1].lean = 0.6f
        punch(d, 0, PunchKind.STRAIGHT)
        d.run(0.4)
        assertEquals(1, d.game.slipped[1], "lean slips a straight")
        d.inputs[1].lean = 0f
        d.inputs[1].avatar.rise = -0.5f
        punch(d, 0, PunchKind.STRAIGHT)
        d.run(0.4)
        assertEquals(2, d.game.slipped[1], "duck under a straight")
        val before = d.game.hp[1]
        punch(d, 0, PunchKind.UPPERCUT)
        d.run(0.4)
        assertEquals(1, d.game.landed[0], "uppercut catches a ducking boxer")
        assertTrue(before - d.game.hp[1] > BoxingGame.damage(PunchKind.UPPERCUT), "and hits harder")
    }

    @Test
    fun flailingTiresYouOut() {
        val d = driver()
        punch(d, 0, PunchKind.STRAIGHT, power = 0.8f)
        d.run(0.4)
        val fresh = BoxingGame.MAX_HP - d.game.hp[1]
        // ten more punches in quick succession
        repeat(10) { i -> punch(d, 0, PunchKind.STRAIGHT, if (i % 2 == 0) LEFT else RIGHT, power = 0.8f); d.run(0.1) }
        d.run(0.3)
        val before = d.game.hp[1]
        punch(d, 0, PunchKind.STRAIGHT, power = 0.8f)
        d.run(0.4)
        val tired = before - d.game.hp[1]
        assertTrue(tired < fresh * 0.75f, "tired punch $tired vs fresh $fresh")
    }

    @Test
    fun knockdownsAndKnockout() {
        val d = driver()
        fun pummel() { var n = 0; while (!d.game.downUntil[1].isNaN().not() && d.game.koWinner < 0 && n < 60) { punch(d, 0, PunchKind.HOOK, power = 1f); d.run(0.5); n++ } }
        pummel()
        assertEquals(1, d.game.knockdowns[1])
        // punches while the opponent is down do nothing; they get up after the count
        punch(d, 0); d.run(0.5)
        d.run(BoxingGame.GETUP_SEC)
        assertTrue(d.game.downUntil[1].isNaN(), "back up")
        assertTrue(d.game.hp[1] > 0f)
        pummel(); d.run(BoxingGame.GETUP_SEC + 0.2)
        pummel()
        assertEquals(0, d.game.koWinner, "three knockdowns is a KO")
        d.run(3.0)
        assertTrue(d.game.isOver)
        assertEquals(0, d.game.result().winner)
        d.screenshot("boxing_ko")
    }

    @Test
    fun harderComputerWins() {
        var hardWins = 0
        for (seed in 1L..6L) {
            val d = GameDriver({ BoxingGame(it) }, seed = seed)
            val rng = arrayOf(Rng(seed), Rng(seed + 9))
            var shot = false
            d.runUntilOver(200.0) { t ->
                d.game.autoplay(0, d.inputs[0], CpuLevel.HARD.skill, rng[0])
                d.game.autoplay(1, d.inputs[1], CpuLevel.EASY.skill, rng[1])
                if (!shot && t > 20.0 && seed == 1L) { shot = true; d.screenshot("boxing_fight") }
            }
            assertTrue(d.game.isOver)
            if (d.game.result().winner == 0) hardWins++
            if (seed == 1L) println("[boxing] ${d.game.points.toList()} ${d.game.result().details}")
        }
        assertTrue(hardWins >= 5, "hard won $hardWins of 6")
    }
}
