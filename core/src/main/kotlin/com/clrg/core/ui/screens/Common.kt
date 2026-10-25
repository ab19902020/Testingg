package com.clrg.core.ui.screens

import com.clrg.core.app.CameraState
import com.clrg.core.app.GameApp
import com.clrg.core.math.Rect
import com.clrg.core.pose.Lm
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.tracking.BodyMetrics
import com.clrg.core.tracking.SlotState
import com.clrg.core.ui.Ui

/** Shared drawing for menu screens. */
object Common {
    fun backdrop(g: Gfx, app: GameApp) =
        if (app.christmasMode) Draw.festiveBackdrop(g, app.time, !app.settings.reducedEffects)
        else com.clrg.core.render.SportsDraw.backdrop(g, app.time, !app.settings.reducedEffects)

    fun header(g: Gfx, ui: Ui, title: String, back: Boolean = true, app: GameApp? = null): Boolean {
        g.gradientRect(0f, 0f, g.width, 150f, 0xEE06122A.toInt(), 0x0006122A)
        ui.title(g, title, 72f, 66f)
        // a short accent underline under the title
        val tw = minOf(g.measureText(title, 66f * ui.theme.textScale), g.width - 600f)
        val acc = if (app?.christmasMode == true) Colors.CANDY else 0xFF40C4FF.toInt()
        g.fillRoundRect(g.width / 2f - tw * 0.3f, 118f, g.width / 2f + tw * 0.3f, 124f, 3f, Colors.withAlpha(acc, 0.85f))
        val b = back && ui.backButton(g)
        if (b) app?.current?.onBack()
        return b
    }

    /** Splits [text] into lines no wider than [maxW] at [size]. */
    fun wrap(g: Gfx, text: String, size: Float, maxW: Float, bold: Boolean = true): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in text.split(" ")) {
            val next = if (line.isEmpty()) w else "$line $w"
            if (line.isNotEmpty() && g.measureText(next, size, bold) > maxW) { out.add(line); line = w } else line = next
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    /** The console's emblem: a TV with motion arcs (and a snowflake in Christmas mode). */
    fun logo(g: Gfx, cx: Float, cy: Float, r: Float, xmas: Boolean, t: Double) {
        val acc = if (xmas) Colors.CANDY else 0xFF40C4FF.toInt()
        g.glow(cx, cy, r * 1.5f, Colors.withAlpha(acc, 0.35f))
        g.fillCircleRadial(cx, cy, r, cx - r * 0.3f, cy - r * 0.4f, r * 1.4f, Colors.lighter(acc, 0.35f), Colors.darker(acc, 0.55f))
        g.strokeCircle(cx, cy, r, 4f, Colors.WHITE)
        com.clrg.core.ui.Icons.draw(g, if (xmas) com.clrg.core.ui.Icon.SNOWFLAKE else com.clrg.core.ui.Icon.TV, cx, cy + 2f, r * 1.1f, Colors.WHITE)
        // motion arcs sweeping round the badge
        val a = (t * 1.6).toFloat()
        g.arc(cx, cy, r + 12f, a, 1.1f, 5f, Colors.withAlpha(Colors.WHITE, 0.8f))
        g.arc(cx, cy, r + 12f, a + 3.14f, 1.1f, 5f, Colors.withAlpha(acc, 0.9f))
    }

    /** Capsule switch between the two halves of the console. */
    fun modeSwitch(g: Gfx, ui: Ui, app: GameApp, r: Rect) {
        g.fillRoundRect(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f, (r.height + 12f) / 2f, 0x88000000.toInt())
        val half = (r.width - 12f) / 2f
        val xmas = app.christmasMode
        val a = Rect(r.left, r.top, r.left + half, r.bottom)
        val b = Rect(r.right - half, r.top, r.right, r.bottom)
        if (ui.button(g, "modeSports", a, com.clrg.core.games.GameMode.SPORTS.title, accent = 0xFF40C4FF.toInt(), selected = !xmas, size = 32f, icon = com.clrg.core.ui.Icon.BALL)) app.mode = com.clrg.core.games.GameMode.SPORTS
        if (ui.button(g, "modeXmas", b, com.clrg.core.games.GameMode.CHRISTMAS.title, accent = Colors.CANDY, selected = xmas, size = 32f, icon = com.clrg.core.ui.Icon.SNOWFLAKE)) app.mode = com.clrg.core.games.GameMode.CHRISTMAS
    }

    /** Live tracking-quality meter for player [k]: five bars, a word and the most useful tip (wrapped to [w]). */
    fun qualityMeter(g: Gfx, app: GameApp, k: Int, x: Float, y: Float, w: Float) {
        val q = com.clrg.core.tracking.TrackingQuality.of(app.tracker.slots[k], if (app.settings.simulatedInput) 0f else app.platform.perf().inferenceFps)
        val c = app.playerColor(k)
        g.text("Tracking", x + w / 2f, y, 28f, app.theme.dim)
        val bw = (w - 40f) / 5f
        for (i in 0 until 5) {
            val bx = x + 20f + i * bw
            val bh = 18f + i * 10f
            val on = i < q.bars
            val col = if (!on) 0x33FFFFFF else when { q.score >= 0.62f -> Colors.GOOD; q.score >= 0.42f -> Colors.WARN; else -> Colors.BAD }
            g.fillRoundRect(bx + 4f, y + 80f - bh, bx + bw - 4f, y + 80f, 5f, col)
        }
        g.text(q.label, x + w / 2f, y + 110f, 34f, if (q.score >= 0.62f) Colors.GOOD else if (q.score >= 0.42f) Colors.WARN else c)
        q.tip?.let { tip ->
            // simple word wrap
            val words = tip.split(" ")
            var line = ""
            var ly = y + 150f
            for (wd in words) {
                val next = if (line.isEmpty()) wd else "$line $wd"
                if (g.measureText(next, 24f, false) > w && line.isNotEmpty()) { g.text(line, x + w / 2f, ly, 24f, Colors.WHITE, bold = false); ly += 30f; line = wd } else line = next
            }
            if (line.isNotEmpty()) g.text(line, x + w / 2f, ly, 24f, Colors.WHITE, bold = false)
        }
    }

    /** Small status chips (camera, TV, players) along the bottom of the menus. */
    fun statusBar(g: Gfx, app: GameApp) {
        val y = g.height - 46f
        val cam = app.platform.cameraStatus()
        val camText = when {
            app.settings.simulatedInput -> "Input: SIMULATED"
            cam.state == CameraState.RUNNING -> "Camera: on"
            else -> "Camera: " + cam.state.name.lowercase().replace('_', ' ')
        }
        val camOk = cam.state == CameraState.RUNNING || app.settings.simulatedInput
        val d = app.platform.displayStatus()
        val tvText = when {
            d.presentationActive -> "TV: second screen"
            d.externalConnected -> "TV: mirrored"
            else -> "TV: not detected"
        }
        val chips = mutableListOf(camText to camOk, tvText to d.externalConnected)
        for (k in 0..1) {
            val s = app.tracker.slots[k]
            val name = app.activePlayers[k].name
            if (!s.enabled) { chips.add("vs $name (CPU, ${app.cpuLevel?.label ?: ""})" to true); continue }
            val state = when {
                s.calibration != null && s.isPlaying -> "ready"
                s.isPlaying -> "seen"
                s.calibration != null -> "away"
                else -> "not set up"
            }
            chips.add("$name: $state" to (s.isPlaying))
        }
        var x = 40f
        for ((text, ok) in chips) {
            val w = g.measureText(text, 28f, false) + 60f
            g.fillRoundRect(x, y - 24f, x + w, y + 24f, 24f, 0xAA000000.toInt())
            g.fillCircle(x + 24f, y, 9f, if (ok) Colors.GOOD else Colors.WARN)
            g.text(text, x + 42f, y, 28f, Colors.WHITE, Align.LEFT, bold = false)
            x += w + 16f
        }
    }
}

/**
 * Live camera preview with player areas and skeletons (shoulders, elbows, wrists, hips) of everyone the tracker
 * sees: players in their colour, spectators in grey. Mirrored like a mirror when the mirror setting is on.
 */
object PreviewView {
    fun draw(g: Gfx, app: GameApp, r: Rect, showAreas: Boolean = true, labels: Boolean = true) {
        val aspect = app.tracker.aspect
        // fit the frame (aspect) inside r
        var w = r.width
        var h = w / aspect
        if (h > r.height) { h = r.height; w = h * aspect }
        val x0 = r.cx - w / 2f
        val y0 = r.cy - h / 2f
        val view = Rect(x0, y0, x0 + w, y0 + h)
        g.fillRoundRect(view.left - 6f, view.top - 6f, view.right + 6f, view.bottom + 6f, 18f, 0xFF000000.toInt())
        val pv = app.platform.preview()
        if (pv != null && !app.settings.simulatedInput) {
            g.save(); g.clipRect(view.left, view.top, view.right, view.bottom)
            g.image(pv.image, view.left, view.top, view.right, view.bottom, mirror = pv.mirror)
            g.restore()
        } else {
            g.fillRect(view.left, view.top, view.right, view.bottom, 0xFF1B2433.toInt())
            val msg = if (app.settings.simulatedInput) "Simulated input - no camera image" else "Waiting for camera..."
            g.text(msg, view.cx, view.cy, minOf(36f, view.width * 0.9f / (msg.length * 0.55f)), Colors.TEXT_DIM)
        }
        val map = { fx: Float, fy: Float -> (view.left + fx / aspect * w) to (view.top + fy * h) }
        if (showAreas) for (k in 0..1) {
            val s = app.tracker.slots[k]
            if (!s.enabled) continue
            val c = app.playerColor(k)
            val (ax0, _) = map(s.area.x0, 0f)
            val (ax1, _) = map(s.area.x1, 0f)
            val inset = 14f
            val ok = s.isPlaying
            g.strokeRoundRect(ax0 + inset, view.top + inset, ax1 - inset, view.bottom - inset, 24f, if (ok) 8f else 5f, Colors.withAlpha(c, if (ok) 1f else 0.6f))
            if (labels) Draw.badge(g, (ax0 + ax1) / 2f, view.top + 46f, "P${k + 1} ${app.activePlayers[k].name}", c, 30f)
            if (!ok) outline(g, (ax0 + ax1) / 2f, view.top + h * 0.2f, h * 0.7f, Colors.withAlpha(c, 0.35f))
        }
        // skeletons stay inside the picture (a person partly out of frame has joints outside it)
        g.save(); g.clipRect(view.left, view.top, view.right, view.bottom)
        for (m in app.tracker.others) skeleton(g, m, map, 0x99B0BEC5.toInt(), 4f)
        for (k in 0..1) {
            val s = app.tracker.slots[k]
            val m = s.metrics ?: continue
            skeleton(g, m, map, app.playerColor(k), 7f)
        }
        g.restore()
        // pipeline debug geometry (search regions / ROIs) when the skeleton overlay is on
        if (app.settings.skeletonOverlay) app.platform.pipelineDebug()?.let { dbg ->
            for (reg in dbg.regions) { val (a, b) = map(reg[0], reg[1]); val (c, d) = map(reg[2], reg[3]); g.strokeRoundRect(minOf(a, c), b, maxOf(a, c), d, 4f, 2f, 0x88FFEB3B.toInt()) }
            for (roi in dbg.rois) { val (cx, cy) = map(roi[0], roi[1]); g.strokeCircle(cx, cy, roi[2] / aspect * w / 2f, 2f, 0x8840C4FF.toInt()) }
        }
    }

    private fun skeleton(g: Gfx, m: BodyMetrics, map: (Float, Float) -> Pair<Float, Float>, color: Int, width: Float) {
        val p = m.pose
        Draw.stickFigure(g, p.x, p.y, FloatArray(p.x.size) { p.vis(it) }, map, color, width)
        for (i in intArrayOf(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, Lm.LEFT_HIP, Lm.RIGHT_HIP)) {
            if (p.vis(i) < 0.4f) continue
            val (x, y) = map(p.x[i], p.y[i])
            g.fillCircle(x, y, width * 1.2f, color)
        }
    }

    /** Dotted body outline showing where to stand. */
    fun outline(g: Gfx, cx: Float, top: Float, h: Float, color: Int) {
        g.strokeCircle(cx, top + h * 0.09f, h * 0.08f, 5f, color)
        g.strokeRoundRect(cx - h * 0.16f, top + h * 0.2f, cx + h * 0.16f, top + h * 0.6f, h * 0.08f, 5f, color)
        g.line(cx - h * 0.16f, top + h * 0.24f, cx - h * 0.3f, top + h * 0.5f, 5f, color)
        g.line(cx + h * 0.16f, top + h * 0.24f, cx + h * 0.3f, top + h * 0.5f, 5f, color)
        g.line(cx - h * 0.08f, top + h * 0.6f, cx - h * 0.1f, top + h * 0.98f, 5f, color)
        g.line(cx + h * 0.08f, top + h * 0.6f, cx + h * 0.1f, top + h * 0.98f, 5f, color)
    }
}
