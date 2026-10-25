package com.clrg.core.games

import com.clrg.core.game.MiniGame
import com.clrg.core.math.Rng
import com.clrg.core.tracking.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays every minigame to the end with scripted players (game-input level). This tests game logic, timers, scoring,
 * endings and fairness - it says nothing about real camera tracking.
 */
class AllGamesTest {

    private fun play(id: String, skills: FloatArray, seeds: LongArray = longArrayOf(1, 2), shots: Boolean = false, modes: Array<MovementMode> = arrayOf(MovementMode.STANDARD, MovementMode.STANDARD)): GameDriver<MiniGame> {
        val entry = GameRegistry.byId(id)!!
        val d = GameDriver({ env -> entry.create(env) }, seed = 99, modes = modes)
        val rngs = arrayOf(Rng(seeds[0]), Rng(seeds[1]))
        var nextShot = 0
        val shotTimes = doubleArrayOf(6.0, 20.0)
        d.runUntilOver(420.0) { t ->
            for (k in 0..1) if (skills[k] >= 0f) d.game.autoplay(k, d.inputs[k], skills[k], rngs[k]) else idle(d, k)
            if (shots && nextShot < shotTimes.size && t >= shotTimes[nextShot]) { d.screenshot("${id}_${nextShot}"); nextShot++ }
        }
        return d
    }

    private fun idle(d: GameDriver<MiniGame>, k: Int) {
        val i = d.inputs[k]
        i.lean = 0f; i.pump = 0f; i.tuck = false
        for (h in 0..1) { i.handVisible[h] = true; i.handX[h] = if (h == 0) -0.35f else 0.35f; i.handY[h] = -0.75f }
    }

    @Test
    fun everyGameFinishesWithAValidResult() {
        for (e in GameRegistry.all) {
            val d = play(e.info.id, floatArrayOf(0.9f, 0.6f), shots = true)
            assertTrue(d.game.isOver, "${e.info.id} should finish")
            val r = d.game.result()
            assertEquals(2, r.scores.size)
            assertTrue(r.winner in -1..1, "${e.info.id} winner ${r.winner}")
            assertEquals(e.info.id, r.gameId)
            d.screenshot("${e.info.id}_end")
        }
    }

    @Test
    fun registryHasTheTenAgreedGames() {
        assertEquals(listOf("tennis", "snowball", "catch", "reindeer", "bowling", "tabletennis", "dance", "slalom", "goalie", "tree"), GameRegistry.christmas.map { it.info.id })
        assertTrue(GameRegistry.christmas.all { it.mode == GameMode.CHRISTMAS })
        assertTrue(GameRegistry.sports.all { it.mode == GameMode.SPORTS })
        assertEquals(GameRegistry.ids.size, GameRegistry.ids.toSet().size, "ids are unique")
        for (e in GameRegistry.all) {
            assertTrue(e.info.howTo.size >= 3, "${e.info.id} instructions")
            assertTrue(e.info.controls.isNotEmpty())
            assertTrue(e.info.gentleNotes.isNotBlank(), "${e.info.id} reduced-movement/seated notes")
        }
    }

    /** Identical players on identical patterns must tie: the challenge is the same in both lanes. */
    @Test
    fun identicalPlayersGetIdenticalChallenges() {
        for (id in listOf("catch", "goalie", "tree", "dance", "slalom", "reindeer")) {
            val d = play(id, floatArrayOf(1f, 1f), seeds = longArrayOf(5, 5))
            val r = d.game.result()
            assertEquals(r.scores[0], r.scores[1], "$id scores ${r.scores.toList()} ${r.details}")
            assertEquals(-1, r.winner, "$id should be a tie: ${r.details}")
        }
    }

    @Test
    fun activePlayerBeatsIdlePlayer() {
        for (id in listOf("tennis", "tabletennis", "catch", "reindeer", "bowling", "dance", "slalom", "goalie", "tree", "snowball", "clays", "boxing", "archery")) {
            val d = play(id, floatArrayOf(0.95f, -1f))
            val r = d.game.result()
            assertEquals(0, r.winner, "$id: active player should win, got ${r.scores.toList()} ${r.details}")
        }
    }

    @Test
    fun timedGamesEndOnTime() {
        val snow = play("snowball", floatArrayOf(0.5f, 0.5f))
        assertTrue(snow.game.playTime in SnowballGame.MATCH_SEC..(SnowballGame.MATCH_SEC + 2.0), "snowball ended at ${snow.game.playTime}")
        val c = play("catch", floatArrayOf(0.5f, 0.5f))
        assertTrue(c.game.playTime in PresentCatchGame.MATCH_SEC..(PresentCatchGame.MATCH_SEC + 0.1))
        val g = play("goalie", floatArrayOf(0.5f, 0.5f))
        assertTrue(g.game.playTime >= (g.game as GoalkeeperGame).totalTime)
    }

    @Test
    fun seatedAndReducedModesStillPlayable() {
        for (id in listOf("catch", "goalie", "tree", "dance", "bowling", "slalom")) {
            val d = play(id, floatArrayOf(0.9f, 0.9f), modes = arrayOf(MovementMode.SEATED, MovementMode.REDUCED))
            assertTrue(d.game.isOver, id)
        }
        val dance = play("dance", floatArrayOf(0.9f, 0.9f), modes = arrayOf(MovementMode.SEATED, MovementMode.STANDARD)).game as DanceOffGame
        assertTrue(dance.shapes === com.clrg.core.gesture.PoseShape.GENTLE_SET, "gentle pose set for both when anyone is seated")
        val slalom = play("slalom", floatArrayOf(0.9f, 0.9f), modes = arrayOf(MovementMode.REDUCED, MovementMode.STANDARD)).game as SleighSlalomGame
        assertTrue(slalom.steeringOnly)
    }
}
