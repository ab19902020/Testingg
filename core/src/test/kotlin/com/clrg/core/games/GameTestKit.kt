package com.clrg.core.games

import com.clrg.core.game.GameEnv
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInfo
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.party.Settings
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.PlayerColors
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import java.io.File

/** Drives a minigame directly with synthetic gesture events / continuous inputs (game-logic tests). */
class GameDriver<G : MiniGame>(factory: (GameEnv) -> G, seed: Long = 5, modes: Array<MovementMode> = arrayOf(MovementMode.STANDARD, MovementMode.STANDARD)) {
    val env = GameEnv(
        listOf(
            PlayerInfo(0, 1, "Holly", PlayerColors.get(0), Hand.RIGHT, modes[0]),
            PlayerInfo(1, 2, "Jasper", PlayerColors.get(1), Hand.RIGHT, modes[1]),
        ),
        Settings(), seed,
    )
    val game: G = factory(env)
    val inputs = Array(2) { PlayerInput(it).apply { tracked = true; avatar.valid = true; mode = modes[it]; handVisible.fill(true) } }
    val dt = 1f / 120f
    var time = 0.0
        private set

    init { game.start() }

    /** Steps the simulation; [control] may set inputs / add events before each step. */
    fun run(seconds: Double, control: (Double) -> Unit = {}) {
        val n = (seconds / dt).toInt()
        repeat(n) {
            control(time)
            game.update(dt, inputs)
            for (i in inputs) i.events.clear()
            time += dt
            playTimeField.set(game, time)
            env.update(dt)
            if (game.isOver) return
        }
    }

    fun runUntilOver(maxSeconds: Double, control: (Double) -> Unit = {}) {
        var t = 0.0
        while (!game.isOver && t < maxSeconds) { run(1.0, control); t += 1.0 }
    }

    fun swing(player: Int, forehand: Boolean = true, power: Float = 0.6f) {
        inputs[player].events.add(GestureEvent.Swing(time, 1, -1f, 0f, power, forehand))
    }

    fun screenshot(name: String, width: Int = 1920, height: Int = 1080) {
        val g = AwtGfx(width, height)
        game.render(g, inputs, time)
        env.particles.render(g)
        env.popups.render(g)
        g.writePng(File("build/screenshots/$name.png"))
    }

    companion object {
        private val playTimeField = MiniGame::class.java.getDeclaredField("playTime").apply { isAccessible = true }
    }
}
