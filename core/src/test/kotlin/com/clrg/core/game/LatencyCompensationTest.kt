package com.clrg.core.game

import com.clrg.core.games.RallyGame
import com.clrg.core.games.TennisGame
import com.clrg.core.gesture.RIGHT
import com.clrg.core.party.Settings
import com.clrg.core.pose.PoseFrame
import com.clrg.core.render.PlayerColors
import com.clrg.core.sim.Motions
import com.clrg.core.sim.Rig
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end through tracking, gestures, the match flow and tennis: a camera player who swings exactly when the
 * ball reaches them *on their TV*, with the camera and tracking delivering their movement late. Their swings must
 * count as on time when the delays are allowed for - and they do not when they are not (the "my swing is always
 * late" problem).
 */
class LatencyCompensationTest {
    private class Outcome(val p1Errors: Int, val p1Returns: Int)

    /**
     * @param cameraDelay seconds from capture to the game receiving the pose.
     * @param tvDelay seconds from the game drawing a frame to the player seeing it.
     * @param compensate whether the game allows for the delays.
     */
    private fun play(cameraDelay: Double, tvDelay: Double, compensate: Boolean, seconds: Double = 45.0): Outcome {
        val rig = Rig()
        rig.acquireAndLock()
        val timing = InputTiming().apply { enabled = compensate; displayDelay = tvDelay }
        val players = listOf(
            PlayerInfo(0, 1, "Holly", PlayerColors.get(0), Hand.RIGHT, MovementMode.STANDARD),
            PlayerInfo(1, -2, "Blitz", PlayerColors.get(1), Hand.RIGHT, MovementMode.STANDARD, cpu = true, cpuSkill = 0.9f),
        )
        val env = GameEnv(players, Settings(), 21, timing = timing)
        val game = TennisGame(env)
        val session = GameSession(game, env)
        session.startNow()

        val p = rig.p1
        val dur = 0.3
        val peaks = ArrayList<Double>() // real times at which the player's swings are fastest
        fun armX(t: Double): Float {
            for (pk in peaks) {
                val u = (t - (pk - dur / 2)) / dur
                if (u in 0.0..1.0) { Motions.swing(p, RIGHT, 0.9f, -0.5f, -0.2f, u.toFloat()); return p.wristX[RIGHT] }
                val back = (t - (pk + dur / 2)) / 0.7
                if (back in 0.0..1.0) { p.setWrist(RIGHT, -0.5f + 1.4f * back.toFloat(), -0.2f); return p.wristX[RIGHT] }
            }
            Motions.swing(p, RIGHT, 0.9f, -0.5f, -0.2f, 0f)
            return p.wristX[RIGHT]
        }

        val inFlight = ArrayDeque<PoseFrame>()
        val frameDt = 1.0 / 60.0
        var realT = rig.cam.tSec
        var tick = 0
        var plannedFor = Double.NaN
        var returns = 0
        var p1Errors = 0
        var lastScores = intArrayOf(0, 0)
        val end = realT + seconds
        while (realT < end && !game.isOver) {
            realT += frameDt
            tick++
            // the camera captures at 30 fps; each frame reaches the game cameraDelay later
            if (tick % 2 == 0) {
                armX(rig.cam.tSec + 1.0 / rig.cam.fps)
                inFlight.addLast(rig.cam.frame(rig.people))
            }
            while (inFlight.isNotEmpty() && inFlight.first().timestampNanos / 1e9 + cameraDelay <= realT + 1e-9) {
                val f = inFlight.removeFirst()
                rig.tracker.update(f)
                for (k in 0..1) rig.controls[k].update(rig.tracker.slots[k], f.timestampNanos / 1e9)
                timing.observe(cameraDelay)
            }
            timing.poseNow = realT
            // the player watches a TV that is tvDelay behind the game: when the game reaches the perfect moment at
            // real time R, the player sees it - and swings - at R + tvDelay
            if (session.phase == Phase.PLAYING) {
                if (game.state == RallyGame.State.SERVE && game.server == 0 && game.stateTime > 1f && peaks.none { it > realT - 1.0 }) peaks.add(realT + 0.2)
                if (game.state == RallyGame.State.RALLY && game.receiver == 0 && !game.hitTime.isNaN() && plannedFor != game.hitTime) {
                    plannedFor = game.hitTime
                    peaks.add(realT + (game.hitTime - game.playTime) + tvDelay)
                }
            }
            val rallyBefore = game.rally
            val receiverBefore = game.receiver
            session.update(frameDt, rig.controls, rig.tracker.slots)
            if (receiverBefore == 0 && game.rally > rallyBefore) returns++
            // in tennis the opponent only scores from Holly's mistakes (late, early or missed swings)
            if (game.scores[1] > lastScores[1]) p1Errors++
            lastScores = game.scores.copyOf()
        }
        return Outcome(p1Errors, returns)
    }

    @Test
    fun onTimeSwingsCountAsOnTimeDespiteCameraAndTvDelay() {
        val ok = play(cameraDelay = 0.16, tvDelay = 0.1, compensate = true)
        assertTrue(ok.p1Returns >= 5, "player returned ${ok.p1Returns} balls")
        assertEquals(0, ok.p1Errors, "on-time swings are never judged late or missed (returns ${ok.p1Returns})")
    }

    @Test
    fun withoutCompensationTheSameSwingsAreLate() {
        val late = play(cameraDelay = 0.16, tvDelay = 0.1, compensate = false)
        assertTrue(late.p1Errors >= 3, "uncompensated delays make on-time swings late: ${late.p1Errors} errors")
    }

    @Test
    fun noDelayNoChange() {
        val ok = play(cameraDelay = 0.0, tvDelay = 0.0, compensate = true)
        assertEquals(0, ok.p1Errors)
    }
}
