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
    fun backdrop(g: Gfx, app: GameApp) = Draw.festiveBackdrop(g, app.time, !app.settings.reducedEffects)

    fun header(g: Gfx, ui: Ui, title: String, back: Boolean = true, app: GameApp? = null): Boolean {
        g.gradientRect(0f, 0f, g.width, 140f, 0xEE06122A.toInt(), 0x0006122A)
        ui.title(g, title, 72f, 70f)
        val b = back && ui.backButton(g)
        if (b) app?.current?.onBack()
        return b
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
        for (m in app.tracker.others) skeleton(g, m, map, 0x99B0BEC5.toInt(), 4f)
        for (k in 0..1) {
            val s = app.tracker.slots[k]
            val m = s.metrics ?: continue
            skeleton(g, m, map, app.playerColor(k), 7f)
        }
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
