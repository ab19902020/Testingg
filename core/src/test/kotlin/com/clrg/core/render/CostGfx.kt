package com.clrg.core.render

/**
 * Counts drawing work instead of drawing: one per shape, text or image, plus the points of polygons and lines (a
 * stand-in for how much the phone's renderer has to do each frame). Cached layers count as one image once made; the
 * work of making them is counted separately in [layerBuildOps].
 */
class CostGfx(override val width: Float = 1920f, override val height: Float = 1080f) : Gfx {
    var ops = 0
        private set
    var points = 0
        private set
    /** Large fills (big gradients and glows, radial fills, images and layers): the costlier kind on a phone GPU. */
    var heavy = 0
        private set
    /** Draws with a light blend mode (add, screen, multiply). */
    var blended = 0
        private set
    var layerBuildOps = 0
        private set
    /** Cached pictures made (cache misses) so far. */
    var layerBuilds = 0
        private set
    private val made = HashMap<String, Int>()
    private var alpha = 1f
    private var blend = Blend.NORMAL
    private val stack = ArrayList<Pair<Float, Blend>>()

    fun resetFrame() { ops = 0; points = 0; heavy = 0; blended = 0 }

    private fun op(n: Int = 1, isHeavy: Boolean = false) {
        ops += n
        if (isHeavy) heavy++
        if (blend != Blend.NORMAL) blended++
    }

    override fun save() { stack.add(alpha to blend) }
    override fun restore() { val (a, b) = stack.removeAt(stack.size - 1); alpha = a; blend = b }
    override fun translate(dx: Float, dy: Float) = Unit
    override fun scale(sx: Float, sy: Float) = Unit
    override fun rotate(radians: Float) = Unit
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) = Unit
    override fun setAlpha(a: Float) { alpha = a }
    override fun getAlpha() = alpha
    override fun setBlend(mode: Blend) { blend = mode }
    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = op()
    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = op()
    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, color: Int) = op()
    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) = op()
    override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) = op()
    override fun fillOval(l: Float, t: Float, r: Float, b: Float, color: Int) = op()
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) = op()
    override fun fillPolygon(pts: FloatArray, n: Int, color: Int) { op(); points += n }
    override fun polyline(pts: FloatArray, n: Int, width: Float, color: Int, closed: Boolean) { op(); points += n }
    override fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, width: Float, color: Int) = op()
    override fun fillPie(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int) = op()
    override fun gradientRect(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) = op(isHeavy = (r - l) * (b - t) > 200_000f)
    override fun glow(cx: Float, cy: Float, r: Float, color: Int) = op(isHeavy = r > 250f)
    override fun fillCircleRadial(cx: Float, cy: Float, r: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) = op()
    override fun fillRectRadial(l: Float, t: Float, r: Float, b: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) = op(isHeavy = true)
    override fun fillRoundRectGradient(l: Float, t: Float, r: Float, b: Float, radius: Float, top: Int, bottom: Int) = op()
    override fun fillPolygonGradient(pts: FloatArray, n: Int, y0: Float, y1: Float, top: Int, bottom: Int) { op(); points += n }
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align, bold: Boolean, outline: Int, outlineWidth: Float) =
        op(if (outlineWidth > 0f && outline != 0) 2 else 1)
    override fun measureText(s: String, size: Float, bold: Boolean): Float = s.length * size * 0.55f
    override fun image(img: ImageRef, l: Float, t: Float, r: Float, b: Float, mirror: Boolean) = op(isHeavy = true)

    override fun layer(key: String, l: Float, t: Float, r: Float, b: Float, version: Int, res: Float, blur: Float, grain: Float, draw: (Gfx) -> Unit) {
        if (made[key] != version) {
            val sub = CostGfx(width, height)
            draw(sub)
            layerBuildOps += sub.ops
            layerBuilds++
            made[key] = version
        }
        // a big picture fills a lot of screen; a small one (a tree, a cloud) is a cheap textured quad
        op(isHeavy = (r - l) * (b - t) > 200_000f)
    }
}
