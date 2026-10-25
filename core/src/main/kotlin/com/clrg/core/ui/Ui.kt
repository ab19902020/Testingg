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
    /** Seconds the cursor has been off its button / on a different one (brief slips do not restart the ring). */
    var awaySec = 0f
    var otherSec = 0f
    /** Seconds since this cursor selected something (for the "pop" on screen), large when long ago. */
    var sinceSelect = 9f
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
class Ui(val theme: Theme, private val sound: (Sfx, Float) -> Unit) {
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
        const val MAGNET_PX = 60f
        /** A hand cursor on a button keeps pointing at it until it is this far (px) outside. */
        const val STICKY_PX = 70f
        /** Slipping off a button for less than this (s) pauses the ring instead of restarting it. */
        const val SLIP_GRACE = 0.35f
        /** Hovering a different button this long (s) moves the ring to it. */
        const val SWITCH_SEC = 0.12f
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
    /** Per-button hover animation (0..1), eased each frame so cards lift and glow smoothly. */
    private val hoverAnim = HashMap<String, Float>()

    private fun hoverAmount(id: String, on: Boolean): Float {
        val cur = hoverAnim[id] ?: 0f
        val next = if (on) minOf(1f, cur + frameDt * 9f) else maxOf(0f, cur - frameDt * 6f)
        hoverAnim[id] = next
        // smoothstep
        return next * next * (3f - 2f * next)
    }

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
            c.sinceSelect += dt
            if (!cursorsEnabled || !c.active) { c.dwell = 0f; c.hoverId = null; c.awaySec = 0f; c.otherSec = 0f; continue }
            val id = hitMagnetic(c)
            if (c.lockoutUntilLeave != null && id != c.lockoutUntilLeave) c.lockoutUntilLeave = null
            if (globalLock > 0f || (id != null && id == c.lockoutUntilLeave)) { c.hoverId = id; c.dwell = 0f; continue }
            when {
                id != null && id == c.hoverId -> {
                    c.awaySec = 0f; c.otherSec = 0f
                    val before = c.dwell
                    c.dwell += dt
                    // the hold-to-choose ring ticks, rising in pitch as it fills
                    val q = (c.dwell / dwellSeconds * 4).toInt()
                    if ((before / dwellSeconds * 4).toInt() != q && q < 4) sound(Sfx.TICK, 1f + 0.15f * q)
                }
                // a moment off the button (a wobble, a tracking blip): the ring pauses rather than restarting
                c.hoverId != null && id == null && c.awaySec + dt < SLIP_GRACE -> c.awaySec += dt
                c.hoverId != null && id != null && c.otherSec + dt < SWITCH_SEC -> c.otherSec += dt
                else -> {
                    if (id != null && id != c.hoverId) sound(Sfx.UI_HOVER, 1f)
                    c.hoverId = id; c.dwell = 0f; c.awaySec = 0f; c.otherSec = 0f
                }
            }
            val target = c.hoverId ?: continue
            if (c.dwell >= dwellSeconds && boxes.any { it.id == target && it.enabled }) {
                activate(target)
                c.dwell = 0f
                c.lockoutUntilLeave = target
                c.sinceSelect = 0f
                globalLock = 0.8f
            }
        }
        nextBoxes = ArrayList()
    }

    private fun activate(id: String) {
        if (activated == null) {
            activated = id
            sound(if (id == "back" || id.startsWith("quit") || id.startsWith("cancel")) Sfx.UI_BACK else Sfx.UI_CONFIRM, 1f)
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

    /** Big rounded button (optionally with an [icon]). Returns true on the frame it is activated. */
    fun button(
        g: Gfx, id: String, r: Rect, label: String, sub: String? = null, accent: Int = Colors.GOLD,
        enabled: Boolean = true, selected: Boolean = false, size: Float = 46f, icon: Icon? = null,
    ): Boolean {
        register(id, r, enabled)
        val focused = focusId == id
        val hover = cursors.any { it.active && it.hoverId == id } || (pointerDown && downInside == id)
        val h = if (enabled) hoverAmount(id, hover || focused) else 0f
        val d = dwellOn(id)
        val pressed = pointerDown && downInside == id
        val base = if (selected) Colors.withAlpha(accent, 0.92f) else theme.button
        val rad = minOf(26f, r.height * 0.32f)
        g.save()
        val sc = (if (pressed && enabled) 0.965f else 1f) * (1f + 0.025f * h)
        if (sc != 1f) { g.translate(r.cx, r.cy); g.scale(sc); g.translate(-r.cx, -r.cy) }
        // soft drop shadow, and a glow when pointed at or focused
        g.fillRoundRect(r.left + 3f, r.top + 10f + 4f * h, r.right + 3f, r.bottom + 11f + 4f * h, rad + 2f, 0x33000000)
        if (h > 0f) {
            g.fillRoundRect(r.left - 10f, r.top - 10f, r.right + 10f, r.bottom + 10f, rad + 8f, Colors.withAlpha(accent, 0.16f * h))
            g.fillRoundRect(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f, rad + 4f, Colors.withAlpha(accent, 0.24f * h))
        }
        when {
            !enabled -> g.fillRoundRect(r.left, r.top, r.right, r.bottom, rad, 0x66222A35)
            theme.highContrast -> g.fillRoundRect(r.left, r.top, r.right, r.bottom, rad, base)
            else -> {
                val tint = if (selected) base else Colors.lerp(base, accent, 0.1f + 0.12f * h)
                g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, rad, Colors.lighter(tint, 0.17f + 0.12f * h), Colors.darker(tint, 0.74f))
                // glassy top sheen and a thin accent line along the bottom
                g.fillRoundRectGradient(r.left + 3f, r.top + 3f, r.right - 3f, r.top + r.height * 0.48f, rad - 3f, 0x30FFFFFF, 0x04FFFFFF)
                if (!selected) g.fillRoundRect(r.left + rad, r.bottom - 7f, r.right - rad, r.bottom - 3f, 2f, Colors.withAlpha(accent, 0.35f + 0.5f * h))
            }
        }
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, rad, if (focused) 7f else theme.borderWidth + 2f * h, if (focused || h > 0.05f) Colors.lerp(theme.buttonBorder, accent, maxOf(h, if (focused) 1f else 0f)) else theme.buttonBorder)
        if (d > 0f) {
            g.save(); g.clipRect(r.left, r.top, r.left + r.width * d, r.bottom)
            g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, rad, Colors.withAlpha(accent, 0.55f), Colors.withAlpha(accent, 0.25f))
            g.restore()
            g.glow(r.left + r.width * d, r.cy, r.height * 0.6f, Colors.withAlpha(accent, 0.5f))
        }
        val textColor = if (!enabled) 0x88FFFFFF.toInt() else if (selected) Colors.BLACK else Colors.WHITE
        val ts = size * theme.textScale
        var tx = r.cx
        var maxW = r.width - 40f
        if (icon != null) {
            val isz = minOf(r.height * 0.56f, ts * 1.15f)
            val labelW = if (label.isEmpty()) 0f else minOf(g.measureText(label, ts), r.width - isz - 70f)
            val groupW = isz + (if (label.isEmpty()) 0f else 18f + labelW)
            val ix = r.cx - groupW / 2f + isz / 2f
            Icons.draw(g, icon, ix, if (sub == null) r.cy else r.cy - ts * 0.12f, isz, if (selected) Colors.BLACK else Colors.lerp(Colors.WHITE, accent, 0.35f + 0.4f * h))
            tx = ix + isz / 2f + 18f + labelW / 2f
            maxW = r.width - isz - 70f
        }
        if (sub == null) {
            if (label.isNotEmpty()) g.text(label, tx, r.cy, fit(g, label, ts, maxW), textColor)
        } else {
            g.text(label, tx, r.cy - ts * 0.38f, fit(g, label, ts, maxW), textColor)
            g.text(sub, tx, r.cy + ts * 0.5f, fit(g, sub, ts * 0.55f, maxW, false), if (selected) 0xCC000000.toInt() else theme.dim, bold = false)
        }
        g.restore()
        return activated == id
    }

    /**
     * A large menu card: artwork (or a big icon on a glowing disc) on top, title, subtitle and tag chips below.
     * Lifts and glows when pointed at; the hold-to-choose progress runs along its bottom edge.
     */
    fun tile(
        g: Gfx, id: String, r: Rect, title: String, sub: String? = null, accent: Int = Colors.GOLD, icon: Icon? = null,
        art: ((Gfx, Rect) -> Unit)? = null, tags: List<String> = emptyList(), enabled: Boolean = true,
        titleSize: Float = 44f, badge: String? = null,
    ): Boolean {
        register(id, r, enabled)
        val focused = focusId == id
        val hover = cursors.any { it.active && it.hoverId == id } || (pointerDown && downInside == id)
        val h = if (enabled) hoverAmount(id, hover || focused) else 0f
        val d = dwellOn(id)
        val pressed = pointerDown && downInside == id
        val rad = 30f
        g.save()
        g.translate(0f, -10f * h)
        val sc = (if (pressed && enabled) 0.97f else 1f) * (1f + 0.02f * h)
        if (sc != 1f) { g.translate(r.cx, r.cy); g.scale(sc); g.translate(-r.cx, -r.cy) }
        // shadow and hover glow
        g.fillRoundRect(r.left + 6f, r.top + 14f + 10f * h, r.right + 6f, r.bottom + 16f + 10f * h, rad, Colors.withAlpha(Colors.BLACK, 0.35f + 0.1f * h))
        if (h > 0f) g.fillRoundRect(r.left - 12f, r.top - 12f, r.right + 12f, r.bottom + 12f, rad + 10f, Colors.withAlpha(accent, 0.22f * h))
        // body: deep glass tinted with the accent
        val top = if (theme.highContrast) 0xFF000000.toInt() else Colors.lerp(0xFF16305A.toInt(), accent, 0.16f + 0.1f * h)
        val bottom = if (theme.highContrast) 0xFF000000.toInt() else 0xFF0A1630.toInt()
        g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, rad, if (enabled) top else 0xFF2A3342.toInt(), bottom)
        val textH = 30f + titleSize * theme.textScale + (if (sub != null) 44f else 0f) + (if (tags.isNotEmpty()) 52f else 0f)
        val artR = Rect(r.left + 14f, r.top + 14f, r.right - 14f, r.bottom - textH)
        if (artR.height > 40f) {
            g.save(); g.clipRect(artR.left, artR.top, artR.right, artR.bottom)
            if (art != null) art(g, artR)
            else {
                g.fillRoundRectGradient(artR.left, artR.top, artR.right, artR.bottom, rad - 10f, Colors.lerp(0xFF0F2547.toInt(), accent, 0.35f), Colors.lerp(0xFF081428.toInt(), accent, 0.12f))
                val isz = minOf(artR.height * 0.62f, artR.width * 0.5f)
                val pulse = 1f + 0.04f * h * kotlin.math.sin(time.toFloat() * 6f)
                g.glow(artR.cx, artR.cy, isz * (0.85f + 0.25f * h), Colors.withAlpha(accent, 0.35f + 0.25f * h))
                if (icon != null) Icons.draw(g, icon, artR.cx, artR.cy, isz * pulse, Colors.lerp(Colors.WHITE, accent, 0.25f))
            }
            // soft vignette where the art meets the text
            g.gradientRect(artR.left, artR.bottom - 60f, artR.right, artR.bottom, 0x00000000, 0x660A1630)
            g.restore()
        }
        // sheen and border
        g.fillRoundRectGradient(r.left + 3f, r.top + 3f, r.right - 3f, r.top + r.height * 0.35f, rad - 3f, 0x22FFFFFF, 0x00FFFFFF)
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, rad, if (focused) 7f else 3f + 3f * h, Colors.lerp(Colors.withAlpha(accent, 0.45f), accent, maxOf(h, if (focused) 1f else 0f)))
        // text
        var y = r.bottom - textH + 18f + titleSize * theme.textScale * 0.5f
        val ts = titleSize * theme.textScale
        val textColor = if (enabled) Colors.WHITE else 0x88FFFFFF.toInt()
        g.text(title, r.cx + 2f, y + 3f, fit(g, title, ts, r.width - 40f), 0x66000000)
        g.text(title, r.cx, y, fit(g, title, ts, r.width - 40f), textColor)
        y += ts * 0.5f + 8f
        if (sub != null) {
            val ss = 27f * theme.textScale
            val fitted = fit(g, sub, ss, r.width - 40f, false)
            if (fitted >= ss * 0.8f) g.text(sub, r.cx, y + 14f, fitted, theme.dim, bold = false)
            else {
                // two lines, split at the space nearest the middle
                val mid = sub.length / 2
                val cut = (0..mid).firstNotNullOfOrNull { d -> listOf(mid - d, mid + d).firstOrNull { it in 1 until sub.length && sub[it] == ' ' } } ?: mid
                val a = sub.substring(0, cut).trim(); val b = sub.substring(cut).trim()
                val sz = minOf(fit(g, a, ss * 0.82f, r.width - 40f, false), fit(g, b, ss * 0.82f, r.width - 40f, false))
                g.text(a, r.cx, y + 2f, sz, theme.dim, bold = false)
                g.text(b, r.cx, y + 2f + sz * 1.1f, sz, theme.dim, bold = false)
            }
            y += 44f
        }
        if (tags.isNotEmpty()) {
            val sizes = tags.map { g.measureText(it, 22f, false) + 30f }
            val total = sizes.sum() + 10f * (tags.size - 1)
            var x = r.cx - minOf(total, r.width - 30f) / 2f
            for ((i, t) in tags.withIndex()) {
                if (x + sizes[i] > r.right - 14f) break
                chip(g, x, y + 22f, t, accent, 22f)
                x += sizes[i] + 10f
            }
        }
        badge?.let { b ->
            val bw = g.measureText(b, 24f) + 34f
            g.fillRoundRect(r.right - bw - 18f, r.top + 22f, r.right - 18f, r.top + 64f, 21f, accent)
            g.text(b, r.right - 18f - bw / 2f, r.top + 43f, 24f, Colors.BLACK)
        }
        if (d > 0f) {
            val bx = r.left + 20f + (r.width - 40f) * d
            g.fillRoundRect(r.left + 20f, r.bottom - 14f, r.right - 20f, r.bottom - 6f, 4f, 0x55000000)
            g.fillRoundRect(r.left + 20f, r.bottom - 14f, bx, r.bottom - 6f, 4f, accent)
            g.glow(bx, r.bottom - 10f, 40f, Colors.withAlpha(accent, 0.7f))
        }
        g.restore()
        return activated == id
    }

    /** Small tag pill whose left edge is at [x], vertically centred on [cy]. Returns its width. */
    fun chip(g: Gfx, x: Float, cy: Float, text: String, color: Int, size: Float = 22f): Float {
        val w = g.measureText(text, size, false) + 30f
        g.fillRoundRect(x, cy - size * 0.8f, x + w, cy + size * 0.8f, size * 0.8f, Colors.withAlpha(color, 0.22f))
        g.strokeRoundRect(x, cy - size * 0.8f, x + w, cy + size * 0.8f, size * 0.8f, 2f, Colors.withAlpha(color, 0.7f))
        g.text(text, x + w / 2f, cy, size, Colors.lerp(Colors.WHITE, color, 0.25f), bold = false)
        return w
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
    fun backButton(g: Gfx): Boolean = button(g, "back", Rect(30f, 26f, 250f, 116f), "Back", size = 38f, accent = Colors.ICE, icon = Icon.BACK)

    fun paragraph(g: Gfx, lines: List<String>, x: Float, y: Float, size: Float = 38f, color: Int = Colors.WHITE, gap: Float = 1.45f, align: Align = Align.LEFT): Float {
        var yy = y
        for (l in lines) { g.text(l, x, yy, size * theme.textScale, color, align, bold = false); yy += size * theme.textScale * gap }
        return yy
    }

    /**
     * Draws the hands-free cursors on top of everything: a mitten in the player's colour, a big ring that fills
     * while holding over a button, a "pop" when something is chosen, and a faded mitten with an up arrow while the
     * hand is lowered (seen, but not pointing).
     */
    fun drawCursors(g: Gfx) {
        for (c in cursors) {
            if (!c.visible) continue
            val x = c.x; val y = c.y
            g.save()
            if (!c.active) {
                g.setAlpha(0.45f)
                mitten(g, x, y, 0.8f, c.color)
                Draw.tri(g, x - 16f, y - 52f, x + 16f, y - 52f, x, y - 74f, Colors.WHITE)
                g.restore()
                continue
            }
            val pointing = c.hoverId != null
            g.glow(x, y, if (pointing) 110f else 86f, Colors.withAlpha(c.color, if (pointing) 0.5f else 0.35f))
            if (c.dwell > 0f || pointing) {
                g.strokeCircle(x, y, 66f, 13f, 0x66000000)
                Draw.progressRing(g, x, y, 66f, (c.dwell / dwellSeconds).coerceIn(0f, 1f), Colors.GOLD, 13f)
            }
            mitten(g, x, y, 1.25f, c.color)
            g.text("P${c.player + 1}", x, y + 8f, 26f, Colors.BLACK)
            if (c.sinceSelect < 0.45f) {
                val k = c.sinceSelect / 0.45f
                g.strokeCircle(x, y, 66f + 80f * k, 12f * (1f - k), Colors.withAlpha(Colors.GOLD, 1f - k))
            }
            g.restore()
        }
    }

    /** A knitted mitten (palm, thumb, white cuff) centred at ([x], [y]). */
    private fun mitten(g: Gfx, x: Float, y: Float, s: Float, color: Int) {
        g.fillCircle(x + 4f * s, y + 7f * s, 32f * s, Colors.SHADOW)
        g.fillRoundRect(x - 14f * s, y + 18f * s, x + 14f * s, y + 34f * s, 7f * s, 0xFFF1F5FA.toInt())
        g.fillCircleRadial(x - 22f * s, y + 2f * s, 11f * s, x - 25f * s, y - 2f * s, 16f * s, Colors.lighter(color, 0.4f), Colors.darker(color, 0.7f))
        g.fillRoundRectGradient(x - 20f * s, y - 30f * s, x + 20f * s, y + 22f * s, 19f * s, Colors.lighter(color, 0.45f), Colors.darker(color, 0.72f))
        g.strokeRoundRect(x - 20f * s, y - 30f * s, x + 20f * s, y + 22f * s, 19f * s, 4f * s, Colors.WHITE)
    }

    fun fit(g: Gfx, s: String, size: Float, maxW: Float, bold: Boolean = true): Float {
        val w = g.measureText(s, size, bold)
        return if (w <= maxW || w <= 0f) size else size * maxW / w
    }
}
