package com.clrg.core.render

/** Draws nothing but validates every coordinate (fast frames in long-running tests). */
class NullGfx(override val width: Float = 1920f, override val height: Float = 1080f) : Gfx {
    private var alpha = 1f
    private val stack = ArrayList<Float>()
    var calls = 0
        private set

    private fun chk(vararg v: Float) {
        calls++
        for (x in v) require(!x.isNaN() && !x.isInfinite()) { "non-finite drawing coordinate" }
    }

    override fun save() { stack.add(alpha) }
    override fun restore() { alpha = stack.removeAt(stack.size - 1) }
    override fun translate(dx: Float, dy: Float) = chk(dx, dy)
    override fun scale(sx: Float, sy: Float) = chk(sx, sy)
    override fun rotate(radians: Float) = chk(radians)
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) = chk(l, t, r, b)
    override fun setAlpha(a: Float) { alpha = a }
    override fun getAlpha() = alpha
    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = chk(l, t, r, b)
    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = chk(l, t, r, b, radius)
    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, color: Int) = chk(l, t, r, b, radius, width)
    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) = chk(cx, cy, r)
    override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) = chk(cx, cy, r, width)
    override fun fillOval(l: Float, t: Float, r: Float, b: Float, color: Int) = chk(l, t, r, b)
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) = chk(x0, y0, x1, y1, width)
    override fun fillPolygon(pts: FloatArray, n: Int, color: Int) { for (i in 0 until n * 2) chk(pts[i]) }
    override fun polyline(pts: FloatArray, n: Int, width: Float, color: Int, closed: Boolean) { for (i in 0 until n * 2) chk(pts[i]) }
    override fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, width: Float, color: Int) = chk(cx, cy, r, start, sweep, width)
    override fun fillPie(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int) = chk(cx, cy, r, start, sweep)
    override fun gradientRect(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) = chk(l, t, r, b)
    override fun glow(cx: Float, cy: Float, r: Float, color: Int) = chk(cx, cy, r)
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align, bold: Boolean, outline: Int, outlineWidth: Float) = chk(x, y, size)
    override fun measureText(s: String, size: Float, bold: Boolean): Float = s.length * size * 0.55f
    override fun image(img: ImageRef, l: Float, t: Float, r: Float, b: Float, mirror: Boolean) = chk(l, t, r, b)
}
