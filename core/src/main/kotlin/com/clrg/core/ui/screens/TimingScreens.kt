package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.TimingCalibrator
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.math.Rect
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Prop
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.abs

/**
 * Timing test: a snowball reaches a ring at a steady beat; players swing as it gets there. The median gap between
 * "the game drew it arriving" and "the swing" is the delay of this phone + TV (+ camera clock), which the timing
 * games then allow for. It needs the real camera; simulated input has no camera or TV delay to measure.
 */
class TimingTestScreen(app: GameApp) : Screen(app) {
    private enum class Phase { INTRO, RUN, RESULT }

    override val music = MusicTrack.CALM
    override val needsCamera = true
    override val allowCursors: Boolean get() = phase != Phase.RUN

    private var phase = Phase.INTRO
    internal var cal: TimingCalibrator? = null
        private set
    private var result: TimingCalibrator.Result? = null
    private val events = ArrayList<GestureEvent>()
    private var lastSwingShown = -9.0

    override fun onEnter() { for (c in app.controls) c.discardEvents() }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Timing test", app = app)
        when (phase) {
            Phase.INTRO -> intro(g, ui)
            Phase.RUN -> run(g, ui)
            Phase.RESULT -> result(g, ui)
        }
    }

    private fun intro(g: Gfx, ui: Ui) {
        val r = Rect(100f, 170f, g.width - 100f, 760f)
        val s = app.settings
        infoPanel(g, app, r, "Make swings land on time", listOf(
            "Every TV shows the picture a little late, and the camera needs a moment too.",
            "This test measures that delay for your phone and TV, so tennis, table tennis,",
            "goalkeeping, snowball dodging and dancing are judged by when you really moved.",
            "",
            "Stand where you play. A snowball will fly into a ring, ten times, at a steady beat.",
            "Swing your arm like a tennis shot just as each snowball reaches the ring.",
            "Watch the snowball - the test is about what you see on the TV.",
        ), 34f)
        val now = if (s.timingMeasured) "Current setting: ${s.displayDelayMs} ms (measured)" else "Current setting: ${s.displayDelayMs} ms (typical value, not measured yet)"
        g.text(now, g.width / 2f, 800f, 32f, app.theme.dim, bold = false)
        when {
            s.simulatedInput -> g.text("Simulated input is on: the timing test needs the real camera.", g.width / 2f, 880f, 36f, Colors.WARN)
            !app.calibrated -> {
                g.text("First, stand in front of the camera so the game can find you.", g.width / 2f, 860f, 36f, Colors.WARN)
                if (ui.button(g, "setup", Rect.ofCenter(g.width / 2f, 960f, 620f, 110f), "Get into place", accent = Colors.GOOD)) {
                    app.pop()
                    SetupFlow.start(app) { app.replace(TimingTestScreen(app)) }
                }
            }
            else -> if (ui.button(g, "start", Rect.ofCenter(g.width / 2f, 950f, 560f, 120f), "Start the test", accent = Colors.GOOD)) start()
        }
    }

    private fun start() {
        for (c in app.controls) c.discardEvents()
        cal = TimingCalibrator(app.timing.poseNow)
        result = null
        lastSwingShown = -9.0
        phase = Phase.RUN
    }

    private fun run(g: Gfx, ui: Ui) {
        val c = cal ?: return
        val now = app.timing.poseNow
        // swings from whoever is playing (computer players have no camera delay to measure)
        for (k in 0..1) {
            events.clear()
            app.controls[k].drainEvents(events)
            if (!app.tracker.slots[k].enabled) continue
            for (e in events) if (e is GestureEvent.Swing && !c.swing(e.t).isNaN()) { lastSwingShown = now; app.audio.play(Sfx.HIT, 0.5f) }
        }
        val ringX = g.width * 0.58f
        val y = 560f
        val startX = 160f
        // track
        g.fillRoundRect(startX - 40f, y + 70f, ringX + 120f, y + 84f, 7f, 0x33FFFFFF)
        // ring: flashes as a snowball arrives
        val nearest = (0 until c.cues).minOf { abs(now - c.cueTime(it)) }
        val flash = (1.0 - nearest / 0.12).coerceIn(0.0, 1.0).toFloat()
        if (flash > 0f) g.glow(ringX, y, 140f, Colors.withAlpha(Colors.GOLD, 0.6f * flash))
        g.strokeCircle(ringX, y, 70f + 10f * flash, 10f, Colors.lerp(Colors.ICE, Colors.GOLD, flash))
        // snowballs: each one flies in over APPROACH seconds and reaches the ring exactly on its cue
        for (i in 0 until c.cues) {
            val u = 1.0 - (c.cueTime(i) - now) / APPROACH
            if (u < 0.0 || u > 1.25) continue
            val x = startX + (ringX - startX) * u.toFloat()
            val a = if (u <= 1.0) 1f else (1f - ((u - 1.0) / 0.25).toFloat()).coerceIn(0f, 1f)
            g.save(); g.setAlpha(g.getAlpha() * a)
            g.fillCircleRadial(x, y, 34f, x - 10f, y - 12f, 46f, Colors.WHITE, 0xFFAFC6E4.toInt())
            g.restore()
        }
        // the player, with a racket, swinging as they swing
        val k = (0..1).firstOrNull { app.tracker.slots[it].enabled && app.tracker.slots[it].isPlaying } ?: 0
        val pose: AvatarPose? = if (app.controls[k].hasPose) app.controls[k].avatar else null
        Draw.buddy(g, ringX + 250f, y + 300f, 360f, app.playerColor(k), pose, if (now - lastSwingShown < 0.4) Expression.EXCITED else Expression.FOCUSED, Prop.RACKET, t = app.time)
        val done = c.offsets.size
        val idx = c.cueIndexAt(now)
        val msg = if (now < c.cueTime(0) - APPROACH) "Get ready..." else "Swing as the snowball reaches the ring"
        g.text(msg, g.width / 2f, 220f, 52f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 6f)
        g.text("Snowball ${minOf(idx + 1, c.cues)} of ${c.cues}   -   swings counted: $done", g.width / 2f, 290f, 34f, app.theme.dim, bold = false)
        timeline(g, c, 930f)
        if (ui.button(g, "stop", Rect(40f, 980f, 360f, 1060f), "Stop", size = 32f)) phase = Phase.INTRO
        if (c.finished(now)) {
            result = c.result()
            phase = Phase.RESULT
        }
    }

    /** Each swing as a dot: left of centre = before the snowball arrived on screen, right = after. */
    private fun timeline(g: Gfx, c: TimingCalibrator, y: Float) {
        val cx = g.width / 2f
        val half = 520f
        g.fillRoundRect(cx - half, y - 6f, cx + half, y + 6f, 6f, 0x55FFFFFF)
        g.line(cx, y - 30f, cx, y + 30f, 4f, Colors.GOLD)
        g.text("early", cx - half, y + 44f, 26f, app.theme.dim, bold = false)
        g.text("late", cx + half, y + 44f, 26f, app.theme.dim, bold = false)
        for ((i, o) in c.offsets.withIndex()) {
            val x = cx + (o / RANGE).toFloat().coerceIn(-1f, 1f) * half
            val last = i == c.offsets.size - 1
            g.fillCircle(x, y, if (last) 16f else 11f, if (last) Colors.WHITE else Colors.ICE)
        }
    }

    private fun result(g: Gfx, ui: Ui) {
        val r = Rect(160f, 190f, g.width - 160f, 760f)
        val res = result
        val c = cal
        when (res) {
            is TimingCalibrator.Result.Measured -> {
                infoPanel(g, app, r, "Delay measured: ${res.delayMs} ms", listOf(
                    "From the game drawing the snowball in the ring to your swing: ${res.delayMs} ms",
                    "(phone screen + TV + camera), from ${res.swings} swings, steady to within ${res.spreadMs} ms.",
                    "",
                    "Timing games will allow for this, so a swing that looks on time counts as on time.",
                    if (res.delayMs > 150) "That is a lot of delay: switch on your TV's Game mode, then test again." else "Tip: your TV's Game mode keeps the delay low.",
                ), 34f)
                if (ui.button(g, "use", Rect.ofCenter(g.width / 2f - 330f, 880f, 600f, 120f), "Use ${res.delayMs} ms", accent = Colors.GOOD)) {
                    app.settings.displayDelayMs = res.delayMs
                    app.settings.timingMeasured = true
                    app.saveSettings()
                    app.notify("Timing set: ${res.delayMs} ms")
                    app.pop()
                }
                if (ui.button(g, "again", Rect.ofCenter(g.width / 2f + 330f, 880f, 500f, 120f), "Test again")) start()
            }
            TimingCalibrator.Result.TooFewSwings -> {
                infoPanel(g, app, r, "Not enough swings picked up", listOf(
                    "Only ${c?.offsets?.size ?: 0} of ${c?.cues ?: 10} swings were picked up near a snowball.",
                    "Make sure your arms are in the picture, and swing clearly - a big,",
                    "quick tennis swing - each time a snowball reaches the ring.",
                ), 34f)
                if (ui.button(g, "again", Rect.ofCenter(g.width / 2f, 880f, 560f, 120f), "Try again", accent = Colors.GOOD)) start()
            }
            TimingCalibrator.Result.TooIrregular, null -> {
                infoPanel(g, app, r, "Swings were too uneven to measure", listOf(
                    "The swings were spread out too much to tell the delay apart from the timing.",
                    "Try to swing at the same moment each time - just as the snowball",
                    "touches the ring. A steady rhythm gives the best result.",
                ), 34f)
                if (ui.button(g, "again", Rect.ofCenter(g.width / 2f, 880f, 560f, 120f), "Try again", accent = Colors.GOOD)) start()
            }
        }
        c?.let { timeline(g, it, 1010f) }
    }

    override fun onBack(): Boolean {
        if (phase == Phase.RUN) { phase = Phase.INTRO; return true }
        app.pop()
        return true
    }

    companion object {
        /** Seconds each snowball takes to fly into the ring. */
        const val APPROACH = 1.3
        /** Timeline range either side of "on time" (s). */
        const val RANGE = 0.4
    }
}
