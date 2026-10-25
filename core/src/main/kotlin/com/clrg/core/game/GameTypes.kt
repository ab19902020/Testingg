package com.clrg.core.game

import com.clrg.core.audio.AudioEngine
import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.PlayerControls
import com.clrg.core.gesture.RIGHT
import com.clrg.core.gesture.LEFT
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.party.Settings
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.render.Popups
import com.clrg.core.render.Shake
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode

/** Who is playing in this match. */
class PlayerInfo(
    val index: Int, val profileId: Int, val name: String, val color: Int, val hand: Hand, val mode: MovementMode,
    /** Controlled by the computer (solo mode), with this skill (0..1, see [CpuLevel]). */
    val cpu: Boolean = false, val cpuSkill: Float = CpuLevel.MEDIUM.skill,
)

/**
 * Snapshot of one player's motion controls for a simulation tick. Every minigame reads only this, so all ten
 * games share the same tracking and gesture system.
 */
class PlayerInput(val index: Int) {
    var tracked = false
    var lean = 0f
    var pump = 0f
    var tuck = false
    var handsUp = false
    var raiseProgress = 0f
    /** Hand positions in reach space ([LEFT], [RIGHT]): 0 = shoulder centre, 1 = comfortable reach, y up. */
    val handX = FloatArray(2)
    val handY = FloatArray(2)
    val handVisible = BooleanArray(2)
    val avatar = AvatarPose()
    val events = ArrayList<GestureEvent>()
    var dominant = RIGHT
    var mode = MovementMode.STANDARD
    /**
     * Written by the game: 0..1 how much it is waiting for this player to swing right now (a ball arriving). The
     * swing detector is made more sensitive accordingly.
     */
    var expectSwing = 0f
    /** Shooting games: where each hand aims (reach units, measured), finger gun / toy gun per hand, the gun hand. */
    val aimX = FloatArray(2)
    val aimY = FloatArray(2)
    val aimSeen = BooleanArray(2)
    val gun = BooleanArray(2)
    val cocked = BooleanArray(2)
    val fingersSeen = BooleanArray(2)
    var gunHand = RIGHT
    /** Boxing: both fists up in front of the face. */
    var guard = false
    /** Archery: 0 = no arrow, 1 = arrow nocked, 2 = drawing; how far drawn (0..1); the bow (aiming) hand. */
    var bowState = 0
    var bowDraw = 0f
    var bowHand = LEFT

    /** Copies continuous state from the player's controls (events are delivered separately). */
    fun fill(c: PlayerControls, isTracked: Boolean) {
        tracked = isTracked
        lean = c.lean
        pump = c.pumpSpeed
        tuck = c.tuck
        handsUp = c.handsUp
        raiseProgress = c.raiseProgress
        c.handX.copyInto(handX); c.handY.copyInto(handY); c.handVisible.copyInto(handVisible)
        avatar.copyFrom(c.avatar)
        dominant = if (c.calibration?.dominantHand == Hand.LEFT) LEFT else RIGHT
        mode = c.calibration?.mode ?: MovementMode.STANDARD
        val g = c.gunCtl
        g.aimX.copyInto(aimX); g.aimY.copyInto(aimY); g.aimSeen.copyInto(aimSeen); g.gun.copyInto(gun); g.fingersSeen.copyInto(fingersSeen)
        for (h in 0..1) cocked[h] = g.trigger[h].cocked
        gunHand = g.gunHand
        guard = c.punch.guard
        bowState = c.bow.state.ordinal
        bowDraw = c.bow.draw
        bowHand = c.bow.bowHand
    }

    fun clearState() {
        tracked = false; lean = 0f; pump = 0f; tuck = false; handsUp = false; raiseProgress = 0f
        handVisible.fill(false); events.clear(); aimSeen.fill(false); gun.fill(false); cocked.fill(false); fingersSeen.fill(false); guard = false
    }

    inline fun <reified T : GestureEvent> take(): T? {
        val i = events.indexOfFirst { it is T }
        return if (i >= 0) events.removeAt(i) as T else null
    }
}

/** How a game looks: the Christmas version, or the everyday sports version (daytime, athletes instead of snowmen). */
enum class GameStyle { CHRISTMAS, SPORTS }

/** Services a minigame can use. */
class GameEnv(
    val players: List<PlayerInfo>,
    val settings: Settings,
    val seed: Long,
    val audio: AudioEngine? = null,
    /** Monotonic clock (ns) - for music-synchronised games. */
    val clock: () -> Long = System::nanoTime,
    /** Camera and display delays, for judging timing by when a movement was made (off by default). */
    val timing: InputTiming = InputTiming(),
    /** Christmas or sports look (set by the game registry entry that creates the game). */
    var style: GameStyle = GameStyle.CHRISTMAS,
) {
    val sporty: Boolean get() = style == GameStyle.SPORTS

    val particles = Particles().also { it.reduced = settings.reducedEffects }
    val popups = Popups()
    val shake = Shake().also { it.reduced = settings.reducedEffects }
    val captions = Captions()

    fun rng(salt: Long = 0) = Rng(seed * 31 + salt)

    fun sfx(s: Sfx, vol: Float = 1f, pan: Float = 0f, pitch: Float = 1f) {
        audio?.play(s, vol, pan, pitch)
        if (settings.captions) captions.add(s)
    }

    fun update(dt: Float) {
        particles.update(dt)
        popups.update(dt)
        shake.update(dt)
        captions.update(dt)
    }

    fun color(i: Int) = players[i].color
    fun name(i: Int) = players[i].name
}

/** On-screen captions for sound effects (accessibility: visual equivalent of audio cues). */
class Captions {
    class Line(val text: String, var t: Float)
    val lines = ArrayList<Line>()
    fun add(s: Sfx) {
        val text = when (s) {
            Sfx.WHISTLE -> "whistle"; Sfx.CHEER -> "crowd cheers"; Sfx.WIN -> "victory bells"; Sfx.BAD -> "bonk"
            Sfx.MISS -> "miss"; Sfx.SPLAT -> "splat"; Sfx.PINS -> "pins crash"; Sfx.GO -> "go!"; Sfx.BEEP -> "beep"
            Sfx.CATCH -> "catch chime"; Sfx.SAVE -> "save!"; Sfx.CRASH -> "crash"; Sfx.SHIELD -> "shield up"
            Sfx.BANG -> "bang"; Sfx.SHATTER -> "clay shatters"; Sfx.PUNCH -> "punch lands"; Sfx.BLOCK -> "blocked"
            Sfx.RING -> "bell rings"; Sfx.TWANG -> "bow twangs"; Sfx.THUNK -> "arrow hits"
            else -> return
        }
        lines.removeAll { it.text == text }
        lines.add(Line(text, 0f))
        if (lines.size > 3) lines.removeAt(0)
    }
    fun update(dt: Float) { lines.forEach { it.t += dt }; lines.removeAll { it.t > 1.6f } }
}

class GameInfo(
    val id: String,
    val title: String,
    val tagline: String,
    /** How to play, standard controls. */
    val howTo: List<String>,
    /** Short gesture -> action list shown on the instructions card. */
    val controls: List<Pair<String, String>>,
    /** Extra notes for reduced-movement / seated players. */
    val gentleNotes: String,
    val music: MusicTrack,
    val scoreUnit: String,
    val goal: String,
)

class MatchResult(
    val gameId: String,
    val scores: IntArray,
    /** Index of the winning player, or -1 for a tie. */
    val winner: Int,
    val scoreUnit: String,
    val details: List<String> = emptyList(),
)

/**
 * A minigame. [update] runs at a fixed 120 Hz only while the match is live (never during instructions, countdowns or
 * tracking pauses), so the simulation is independent of the render frame rate and scoring is suspended fairly.
 */
abstract class MiniGame(val env: GameEnv) {
    abstract val info: GameInfo
    /** Seconds of live play so far. */
    var playTime = 0.0
        internal set

    /** Called once before the countdown. */
    open fun start() {}

    abstract fun update(dt: Float, inputs: Array<PlayerInput>)

    /** Purely cosmetic animation that may run while paused (snow, idle bobbing). Must not change game state. */
    open fun animate(dt: Float) {}

    abstract fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double)

    abstract val isOver: Boolean

    abstract fun result(): MatchResult

    /** Animated instruction illustration (a demo of the gesture, clearly an instruction - not tracking). */
    open fun demo(g: Gfx, r: Rect, t: Double) {}

    /**
     * Scripted player used by automated tests and the developer "autoplay" soak mode. It writes directly into
     * [inp] (gesture events / hand positions / lean) and is never presented as camera tracking.
     * [skill] 0..1 controls timing/accuracy errors.
     */
    open fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {}

    /** Called when live play starts or stops (pause, tracking loss, finish) - e.g. to pause synchronised music. */
    open fun onLiveChanged(live: Boolean) {}

    /** True when the game poses its CPU player's character itself in [autoplay] (the dance game). */
    open val cpuPosesAvatar: Boolean = false

    /** Games that use small, quick arm strokes (table tennis) lower the swing thresholds. */
    open val compactSwings: Boolean = false

    /** Games played with finger guns: the camera then also follows the players' fingers (costs extra tracking time). */
    open val usesFingers: Boolean = false

    /** Text for the current status line under the HUD, e.g. "Holly to serve". */
    open fun status(): String? = null

    /** Play time at which [e] was made (as the player saw the game); "now" for computer and scripted players. */
    protected fun madeAt(e: GestureEvent): Double = if (e.at.isNaN()) playTime else minOf(e.at, playTime)

    /**
     * Extra time to wait after a deadline before deciding that camera player [k] did not react: their movement may
     * still be on its way through the camera and the TV. Zero for computer players.
     */
    protected fun inputGrace(k: Int): Double = if (env.players[k].cpu) 0.0 else env.timing.grace

    /** How far behind the real body the avatar/hand data of player [k] are (zero for computer players). */
    protected fun avatarLag(k: Int): Double = if (env.players[k].cpu) 0.0 else env.timing.avatarLag

    /**
     * For camera player [k]: how long ago (s) the game moment was that the player was reacting to when their hands
     * were where the game now sees them - the hands reach the game late, and the TV shows the picture late. Zero
     * for computer players.
     */
    protected fun viewLag(k: Int): Double = if (env.players[k].cpu || !env.timing.enabled) 0.0 else env.timing.avatarLag + env.timing.displayDelay

    protected fun winnerOf(a: Int, b: Int) = if (a > b) 0 else if (b > a) 1 else -1
}
