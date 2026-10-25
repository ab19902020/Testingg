package com.clrg.core.games

import com.clrg.core.gesture.GestureEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Goalkeeping and snowball shields with a camera player whose movements reach the game late: the camera needs
 * 0.15 s and the TV shows the picture 0.1 s late. The player reacts at the last moment *as they see it*. With the
 * delays allowed for, those last-moment saves and shields count; without, they all come too late.
 */
class DelayAllowanceTest {
    private val camera = 0.15
    private val tv = 0.1

    private fun <G : com.clrg.core.game.MiniGame> GameDriver<G>.allowFor(on: Boolean) {
        env.timing.enabled = on
        env.timing.displayDelay = tv
        env.timing.observe(camera)
    }

    /** Saves by a keeper whose hand reaches each target exactly as they see the ball arrive; [late] = s the game sees it late. */
    private fun lastMomentSaves(late: Double, compensate: Boolean): Pair<Int, Int> {
        val d = GameDriver(::GoalkeeperGame, seed = 4)
        d.allowFor(compensate)
        val shots = d.game.shotList
        d.run(GoalkeeperGame.ROUND_SEC + 1.0) { t ->
            val inp = d.inputs[0]
            inp.handX[0] = -0.2f; inp.handY[0] = -1.4f; inp.handX[1] = 0.2f; inp.handY[1] = -1.4f // resting, out of the way
            for (s in shots) {
                val arrive = s.time + GoalkeeperGame.WINDUP + s.flight
                if (t >= arrive + late && t <= arrive + late + 0.3) { inp.handX[1] = s.tx; inp.handY[1] = s.ty }
            }
            d.inputs[1].handVisible.fill(false)
        }
        return d.game.saves[0] to d.game.goals[0]
    }

    @Test
    fun lastMomentSavesCountWhenTheDelayIsAllowedFor() {
        val (instant, instantGoals) = lastMomentSaves(0.0, compensate = true)
        val (allowed, allowedGoals) = lastMomentSaves(camera + tv, compensate = true)
        val (ignored, _) = lastMomentSaves(camera + tv, compensate = false)
        println("GOALIE last-moment saves: no delay $instant/${instant + instantGoals}, delay allowed for $allowed/${allowed + allowedGoals}, delay ignored $ignored")
        assertTrue(instant >= 5 && instantGoals == 0, "no delay: every last-moment save counts ($instant, goals $instantGoals)")
        assertEquals(instant, allowed, "with the delay allowed for, the same saves count")
        assertEquals(0, ignored, "ignoring the delay, every last-moment save is too late")
    }

    /** Times player 2 is hit when they raise their shield just before they see each snowball land. */
    private fun hitsWithLastMomentShields(compensate: Boolean): Int {
        val d = GameDriver(::SnowballGame, seed = 8)
        d.allowFor(compensate)
        val power = 0.5f
        val flight = 1.0 // lerp(1.15, 0.85, 0.5)
        val throwsAt = listOf(2.0, 5.0, 8.0)
        d.run(11.0) { t ->
            d.inputs[0].lean = 0f; d.inputs[1].lean = 0f
            for (th in throwsAt) {
                if (kotlin.math.abs(t - th) < d.dt / 2) d.inputs[0].events.add(GestureEvent.Throw(t, 1, 0f, power))
                // shield raised 0.1 s before the landing they see (tv late), reaching the game camera later
                val arrives = th + flight - 0.1 + tv + camera
                if (kotlin.math.abs(t - arrives) < d.dt / 2) d.inputs[1].events.add(GestureEvent.HandsUp(t))
            }
        }
        return d.game.scores[0]
    }

    @Test
    fun lastMomentShieldsCountWhenTheDelayIsAllowedFor() {
        val allowed = hitsWithLastMomentShields(compensate = true)
        val ignored = hitsWithLastMomentShields(compensate = false)
        println("SNOWBALL hits through last-moment shields: delay allowed for $allowed, delay ignored $ignored")
        assertEquals(0, allowed, "every shield raised in time (as the player saw it) blocks")
        assertEquals(3, ignored, "ignoring the delay, every shield comes too late")
    }
}
