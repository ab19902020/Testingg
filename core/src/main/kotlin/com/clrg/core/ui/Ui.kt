package com.clrg.core.ui

import com.clrg.core.audio.Sfx
import com.clrg.core.math.Rect
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import kotlin.math.abs
import kotlin.math.hypot

enum class Key { UP, DOWN, LEFT, RIGHT, ENTER, BACK, MENU }

sealed class InputEvent {
    class Touch(val action: Int, val x: Float, val y: Float) : InputEvent() {
        companion object { const val DOWN = 0; const val MOVE = 1; const val UP = 2; const val CANCEL = 3 }
    }
    class KeyPress(val key: Key) : InputEvent()
}

/** A hands-free pointer driven by one player's raised hand. */
class HandCursor(val player: Int) {
    var x = 0f
    var y = 0f
    var active = false
    var visible = false
    var color = Colors.WHITE
    var hoverId: String? = null
    var dwell = 0f
    var lockoutUntilLeave: String? = null
}

/** Visual theme; high-contrast and large-text accessibility options adjust it. */
class Theme(var highContrast: Boolean = false, var largeText: Boolean = false) {
    val textScale: Float get() = if (largeText) 1.18f else 1f
    val panel: Int get() = if (highContrast) 0xFF000000.toInt() else Colors.PANEL
    val panelBorder: Int get() = if (highContrast) Colors.WHITE else Colors.PANEL_BORDER
    val button: Int get() = if (highContrast) 0xFF000000.toInt() else 0xFF1C3A66.toInt()
    val buttonBorder: Int get() = if (highContrast) Colors.WHITE else 0xFF5D8BC9.toInt()
    val borderWidth: Float get() = if (highContrast) 6f else 3f
    val dim: Int get() = if (highContrast) Colors.WHITE else Colors.TEXT_DIM
}

/**
 * Immediate-mode UI. Each frame screens call [button] etc.; activation is decided at the start of the next frame
 * from the boxes registered in the previous frame, using (1) touch taps, (2) keyboard / D-pad focus + Enter and
 * (3) dwell: a player's raised hand held over a button until the ring fills ([dwellSeconds]), after which the
 * cursor must leave the button before it can activate again.
 */
class Ui(val theme: Theme, private val sound: (Sfx) -> Unit) {
    class Box(val id: String, val rect: Rect, val enabled: Boolean)

    private var boxes = ArrayList<Box>()
    private var nextBoxes = ArrayList<Box>()
    var activated: String? = null
        private set
    var focusId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var downInside: String? = null
    private var pointerDown = false
    var pointerX = 0f
        private set
    var pointerY = 0f
        private set
    companion object {
        /** A hand cursor this close (virtual px) to a button points at it. */
        const val MAGNET_PX = 40f
        /** A hand cursor on a button keeps pointing at it until it is this far (px) outside. */
        const val STICKY_PX = 48f
    }

    var dwellSeconds = 1.5f
    val cursors = arrayOf(HandCursor(0), HandCursor(1))
    private var globalLock = 0f
    /** Last touch position this frame (for screens that use raw touches, e.g. simulated input). */
    val touches = ArrayList<InputEvent.Touch>()
    var backPressed = false
        private set
    private var frameDt = 0f
    var time = 0.0
        private set

    /** Processes queued input and decides which button (if any) activates this frame. */
    fun begin(dt: Float, events: List<InputEvent>, cursorsEnabled: Boolean) {
        frameDt = dt
        time += dt
        activated = null
        backPressed = false
        touches.clear()
        globalLock = (globalLock - dt).coerceAtLeast(0f)
        for (e in events) when (e) {
            is InputEvent.Touch -> {
                touches.add(e)
                pointerX = e.x; pointerY = e.y
                when (e.action) {
                    InputEvent.Touch.DOWN -> { pointerDown = true; downX = e.x; downY = e.y; downInside = hit(e.x, e.y) }
                    InputEvent.Touch.UP -> {
                        val id = hit(e.x, e.y)
                        if (pointerDown && id != null && id == downInside && hypot(e.x - downX, e.y - downY) < 120f) activate(id)
                        pointerDown = false; downInside = null
                    }
                    InputEvent.Touch.CANCEL -> { pointerDown = false; downInside = null }
                }
            }
            is InputEvent.KeyPress -> when (e.key) {
                Key.ENTER -> focusId?.let { id -> if (boxes.any { it.id == id && it.enabled }) activate(id) }
                Key.BACK -> backPressed = true
                Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT -> moveFocus(e.key)
                Key.MENU -> Unit
            }
        }
        // hands-free dwell
        for (c in cursors) {
            if (!cursorsEnabled || !c.active) { c.dwell = 0f; c.hoverId = null; continue }
            val id = hitMagnetic(c)
            if (c.lockoutUntilLeave != null && id != c.lockoutUntilLeave) c.lockoutUntilLeave = null
            if (id == null || id == c.lockoutUntilLeave || globalLock > 0f) { c.hoverId = id; c.dwell = 0f; continue }
            if (id == c.hoverId) {
                val before = c.dwell
                c.dwell += dt
                if ((before * 4).toInt() != (c.dwell * 4).toInt()) sound(Sfx.TICK)
            } else { c.hoverId = id; c.dwell = 0f }
            if (c.dwell >= dwellSeconds) {
                activate(id)
                c.dwell = 0f
                c.lockoutUntilLeave = id
                globalLock = 0.8f
            }
        }
        nextBoxes = ArrayList()
    }

    private fun activate(id: String) {
        if (activated == null) {
            activated = id
            sound(Sfx.SELECT)
        }
    }

    private fun hit(x: Float, y: Float): String? = boxes.lastOrNull { it.enabled && it.rect.contains(x, y) }?.id

    /**
     * What a hand cursor is pointing at. Hands wobble and tracking is never perfectly still, so buttons are a little
     * magnetic: a cursor just outside a button still points at the nearest one, and once on a button it stays there
     * through a small wobble over its edge (so the hold-to-choose ring does not keep restarting).
     */
    private fun hitMagnetic(c: HandCursor): String? {
        hit(c.x, c.y)?.let { return it }
        c.hoverId?.let { h -> boxes.lastOrNull { it.id == h && it.enabled }?.let { b -> if (distToRect(b.rect, c.x, c.y) < STICKY_PX) return h } }
        var best: Box? = null
        var bd = MAGNET_PX
        for (b in boxes) if (b.enabled) { val d = distToRect(b.rect, c.x, c.y); if (d < bd) { bd = d; best = b } }
        return best?.id
    }

    private fun distToRect(r: Rect, x: Float, y: Float): Float {
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return hypot(dx, dy)
    }

    private fun moveFocus(k: Key) {
        val enabled = boxes.filter { it.enabled }
        if (enabled.isEmpty()) return
        val cur = enabled.firstOrNull { it.id == focusId } ?: run { focusId = enabled.first().id; return }
        var best: Box? = null
        var bestScore = Float.MAX_VALUE
        for (b in enabled) {
            if (b === cur) continue
            val dx = b.rect.cx - cur.rect.cx
            val dy = b.rect.cy - cur.rect.cy
            val ok = when (k) { Key.UP -> dy < -10f; Key.DOWN -> dy > 10f; Key.LEFT -> dx < -10f; Key.RIGHT -> dx > 10f; else -> false }
            if (!ok) continue
            val primary = if (k == Key.UP || k == Key.DOWN) abs(dy) else abs(dx)
            val secondary = if (k == Key.UP || k == Key.DOWN) abs(dx) else abs(dy)
            val score = primary + secondary * 2f
            if (score < bestScore) { bestScore = score; best = b }
        }
        if (best != null) focusId = best.id
    }

    /** Called after screens drew: swaps in this frame's boxes. */
    fun end() {
        boxes = nextBoxes
        if (focusId != null && boxes.none { it.id == focusId }) focusId = null
    }

    fun register(id: String, r: Rect, enabled: Boolean = true) { nextBoxes.add(Box(id, r, enabled)) }

    fun isActivated(id: String) = activated == id

    /** Rectangle of a button registered in the last frame (tests, accessibility tooling). */
    fun boxRect(id: String): Rect? = boxes.lastOrNull { it.id == id }?.rect

    /** Ids of the buttons registered in the last frame. */
    fun boxIds(): List<String> = boxes.map { it.id }

    /** Dwell progress (0..1) of any hand cursor on [id]. */
    fun dwellOn(id: String): Float = cursors.maxOf { if (it.hoverId == id && it.active) it.dwell / dwellSeconds else 0f }

    // ------------------------------------------------------------------ widgets

    /** Big rounded button. Returns true on the frame it is activated. */
    fun button(
        g: Gfx, id: String, r: Rect, label: String, sub: String? = null, accent: Int = Colors.GOLD,
        enabled: Boolean = true, selected: Boolean = false, size: Float = 46f, icon: ((Gfx, Rect) -> Unit)? = null,
    ): Boolean {
        register(id, r, enabled)
        val focused = focusId == id
        val hover = cursors.any { it.active && it.hoverId == id } || (pointerDown && downInside == id)
        val d = dwellOn(id)
        val pressed = pointerDown && downInside == id
        val base = if (selected) Colors.withAlpha(accent, 0.92f) else theme.button
        g.save()
        if (pressed && enabled) { g.translate(r.cx, r.cy); g.scale(0.965f); g.translate(-r.cx, -r.cy) }
        // soft drop shadow, and a glow when pointed at or focused
        g.fillRoundRect(r.left + 3f, r.top + 10f, r.right + 3f, r.bottom + 11f, 28f, 0x33000000)
        if ((focused || hover) && enabled) {
            g.fillRoundRect(r.left - 9f, r.top - 9f, r.right + 9f, r.bottom + 9f, 34f, Colors.withAlpha(accent, 0.16f))
            g.fillRoundRect(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f, 30f, Colors.withAlpha(accent, 0.22f))
        }
        when {
            !enabled -> g.fillRoundRect(r.left, r.top, r.right, r.bottom, 26f, 0x66222A35)
            theme.highContrast -> g.fillRoundRect(r.left, r.top, r.right, r.bottom, 26f, base)
            else -> {
                val lift = if (hover) 0.3f else 0.17f
                g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, 26f, Colors.lighter(base, lift), Colors.darker(base, 0.78f))
                g.fillRoundRectGradient(r.left + 3f, r.top + 3f, r.right - 3f, r.top + r.height * 0.48f, 22f, 0x2EFFFFFF, 0x05FFFFFF)
            }
        }
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, 26f, if (focused) 7f else theme.borderWidth, if (focused || hover) accent else theme.buttonBorder)
        if (d > 0f) {
            g.save(); g.clipRect(r.left, r.top, r.left + r.width * d, r.bottom)
            g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, 26f, Colors.withAlpha(accent, 0.55f), Colors.withAlpha(accent, 0.25f))
            g.restore()
            g.glow(r.left + r.width * d, r.cy, r.height * 0.6f, Colors.withAlpha(accent, 0.5f))
        }
        val textColor = if (!enabled) 0x88FFFFFF.toInt() else if (selected) Colors.BLACK else Colors.WHITE
        val ts = size * theme.textScale
        var tx = r.cx
        if (icon != null) {
            val ir = Rect(r.left + 16f, r.top + 12f, r.left + 16f + (r.height - 24f), r.bottom - 12f)
            icon(g, ir)
            tx = r.cx + (r.height - 24f) / 2f
        }
        if (sub == null) g.text(label, tx, r.cy, fit(g, label, ts, r.width - 40f - (if (icon != null) r.height else 0f)), textColor)
        else {
            g.text(label, tx, r.cy - ts * 0.38f, fit(g, label, ts, r.width - 40f), textColor)
            g.text(sub, tx, r.cy + ts * 0.5f, fit(g, sub, ts * 0.55f, r.width - 40f, false), if (selected) 0xCC000000.toInt() else theme.dim, bold = false)
        }
        g.restore()
        return activated == id
    }

    fun toggle(g: Gfx, id: String, r: Rect, label: String, value: Boolean, enabled: Boolean = true): Boolean {
        val act = button(g, id, r, "", enabled = enabled)
        g.text(label, r.left + 30f, r.cy, fit(g, label, 40f * theme.textScale, r.width - 200f), Colors.WHITE, Align.LEFT)
        val sx = r.right - 110f
        val track = if (value) Colors.GOOD else 0xFF54637A.toInt()
        g.fillRoundRectGradient(sx - 50f, r.cy - 24f, sx + 50f, r.cy + 24f, 24f, Colors.darker(track, 0.75f), Colors.lighter(track, 0.15f))
        if (value) g.glow(sx, r.cy, 70f, Colors.withAlpha(Colors.GOOD, 0.25f))
        val kx = if (value) sx + 26f else sx - 26f
        g.fillCircle(kx + 2f, r.cy + 4f, 20f, 0x44000000)
        g.fillCircleRadial(kx, r.cy, 20f, kx - 6f, r.cy - 8f, 28f, Colors.WHITE, 0xFFC9D6E6.toInt())
        g.text(if (value) "ON" else "OFF", sx + (if (value) -22f else 22f), r.cy, 20f, if (value) 0xFF0B4D2C.toInt() else Colors.WHITE)
        return act
    }

    /** Label + value with - and + buttons. Returns -1, 0 or +1. */
    fun stepper(g: Gfx, id: String, r: Rect, label: String, value: String): Int {
        if (theme.highContrast) g.fillRoundRect(r.left, r.top, r.right, r.bottom, 26f, theme.button)
        else g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, 26f, Colors.lighter(theme.button, 0.12f), Colors.darker(theme.button, 0.8f))
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, 26f, theme.borderWidth, theme.buttonBorder)
        g.text(label, r.left + 30f, r.cy, fit(g, label, 40f * theme.textScale, r.width - 420f), Colors.WHITE, Align.LEFT)
        val bw = r.height - 16f
        val minus = Rect(r.right - 3 * bw - 120f, r.top + 8f, r.right - 2 * bw - 120f, r.bottom - 8f)
        val plus = Rect(r.right - bw - 16f, r.top + 8f, r.right - 16f, r.bottom - 8f)
        val m = button(g, "$id-", minus, "-", size = 52f)
        val p = button(g, "$id+", plus, "+", size = 52f)
        g.text(value, (minus.right + plus.left) / 2f, r.cy, 38f * theme.textScale, Colors.GOLD)
        return if (m) -1 else if (p) 1 else 0
    }

    fun title(g: Gfx, text: String, y: Float = 70f, size: Float = 76f) {
        val s = size * theme.textScale
        g.text(text, g.width / 2f + 3f, y + 5f, s, 0x66000000, outline = 0x44000000, outlineWidth = 10f)
        g.text(text, g.width / 2f, y, s, Colors.WHITE, outline = 0xCC0A1A33.toInt(), outlineWidth = 8f)
    }

    /** Touch/dwell back button (the Back key is handled globally through Screen.onBack). */
    fun backButton(g: Gfx): Boolean = button(g, "back", Rect(30f, 26f, 250f, 116f), "< Back", size = 40f, accent = Colors.ICE)

    fun paragraph(g: Gfx, lines: List<String>, x: Float, y: Float, size: Float = 38f, color: Int = Colors.WHITE, gap: Float = 1.45f, align: Align = Align.LEFT): Float {
        var yy = y
        for (l in lines) { g.text(l, x, yy, size * theme.textScale, color, align, bold = false); yy += size * theme.textScale * gap }
        return yy
    }

    /** Draws the hands-free cursors on top of everything. */
    fun drawCursors(g: Gfx) {
        for (c in cursors) {
            if (!c.visible) continue
            val a = if (c.active) 1f else 0.35f
            g.save(); g.setAlpha(a)
            g.glow(c.x, c.y, 70f, Colors.withAlpha(c.color, 0.35f))
            g.fillCircle(c.x + 4f, c.y + 6f, 30f, Colors.SHADOW)
            g.fillCircleRadial(c.x, c.y, 28f, c.x - 9f, c.y - 10f, 40f, Colors.lighter(c.color, 0.4f), Colors.darker(c.color, 0.7f))
            g.strokeCircle(c.x, c.y, 28f, 5f, Colors.WHITE)
            if (c.dwell > 0f) Draw.progressRing(g, c.x, c.y, 44f, c.dwell / dwellSeconds, Colors.GOLD, 9f)
            g.text("P${c.player + 1}", c.x, c.y, 24f, Colors.BLACK)
            g.restore()
        }
    }

    fun fit(g: Gfx, s: String, size: Float, maxW: Float, bold: Boolean = true): Float {
        val w = g.measureText(s, size, bold)
        return if (w <= maxW || w <= 0f) size else size * maxW / w
    }
}
