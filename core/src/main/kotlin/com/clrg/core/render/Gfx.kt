package com.clrg.core.render

/** Opaque platform image (the live camera preview). */
interface ImageRef {
    val width: Int
    val height: Int
}

enum class Align { LEFT, CENTER, RIGHT }

/**
 * 2D drawing API used by every screen and minigame. The canvas is *virtual*: its height is always 1080 units and its
 * width follows the display aspect ratio (1920 on a 16:9 TV, ~2400 on a 20:9 phone screen), so layouts stay
 * readable on any screen. Colours are ARGB ints. Implemented with android.graphics.Canvas on the device.
 */
interface Gfx {
    val width: Float
    val height: Float

    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun scale(sx: Float, sy: Float = sx)
    fun rotate(radians: Float)
    fun clipRect(l: Float, t: Float, r: Float, b: Float)
    /** Global opacity multiplier (saved/restored with [save]/[restore]). */
    fun setAlpha(a: Float)
    fun getAlpha(): Float

    fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int)
    fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int)
    fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, color: Int)
    fun fillCircle(cx: Float, cy: Float, r: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int)
    fun fillOval(l: Float, t: Float, r: Float, b: Float, color: Int)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int)
    /** pts = x0,y0,x1,y1,... (n points). */
    fun fillPolygon(pts: FloatArray, n: Int, color: Int)
    fun polyline(pts: FloatArray, n: Int, width: Float, color: Int, closed: Boolean = false)
    /** Stroked arc (radians, clockwise on screen from +x). */
    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, width: Float, color: Int)
    fun fillPie(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int)
    /** Vertical gradient. */
    fun gradientRect(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int)
    /** Soft radial glow: [color] at the centre fading to transparent at radius [r]. */
    fun glow(cx: Float, cy: Float, r: Float, color: Int)
    /** Draws text vertically centred on [y]. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align = Align.CENTER, bold: Boolean = true, outline: Int = 0, outlineWidth: Float = 0f)
    fun measureText(s: String, size: Float, bold: Boolean = true): Float
    fun image(img: ImageRef, l: Float, t: Float, r: Float, b: Float, mirror: Boolean = false)

    // ---- gradient fills (lighting). Defaults fall back to flat colours for renderers without gradients.

    /** Circle lit by a radial gradient centred at (gx, gy) with radius [gr]: [inner] at the centre to [outer]. */
    fun fillCircleRadial(cx: Float, cy: Float, r: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) =
        fillCircle(cx, cy, r, Colors.lerp(inner, outer, 0.5f))

    /** Rectangle filled with a radial gradient (vignettes, spotlights, floor light pools). */
    fun fillRectRadial(l: Float, t: Float, r: Float, b: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) = Unit

    /** Rounded rectangle with a vertical gradient. */
    fun fillRoundRectGradient(l: Float, t: Float, r: Float, b: Float, radius: Float, top: Int, bottom: Int) =
        fillRoundRect(l, t, r, b, radius, Colors.lerp(top, bottom, 0.5f))

    /** Polygon with a vertical gradient from [top] at y = [y0] to [bottom] at y = [y1]. */
    fun fillPolygonGradient(pts: FloatArray, n: Int, y0: Float, y1: Float, top: Int, bottom: Int) =
        fillPolygon(pts, n, Colors.lerp(top, bottom, 0.5f))
}

object Colors {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)
    fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    fun withAlpha(c: Int, a: Float): Int = (((alpha(c) * a.coerceIn(0f, 1f)).toInt()) shl 24) or (c and 0xFFFFFF)
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * k).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    fun darker(c: Int, f: Float = 0.7f): Int = lerp(c, (c and 0xFF000000.toInt()), 1f - f)
    fun lighter(c: Int, f: Float = 0.35f): Int = lerp(c, (c and 0xFF000000.toInt()) or 0xFFFFFF, f)

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val TRANSPARENT = 0
    const val SNOW = 0xFFF4F8FF.toInt()
    const val NIGHT_TOP = 0xFF06122A.toInt()
    const val NIGHT_BOTTOM = 0xFF1B3D6E.toInt()
    const val PANEL = 0xEE0E2240.toInt()
    const val PANEL_LIGHT = 0xFF1C3A66.toInt()
    const val PANEL_BORDER = 0xFF5D8BC9.toInt()
    const val GOLD = 0xFFFFD54F.toInt()
    const val WARM = 0xFFFFB74D.toInt()
    const val CANDY = 0xFFE53950.toInt()
    const val PINE = 0xFF2E8B57.toInt()
    const val PINE_DARK = 0xFF1E5E3B.toInt()
    const val ICE = 0xFF9AD9FF.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0xFFB9CCE6.toInt()
    const val GOOD = 0xFF69F0AE.toInt()
    const val BAD = 0xFFFF6E6E.toInt()
    const val WARN = 0xFFFFC857.toInt()
    const val SHADOW = 0x66000000
}

/**
 * Player colours chosen for contrast on dark and snowy backgrounds and to stay distinguishable with the common
 * forms of colour blindness (players are also always labelled by name and P1/P2 badges, never colour alone).
 */
object PlayerColors {
    val all = intArrayOf(
        0xFFFF5252.toInt(), // red
        0xFF40A9FF.toInt(), // blue
        0xFFFFC400.toInt(), // gold
        0xFFB388FF.toInt(), // purple
        0xFF1DE9B6.toInt(), // teal
        0xFFFF9100.toInt(), // orange
        0xFFFF80AB.toInt(), // pink
        0xFFC6FF00.toInt(), // lime
    )
    val names = listOf("Red", "Blue", "Gold", "Purple", "Teal", "Orange", "Pink", "Lime")
    fun get(i: Int) = all[((i % all.size) + all.size) % all.size]
}
