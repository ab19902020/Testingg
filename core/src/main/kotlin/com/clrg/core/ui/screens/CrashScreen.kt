package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.math.Rect
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui

/**
 * Shown once after the game closed unexpectedly: what went wrong (phone model, where the error happened) and a
 * Share button so the player can send the details to the developer. Nothing is sent automatically.
 */
class CrashReportScreen(app: GameApp, private val report: String) : Screen(app) {
    private var wrapped: List<String> = emptyList()
    private var wrappedFor = -1f
    private var first = 0

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        val r = Rect(50f, 30f, g.width - 50f, g.height - 30f)
        Draw.panel(g, r, app.theme.panel, app.theme.panelBorder)
        ui.title(g, "Sorry - the game closed unexpectedly", 100f, 60f)
        ui.paragraph(g, listOf(
            "The details below show where it went wrong. They contain only the phone model and program names -",
            "no pictures, video or personal data. Please share them with the developer.",
        ), g.width / 2f, 175f, 30f, app.theme.dim, align = Align.CENTER)

        val box = Rect(r.left + 40f, 260f, r.right - 40f, r.bottom - 150f)
        g.fillRoundRect(box.left, box.top, box.right, box.bottom, 18f, 0xFF07101F.toInt())
        val size = 24f
        val lineH = size * 1.35f
        if (wrappedFor != box.width) { wrapped = wrap(g, report, size, box.width - 40f); wrappedFor = box.width }
        val perPage = ((box.height - 30f) / lineH).toInt().coerceAtLeast(1)
        first = first.coerceIn(0, maxOf(0, wrapped.size - perPage))
        for (i in 0 until perPage) {
            val line = wrapped.getOrNull(first + i) ?: break
            g.text(line, box.left + 20f, box.top + 30f + i * lineH, size, Colors.WHITE, Align.LEFT, bold = false)
        }

        val by = r.bottom - 125f
        if (ui.button(g, "share", Rect(r.left + 40f, by, r.left + 560f, by + 100f), "Share details", accent = Colors.GOOD, size = 38f)) {
            app.platform.shareText("Living Room Games - crash report", report)
        }
        val more = wrapped.size > perPage
        if (more) {
            val atEnd = first + perPage >= wrapped.size
            if (ui.button(g, "more", Rect(g.width / 2f - 230f, by, g.width / 2f + 230f, by + 100f), if (atEnd) "Back to top" else "Show more", size = 34f)) {
                first = if (atEnd) 0 else first + perPage - 2
            }
        }
        if (ui.button(g, "continue", Rect(r.right - 520f, by, r.right - 40f, by + 100f), "Continue", accent = Colors.ICE, size = 38f)) app.pop()
    }

    /** Breaks the report into lines that fit [maxW]. */
    private fun wrap(g: Gfx, text: String, size: Float, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (raw in text.lines()) {
            var rest = raw
            if (rest.isEmpty()) { out.add(""); continue }
            var guard = 0
            while (rest.isNotEmpty() && guard++ < 100) {
                if (g.measureText(rest, size, bold = false) <= maxW) { out.add(rest); break }
                // longest prefix that fits, preferring a break at a space, dot or bracket
                var lo = 1
                var hi = rest.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (g.measureText(rest.substring(0, mid), size, bold = false) <= maxW) lo = mid else hi = mid - 1
                }
                val cut = (rest.lastIndexOfAny(charArrayOf(' ', '.', '(', '$'), lo - 1).takeIf { it > lo / 2 }?.plus(1) ?: lo).coerceAtLeast(5).coerceAtMost(rest.length)
                out.add(rest.substring(0, cut))
                rest = "    " + rest.substring(cut).trimStart()
                if (rest.isBlank()) break
            }
        }
        return out
    }
}
