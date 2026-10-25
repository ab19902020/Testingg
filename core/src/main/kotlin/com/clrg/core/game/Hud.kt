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

    /**
     * Broadcast-style scoreboard across the top: each player's plate (badge, name, tracking light, score) and the
     * timer or target in a central box. Scores pop when they change.
     */
    fun topBar(g: Gfx, env: GameEnv, scores: IntArray?, center: String?, sub: String? = null, inputs: Array<PlayerInput>? = null) {
        // every game draws its HUD last: darken the scene's edges first (cinematic framing), under the HUD
        Draw.vignette(g, if (env.settings.reducedEffects) 0.25f else 0.42f)
        g.gradientRect(0f, 0f, g.width, BAR_H + 30f, 0xC0040C1E.toInt(), 0x00040C1E)
        val st = env.hud
        val now = st.time
        val cw = 520f
        val cl = g.width / 2 - cw / 2
        val cr = g.width / 2 + cw / 2
        for (i in 0..1) {
            val left = i == 0
            val c = env.color(i)
            if (scores != null && scores[i] != st.last[i]) {
                if (st.last[i] != Int.MIN_VALUE && scores[i] > st.last[i]) st.changedAt[i] = now
                st.last[i] = scores[i]
            }
            val pl = if (left) 24f else cr + 14f
            val pr = if (left) cl - 14f else g.width - 24f
            val top = 16f; val bot = 100f
            g.fillRoundRect(pl + 4f, top + 8f, pr + 4f, bot + 8f, 22f, 0x55000000)
            g.fillRoundRectGradient(pl, top, pr, bot, 22f, 0xEE14284A.toInt(), 0xEE0A1730.toInt())
            // colour stripe on the outer end
            val sx0 = if (left) pl else pr - 16f
            g.fillRoundRect(sx0, top, sx0 + 16f, bot, 8f, c)
            g.strokeRoundRect(pl, top, pr, bot, 22f, 2f, Colors.withAlpha(c, 0.7f))
            // badge and name
            val bx = if (left) pl + 58f else pr - 58f
            Draw.badge(g, bx, (top + bot) / 2, "P${i + 1}", c, 26f)
            val scoreW = if (scores != null) 128f else 0f
            val nx = if (left) bx + 44f else bx - 44f
            val nameMax = (pr - pl) - 120f - scoreW - 50f
            val name = env.name(i)
            val ns = minOf(40f, 40f * nameMax / maxOf(1f, g.measureText(name, 40f)))
            g.text(name, nx, (top + bot) / 2 - 10f, ns, Colors.WHITE, if (left) Align.LEFT else Align.RIGHT)
            // status: computer player, or the camera's tracking light
            val sy = (top + bot) / 2 + 24f
            if (env.players[i].cpu) {
                g.text("COMPUTER", nx, sy, 20f, 0xFFCFD8DC.toInt(), if (left) Align.LEFT else Align.RIGHT)
            } else if (inputs != null) {
                val ok = inputs[i].tracked
                val dx = if (left) nx + 9f else nx - 9f
                g.fillCircle(dx, sy, 8f, if (ok) Colors.GOOD else Colors.BAD)
                g.text(if (ok) "tracking" else "can't see you", if (left) dx + 16f else dx - 16f, sy, 20f, if (ok) Colors.TEXT_DIM else Colors.BAD, if (left) Align.LEFT else Align.RIGHT, bold = false)
            }
            if (scores != null) {
                val bl = if (left) pr - scoreW - 10f else pl + 10f
                val br = bl + scoreW
                val since = (now - st.changedAt[i]).toFloat()
                val bump = if (since < 0.35f) 1f + 0.3f * (1f - since / 0.35f) else 1f
                if (since < 0.6f) g.glow((bl + br) / 2, (top + bot) / 2, 90f, Colors.withAlpha(c, 0.6f * (1f - since / 0.6f)))
                g.fillRoundRectGradient(bl, top + 8f, br, bot - 8f, 16f, Colors.lighter(c, 0.3f), Colors.darker(c, 0.72f))
                g.fillRoundRectGradient(bl + 3f, top + 10f, br - 3f, (top + bot) / 2, 13f, 0x40FFFFFF, 0x00FFFFFF)
                g.save(); g.translate((bl + br) / 2, (top + bot) / 2); g.scale(bump)
                g.text(scores[i].toString(), 0f, 2f, ui(g, scores[i].toString(), 52f, scoreW - 16f), Colors.BLACK)
                g.restore()
            }
        }
        // centre: timer / target
        g.fillRoundRect(cl + 4f, 12f, cr + 4f, 112f, 26f, 0x55000000)
        g.fillRoundRectGradient(cl, 6f, cr, 106f, 26f, 0xF01B3765.toInt(), 0xF00B1A36.toInt())
        g.strokeRoundRect(cl, 6f, cr, 106f, 26f, 3f, 0xAA80D8FF.toInt())
        if (center != null) {
            val cs = ui(g, center, if (sub == null) 58f else 46f, cw - 40f)
            g.text(center, g.width / 2, if (sub == null) 56f else 42f, cs, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 5f)
        }
        if (sub != null) g.text(sub, g.width / 2, 84f, ui(g, sub, 26f, cw - 36f, false), Colors.TEXT_DIM, bold = false)
    }

    /** Text size that fits [s] into [maxW]. */
    private fun ui(g: Gfx, s: String, size: Float, maxW: Float, bold: Boolean = true): Float {
        val w = g.measureText(s, size, bold)
        return if (w <= maxW || w <= 0f) size else size * maxW / w
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
