package com.clrg.core.render

import kotlin.math.cos
import kotlin.math.sin

/** Drawing for the sports (non-Christmas) games: daytime skies, fields, crowds and hand icons. */
object SportsDraw {
    const val SKY_TOP = 0xFF2F7FD0.toInt()
    const val SKY_HORIZON = 0xFFBFE3FF.toInt()
    const val GRASS_FAR = 0xFF6FAE4A.toInt()
    const val GRASS_NEAR = 0xFF3E7F2C.toInt()

    /** Blue sky down to [horizon] with a warm haze, a haloed sun and slowly drifting, softly shaded clouds. */
    fun daySky(g: Gfx, l: Float, t: Float, r: Float, horizon: Float, time: Double, sunX: Float = l + (r - l) * 0.78f) {
        val mid = t + (horizon - t) * 0.62f
        g.gradientRect(l, t, r, mid, 0xFF2A74C9.toInt(), 0xFF8EC6F5.toInt())
        g.gradientRect(l, mid - 1f, r, horizon, 0xFF8EC6F5.toInt(), 0xFFE8F1F6.toInt())
        val sunY = t + (horizon - t) * 0.22f
        g.glow(sunX, sunY, 420f, 0x33FFF3C4)
        g.glow(sunX, sunY, 200f, 0x66FFF3C4)
        g.fillCircle(sunX, sunY, 46f, 0xFFFFF8E1.toInt())
        val w = r - l
        for (i in 0 until 7) {
            val speed = 6f + i * 2.5f
            val cx = l + ((i * 0.41f * w + time.toFloat() * speed) % (w + 600f)) - 300f
            val cy = t + (horizon - t) * (0.1f + 0.11f * (i % 3))
            val size = 110f + 45f * (i % 3) - (if (i > 4) 40f else 0f)
            cloud(g, cx, cy, size, 0xF2FFFFFF.toInt())
        }
    }

    /** A puffy cloud: flat shaded base, lit tops. */
    fun cloud(g: Gfx, cx: Float, cy: Float, size: Float, color: Int) {
        val shade = Colors.lerp(color, 0xFF9FB6CC.toInt(), 0.35f)
        g.fillOval(cx - size, cy - size * 0.2f, cx + size, cy + size * 0.34f, Colors.withAlpha(shade, 0.85f))
        g.fillCircle(cx - size * 0.38f, cy - size * 0.12f, size * 0.4f, color)
        g.fillCircle(cx + size * 0.12f, cy - size * 0.28f, size * 0.5f, color)
        g.fillCircle(cx + size * 0.58f, cy - size * 0.04f, size * 0.32f, color)
        g.fillOval(cx - size * 0.8f, cy + size * 0.02f, cx + size * 0.85f, cy + size * 0.3f, Colors.withAlpha(shade, 0.7f))
    }

    /** Distant blue mountains, rolling hills and a varied tree line along the horizon. */
    fun hills(g: Gfx, l: Float, r: Float, horizon: Float, seed: Int = 0) {
        val w = r - l
        // far mountains with snowy tops, hazy
        val mn = 14
        val mp = FloatArray((mn + 3) * 2)
        for (i in 0..mn) {
            val x = l + w * i / mn
            val h = 120f + 70f * sin(i * 1.7f + seed * 0.7f) + 40f * sin(i * 3.1f + seed)
            mp[i * 2] = x; mp[i * 2 + 1] = horizon - h
        }
        mp[(mn + 1) * 2] = r; mp[(mn + 1) * 2 + 1] = horizon + 2f; mp[(mn + 2) * 2] = l; mp[(mn + 2) * 2 + 1] = horizon + 2f
        g.fillPolygonGradient(mp, mn + 3, horizon - 230f, horizon, 0xFF9FB9D4.toInt(), 0xFFC9DBE6.toInt())
        for (i in 0..mn) {
            val px = mp[i * 2]; val py = mp[i * 2 + 1]
            if (horizon - py > 150f) Draw.tri(g, px - 34f, py + 26f, px + 34f, py + 26f, px, py, 0xCCF4F8FF.toInt())
        }
        // nearer hills
        val n = 24
        val pts = FloatArray((n + 3) * 2)
        for (i in 0..n) {
            val x = l + w * i / n
            val h = 40f + 30f * sin(i * 0.9f + seed) + 18f * sin(i * 2.3f + seed * 2)
            pts[i * 2] = x; pts[i * 2 + 1] = horizon - h
        }
        pts[(n + 1) * 2] = r; pts[(n + 1) * 2 + 1] = horizon + 2f
        pts[(n + 2) * 2] = l; pts[(n + 2) * 2 + 1] = horizon + 2f
        g.fillPolygonGradient(pts, n + 3, horizon - 80f, horizon, 0xFF86B192.toInt(), 0xFF5F8F6C.toInt())
        // tree line: a mix of round trees and pines, lit from the sun's side
        var x = l
        var i = 0
        while (x < r) {
            val k = ((i * 7919 + seed * 31) % 7)
            val h = 18f + 12f * k / 6f
            if (k % 3 == 0) {
                Draw.tri(g, x - 10f, horizon + 2f, x + 10f, horizon + 2f, x, horizon - h - 14f, 0xFF2F5A3A.toInt())
            } else {
                g.fillOval(x - 15f, horizon - h - 12f, x + 15f, horizon + 2f, 0xFF3C6E45.toInt())
                g.fillOval(x - 9f, horizon - h - 10f, x + 8f, horizon - h + 2f, 0xFF5A8F5C.toInt())
            }
            x += 17f + (i % 3) * 6f; i++
        }
    }

    /** A little hut through [cam]: walls (a box) and a pitched roof running left to right. */
    fun hut(g: Gfx, cam: Cam3, ox: Float, oy: Float, x0: Float, y0: Float, x1: Float, y1: Float, h: Float, roofH: Float, wall: Int, roof: Int, z0: Float = 0f) {
        box(g, cam, ox, oy, x0, y0, z0, x1, y1, h, Colors.darker(wall, 0.9f), wall, Colors.darker(wall, 0.75f))
        val ym = (y0 + y1) / 2f
        val pts = FloatArray(8)
        fun poly(vararg v: Float, color: Int) {
            val n = v.size / 3
            for (i in 0 until n) { if (!cam.project(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])) return; pts[i * 2] = cam.px + ox; pts[i * 2 + 1] = cam.py + oy }
            g.fillPolygon(pts, n, color)
        }
        val o = 0.15f
        // front roof slope, then the gable end facing the camera
        poly(x0 - o, y0 - o, h, x1 + o, y0 - o, h, x1 + o, ym, h + roofH, x0 - o, ym, h + roofH, color = roof)
        if (cam.ex < x0) poly(x0, y0, h, x0, y1, h, x0, ym, h + roofH, color = Colors.darker(wall, 0.85f))
        else if (cam.ex > x1) poly(x1, y0, h, x1, y1, h, x1, ym, h + roofH, color = Colors.darker(wall, 0.85f))
        // the opening the clays fly out of
        val hx = (x0 + x1) / 2f
        val wh = h - z0
        poly(hx - (x1 - x0) * 0.3f, y0 - 0.01f, z0 + wh * 0.35f, hx + (x1 - x0) * 0.3f, y0 - 0.01f, z0 + wh * 0.35f, hx + (x1 - x0) * 0.3f, y0 - 0.01f, z0 + wh * 0.75f, hx - (x1 - x0) * 0.3f, y0 - 0.01f, z0 + wh * 0.75f, color = 0xFF2B1B12.toInt())
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
