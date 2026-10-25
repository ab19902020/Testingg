package com.clrg.core.game

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.PlayerControls
import com.clrg.core.math.Rng
import com.clrg.core.math.clamp01
import com.clrg.core.math.easeOutBack
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.tracking.PlayerSlot

enum class Phase { INSTRUCTIONS, COUNTDOWN, PLAYING, TRACKING_PAUSED, RESUMING, PAUSED, FINISHED, DONE }

/**
 * Shared match flow for every minigame: instructions -> countdown -> play -> finish. Whenever a player's tracking
 * is lost the match pauses (scoring and simulation frozen for both), the overlay names who must reposition, and play
 * resumes with a short countdown once the right person has been reacquired.
 */
class GameSession(val game: MiniGame, val env: GameEnv) {
    companion object {
        const val STEP = 1.0 / 120.0
        const val COUNTDOWN_SEC = 3.0
        const val RESUME_SEC = 3.0
        const val FINISH_SEC = 3.2
        const val REACQUIRE_SETTLE_SEC = 0.5
    }

    var phase = Phase.INSTRUCTIONS
        private set
    var phaseTime = 0.0
        private set
    val inputs = Array(2) { PlayerInput(it) }
    /** Players currently missing (indices) while [Phase.TRACKING_PAUSED]. */
    val missing = ArrayList<Int>()
    val ready = BooleanArray(2)
    var trackingPauses = 0
        private set
    private var accumulator = 0.0
    private var settle = 0.0
    private var lastCount = 4
    private val pendingEvents = Array(2) { ArrayList<GestureEvent>() }
    var allowWithoutTracking = false
    /** Called before every fixed simulation step (developer autoplay writes scripted inputs here). */
    var beforeStep: (() -> Unit)? = null

    /** Players controlled by the computer (solo mode): never tracked, always ready, driven every step. */
    val cpu = BooleanArray(2) { env.players[it].cpu }
    /** Camera players whose input is currently written by the developer autoplay (tracking input is not copied in). */
    val scripted = BooleanArray(2)
    private val cpuRng = Array(2) { Rng(env.seed * 7919 + 101 + it) }
    private val bots = Array(2) { BotBody() }
    private val newCpuEvents = ArrayList<GestureEvent>()

    init {
        for (k in 0..1) if (cpu[k]) { ready[k] = true; inputs[k].tracked = true; bots[k].reset(inputs[k]) }
    }

    /** Runs the CPU players for one simulation step (their gestures go through the same input path as a human's). */
    private fun driveCpu(dt: Float, live: Boolean) {
        for (k in 0..1) {
            if (!cpu[k]) continue
            val inp = inputs[k]
            inp.tracked = true
            newCpuEvents.clear()
            if (live) {
                val before = inp.events.size
                game.autoplay(k, inp, env.players[k].cpuSkill, cpuRng[k])
                for (i in before until inp.events.size) newCpuEvents.add(inp.events[i])
            }
            bots[k].step(inp, dt, newCpuEvents, game.cpuPosesAvatar)
        }
    }

    fun startNow() {
        if (phase == Phase.INSTRUCTIONS) enter(Phase.COUNTDOWN)
    }

    fun userPause() {
        if (phase == Phase.PLAYING || phase == Phase.COUNTDOWN || phase == Phase.RESUMING) enter(Phase.PAUSED)
    }

    fun userResume() {
        if (phase == Phase.PAUSED) enter(Phase.RESUMING)
    }

    private fun enter(p: Phase) {
        val wasLive = phase == Phase.PLAYING
        phase = p
        if (wasLive && p != Phase.PLAYING) game.onLiveChanged(false)
        if (!wasLive && p == Phase.PLAYING) game.onLiveChanged(true)
        phaseTime = 0.0
        lastCount = 4
        when (p) {
            Phase.COUNTDOWN -> { game.start(); env.audio?.playMusic(game.info.music) }
            Phase.FINISHED -> {
                if (env.sporty) { env.sfx(Sfx.FANFARE); env.sfx(Sfx.CROWD_CHEER, 0.7f) } else env.sfx(Sfx.WIN)
                env.sfx(Sfx.CONFETTI, 0.6f)
                env.audio?.playMusic(MusicTrack.VICTORY, restart = true)
                env.particles.confetti(0f, 1920f * 1.3f, -20f, 120)
            }
            Phase.TRACKING_PAUSED -> trackingPauses++
            else -> Unit
        }
    }

    /**
     * Advances the match by [dt] seconds of wall time. [controls]/[slots] are the shared tracking state for the two
     * players.
     */
    fun update(dt: Double, controls: Array<PlayerControls>, slots: Array<PlayerSlot>) {
        phaseTime += dt
        val anyLost = !allowWithoutTracking && slots.indices.any { !cpu[it] && slots[it].needsAttention }
        for (k in 0..1) {
            if (cpu[k]) continue
            // the game's wish for a more sensitive swing detector (a ball on its way) applies from the next pose
            controls[k].swingBoost = if (phase == Phase.PLAYING) inputs[k].expectSwing.coerceIn(0f, 1f) else 0f
            if (scripted[k]) { controls[k].discardEvents(); continue }
            inputs[k].fill(controls[k], slots[k].isPlaying)
            controls[k].drainEvents(pendingEvents[k])
        }
        if (phase != Phase.PLAYING) driveCpu(dt.toFloat(), live = false)
        when (phase) {
            Phase.INSTRUCTIONS -> {
                for (k in 0..1) {
                    if (pendingEvents[k].any { it is GestureEvent.RaiseHold }) {
                        if (!ready[k]) env.sfx(Sfx.SELECT)
                        ready[k] = true
                    }
                }
                discardEvents()
                if (ready[0] && ready[1] && !anyLost) enter(Phase.COUNTDOWN)
            }
            Phase.COUNTDOWN, Phase.RESUMING -> {
                discardEvents()
                if (anyLost) { markMissing(slots); enter(Phase.TRACKING_PAUSED); return }
                val total = if (phase == Phase.COUNTDOWN) COUNTDOWN_SEC else RESUME_SEC
                val count = (total - phaseTime).toInt() + 1
                if (count in 1..3 && count != lastCount) { env.sfx(Sfx.BEEP); lastCount = count }
                if (phaseTime >= total) { env.sfx(Sfx.GO); enter(Phase.PLAYING) }
            }
            Phase.PLAYING -> {
                if (anyLost) { markMissing(slots); discardEvents(); enter(Phase.TRACKING_PAUSED); return }
                for (k in 0..1) {
                    // camera gestures: when were they made, in game time, as the player saw the game?
                    for (e in pendingEvents[k]) if (e.at.isNaN()) e.at = game.playTime - env.timing.rewind(e.t)
                    inputs[k].events.addAll(pendingEvents[k]); pendingEvents[k].clear() // kept until a step consumes them
                }
                accumulator += dt.coerceAtMost(0.25)
                var steps = 0
                while (accumulator >= STEP && steps < 30) {
                    beforeStep?.invoke()
                    driveCpu(STEP.toFloat(), live = true)
                    game.update(STEP.toFloat(), inputs)
                    for (k in 0..1) if (cpu[k]) inputs[k].events.clear() // CPU gestures are for this step only
                    game.playTime += STEP
                    accumulator -= STEP
                    steps++
                    // events are delivered on the first step of the frame only
                    if (steps == 1) for (k in 0..1) if (!cpu[k]) inputs[k].events.clear()
                    if (game.isOver) break
                }
                if (game.isOver) enter(Phase.FINISHED)
            }
            Phase.TRACKING_PAUSED -> {
                discardEvents()
                markMissing(slots)
                settle = if (!anyLost) settle + dt else 0.0
                if (settle >= REACQUIRE_SETTLE_SEC) { settle = 0.0; enter(Phase.RESUMING) }
            }
            Phase.PAUSED -> discardEvents()
            Phase.FINISHED -> {
                discardEvents()
                if (phaseTime >= FINISH_SEC) enter(Phase.DONE)
            }
            Phase.DONE -> discardEvents()
        }
        env.update(dt.toFloat())
        game.animate(dt.toFloat())
    }

    private fun discardEvents() {
        for (k in 0..1) { pendingEvents[k].clear(); inputs[k].events.clear() }
    }

    private fun markMissing(slots: Array<PlayerSlot>) {
        missing.clear()
        slots.forEachIndexed { i, s -> if (!cpu[i] && s.needsAttention) missing.add(i) }
    }

    val isLive: Boolean get() = phase == Phase.PLAYING

    /** Draws the game plus countdown / finish overlays (pause and tracking overlays are drawn by the screen). */
    fun render(g: Gfx, time: Double) {
        g.save()
        g.translate(env.shake.dx, env.shake.dy)
        game.render(g, inputs, time)
        env.particles.render(g)
        env.popups.render(g)
        g.restore()
        when (phase) {
            Phase.COUNTDOWN, Phase.RESUMING -> {
                val total = if (phase == Phase.COUNTDOWN) COUNTDOWN_SEC else RESUME_SEC
                val left = total - phaseTime
                val n = left.toInt() + 1
                val k = clamp01((1.0 - (left - left.toInt())).toFloat() * 3f)
                val s = 220f * easeOutBack(k)
                g.fillRect(0f, 0f, g.width, g.height, 0x40000000)
                if (phase == Phase.RESUMING) g.text("Get ready!", g.width / 2, g.height / 2 - 250f, 64f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 8f)
                // a disc behind the number, and a ring that sweeps round each second
                val frac = (left - left.toInt()).toFloat()
                g.glow(g.width / 2, g.height / 2, 300f, 0x55FFD54F)
                g.fillCircle(g.width / 2, g.height / 2, 170f * easeOutBack(clamp01(k)), 0xCC0B1A36.toInt())
                Draw.progressRing(g, g.width / 2, g.height / 2, 170f, frac, Colors.GOLD, 14f, 0x33FFFFFF)
                g.text(n.coerceIn(1, 3).toString(), g.width / 2, g.height / 2 + 6f, s.coerceAtLeast(10f), Colors.GOLD, outline = 0xFF7A3B00.toInt(), outlineWidth = 16f)
            }
            Phase.PLAYING -> if (game.playTime < 0.7) {
                val k = (game.playTime / 0.7).toFloat()
                g.save(); g.setAlpha(1f - k)
                g.text("GO!", g.width / 2, g.height / 2, 200f + 80f * k, Colors.GOOD, outline = 0xFF0B4D2C.toInt(), outlineWidth = 16f)
                g.restore()
            }
            Phase.FINISHED -> {
                val r = game.result()
                val text = if (r.winner < 0) "It's a tie!" else "${env.name(r.winner)} wins!"
                val color = if (r.winner < 0) Colors.GOLD else env.color(r.winner)
                val k = easeOutBack(clamp01((phaseTime / 0.6).toFloat()))
                g.fillRect(0f, g.height / 2 - 130f * k, g.width, g.height / 2 + 130f * k, 0xAA000000.toInt())
                g.text(text, g.width / 2, g.height / 2, 120f * k + 1f, color, outline = Colors.BLACK, outlineWidth = 12f)
            }
            else -> Unit
        }
        Hud.captions(g, env)
    }
}
