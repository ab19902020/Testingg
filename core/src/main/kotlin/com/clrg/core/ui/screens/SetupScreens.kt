package com.clrg.core.ui.screens

import com.clrg.core.app.CameraState
import com.clrg.core.app.GameApp
import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.math.Rect
import com.clrg.core.pose.Lm
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.tracking.BodyMetrics
import com.clrg.core.tracking.CalibrationProcess
import com.clrg.core.tracking.CalibrationStep
import com.clrg.core.tracking.MovementMode
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.sin

/** Decides which setup steps are needed before [then] runs (usually: start a match). */
object SetupFlow {
    fun cameraReady(app: GameApp) = app.settings.simulatedInput || app.platform.cameraStatus().state == CameraState.RUNNING

    fun start(app: GameApp, then: () -> Unit) {
        when {
            !cameraReady(app) -> app.push(CameraSetupScreen(app, then))
            !app.calibrated -> app.push(FramingScreen(app, then))
            else -> app.push(ReadyScreen(app, then))
        }
    }

    /** The full guided set-up from the main menu: TV, phone placement, framing, calibration, ready. */
    fun startFull(app: GameApp) {
        app.leaveSolo() // the full guided set-up is for two people; solo matches set up their single player themselves
        app.push(TvSetupScreen(app) { app.popToRoot() })
    }

    fun stepLabel(g: Gfx, step: Int, of: Int = 7) {
        g.text("Step $step of $of", g.width - 160f, 70f, 32f, Colors.TEXT_DIM)
    }
}

class TvSetupScreen(app: GameApp, private val then: () -> Unit) : Screen(app) {
    override val music = MusicTrack.CALM

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "1. Connect the phone to your TV", app = app)
        SetupFlow.stepLabel(g, 1)
        val d = app.platform.displayStatus()
        val r = Rect(60f, 170f, g.width * 0.56f, 900f)
        infoPanel(g, app, r, "Wired TV connection", listOf(
            "1. Plug a USB-C to HDMI adapter or cable into the phone.",
            "2. Connect it to the TV and choose that HDMI input on the TV.",
            "3. The game appears on the TV (mirrored, or as a TV-only screen",
            "    when the phone supports a second screen).",
            "",
            "Not every phone can send video over USB-C - even very fast ones.",
            "Check the phone's specifications for 'DisplayPort Alt Mode'",
            "or 'USB-C video out'. Screen casting also works but adds delay.",
        ), 34f)
        // illustration: phone -> cable -> TV
        val ix = g.width * 0.6f
        g.fillRoundRect(ix + 120f, 220f, ix + 640f, 520f, 18f, 0xFF0A0F18.toInt())
        g.strokeRoundRect(ix + 120f, 220f, ix + 640f, 520f, 18f, 8f, 0xFF37474F.toInt())
        g.save(); g.clipRect(ix + 130f, 230f, ix + 630f, 510f)
        Draw.nightSky(g, Rect(ix + 130f, 230f, ix + 630f, 510f), app.time)
        Draw.snowHills(g, Rect(ix + 130f, 230f, ix + 630f, 510f), 470f)
        g.restore()
        g.fillRect(ix + 360f, 520f, ix + 400f, 570f, 0xFF37474F.toInt())
        g.fillRoundRect(ix + 280f, 565f, ix + 480f, 585f, 8f, 0xFF37474F.toInt())
        g.text(if (d.externalConnected) "TV detected" else "TV", ix + 380f, 370f, 54f, if (d.externalConnected) Colors.GOOD else Colors.TEXT_DIM)
        g.fillRoundRect(ix + 40f, 640f, ix + 230f, 735f, 16f, 0xFF263238.toInt())
        g.fillRoundRect(ix + 52f, 650f, ix + 218f, 725f, 10f, 0xFF4FC3F7.toInt())
        g.polyline(floatArrayOf(ix + 230f, 690f, ix + 300f, 690f, ix + 330f, 600f, ix + 380f, 580f), 4, 8f, 0xFFB0BEC5.toInt())
        val status = when {
            d.presentationActive -> "Connected: ${d.externalName ?: "TV"} ${d.externalWidth}x${d.externalHeight} - game on TV, phone shows the camera view"
            d.externalConnected -> "Connected: ${d.externalName ?: "external display"} ${d.externalWidth}x${d.externalHeight} @ ${d.externalRefreshHz.toInt()} Hz"
            else -> "No TV detected yet. You can also play on the phone screen."
        }
        g.text(status, g.width * 0.78f, 800f, ui.fit(g, status, 32f, g.width * 0.4f, false), if (d.externalConnected) Colors.GOOD else Colors.WARN, bold = false)
        if (ui.button(g, "next", Rect(g.width - 560f, 930f, g.width - 60f, 1040f), "Next: place the phone", accent = Colors.GOOD, size = 40f)) app.replace(CameraSetupScreen(app, then, full = true))
    }
}

class CameraSetupScreen(app: GameApp, private val then: () -> Unit, private val full: Boolean = false) : Screen(app) {
    override val music = MusicTrack.CALM
    override val needsCamera = true

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "2. Place the phone", app = app)
        if (full) SetupFlow.stepLabel(g, 2)
        val tips = Rect(40f, 160f, 760f, 1010f)
        infoPanel(g, app, tips, "Where to put it", listOf(
            "Landscape, on a stand or shelf",
            "near the TV - secure, not handheld.",
            "Camera facing the players,",
            "about chest height.",
        ) + (if (app.solo) listOf("Stand 2 - 3 m away, in the middle", "of the picture.") else listOf("Stand 2 - 3 m away, side by side,", "with an arm's length between you.")) + listOf(
            "Light the room well; avoid a bright",
            "window behind you.",
        ), 32f)
        val st = app.platform.cameraStatus()
        when (st.state) {
            CameraState.NEEDS_PERMISSION, CameraState.DENIED -> {
                g.text("The camera permission is needed.", 1300f, 400f, 40f, Colors.WARN)
                if (ui.button(g, "perm", Rect(1050f, 470f, 1550f, 580f), "Camera permission...")) app.push(PermissionScreen(app))
            }
            else -> PreviewView.draw(g, app, Rect(800f, 160f, g.width - 40f, 690f), showAreas = true)
        }
        // camera choice
        val opts = app.platform.cameraOptions()
        var x = 800f
        g.text("Camera:", x, 730f, 34f, Colors.WHITE, Align.LEFT)
        x += 150f
        for (o in opts.take(3)) {
            val label = "${o.label} (~${o.horizontalFovDeg.toInt()}°)"
            val w = 360f
            val sel = (app.settings.cameraId ?: st.activeId) == o.id
            if (ui.button(g, "cam_${o.id}", Rect(x, 690f, x + w, 770f), label, size = 28f, selected = sel)) app.selectCamera(o.id)
            x += w + 16f
        }
        val wideAvail = opts.any { it.wideAvailable && it.id == (app.settings.cameraId ?: st.activeId) }
        if (ui.toggle(g, "wide", Rect(800f, 790f, 1300f, 870f), "Wide angle", app.settings.wideAngle, enabled = wideAvail)) { app.settings.wideAngle = !app.settings.wideAngle; app.saveSettings() }
        if (ui.toggle(g, "mirror", Rect(1320f, 790f, g.width - 40f, 870f), "Mirror image", app.mirrorNow)) app.flipLeftRight()
        val ok = SetupFlow.cameraReady(app)
        if (ui.button(g, "next", Rect(g.width - 560f, 900f, g.width - 40f, 1010f), "Next: get into place", accent = Colors.GOOD, enabled = ok, size = 40f)) app.replace(FramingScreen(app, then, full))
    }
}

/** Live framing: both players get into their starting areas; identities come from the areas. */
class FramingScreen(app: GameApp, private val then: () -> Unit, private val full: Boolean = false) : Screen(app) {
    override val music = MusicTrack.CALM
    override val needsCamera = true
    override val allowCursors = false
    private var stable = 0f

    override fun onEnter() {
        app.tracker.unlock(clear = true)
        app.controls.forEach { it.calibration = null; it.reset() }
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, if (app.solo) "3. Stand in the picture" else "3. Stand in your areas", app = app)
        if (full) SetupFlow.stepLabel(g, 3)
        PreviewView.draw(g, app, Rect(260f, 150f, g.width - 260f, 860f))
        Common.qualityMeter(g, app, 0, 20f, 260f, 220f)
        if (!app.solo) Common.qualityMeter(g, app, 1, g.width - 240f, 260f, 220f)
        if (app.solo) {
            val (msg, ok) = hint(0)
            val text = "${app.activePlayers[0].name}: $msg"
            g.fillRoundRect(g.width / 2f - 520f, 880f, g.width / 2f + 520f, 960f, 30f, if (ok) 0xCC0B4D2C.toInt() else 0xCC000000.toInt())
            g.text(text, g.width / 2f, 920f, ui.fit(g, text, 34f, 1000f), if (ok) Colors.GOOD else app.playerColor(0))
        }
        for (k in 0..1) {
            if (app.solo) break
            val (msg, ok) = hint(k)
            val x = if (k == 0) g.width * 0.27f else g.width * 0.73f
            val c = app.playerColor(k)
            g.fillRoundRect(x - 420f, 880f, x + 420f, 960f, 30f, if (ok) 0xCC0B4D2C.toInt() else 0xCC000000.toInt())
            g.text("${app.activePlayers[k].name}: $msg", x, 920f, ui.fit(g, "${app.activePlayers[k].name}: $msg", 34f, 800f), if (ok) Colors.GOOD else c)
        }
        val both = app.tracker.allPlaying
        stable = if (both) stable + dt else 0f
        if (both) Draw.progressRing(g, g.width / 2f, 1010f, 34f, stable / 1.5f, Colors.GOOD, 9f)
        if (!app.solo && ui.button(g, "swap", Rect(40f, 980f, 420f, 1060f), "Swap sides", size = 34f)) {
            val a = app.activePlayers[0]; app.activePlayers[0] = app.activePlayers[1]; app.activePlayers[1] = a
        }
        val camX = if (app.solo) 40f else 440f
        if (app.canSwitchCamera && ui.button(g, "camSwitch", Rect(camX, 980f, camX + 420f, 1060f), if (app.currentCamera()?.front == true) "Use back camera" else "Use front camera", size = 32f, accent = Colors.ICE)) {
            app.switchCamera()
            stable = 0f
        }
        // movements going the wrong way? one tap fixes it for this camera
        if (ui.button(g, "flipLR", Rect(g.width - 470f, 150f, g.width - 40f, 230f), "Flip left/right", "if moves look backwards", accent = Colors.ICE, size = 30f)) {
            app.flipLeftRight()
            stable = 0f
        }
        val go = stable >= 1.5f
        if (ui.button(g, "next", Rect(g.width - 480f, 980f, g.width - 40f, 1060f), "Continue", accent = Colors.GOOD, enabled = both, size = 36f) || go) {
            app.audio.play(Sfx.SELECT)
            app.replace(CalibrationScreen(app, then, full))
        }
    }

    /** Plain-language framing hint for player [k]. */
    private fun hint(k: Int): Pair<String, Boolean> {
        val slot = app.tracker.slots[k]
        val inArea = app.tracker.lastCandidates.filter { slot.area.contains(it.shoulderX) }
        val m: BodyMetrics = slot.metrics ?: inArea.maxByOrNull { it.scale } ?: return (if (app.solo) "step into the middle of the picture" else if (k == 0) "step into the LEFT area" else "step into the RIGHT area") to false
        if (inArea.size > 1 && inArea.count { it.scale > 0.6f * m.scale } > 1) return (if (app.solo) "solo play: only one person in front, please" else "only one player per side, please") to false
        val p = m.pose
        if (p.y[Lm.NOSE] < 0.06f || p.vis(Lm.NOSE) < 0.3f) return "step back - your head is cut off" to false
        if (p.vis(Lm.LEFT_WRIST) < 0.3f || p.vis(Lm.RIGHT_WRIST) < 0.3f) return "make sure both hands are visible" to false
        if (m.scale > 0.42f) return "too close - take a step back" to false
        if (m.scale < 0.09f) return "come a little closer" to false
        if (!slot.isPlaying) return "hold still..." to false
        return (if (m.hipsVisible) "great - you're in!" else "great (seated / hips hidden is OK)") to true
    }
}

/** Per-player calibration of neutral pose, body size, comfortable reach and playing hand. */
class CalibrationScreen(app: GameApp, private val then: () -> Unit, private val full: Boolean = false) : Screen(app) {
    override val music = MusicTrack.CALM
    override val needsCamera = true
    override val allowCursors = false
    private val modes = Array(2) { app.activePlayers[it].mode }
    private val procs = Array(2) { CalibrationProcess(modes[it], app.activePlayers[it].hand) }
    private var doneTime = 0f

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "4. Calibrate", app = app)
        if (full) SetupFlow.stepLabel(g, 4)
        if (app.solo) {
            val slot = app.tracker.slots[0]
            if (slot.isPlaying) procs[0].update(slot, dt)
            panel(g, ui, 0, Rect(g.width / 2f - 560f, 150f, g.width / 2f + 360f, 1050f))
            PreviewView.draw(g, app, Rect(g.width / 2f + 390f, 160f, g.width - 40f, 520f), labels = false)
            ui.paragraph(g, listOf("Only move as far", "as is comfortable -", "the games adapt", "to your reach."), g.width / 2f + 390f + (g.width / 2f - 430f) / 2f, 600f, 30f, app.theme.dim, align = Align.CENTER)
        } else {
            for (k in 0..1) {
                val slot = app.tracker.slots[k]
                if (slot.isPlaying) procs[k].update(slot, dt)
                panel(g, ui, k, Rect(if (k == 0) 40f else g.width / 2f + 190f, 150f, if (k == 0) g.width / 2f - 190f else g.width - 40f, 1050f))
            }
            PreviewView.draw(g, app, Rect(g.width / 2f - 170f, 160f, g.width / 2f + 170f, 360f), labels = false)
            ui.paragraph(g, listOf("Both players", "calibrate at", "the same time."), g.width / 2f, 420f, 30f, app.theme.dim, align = Align.CENTER)
        }
        if (procs[0].isDone && (app.solo || procs[1].isDone)) {
            doneTime += dt
            if (doneTime > 0.8f) finish()
        }
    }

    private fun panel(g: Gfx, ui: Ui, k: Int, r: Rect) {
        val c = app.playerColor(k)
        Draw.panel(g, r, app.theme.panel, c)
        val p = procs[k]
        val prof = app.activePlayers[k]
        g.text("P${k + 1}  ${prof.name}", r.cx, r.top + 50f, 46f, c)
        // movement mode
        val mw = (r.width - 80f) / 3f
        MovementMode.values().forEachIndexed { i, m ->
            val mr = Rect(r.left + 30f + i * (mw + 10f), r.top + 95f, r.left + 30f + i * (mw + 10f) + mw, r.top + 175f)
            if (ui.button(g, "mode${k}_$i", mr, m.label, size = 28f, selected = modes[k] == m, accent = c)) {
                modes[k] = m
                prof.mode = m
                app.saveRoster()
                procs[k] = CalibrationProcess(m, prof.hand)
            }
        }
        val slot = app.tracker.slots[k]
        val step = p.step
        g.text(step.title, r.cx, r.top + 240f, 54f * app.theme.textScale, Colors.WHITE)
        val instr = if (!slot.isPlaying) "Step back into your area" else step.instruction
        g.text(instr, r.cx, r.top + 300f, ui.fit(g, instr, 34f, r.width - 60f, false), if (slot.isPlaying) app.theme.dim else Colors.WARN, bold = false)
        // demonstration buddy (left) and the player's live mirror (right)
        val demo = AvatarPose().apply { valid = true }
        val wave = sin(app.time * 3).toFloat() * 0.1f
        when (step) {
            CalibrationStep.NEUTRAL -> { demo.upper[0] = -1.8f; demo.fore[0] = -1.75f; demo.upper[1] = -1.34f; demo.fore[1] = -1.4f }
            CalibrationStep.ARMS_OUT -> { demo.upper[0] = 3.14f; demo.fore[0] = 3.14f; demo.upper[1] = 0f + wave; demo.fore[1] = 0f + wave }
            CalibrationStep.REACH_UP -> { demo.upper[0] = 1.75f; demo.fore[0] = 1.7f; demo.upper[1] = 1.4f; demo.fore[1] = 1.45f }
            CalibrationStep.PLAYING_HAND, CalibrationStep.DONE -> { demo.upper[0] = -1.8f; demo.fore[0] = -1.75f; demo.upper[1] = 1.4f + wave; demo.fore[1] = 1.5f }
        }
        Draw.buddy(g, r.left + r.width * 0.25f, r.bottom - 150f, 340f, 0xFF90A4AE.toInt(), demo, Expression.HAPPY, t = app.time)
        g.text("Do this", r.left + r.width * 0.25f, r.bottom - 112f, 28f, app.theme.dim)
        Draw.buddy(g, r.left + r.width * 0.75f, r.bottom - 150f, 340f, c, if (app.controls[k].avatar.valid && slot.isPlaying) app.controls[k].avatar else null, Expression.FOCUSED, t = app.time)
        g.text("You", r.left + r.width * 0.75f, r.bottom - 112f, 28f, app.theme.dim)
        Draw.progressRing(g, r.cx, r.top + 420f, 60f, if (p.isDone) 1f else p.progress, if (p.isDone) Colors.GOOD else c, 14f)
        if (p.isDone) g.text("OK", r.cx, r.top + 420f, 44f, Colors.GOOD)
        val stepsDone = CalibrationStep.values().indexOf(step)
        g.text("Step ${minOf(stepsDone + 1, 4)} of 4", r.cx, r.top + 510f, 28f, app.theme.dim)
        if (!p.isDone && ui.button(g, "skip$k", Rect(r.cx - 220f, r.bottom - 90f, r.cx + 220f, r.bottom - 20f), "Skip (use defaults)", size = 28f, enabled = slot.isPlaying)) {
            p.skipWithDefaults(slot, prof.hand ?: com.clrg.core.tracking.Hand.RIGHT)
        }
    }

    private fun finish() {
        for (k in 0..1) {
            if (app.solo && k > 0) continue
            val cal = procs[k].result ?: continue
            app.tracker.slots[k].calibration = cal
            app.controls[k].calibration = cal
            app.activePlayers[k].hand = cal.dominantHand
        }
        app.saveRoster()
        app.tracker.lock()
        app.audio.play(Sfx.SPARKLE)
        app.replace(ReadyScreen(app, then, full))
    }
}

/** "Both players: raise a hand and hold" - the deliberate start gesture. */
class ReadyScreen(app: GameApp, private val then: () -> Unit, private val full: Boolean = false) : Screen(app) {
    override val music = MusicTrack.CALM
    override val needsCamera = true
    override val allowCursors = false
    private val ready = BooleanArray(2)
    private val events = ArrayList<GestureEvent>()

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, if (full) "5. Ready!" else "Get ready", app = app)
        if (full) SetupFlow.stepLabel(g, 7)
        for (k in 0..1) {
            val x = if (k == 0) g.width * 0.27f else g.width * 0.73f
            val c = app.playerColor(k)
            if (app.solo && k == 1) {
                // the computer opponent is always ready
                ready[1] = true
                val p = AvatarPose().apply { valid = true; upper[0] = -1.9f; fore[0] = -1.8f; upper[1] = 1.2f + 0.2f * sin(app.time * 4).toFloat(); fore[1] = 1.8f }
                Draw.buddy(g, x, 860f, 420f, c, p, Expression.EXCITED, t = app.time)
                g.text("${app.activePlayers[1].name} (CPU)", x, 905f, 46f, c, outline = Colors.BLACK, outlineWidth = 5f)
                Draw.progressRing(g, x, 320f, 66f, 1f, Colors.GOOD, 16f)
                g.text("READY", x, 320f, 36f, Colors.GOOD)
                val msg = "${app.cpuLevel?.label ?: ""} computer player"
                val mw = g.measureText(msg, 32f, false) + 50f
                g.fillRoundRect(x - mw / 2f, 930f, x + mw / 2f, 980f, 25f, 0xCC000000.toInt())
                g.text(msg, x, 955f, 32f, Colors.WHITE, bold = false)
                continue
            }
            events.clear()
            app.controls[k].drainEvents(events)
            if (events.any { it is GestureEvent.RaiseHold } && app.tracker.slots[k].isPlaying) { if (!ready[k]) app.audio.play(Sfx.SELECT); ready[k] = true }
            val slot = app.tracker.slots[k]
            Draw.buddy(g, x, 860f, 420f, c, if (slot.isPlaying) app.controls[k].avatar else null, if (ready[k]) Expression.EXCITED else Expression.HAPPY, t = app.time)
            val prog = if (ready[k]) 1f else app.controls[k].raiseProgress
            Draw.progressRing(g, x, 320f, 66f, prog, if (ready[k]) Colors.GOOD else c, 16f)
            g.text(if (ready[k]) "READY" else "${(prog * 100).toInt()}%", x, 320f, 36f, if (ready[k]) Colors.GOOD else Colors.WHITE)
            g.text(app.activePlayers[k].name, x, 905f, 46f, c, outline = Colors.BLACK, outlineWidth = 5f)
            val msg = if (!slot.isPlaying) "Step back into view" else if (ready[k]) "Ready!" else "Raise a hand and hold it up"
            val mw = g.measureText(msg, 32f, false) + 50f
            g.fillRoundRect(x - mw / 2f, 930f, x + mw / 2f, 980f, 25f, 0xCC000000.toInt())
            g.text(msg, x, 955f, 32f, if (slot.isPlaying) Colors.WHITE else Colors.WARN, bold = false)
        }
        if (app.settings.simulatedInput && app.sim.autoplay) { ready[0] = true; ready[1] = true } // developer soak mode
        g.text(if (app.solo) "Raise a hand and hold it to start" else "Both players: raise a hand and hold", g.width / 2f, 190f, 50f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 6f)
        if (ui.button(g, "recal", Rect(40f, 980f, 460f, 1060f), "Recalibrate", size = 32f)) app.replace(FramingScreen(app, then, full))
        val go = ready[0] && ready[1]
        if (ui.button(g, "start", Rect(g.width - 460f, 980f, g.width - 40f, 1060f), "Start now", accent = Colors.GOOD, size = 34f) || go) {
            for (c in app.controls) c.discardEvents()
            then()
        }
    }
}
