package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx

/** Developer overlay: measured frame rate, camera/inference timing, latency and tracking state. */
class DevOverlay(private val app: GameApp) {
    fun draw(g: Gfx) {
        val p = app.platform.perf()
        val lines = ArrayList<String>()
        lines += "Render %.0f fps (%.1f ms)".format(app.renderFps, app.frameMs)
        lines += "Camera %.0f fps   Inference %.0f fps".format(p.cameraFps, p.inferenceFps)
        lines += "Pose %.1f ms (prep %.1f, det %.1f, lm %.1f)".format(p.inferenceMs, p.preprocessMs, p.detectorMs, p.landmarkMs)
        lines += if (p.captureToResultMs.isNaN()) "Capture->pose: n/a" else "Capture->pose %.0f ms".format(p.captureToResultMs)
        lines += if (app.lastPoseLatencyMs.isNaN()) "Capture->game: n/a" else "Capture->game %.0f ms".format(app.lastPoseLatencyMs)
        lines += "${p.delegate} | ${p.model} | ${p.inputSize}"
        lines += "Thermal ${p.thermal} | adapt ${p.adaptiveLevel}"
        lines += "Dropped cam ${p.droppedFrames}  pose ${app.droppedPoseFrames}"
        lines += "People seen ${app.tracker.lastCandidates.size} (others ${app.tracker.others.size})"
        for (k in 0..1) {
            val s = app.tracker.slots[k]
            lines += "P${k + 1} ${slotLabel(s)} conf %.2f S %.3f lost ${s.lossEvents} re ${s.reacquisitions}".format(s.confidence, s.scale)
        }
        val w = 620f
        val h = lines.size * 34f + 20f
        val x = g.width - w - 10f
        val y = 130f
        g.fillRoundRect(x, y, x + w, y + h, 12f, 0xCC000000.toInt())
        lines.forEachIndexed { i, l -> g.text(l, x + 14f, y + 26f + i * 34f, 24f, if (i < 2) Colors.GOOD else Colors.WHITE, Align.LEFT, bold = false) }
    }
}
