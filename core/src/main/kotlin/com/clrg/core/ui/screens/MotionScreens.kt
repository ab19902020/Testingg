package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.LEFT
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rect
import com.clrg.core.pose.HeldLearnStatus
import com.clrg.core.pose.Lm
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui

/** Where the camera picture lands inside a rectangle (same fitting as [PreviewView]), to draw on top of it. */
internal class PreviewMap(app: GameApp, r: Rect) {
    private val aspect = app.tracker.aspect
    val w: Float
    val h: Float
    val x0: Float
    val y0: Float
    init {
        var ww = r.width; var hh = ww / aspect
        if (hh > r.height) { hh = r.height; ww = hh * aspect }
        w = ww; h = hh; x0 = r.cx - ww / 2f; y0 = r.cy - hh / 2f
    }
    fun x(fx: Float) = x0 + fx / aspect * w
    fun y(fy: Float) = y0 + fy * h
}

/** Draws the held object seen for player [k] on top of a camera preview in [r]. */
internal fun drawHeldOverlay(g: Gfx, app: GameApp, k: Int, r: Rect) {
    val held = app.tracker.slots[k].metrics?.pose?.held ?: return
    val m = PreviewMap(app, r)
    val bx = m.x(held.baseX); val by = m.y(held.baseY); val tx = m.x(held.tipX); val ty = m.y(held.tipY)
    g.line(bx, by, tx, ty, 14f, 0x88000000.toInt())
    g.line(bx, by, tx, ty, 8f, Colors.GOOD)
    g.fillCircle(tx, ty, 12f, Colors.GOLD)
    g.strokeCircle(bx, by, 14f, 4f, Colors.WHITE)
}

/**
 * "Use a real object": a player holds a stick, a toy racket or a pool noodle straight up; the game learns its colours
 * and from then on follows it, so the racket, bat or club in the games points the way the real object does. The
 * line drawn over the camera picture shows what the game sees, before the player decides to keep it.
 */
class HeldObjectScreen(app: GameApp, private val kind: com.clrg.core.game.ObjectKind = com.clrg.core.game.ObjectKind.RACKET, startPlayer: Int = 0) : Screen(app) {
    override val needsCamera = true
    private enum class Step { CHOOSE, COUNTDOWN, LEARNING, LEARNED, FAILED }
    private var step = Step.CHOOSE
    private var player = startPlayer.coerceIn(0, 1)
    private val gun = kind == com.clrg.core.game.ObjectKind.GUN
    private var t = 0f
    private var result: HeldLearnStatus? = null

    private var prevKind: com.clrg.core.game.ObjectKind? = null

    override fun onEnter() { prevKind = app.objectKind; app.objectKind = kind }

    override fun onExit() {
        app.platform.cancelHeldLearning()
        app.previewHeld = null
        app.objectKind = prevKind
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, if (gun) "Use a toy gun" else "Use a real ${kind.label}", app = app)
        t += dt
        val view = Rect(40f, 160f, g.width * 0.6f, 900f)
        PreviewView.draw(g, app, view, showAreas = false, labels = false)
        drawHeldOverlay(g, app, player, view)
        val side = Rect(g.width * 0.6f + 30f, 160f, g.width - 40f, 1050f)
        Draw.panel(g, side, app.theme.panel, app.theme.panelBorder)
        val prof = app.activePlayers[player]
        val solo = app.tracker.solo
        // who is teaching
        if (!solo) for (k in 0..1) {
            val r = Rect(side.left + 30f + k * (side.width - 60f) / 2f, side.top + 30f, side.left + 20f + (k + 1) * (side.width - 60f) / 2f, side.top + 120f)
            if (ui.button(g, "heldP$k", r, app.activePlayers[k].name, accent = app.playerColor(k), selected = player == k, size = 34f, enabled = step == Step.CHOOSE || step == Step.LEARNED || step == Step.FAILED)) { player = k; reset() }
        }
        var y = side.top + if (solo) 60f else 170f
        fun line(text: String, size: Float = 32f, color: Int = Colors.WHITE, bold: Boolean = false) {
            g.text(text, side.cx, y, ui.fit(g, text, size, side.width - 60f, bold), color, bold = bold); y += size * 1.45f
        }
        val slot = app.tracker.slots[player]
        when (step) {
            Step.CHOOSE -> {
                if (gun) {
                    line("A toy gun (or anything gun-shaped)", 34f)
                    line("becomes your gun in shooting games.", 34f)
                    line("Flick it up to fire, like a recoil.", 34f)
                    y += 20f
                    line("Bright, single-coloured toys work best.", 28f, app.theme.dim)
                    line("Hold it pointing straight up in one hand,", 28f, app.theme.dim)
                    line("beside your shoulder, and keep still.", 28f, app.theme.dim)
                } else {
                    line("${kind.examples.replaceFirstChar { it.uppercase() }}", 32f)
                    line("becomes your ${kind.label} - it replaces ${kind.replaces}.", 32f)
                    y += 20f
                    line("Bright, single-coloured objects work best.", 28f, app.theme.dim)
                    line("Hold it straight up in one hand, beside", 28f, app.theme.dim)
                    line("your shoulder, and keep still.", 28f, app.theme.dim)
                }
                val has = app.hasHeldObject(player, kind)
                if (ui.button(g, "heldLearn", Rect(side.left + 40f, side.bottom - 360f, side.right - 40f, side.bottom - 250f), if (has) "Learn a new ${kind.label}" else "Learn my ${kind.label}", "for ${prof.name}", accent = Colors.GOOD, size = 40f)) {
                    step = Step.COUNTDOWN; t = 0f
                }
                if (has && ui.button(g, "heldForget", Rect(side.left + 40f, side.bottom - 230f, side.right - 40f, side.bottom - 130f), "Forget ${prof.name}'s ${kind.label}", accent = Colors.BAD, size = 32f)) {
                    app.forgetHeldObject(player, kind); app.notify("Forgotten - games use ${kind.replaces} again")
                }
                if (ui.toggle(g, "heldUse", Rect(side.left + 40f, side.bottom - 110f, side.right - 40f, side.bottom - 20f), "Use objects in games", app.settings.useHeldObjects)) {
                    app.settings.useHeldObjects = !app.settings.useHeldObjects; app.saveSettings()
                }
            }
            Step.COUNTDOWN -> {
                line("Get ready...", 48f, Colors.GOLD, true)
                line(if (gun) "Hold your toy gun pointing up," else "Hold your ${kind.label} straight up,", 34f)
                line("beside your shoulder", 34f)
                val n = 3 - t.toInt()
                g.text("$n", side.cx, side.cy + 120f, 160f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 10f)
                if (t >= 3f) {
                    val track = slot.trackHint
                    if (!slot.isPlaying || track < 0) { app.notify("Stand in front of the camera first"); step = Step.CHOOSE }
                    else { app.platform.learnHeldObject(track, player); step = Step.LEARNING; t = 0f }
                }
            }
            Step.LEARNING -> {
                val st = app.platform.heldLearnStatus()
                line("Learning...", 48f, Colors.GOLD, true)
                line(st?.message ?: "Keep still", 30f)
                Draw.progressRing(g, side.cx, side.cy + 120f, 90f, st?.progress ?: 0f, Colors.GOLD, 18f)
                if (st != null && st.done) {
                    result = st
                    if (st.ok && st.model != null) { step = Step.LEARNED; app.previewHeld = player to st.model } else step = Step.FAILED
                    app.platform.cancelHeldLearning()
                }
            }
            Step.LEARNED -> {
                line("Got it!", 56f, Colors.GOOD, true)
                line("Move it about: the green line on", 32f)
                line("the picture should follow it.", 32f)
                val seen = slot.metrics?.pose?.held != null
                line(if (seen) "Seeing it now" else "Not seeing it right now", 30f, if (seen) Colors.GOOD else Colors.WARN)
                if (ui.button(g, "heldKeep", Rect(side.left + 40f, side.bottom - 250f, side.right - 40f, side.bottom - 140f), "Keep it", "for ${prof.name}", accent = Colors.GOOD, size = 42f)) {
                    result?.model?.let { app.saveHeldObject(player, kind, it) }
                    app.previewHeld = null
                    app.notify(if (gun) "${prof.name}'s toy gun is ready for the shooting games" else "${prof.name}'s ${kind.label} is ready")
                    reset()
                    if (returnWhenKept) { app.pop(); return }
                }
                if (ui.button(g, "heldAgain", Rect(side.left + 40f, side.bottom - 120f, side.right - 40f, side.bottom - 20f), "Try again", accent = Colors.ICE, size = 34f)) reset()
            }
            Step.FAILED -> {
                line("Not this time", 48f, Colors.WARN, true)
                val msg = result?.message ?: ""
                for (part in wrap(g, msg, 30f, side.width - 80f)) line(part, 30f)
                if (ui.button(g, "heldAgain2", Rect(side.left + 40f, side.bottom - 120f, side.right - 40f, side.bottom - 20f), "Try again", accent = Colors.ICE, size = 34f)) reset()
            }
        }
        if (app.settings.simulatedInput) g.text("Needs the camera (simulated input is on)", view.cx, view.bottom + 40f, 30f, Colors.WARN, bold = false)
        else g.text(if (slot.isPlaying) "${prof.name} is seen" else "Stand in front of the camera", view.cx, view.bottom + 40f, 30f, if (slot.isPlaying) Colors.GOOD else Colors.WARN, bold = false)
    }

    private fun reset() { app.platform.cancelHeldLearning(); app.previewHeld = null; step = Step.CHOOSE; t = 0f; result = null }

    /** Opened from a game's start screen: go straight back once the object is kept. */
    var returnWhenKept = false

    private fun wrap(g: Gfx, text: String, size: Float, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (g.measureText(next, size, false) > maxW && cur.isNotEmpty()) { out.add(cur); cur = word } else cur = next
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}

/**
 * Motion test: what the game sees of each player right now - the camera picture with their skeleton and any held
 * object, how well they are tracked, a live swing meter per hand (with every recognised swing flashed and counted,
 * and which part of the arm saw it), the golf grip, the pointer, and the camera and tracking speed. For trying
 * movements out, and for screenshots when something does not work.
 */
class MotionLabScreen(app: GameApp) : Screen(app) {
    override val needsCamera = true
    private val swings = IntArray(2)
    private val flash = floatArrayOf(9f, 9f)
    private val flashText = arrayOf("", "")
    private val events = ArrayList<GestureEvent>()
    private val peak = Array(2) { FloatArray(2) }

    override fun onEnter() {
        for (c in app.controls) { c.discardEvents(); c.swingBoost = 0f }
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Motion test", app = app)
        val view = Rect(40f, 150f, g.width * 0.56f, 760f)
        PreviewView.draw(g, app, view, showAreas = false, labels = true)
        for (k in 0..1) drawHeldOverlay(g, app, k, view)
        // camera and tracking speed
        val p = app.platform.perf()
        val lines = if (app.settings.simulatedInput) listOf("Simulated input - not the camera") else listOf(
            "Camera %.0f fps  -  tracking %.0f per second  -  delay %s".format(p.cameraFps, p.inferenceFps, if (app.lastPoseLatencyMs.isNaN()) "-" else "%.0f ms".format(app.lastPoseLatencyMs)),
            "${p.model} on ${p.delegate}  -  ${p.exposure}",
        )
        var y = view.bottom + 40f
        for (l in lines) { g.text(l, view.cx, y, ui.fit(g, l, 30f, view.width, false), app.theme.dim, bold = false); y += 42f }
        if (ui.button(g, "labHeld", Rect(view.left, 900f, view.cx - 10f, 1010f), "Use a real object", "stick, toy racket, noodle", accent = Colors.GOOD, size = 36f)) app.push(HeldObjectScreen(app))
        if (ui.button(g, "labCheck", Rect(view.cx + 10f, 900f, view.right, 1010f), "Check left/right", accent = Colors.ICE, size = 36f)) {
            app.directionCheck.request(); app.notify("Raise a hand to start the left/right check")
        }
        // per player
        val px = g.width * 0.56f + 30f
        val pw = g.width - 40f - px
        val players = if (app.tracker.solo) 1 else 2
        val ph = (1050f - 150f - 20f * (players - 1)) / players
        for (k in 0 until players) playerPanel(g, ui, k, Rect(px, 150f + k * (ph + 20f), px + pw, 150f + k * (ph + 20f) + ph), dt)
    }

    private fun playerPanel(g: Gfx, ui: Ui, k: Int, r: Rect, dt: Float) {
        val ctl = app.controls[k]
        val slot = app.tracker.slots[k]
        events.clear(); ctl.drainEvents(events)
        for (e in events) if (e is GestureEvent.Swing) {
            swings[k]++
            flash[k] = 0f
            flashText[k] = "SWING " + (if (e.hand == LEFT) "left" else "right") + " hand, seen by the " + when (ctl.lastSwingSource) { 0 -> "wrist"; 1 -> "elbow"; else -> "object" }
        }
        flash[k] += dt
        Draw.panel(g, r, app.theme.panel, if (flash[k] < 0.4f) Colors.GOOD else app.theme.panelBorder)
        val c = app.playerColor(k)
        g.text(app.activePlayers[k].name, r.left + 30f, r.top + 40f, 38f, c, Align.LEFT)
        Common.qualityMeter(g, app, k, r.right - 300f, r.top + 10f, 270f)
        if (!slot.isPlaying || !ctl.hasPose) { g.text("Not seen - step into the picture", r.cx, r.cy, 32f, Colors.WARN, bold = false); return }
        // swing meters: how close each hand's movement is to counting as a swing
        var y = r.top + 150f
        for (h in intArrayOf(LEFT, RIGHT)) {
            val lvl = ctl.swingLevel[h]
            peak[k][h] = maxOf(lvl, peak[k][h] - dt * 0.8f)
            val bar = Rect(r.left + 160f, y - 18f, r.right - 40f, y + 18f)
            g.text(if (h == LEFT) "Left" else "Right", r.left + 30f, y, 28f, Colors.WHITE, Align.LEFT, bold = false)
            g.fillRoundRect(bar.left, bar.top, bar.right, bar.bottom, 18f, 0x44FFFFFF)
            val f = (peak[k][h] / 1.5f).coerceIn(0f, 1f)
            g.fillRoundRect(bar.left, bar.top, bar.left + bar.width * f, bar.bottom, 18f, if (peak[k][h] >= 1f) Colors.GOOD else Colors.WARN)
            val thr = bar.left + bar.width / 1.5f
            g.line(thr, bar.top - 6f, thr, bar.bottom + 6f, 4f, Colors.WHITE)
            y += 52f
        }
        g.text("Swings counted: ${swings[k]}", r.left + 30f, y + 4f, 28f, Colors.WHITE, Align.LEFT, bold = false)
        y += 42f
        if (flash[k] < 1.5f) g.text(flashText[k], r.left + 30f, y, ui.fit(g, flashText[k], 30f, r.width - 60f), Colors.GOOD, Align.LEFT)
        y += 46f
        // golf grip, held object, pointer
        val gap = handsGap(k)
        val grip = !gap.isNaN() && gap < com.clrg.core.gesture.GolfSwingDetector.GRIP_GAP
        val held = if (ctl.hasObject) "object seen in the ${if (ctl.avatar.propHand == LEFT) "left" else "right"} hand" else if (app.heldModelFor(k) != null) "object learned, not seen now" else "no object"
        val pointer = app.pointers[k]
        val info = listOf(
            "Golf grip: " + (if (gap.isNaN()) "hands not both seen" else if (grip) "hands together" else "hands %.1f apart".format(gap)),
            "Held: $held",
            "Pointer: " + if (pointer.active) "pointing with the ${if (pointer.hand == RIGHT) "right" else "left"} hand" else if (pointer.visible) "lift your hand a little higher" else "hand down",
        )
        for (l in info) { if (y > r.bottom - 20f) break; g.text(l, r.left + 30f, y, ui.fit(g, l, 28f, r.width - 60f, false), app.theme.dim, Align.LEFT, bold = false); y += 40f }
    }

    /** Distance between the wrists in body units (NaN unless both are seen well). */
    private fun handsGap(k: Int): Float {
        val s = app.tracker.slots[k]
        val sm = s.smooth
        if (!sm.valid || s.scale <= 0f) return Float.NaN
        if (sm.vis[Lm.LEFT_WRIST] < 0.3f || sm.vis[Lm.RIGHT_WRIST] < 0.3f) return Float.NaN
        return kotlin.math.hypot(sm.x[Lm.LEFT_WRIST] - sm.x[Lm.RIGHT_WRIST], sm.y[Lm.LEFT_WRIST] - sm.y[Lm.RIGHT_WRIST]) / s.scale
    }
}
