package com.clrg.core.game

import com.clrg.core.math.Rect
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx

/** Shared heads-up display pieces so every game reads the same way from across the room. */
object Hud {
    const val BAR_H = 120f

    fun formatTime(sec: Double): String {
        val s = kotlin.math.ceil(sec.coerceAtLeast(0.0)).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Top bar: P1 name/score left, P2 right, [center] text (timer/target) in the middle. */
    fun topBar(g: Gfx, env: GameEnv, scores: IntArray?, center: String?, sub: String? = null, inputs: Array<PlayerInput>? = null) {
        // every game draws its HUD last: darken the scene's edges first (cinematic framing), under the HUD
        Draw.vignette(g, if (env.settings.reducedEffects) 0.25f else 0.42f)
        g.gradientRect(0f, 0f, g.width, BAR_H, 0xF0040C1E.toInt(), 0xA0061226.toInt())
        g.fillRect(0f, BAR_H - 2f, g.width, BAR_H, 0x33FFFFFF)
        g.gradientRect(0f, BAR_H, g.width, BAR_H + 24f, 0x55000000, 0x00000000)
        for (i in 0..1) {
            val left = i == 0
            val c = env.color(i)
            val nameX = if (left) 40f else g.width - 40f
            val align = if (left) Align.LEFT else Align.RIGHT
            g.text(env.name(i), nameX + (if (left) 50f else -50f), 42f, 40f, Colors.WHITE, align)
            Draw.badge(g, nameX + (if (left) 22f else -22f), 42f, "P${i + 1}", c, 24f)
            if (scores != null) {
                val bx = if (left) 40f else g.width - 40f - 150f
                g.fillRoundRect(bx + 2f, 70f, bx + 152f, 118f, 16f, 0x55000000)
                g.fillRoundRectGradient(bx, 66f, bx + 150f, 114f, 16f, Colors.lighter(c, 0.3f), Colors.darker(c, 0.75f))
                g.fillRoundRectGradient(bx + 3f, 68f, bx + 147f, 90f, 13f, 0x40FFFFFF, 0x00FFFFFF)
                g.text(scores[i].toString(), bx + 75f, 90f, 44f, Colors.BLACK)
            }
            if (env.players[i].cpu) {
                val tagX = if (left) 240f else g.width - 240f
                Draw.badge(g, tagX, 90f, "CPU", 0xFFCFD8DC.toInt(), 22f)
            } else if (inputs != null) {
                val dotX = if (left) 215f else g.width - 215f
                g.fillCircle(dotX, 90f, 10f, if (inputs[i].tracked) Colors.GOOD else Colors.BAD)
            }
        }
        if (center != null) g.text(center, g.width / 2, if (sub == null) BAR_H / 2 else 44f, 60f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 6f)
        if (sub != null) g.text(sub, g.width / 2, 96f, 32f, Colors.TEXT_DIM)
    }

    fun banner(g: Gfx, text: String, y: Float, color: Int, size: Float = 72f) {
        val w = g.measureText(text, size) + 80f
        g.fillRoundRect(g.width / 2 - w / 2, y - size * 0.8f, g.width / 2 + w / 2, y + size * 0.8f, size * 0.5f, 0xCC000000.toInt())
        g.text(text, g.width / 2, y, size, color)
    }

    fun laneLabel(g: Gfx, r: Rect, env: GameEnv, i: Int) {
        Draw.badge(g, r.cx, r.top + 40f, env.name(i), env.color(i), 32f)
    }

    fun divider(g: Gfx) {
        g.fillRect(g.width / 2 - 4f, 0f, g.width / 2 + 4f, g.height, 0xFF0A1830.toInt())
        g.fillRect(g.width / 2 - 1f, 0f, g.width / 2 + 1f, g.height, 0x66FFFFFF)
    }

    fun captions(g: Gfx, env: GameEnv) {
        if (!env.settings.captions) return
        var y = g.height - 46f
        for (l in env.captions.lines.reversed()) {
            val a = (1f - l.t / 1.6f).coerceIn(0f, 1f)
            val text = "[${l.text}]"
            val w = g.measureText(text, 30f, bold = false) + 30f
            g.save(); g.setAlpha(a)
            g.fillRoundRect(g.width / 2 - w / 2, y - 22f, g.width / 2 + w / 2, y + 22f, 12f, 0xCC000000.toInt())
            g.text(text, g.width / 2, y, 30f, Colors.WHITE, bold = false)
            g.restore()
            y -= 52f
        }
    }

    /** Small, readable "ring timer" used by catch/goal targets. */
    fun timerRing(g: Gfx, cx: Float, cy: Float, r: Float, frac: Float, color: Int) {
        Draw.progressRing(g, cx, cy, r, frac, color, 6f, 0x33FFFFFF)
    }
}
