package com.clrg.core.render

import kotlin.math.cos
import kotlin.math.sin

/** Drawing for the sports (non-Christmas) games: daytime skies, fields, crowds and hand icons. */
object SportsDraw {
    const val SKY_TOP = 0xFF2F7FD0.toInt()
    const val SKY_HORIZON = 0xFFBFE3FF.toInt()
    const val GRASS_FAR = 0xFF6FAE4A.toInt()
    const val GRASS_NEAR = 0xFF3E7F2C.toInt()

    /** Blue sky down to [horizon], a soft sun and slowly drifting clouds. */
    fun daySky(g: Gfx, l: Float, t: Float, r: Float, horizon: Float, time: Double, sunX: Float = l + (r - l) * 0.78f) {
        g.gradientRect(l, t, r, horizon, SKY_TOP, SKY_HORIZON)
        g.glow(sunX, t + (horizon - t) * 0.22f, 260f, 0x66FFF3C4)
        g.fillCircle(sunX, t + (horizon - t) * 0.22f, 46f, 0xFFFFF8E1.toInt())
        val w = r - l
        for (i in 0 until 6) {
            val speed = 8f + i * 3f
            val cx = l + ((i * 0.37f * w + time.toFloat() * speed) % (w + 500f)) - 250f
            val cy = t + (horizon - t) * (0.12f + 0.12f * (i % 3))
            cloud(g, cx, cy, 120f + 40f * (i % 3), 0xEEFFFFFF.toInt())
        }
    }

    fun cloud(g: Gfx, cx: Float, cy: Float, size: Float, color: Int) {
        g.fillOval(cx - size, cy - size * 0.28f, cx + size, cy + size * 0.32f, Colors.withAlpha(color, 0.9f))
        g.fillCircle(cx - size * 0.35f, cy - size * 0.18f, size * 0.38f, color)
        g.fillCircle(cx + size * 0.15f, cy - size * 0.3f, size * 0.48f, color)
        g.fillCircle(cx + size * 0.6f, cy - size * 0.08f, size * 0.3f, color)
    }

    /** Rolling hills with a tree line along the horizon. */
    fun hills(g: Gfx, l: Float, r: Float, horizon: Float, seed: Int = 0) {
        val n = 24
        val pts = FloatArray((n + 3) * 2)
        val w = r - l
        for (i in 0..n) {
            val x = l + w * i / n
            val h = 40f + 30f * sin(i * 0.9f + seed) + 18f * sin(i * 2.3f + seed * 2)
            pts[i * 2] = x; pts[i * 2 + 1] = horizon - h
        }
        pts[(n + 1) * 2] = r; pts[(n + 1) * 2 + 1] = horizon + 2f
        pts[(n + 2) * 2] = l; pts[(n + 2) * 2 + 1] = horizon + 2f
        g.fillPolygonGradient(pts, n + 3, horizon - 80f, horizon, 0xFF8DB7A0.toInt(), 0xFF6E9C7E.toInt())
        // tree line
        var x = l
        var i = 0
        while (x < r) {
            val h = 16f + 10f * ((i * 7919) % 5) / 4f
            g.fillOval(x - 14f, horizon - h - 12f, x + 14f, horizon + 2f, 0xFF3F6F45.toInt())
            x += 18f + (i % 3) * 6f; i++
        }
    }

    /** A cartoon "finger gun" hand: index finger along [angle], thumb raised or dropped. */
    fun fingerGun(g: Gfx, x: Float, y: Float, size: Float, angle: Float, thumbDown: Float, skin: Int = 0xFFF2C9A0.toInt(), outline: Int = 0xFF6D4C41.toInt()) {
        g.save()
        g.translate(x, y)
        g.rotate(angle)
        val s = size
        // fist (palm with curled fingers)
        g.fillRoundRect(-s * 0.45f, -s * 0.3f, s * 0.15f, s * 0.35f, s * 0.15f, skin)
        g.strokeRoundRect(-s * 0.45f, -s * 0.3f, s * 0.15f, s * 0.35f, s * 0.15f, s * 0.04f, outline)
        for (k in 0 until 3) g.line(-s * 0.1f, -s * 0.05f + k * s * 0.12f, s * 0.12f, -s * 0.05f + k * s * 0.12f, s * 0.03f, Colors.withAlpha(outline, 0.6f))
        // index finger pointing along +x
        g.fillRoundRect(s * 0.05f, -s * 0.3f, s * 0.75f, -s * 0.12f, s * 0.09f, skin)
        g.strokeRoundRect(s * 0.05f, -s * 0.3f, s * 0.75f, -s * 0.12f, s * 0.09f, s * 0.04f, outline)
        // thumb: up (cocked) to down along the finger (fired)
        g.save()
        g.translate(-s * 0.1f, -s * 0.28f)
        g.rotate(-1.25f + 1.1f * thumbDown)
        g.fillRoundRect(0f, -s * 0.08f, s * 0.42f, s * 0.08f, s * 0.08f, skin)
        g.strokeRoundRect(0f, -s * 0.08f, s * 0.42f, s * 0.08f, s * 0.08f, s * 0.035f, outline)
        g.restore()
        g.restore()
    }

    /** A boxing glove seen from the front (fist towards the viewer), [size] across. */
    fun glove(g: Gfx, x: Float, y: Float, size: Float, color: Int, tilt: Float = 0f) {
        g.save(); g.translate(x, y); g.rotate(tilt)
        val r = size / 2f
        g.fillCircleRadial(0f, 0f, r, -r * 0.35f, -r * 0.4f, r * 1.3f, Colors.lighter(color, 0.45f), Colors.darker(color, 0.6f))
        g.fillOval(-r * 0.95f, -r * 0.1f, -r * 0.35f, r * 0.75f, Colors.darker(color, 0.8f)) // thumb
        g.fillRoundRect(-r * 0.55f, r * 0.75f, r * 0.55f, r * 1.25f, r * 0.15f, 0xFFF5F5F5.toInt()) // cuff
        g.strokeCircle(0f, 0f, r, r * 0.06f, Colors.darker(color, 0.45f))
        g.restore()
    }

    /** Cheering crowd silhouettes in a band (stadium seating). */
    fun crowd(g: Gfx, l: Float, r: Float, top: Float, bottom: Float, time: Double, rows: Int = 3) {
        g.gradientRect(l, top, r, bottom, 0xFF3A4660.toInt(), 0xFF262E40.toInt())
        val rowH = (bottom - top) / rows
        val colors = intArrayOf(0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFFBA68C8.toInt(), 0xFFFFB74D.toInt())
        for (row in 0 until rows) {
            var x = l + (row % 2) * 14f
            var i = row * 31
            while (x < r) {
                val bob = sin(time * 6 + i * 1.7).toFloat() * 3f
                val cy = top + rowH * (row + 0.65f) + bob
                val c = colors[(i * 7) % colors.size]
                g.fillRoundRect(x - 10f, cy - 4f, x + 10f, top + rowH * (row + 1f), 6f, Colors.darker(c, 0.75f))
                g.fillCircle(x, cy - 10f, 8f, 0xFFE0B48A.toInt())
                x += 26f; i++
            }
        }
    }

    /** Flat-shaded 3D box (axis-aligned, world units) through [cam], offset by ([ox], [oy]) on screen. */
    fun box(g: Gfx, cam: Cam3, ox: Float, oy: Float, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, top: Int, front: Int, side: Int) {
        val pts = FloatArray(8)
        fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, color: Int) {
            if (!cam.project(ax, ay, az)) return; pts[0] = cam.px + ox; pts[1] = cam.py + oy
            if (!cam.project(bx, by, bz)) return; pts[2] = cam.px + ox; pts[3] = cam.py + oy
            if (!cam.project(cx, cy, cz)) return; pts[4] = cam.px + ox; pts[5] = cam.py + oy
            if (!cam.project(dx, dy, dz)) return; pts[6] = cam.px + ox; pts[7] = cam.py + oy
            g.fillPolygon(pts, 4, color)
        }
        // the side facing the camera
        if (cam.ex < x0) quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, side)
        else if (cam.ex > x1) quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, side)
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, front)
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, top)
    }

    /**
     * Menu background for sports mode: an evening stadium - deep blue sky, floodlight towers, stands and a lit
     * pitch. Darker than a daytime scene so white menu text stays easy to read.
     */
    fun backdrop(g: Gfx, time: Double, effects: Boolean = true) {
        val w = g.width; val h = g.height
        g.gradientRect(0f, 0f, w, h * 0.62f, 0xFF0B2350.toInt(), 0xFF2B5C9E.toInt())
        // stands
        val standTop = h * 0.5f
        g.gradientRect(0f, standTop, w, h * 0.74f, 0xFF1C2638.toInt(), 0xFF101722.toInt())
        if (effects) {
            var x = 10f; var i = 0
            while (x < w) {
                val row = i % 4
                val y = standTop + 22f + row * 34f
                val on = sin(time * 1.7 + i * 1.3).toFloat() > 0.6f
                g.fillCircle(x, y, 3.5f, if (on) 0xCCFFF59D.toInt() else 0x33FFFFFF)
                x += 17f + (i % 3) * 5f; i++
            }
        }
        // floodlight towers with beams
        for (fx in floatArrayOf(w * 0.08f, w * 0.92f)) {
            g.line(fx, h * 0.18f, fx, h * 0.74f, 10f, 0xFF263238.toInt())
            g.fillRoundRect(fx - 60f, h * 0.12f, fx + 60f, h * 0.2f, 8f, 0xFF37474F.toInt())
            for (j in 0 until 4) g.fillCircle(fx - 42f + j * 28f, h * 0.16f, 11f, 0xFFFFFDE7.toInt())
            g.glow(fx, h * 0.16f, 240f, 0x55FFF9C4)
        }
        // pitch with mown stripes and lines
        g.gradientRect(0f, h * 0.74f, w, h, 0xFF2E7D32.toInt(), 0xFF1B5E20.toInt())
        for (i in 0 until 12) if (i % 2 == 0) g.fillRect(i * w / 12f, h * 0.74f, (i + 1) * w / 12f, h, 0x10FFFFFF)
        g.line(0f, h * 0.78f, w, h * 0.78f, 4f, 0x66FFFFFF)
        g.strokeCircle(w / 2f, h * 0.9f, 90f, 4f, 0x55FFFFFF)
        Draw.vignette(g, 0.5f)
    }

    /** Point on a circle helper. */
    fun cx(a: Float, r: Float) = cos(a) * r
    fun cy(a: Float, r: Float) = sin(a) * r
}
