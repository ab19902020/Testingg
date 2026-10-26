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

    private val shots = IntArray(2)
    private val punches = IntArray(2)
    private val arrows = IntArray(2)

    /**
     * Live graph of one move's signal for both players over the last few seconds, with the lines it has to cross and
     * a mark where the move fired: shows what the game reads from you (and a screenshot of it says what to tune).
     */
    private enum class Scope(val label: String) {
        OFF("Graph: off"), THUMB("Graph: thumb"), BOWL("Graph: bowling"), LEAN("Graph: lean + tuck"), BOW("Graph: bow"), SWING("Graph: swing")
    }
    private var scope = Scope.OFF
    private var clock = 0.0
    private val scopeT = DoubleArray(SCOPE_N)
    /** Per player, per signal (thumb, lowest wrist, lean, bow draw, swing, tuck, bow release speed). */
    private val scopeV = Array(2) { Array(7) { FloatArray(SCOPE_N) } }
    private var scopeN = 0
    private val marks = ArrayList<Mark>()
    private class Mark(val t: Double, val player: Int, val kind: Scope, val label: String)

    override fun onEnter() {
        // follow the fingers too, so finger guns can be tried out here
        for (c in app.controls) { c.discardEvents(); c.swingBoost = 0f; c.wantFingers = true }
    }

    override fun onExit() {
        for (c in app.controls) c.wantFingers = false
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Motion test", app = app)
        val view = Rect(40f, 150f, g.width * 0.56f, 730f)
        PreviewView.draw(g, app, view, showAreas = false, labels = true)
        for (k in 0..1) drawHeldOverlay(g, app, k, view)
        // camera and tracking speed
        sampleScope(dt)
        if (scope != Scope.OFF) drawScope(g, Rect(view.left + 12f, view.top + view.height * 0.4f, view.right - 12f, view.bottom - 12f))
        val p = app.platform.perf()
        val lines = if (app.settings.simulatedInput) listOf("Simulated input - not the camera") else listOf(
            "Camera %.0f fps  -  tracking %.0f per second, %.0f ms each  -  delay %s".format(p.cameraFps, p.inferenceFps, p.inferenceMs,
                if (app.lastPoseLatencyMs.isNaN()) "-" else "%.0f ms".format(app.lastPoseLatencyMs)),
            "${p.model} on ${p.delegate}  -  ${p.exposure}",
            "Fingers: ${p.fingers}  -  low-light boost: ${p.lowLight}  -  aids: ${if (p.aids) "on" else "off"}  -  heat: ${p.thermal}",
        )
        var y = view.bottom + 30f
        for (l in lines) { g.text(l, view.cx, y, ui.fit(g, l, 27f, view.width, false), app.theme.dim, bold = false); y += 35f }
        // tracking held back by the phone: say so plainly (a screenshot of this screen tells the whole story)
        val warn = if (app.settings.simulatedInput) null else p.warning()
        val health = warn ?: if (p.inferenceFps > 0.5f) "Tracking keeps up" else null
        if (health != null) g.text(health, view.cx, y, ui.fit(g, health, 27f, view.width, true), if (warn != null) Colors.GOLD else Colors.GOOD, bold = true)
        val bw = (view.width - 40f) / 3f
        if (ui.button(g, "labScope", Rect(view.left, 900f, view.left + bw, 1010f), scope.label, "tap or hold: next move", accent = Colors.GOLD, size = 32f)) {
            scope = Scope.values()[(scope.ordinal + 1) % Scope.values().size]
        }
        if (ui.button(g, "labHeld", Rect(view.left + bw + 20f, 900f, view.left + 2 * bw + 20f, 1010f), "Use a real object", "stick, toy racket, noodle", accent = Colors.GOOD, size = 32f)) app.push(HeldObjectScreen(app))
        if (ui.button(g, "labCheck", Rect(view.right - bw, 900f, view.right, 1010f), "Check left/right", accent = Colors.ICE, size = 32f)) {
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
        for (e in events) {
            val side = if (e.hand == LEFT) "left" else "right"
            val text = when (e) {
                is GestureEvent.Swing -> { swings[k]++; marks.add(Mark(clock, k, Scope.SWING, "swing")); "SWING $side hand, seen by the " + when (ctl.lastSwingSource) { 0 -> "wrist"; 1 -> "elbow"; else -> "object" } }
                is GestureEvent.Shot -> { shots[k]++; marks.add(Mark(clock, k, Scope.THUMB, if (e.byThumb) "BANG" else "flick")); "BANG! $side hand - " + if (e.byThumb) "thumb dropped" else "recoil flick" }
                is GestureEvent.Punch -> { punches[k]++; "PUNCH: ${e.kind.name.lowercase()} with the $side hand" }
                is GestureEvent.Release -> { arrows[k]++; marks.add(Mark(clock, k, Scope.BOW, "arrow")); "ARROW let go - power %.0f%%".format(e.power * 100f) }
                is GestureEvent.Bowl -> { marks.add(Mark(clock, k, Scope.BOWL, "bowl")); "BOWL with the $side hand" }
                else -> null
            } ?: continue
            flash[k] = 0f
            flashText[k] = text
        }
        flash[k] += dt
        Draw.panel(g, r, app.theme.panel, if (flash[k] < 0.4f) Colors.GOOD else app.theme.panelBorder)
        val c = app.playerColor(k)
        g.text(app.activePlayers[k].name, r.left + 30f, r.top + 40f, 38f, c, Align.LEFT)
        // no tip here: it would cover the counters (the Motion test says under the camera view when tracking is slow)
        Common.qualityMeter(g, app, k, r.right - 300f, r.top + 10f, 270f, showTip = false)
        if (!slot.isPlaying || !ctl.hasPose) { g.text("Not seen - step into the picture", r.cx, r.cy, 32f, Colors.WARN, bold = false); return }
        // swing meters: how close each hand's movement is to counting as a swing
        var y = r.top + 125f
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
            y += 46f
        }
        g.text("Swings ${swings[k]}   Shots ${shots[k]}   Punches ${punches[k]}   Arrows ${arrows[k]}", r.left + 30f, y + 4f, 28f, Colors.WHITE, Align.LEFT, bold = false)
        y += 38f
        if (flash[k] < 1.5f) g.text(flashText[k], r.left + 30f, y, ui.fit(g, flashText[k], 30f, r.width - 60f), Colors.GOOD, Align.LEFT)
        y += 40f
        // golf grip, held object, pointer
        val gap = handsGap(k)
        val grip = !gap.isNaN() && gap < com.clrg.core.gesture.GolfSwingDetector.GRIP_GAP
        val held = if (ctl.hasObject) "object seen in the ${if (ctl.avatar.propHand == LEFT) "left" else "right"} hand" else if (app.heldModelFor(k) != null) "object learned, not seen now" else "no object"
        val pointer = app.pointers[k]
        val gc = ctl.gunCtl
        val gh = gc.gunHand
        val tr = gc.trigger[gh]
        val fingers = when {
            !gc.fingersSeen[0] && !gc.fingersSeen[1] -> "Fingers: not seen (hold a hand up towards the TV)"
            tr.level.isNaN() -> "Fingers seen - point one or two fingers at the TV, thumb up"
            else -> "${if (gh == RIGHT) "Right" else "Left"} hand: thumb ${"%.0f".format(tr.thumb01 * 100)}% up - " +
                if (tr.cocked) "READY, drop the thumb to shoot" else "raise the thumb to get ready"
        }
        val info = listOf(
            fingers,
            "Boxing: " + (if (ctl.punch.guard) "guard up" else "guard down") + "   Bow: " + ctl.bow.state.name.lowercase() + (if (ctl.bow.state == com.clrg.core.gesture.BowDetector.State.DRAWING) " %.0f%%".format(ctl.bow.draw * 100f) else ""),
            "Golf grip: " + (if (gap.isNaN()) "hands not both seen" else if (grip) "hands together" else "hands %.1f apart".format(gap)),
            "Held: $held",
            "Pointer: " + if (pointer.active) "pointing with the ${if (pointer.hand == RIGHT) "right" else "left"} hand" else if (pointer.visible) "lift your hand a little higher" else "hand down",
        )
        for (l in info) { if (y > r.bottom - 16f) break; g.text(l, r.left + 30f, y, ui.fit(g, l, 26f, r.width - 60f, false), app.theme.dim, Align.LEFT, bold = false); y += 33f }
    }

    /** Records this frame's value of every graphed signal for both players. */
    private fun sampleScope(dt: Float) {
        clock += dt
        val i = scopeN % SCOPE_N
        scopeT[i] = clock
        for (k in 0..1) {
            val ctl = app.controls[k]
            val v = scopeV[k]
            val seen = app.tracker.slots[k].isPlaying && ctl.hasPose
            val gc = ctl.gunCtl
            val tr = gc.trigger[gc.gunHand]
            v[0][i] = if (!seen || tr.level.isNaN() || !gc.fingersSeen[gc.gunHand]) Float.NaN else tr.thumb01
            val f = ctl.history.latest
            // the bowling (playing) hand's height
            val dom = if (ctl.calibration?.dominantHand == com.clrg.core.tracking.Hand.LEFT) LEFT else RIGHT
            v[1][i] = if (!seen || f == null || f.wv[dom] <= 0.2f) Float.NaN else f.wy[dom]
            v[2][i] = if (seen) ctl.lean else Float.NaN
            v[3][i] = if (seen && ctl.bow.state != com.clrg.core.gesture.BowDetector.State.IDLE) ctl.bow.draw else if (seen) 0f else Float.NaN
            v[4][i] = if (seen) ((maxOf(ctl.swingLevel[0], ctl.swingLevel[1])) / 1.5f).coerceIn(0f, 1.2f) else Float.NaN
            v[5][i] = if (seen && ctl.tuck) 1f else 0f
            v[6][i] = if (seen) ctl.bow.releaseSpeed01.coerceIn(0f, 1.5f) else Float.NaN
        }
        scopeN++
        marks.removeAll { clock - it.t > SCOPE_SEC + 0.5 }
    }

    private fun drawScope(g: Gfx, r: Rect) {
        g.fillRoundRect(r.left, r.top, r.right, r.bottom, 16f, 0xD80B1A36.toInt())
        val plot = Rect(r.left + 16f, r.top + 44f, r.right - 16f, r.bottom - 14f)
        val tune = app.controls[0].tuning
        // signal index, vertical range, the lines to cross (value, label, colour), and a title
        val (idx, lo, hi) = when (scope) {
            Scope.THUMB -> Triple(0, -0.05f, 1.1f)
            Scope.BOWL -> Triple(1, -1.6f, 0.6f)
            Scope.LEAN -> Triple(2, -1.2f, 1.2f)
            Scope.BOW -> Triple(3, -0.05f, 1.3f)
            else -> Triple(4, -0.05f, 1.25f)
        }
        val lines: List<Triple<Float, String, Int>> = when (scope) {
            Scope.THUMB -> listOf(Triple(com.clrg.core.gesture.FingerTrigger.COCK_AT, "thumb up: ready", Colors.GOOD), Triple(com.clrg.core.gesture.FingerTrigger.FIRE_AT, "drop below: BANG", Colors.GOLD))
            Scope.BOWL -> listOf(Triple(tune.bowlLowY, "swing the hand down below here...", Colors.ICE), Triple(tune.bowlReleaseY, "...then up past here quickly", Colors.GOLD))
            Scope.LEAN -> listOf(Triple(1f, "full right", Colors.GOOD), Triple(0f, "straight", 0x88FFFFFF.toInt()), Triple(-1f, "full left", Colors.GOOD))
            Scope.BOW -> listOf(Triple(1f, "full draw / let-go speed", Colors.GOLD))
            else -> listOf(Triple(1f / 1.5f, "a swing counts above here", Colors.GOLD))
        }
        val title = when (scope) {
            Scope.THUMB -> "Thumb of the gun hand (0 = down, 100% = up), last 4 s"
            Scope.BOWL -> "Height of the bowling hand (body units), last 4 s"
            Scope.LEAN -> "Lean (bar along the bottom: tuck), last 4 s"
            Scope.BOW -> "Bow draw (solid) and drawing-hand speed (thin), last 4 s"
            else -> "Swing strength of the faster hand, last 4 s"
        }
        g.text(title, r.left + 20f, r.top + 24f, 26f, Colors.WHITE, Align.LEFT)
        fun yOf(v: Float) = plot.bottom - (v - lo) / (hi - lo) * plot.height
        for ((v, label, col) in lines) {
            val y = yOf(v)
            g.line(plot.left, y, plot.right, y, 3f, col)
            g.text(label, plot.right - 8f, y - 14f, 22f, col, Align.RIGHT, bold = false)
        }
        val n = minOf(scopeN, SCOPE_N)
        fun xOf(t: Double) = plot.right - ((clock - t) / SCOPE_SEC).toFloat() * plot.width
        val players = if (app.tracker.solo) 1 else 2
        for (k in 0 until players) {
            val col = app.playerColor(k)
            // tuck bar (lean graph) and release speed (bow graph) underneath the main trace
            if (scope == Scope.LEAN || scope == Scope.BOW) {
                var px = Float.NaN; var py = Float.NaN
                for (j in n - 1 downTo 0) {
                    val i = (scopeN - 1 - j) % SCOPE_N
                    if (clock - scopeT[i] > SCOPE_SEC) continue
                    val x = xOf(scopeT[i])
                    if (scope == Scope.LEAN) {
                        if (scopeV[k][5][i] > 0.5f) g.fillRect(x - 2f, plot.bottom - 10f - k * 12f, x + 2f, plot.bottom - k * 12f, col)
                    } else {
                        val v = scopeV[k][6][i]
                        val y = if (v.isNaN()) Float.NaN else yOf(v)
                        if (!py.isNaN() && !y.isNaN()) g.line(px, py, x, y, 2f, (col and 0x00FFFFFF) or 0x99000000.toInt())
                        px = x; py = y
                    }
                }
            }
            var px = Float.NaN; var py = Float.NaN
            for (j in n - 1 downTo 0) {
                val i = (scopeN - 1 - j) % SCOPE_N
                if (clock - scopeT[i] > SCOPE_SEC) { px = Float.NaN; continue }
                val v = scopeV[k][idx][i]
                val x = xOf(scopeT[i])
                val y = if (v.isNaN()) Float.NaN else yOf(v.coerceIn(lo, hi))
                if (!py.isNaN() && !y.isNaN()) g.line(px, py, x, y, 4f, col)
                px = x; py = y
            }
        }
        for (m in marks) {
            if (m.kind != scope || clock - m.t > SCOPE_SEC) continue
            val x = xOf(m.t)
            val col = app.playerColor(m.player)
            g.line(x, plot.top, x, plot.bottom, 3f, col)
            g.text(m.label, x + 6f, plot.top + 14f, 22f, col, Align.LEFT)
        }
        if (scope == Scope.THUMB && app.controls.none { it.wantFingers }) g.text("Finger tracking is off", r.cx, r.cy, 28f, Colors.WARN)
    }

    private companion object {
        const val SCOPE_N = 360
        const val SCOPE_SEC = 4.0
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
