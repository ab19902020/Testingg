package com.clrg.core.ui.screens

import com.clrg.core.golf.Blob
import com.clrg.core.golf.GolfPhysics
import com.clrg.core.golf.Hole
import com.clrg.core.golf.Pt
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Top-down drawing of a golf hole, seen from above with the green up the screen. Distances are metres. */
class GolfView {
    /** Camera: world point at the centre of the view and pixels per metre. */
    var cx = 0f
    var cy = 0f
    var scale = 1f
    private var ready = false
    private var r = Rect(0f, 0f, 1f, 1f)

    fun sx(x: Float) = r.cx + (x - cx) * scale
    fun sy(y: Float) = r.cy - (y - cy) * scale

    /** Smoothly frames the box [x0..x1] x [y0..y1] (metres) in [rect], at least [minSpan] metres across. */
    fun frame(rect: Rect, x0: Float, y0: Float, x1: Float, y1: Float, minSpan: Float, dt: Float, snap: Boolean = false) {
        r = rect
        val w = max(x1 - x0, minSpan)
        val h = max(y1 - y0, minSpan)
        val s = min(rect.width / w, rect.height / h).coerceIn(0.3f, 70f)
        val tx = (x0 + x1) / 2f
        val ty = (y0 + y1) / 2f
        if (!ready || snap) { cx = tx; cy = ty; scale = s; ready = true; return }
        val k = min(1f, dt * 4.5f)
        cx += (tx - cx) * k
        cy += (ty - cy) * k
        scale = exp(ln(scale) + (ln(s) - ln(scale)) * k)
    }

    /** Frames a set of points with [pad] metres around them. */
    fun frame(rect: Rect, pts: List<Pt>, pad: Float, minSpan: Float, dt: Float, snap: Boolean = false) {
        frame(rect, pts.minOf { it.x } - pad, pts.minOf { it.y } - pad, pts.maxOf { it.x } + pad, pts.maxOf { it.y } + pad, minSpan, dt, snap)
    }

    fun frameHole(rect: Rect, h: Hole, dt: Float, snap: Boolean = false) = frame(rect, h.xMin, h.yMin, h.xMax, h.yMax, 60f, dt, snap)

    private val fwPts = FloatArray(4)

    /** The whole hole: snow beyond the boundary, rough, fairway, hazards, trees, green and flag. */
    fun drawHole(g: Gfx, h: Hole, time: Double, putting: Boolean) {
        g.save()
        g.clipRect(r.left, r.top, r.right, r.bottom)
        // snow beyond the boundary
        g.gradientRect(r.left, r.top, r.right, r.bottom, 0xFFE8F1FB.toInt(), 0xFFCBDCEF.toInt())
        // rough inside the boundary, with out-of-bounds stakes
        val bl = sx(h.xMin); val br = sx(h.xMax); val bt = sy(h.yMax); val bb = sy(h.yMin)
        g.fillRect(bl, bt, br, bb, 0xFF6F9E6A.toInt())
        rng.reset()
        val speck = (scale * 1.2f).coerceIn(1f, 3f)
        if (scale > 1.2f) for (i in 0 until 160) {
            val x = h.xMin + rng.nextFloat() * (h.xMax - h.xMin); val y = h.yMin + rng.nextFloat() * (h.yMax - h.yMin)
            g.fillCircle(sx(x), sy(y), speck, 0x55FFFFFF)
        }
        var y = h.yMin
        while (y <= h.yMax) {
            for (x in floatArrayOf(h.xMin, h.xMax)) g.fillRect(sx(x) - 2f, sy(y) - 6f, sx(x) + 2f, sy(y) + 6f, Colors.WHITE)
            y += 20f
        }
        // fairway: a darker edge, the mown strip with rounded joints, and light/dark mowing bands across it
        val fw = h.fairwayWidth * scale
        for (pass in 0..1) {
            val c = if (pass == 0) 0xFF3F9A48.toInt() else 0xFF56B85E.toInt()
            val wdt = if (pass == 0) fw + 2.4f * scale else fw
            for (i in 0 until h.fairway.size - 1) {
                val a = h.fairway[i]; val b = h.fairway[i + 1]
                g.line(sx(a.x), sy(a.y), sx(b.x), sy(b.y), wdt, c)
            }
            for (p in h.fairway) g.fillCircle(sx(p.x), sy(p.y), wdt / 2f, c)
        }
        for (i in 0 until h.fairway.size - 1) {
            val a = h.fairway[i]; val b = h.fairway[i + 1]
            val len = a.dist(b)
            var d = 0f
            var k = 0
            while (d < len) {
                val e = minOf(len, d + 9f)
                if (k % 2 == 0) {
                    val p0 = a + (b - a) * (d / len); val p1 = a + (b - a) * (e / len)
                    g.line(sx(p0.x), sy(p0.y), sx(p1.x), sy(p1.y), fw * 0.98f, 0x1AFFFFFF)
                }
                d = e; k++
            }
        }
        // tee box with markers
        g.fillRoundRect(sx(-4f), sy(4f), sx(4f), sy(-3f), 4f, 0xFF7ACF7F.toInt())
        g.fillCircle(sx(-3f), sy(0f), max(3f, 0.6f * scale), Colors.CANDY)
        g.fillCircle(sx(3f), sy(0f), max(3f, 0.6f * scale), Colors.CANDY)
        // water: icy rim, water, shine
        for (b in h.water) {
            oval(g, b, 1.6f, 0xFFF4FAFF.toInt())
            oval(g, b, 0f, 0xFF2F7FCB.toInt())
            g.fillOval(sx(b.x - b.rx * 0.6f), sy(b.y + b.ry * 0.55f), sx(b.x + b.rx * 0.1f), sy(b.y + b.ry * 0.1f), 0x334FC3F7)
            val t = time.toFloat()
            g.line(sx(b.x - b.rx * 0.3f), sy(b.y - b.ry * 0.2f) + sin(t) * 2f, sx(b.x + b.rx * 0.2f), sy(b.y - b.ry * 0.2f) + sin(t) * 2f, 2f, 0x55FFFFFF)
        }
        // bunkers
        for (b in h.bunkers) {
            oval(g, b, 0.8f, 0xFFC9A96A.toInt())
            oval(g, b, 0f, 0xFFF2DFA6.toInt())
        }
        // green: fringe, putting surface, slope arrows when putting
        val gcx = sx(h.pin.x); val gcy = sy(h.pin.y)
        g.fillCircle(gcx, gcy, (h.greenR + 2.5f) * scale, 0xFF54B95A.toInt())
        g.fillCircleRadial(gcx, gcy, h.greenR * scale, gcx - h.greenR * scale * 0.3f, gcy - h.greenR * scale * 0.3f, h.greenR * scale * 1.4f, 0xFF9BE89B.toInt(), 0xFF6CCB6C.toInt())
        if (putting) slopeArrows(g, h, time)
        // trees
        for (b in h.trees) trees(g, b)
        g.restore()
    }

    private fun oval(g: Gfx, b: Blob, grow: Float, color: Int) =
        g.fillOval(sx(b.x - b.rx - grow), sy(b.y + b.ry + grow), sx(b.x + b.rx + grow), sy(b.y - b.ry - grow), color)

    private val rng = ResettableRng(11)

    private fun trees(g: Gfx, b: Blob) {
        oval(g, b, 0f, 0x552E5E33)
        val trng = Rng((b.x * 31 + b.y * 17).toLong())
        val n = ((b.rx * b.ry) / 45f).toInt().coerceIn(3, 40)
        val tr = 4.2f * scale
        repeat(n) {
            val a = trng.nextFloat() * 6.283f; val d = kotlin.math.sqrt(trng.nextFloat()) * 0.85f
            val x = sx(b.x + cos(a) * b.rx * d); val yy = sy(b.y + sin(a) * b.ry * d)
            g.fillCircle(x + tr * 0.25f, yy + tr * 0.3f, tr, 0x44000000)
            g.fillCircleRadial(x, yy, tr, x - tr * 0.3f, yy - tr * 0.3f, tr * 1.3f, 0xFF3E8E4A.toInt(), 0xFF173F22.toInt())
            g.fillCircle(x - tr * 0.25f, yy - tr * 0.3f, tr * 0.32f, 0xCCFFFFFF.toInt())
        }
    }

    private fun slopeArrows(g: Gfx, h: Hole, time: Double) {
        val s = h.slope
        val mag = s.len()
        if (mag < 0.01f) return
        val ux = s.x / mag; val uy = s.y / mag
        val step = 3f
        val len = (0.6f + mag * 6f) * scale
        val phase = ((time * 0.6) % 1.0).toFloat()
        var gx = -h.greenR
        while (gx <= h.greenR) {
            var gy = -h.greenR
            while (gy <= h.greenR) {
                if (hypot(gx, gy) < h.greenR - 1.5f) {
                    val x = sx(h.pin.x + gx + ux * step * phase); val y = sy(h.pin.y + gy + uy * step * phase)
                    val ex = x + ux * len; val ey = y - uy * len
                    g.line(x, y, ex, ey, 2f, 0x66FFFFFF)
                    val a = atan2(-uy, ux)
                    g.line(ex, ey, ex - cos(a - 0.6f) * len * 0.35f, ey - sin(a - 0.6f) * len * 0.35f, 2f, 0x66FFFFFF)
                    g.line(ex, ey, ex - cos(a + 0.6f) * len * 0.35f, ey - sin(a + 0.6f) * len * 0.35f, 2f, 0x66FFFFFF)
                }
                gy += step
            }
            gx += step
        }
    }

    /** The hole and the candy-striped flag, which flies with the wind. */
    fun drawFlag(g: Gfx, h: Hole, time: Double) {
        val x = sx(h.pin.x); val y = sy(h.pin.y)
        g.fillCircle(x, y, max(4f, GolfPhysics.CAPTURE_R * 1.6f * scale), 0xFF1B1B1B.toInt())
        val pole = 54f
        g.line(x, y, x, y - pole, 3f, 0xFFECEFF1.toInt())
        val dir = if (h.wind.x >= 0f) 1f else -1f
        val wave = sin(time.toFloat() * 6f) * 3f
        pts[0] = x; pts[1] = y - pole; pts[2] = x + dir * 30f; pts[3] = y - pole + 8f + wave; pts[4] = x; pts[5] = y - pole + 18f
        g.fillPolygon(pts, 3, Colors.CANDY)
        g.line(x + dir * 8f, y - pole + 2f, x + dir * 12f, y - pole + 15f, 3f, Colors.WHITE)
    }

    private val pts = FloatArray(8)

    /** The ball (with its shadow when in the air). */
    fun drawBall(g: Gfx, p: Pt, height: Float, color: Int) {
        val x = sx(p.x); val y = sy(p.y)
        val lift = height * min(scale, 3f) * 0.9f
        g.fillOval(x - 8f, y - 3f, x + 8f, y + 4f, 0x55000000)
        val r = 8f + height * 0.18f
        g.glow(x, y - lift, r * 2.4f, Colors.withAlpha(color, 0.35f))
        g.fillCircleRadial(x, y - lift, r, x - r * 0.35f, y - lift - r * 0.35f, r * 1.4f, Colors.WHITE, 0xFFC7D3E0.toInt())
        g.strokeCircle(x, y - lift, r, 2f, Colors.withAlpha(color, 0.9f))
    }

    /** Aim guide: a dashed line from the ball to where the shot is heading, and a landing ring. */
    fun drawAim(g: Gfx, from: Pt, aim: Float, dist: Float, ringR: Float, color: Int, strong: Boolean) {
        val d = GolfPhysics.dir(aim)
        val to = from + d * dist
        val x0 = sx(from.x); val y0 = sy(from.y); val x1 = sx(to.x); val y1 = sy(to.y)
        val l = hypot(x1 - x0, y1 - y0)
        val n = (l / 18f).toInt()
        for (i in 0 until n step 2) {
            val a = i / n.toFloat(); val b = (i + 1) / n.toFloat()
            g.line(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a, x0 + (x1 - x0) * b, y0 + (y1 - y0) * b, if (strong) 5f else 3f, Colors.withAlpha(Colors.WHITE, if (strong) 0.95f else 0.7f))
        }
        g.strokeCircle(x1, y1, max(10f, ringR * scale), 4f, color)
        g.fillCircle(x1, y1, 5f, color)
    }

    /** Wind arrow and speed in the corner of the map. */
    fun drawWind(g: Gfx, h: Hole) {
        val x = r.left + 80f; val y = r.top + 70f
        g.fillRoundRect(x - 64f, y - 52f, x + 64f, y + 60f, 16f, 0x88000000.toInt())
        val m = h.wind.len()
        val ang = atan2(-h.wind.y, h.wind.x)
        val l = 34f
        val ex = x + cos(ang) * l; val ey = y - 8f + sin(ang) * l
        val bx = x - cos(ang) * l; val by = y - 8f - sin(ang) * l
        g.line(bx, by, ex, ey, 5f, Colors.ICE)
        g.line(ex, ey, ex - cos(ang - 0.5f) * 14f, ey - sin(ang - 0.5f) * 14f, 5f, Colors.ICE)
        g.line(ex, ey, ex - cos(ang + 0.5f) * 14f, ey - sin(ang + 0.5f) * 14f, 5f, Colors.ICE)
        g.text("Wind %.1f m/s".format(m), x, y + 40f, 22f, Colors.WHITE, Align.CENTER, bold = false)
    }

    /** Thin border and a frosty vignette around the map. */
    fun frameBorder(g: Gfx) {
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, 12f, 4f, 0xFF5D8BC9.toInt())
    }

    /** A small Rng that can be rewound so decorations stay put from frame to frame. */
    private class ResettableRng(private val seed: Long) {
        private var r = Rng(seed)
        fun reset() { r = Rng(seed) }
        fun nextFloat() = r.nextFloat()
    }
}
