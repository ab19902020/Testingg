package com.clrg.core.render

/** Opaque platform image (the live camera preview). */
interface ImageRef {
    val width: Int
    val height: Int
}

enum class Align { LEFT, CENTER, RIGHT }

/**
 * How what is drawn mixes with what is already there. [ADD] adds light (glows, sparks, lamps, beams: they brighten
 * instead of washing colours out), [SCREEN] is a softer add that never goes past white, [MULTIPLY] darkens (shadows,
 * tints).
 */
enum class Blend { NORMAL, ADD, SCREEN, MULTIPLY }

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

    /** Polygon with a linear gradient in any direction: [c0] at ([x0], [y0]) to [c1] at ([x1], [y1]). */
    fun fillPolygonLinear(pts: FloatArray, n: Int, x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int) =
        fillPolygon(pts, n, Colors.lerp(c0, c1, 0.5f))

    // ---- blending and cached layers. Defaults fall back to plain drawing for renderers without them.

    /** Blend mode for what is drawn next (saved/restored with [save]/[restore]). */
    fun setBlend(mode: Blend) = Unit

    /**
     * Draws a cached picture of [draw] covering ([l], [t], [r], [b]) in the current coordinates. The picture is drawn
     * once (in the same virtual coordinates as everything else, so [draw] can be ordinary drawing code) and then
     * reused every frame as one image - so a detailed, static backdrop costs about as much as a single rectangle.
     * It is redrawn when [key], [version] or the size changes.
     *
     *  - [res]: resolution relative to the screen (0.5 = half; fine for blurred or soft layers).
     *  - [blur]: blur radius in virtual units, applied once when the picture is made (depth of field, haze).
     *  - [grain]: 0..1 fine film-grain texture, applied once.
     *
     * Only static content belongs in a layer; anything that moves is drawn on top as usual.
     */
    fun layer(key: String, l: Float, t: Float, r: Float, b: Float, version: Int = 0, res: Float = 1f, blur: Float = 0f,
              grain: Float = 0f, draw: (Gfx) -> Unit) {
        save(); clipRect(l, t, r, b); draw(this); restore()
    }
}

/** Pixel operations used when cached layers are made (pure Kotlin, so the phone and the tests share them). */
object ImageOps {
    /**
     * Box blur repeated three times (close to a Gaussian) on ARGB pixels, done on premultiplied colour so
     * transparent edges don't darken.
     */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1 || w < 2 || h < 2) return
        val n = w * h
        // an opaque picture (a backdrop) repeats its edge; one with transparent edges (a sprite) fades out at them
        val clamp = intArrayOf(px[0], px[w - 1], px[n - w], px[n - 1]).all { (it ushr 24) == 255 }
        val a = IntArray(n); val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]; val al = (c ushr 24) and 0xFF
            a[i] = al; r[i] = ((c shr 16) and 0xFF) * al / 255; g[i] = ((c shr 8) and 0xFF) * al / 255; b[i] = (c and 0xFF) * al / 255
        }
        val tmp = IntArray(maxOf(w, h))
        val rad = radius.coerceAtMost(maxOf(w, h) / 2)
        repeat(3) {
            for (ch in arrayOf(a, r, g, b)) {
                for (y in 0 until h) boxLine(ch, y * w, 1, w, rad, tmp, clamp)
                for (x in 0 until w) boxLine(ch, x, w, h, rad, tmp, clamp)
            }
        }
        for (i in 0 until n) {
            val al = a[i].coerceIn(0, 255)
            px[i] = if (al == 0) 0 else (al shl 24) or ((r[i] * 255 / al).coerceIn(0, 255) shl 16) or
                ((g[i] * 255 / al).coerceIn(0, 255) shl 8) or (b[i] * 255 / al).coerceIn(0, 255)
        }
    }

    /** One line of a box blur: [len] values from [start], [stride] apart; outside is the edge value or nothing. */
    private fun boxLine(ch: IntArray, start: Int, stride: Int, len: Int, rad: Int, tmp: IntArray, clamp: Boolean) {
        fun at(i: Int): Int = if (i in 0 until len) ch[start + i * stride] else if (clamp) ch[start + i.coerceIn(0, len - 1) * stride] else 0
        var sum = 0
        for (k in -rad..rad) sum += at(k)
        val win = rad * 2 + 1
        for (i in 0 until len) {
            tmp[i] = sum / win
            sum += at(i + rad + 1) - at(i - rad)
        }
        for (i in 0 until len) ch[start + i * stride] = tmp[i]
    }

    /** Fine monochrome grain: each pixel lighter or darker by up to [amount] x 24 levels (opaque pixels only). */
    fun grain(px: IntArray, w: Int, h: Int, amount: Float, seed: Int = 1) {
        if (amount <= 0f) return
        val k = (amount.coerceIn(0f, 1f) * 24f)
        for (i in 0 until w * h) {
            val c = px[i]
            if ((c ushr 24) < 8) continue
            var x = (i + seed * 0x9E3779B1.toInt()) * 0x45D9F3B
            x = (x ushr 16 xor x) * 0x45D9F3B
            x = x ushr 16 xor x
            val d = (((x and 0xFFFF) / 65535f) - 0.5f) * 2f * k
            fun ch(s: Int) = (((c shr s) and 0xFF) + d).toInt().coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
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
