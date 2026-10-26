package com.clrg.core.render

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Java2D implementation of [Gfx] used by tests to render screenshots of screens and games on the build machine.
 * (On the device the same drawing code runs on android.graphics.Canvas.) It also fails fast on NaN coordinates.
 */
class AwtGfx private constructor(
    val pixelWidth: Int, val pixelHeight: Int, private val pxScale: Float, override val width: Float, private val inLayer: Boolean,
) : Gfx {
    constructor(pixelWidth: Int, pixelHeight: Int) : this(pixelWidth, pixelHeight, pixelHeight / 1080f, pixelWidth / (pixelHeight / 1080f), false)

    val image = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB)
    private val g2: Graphics2D = image.createGraphics()
    override val height: Float = 1080f
    private var alpha = 1f
    private var blend = Blend.NORMAL
    private val stack = ArrayList<Triple<AffineTransform, Shape?, Pair<Float, Blend>>>()
    /** Layers made (cache misses) and drawn from the cache, for tests. */
    var layersMade = 0
        private set
    var layersDrawn = 0
        private set
    private class Layer(val img: BufferedImage, val version: Int)
    private val layers = HashMap<String, Layer>()
    var calls = 0
        private set

    init {
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.scale(pxScale.toDouble(), pxScale.toDouble())
    }

    private fun chk(vararg v: Float) {
        calls++
        for (x in v) require(!x.isNaN() && !x.isInfinite()) { "non-finite drawing coordinate" }
    }

    private fun col(c: Int): Color {
        val a = ((c ushr 24) and 0xFF) * alpha
        return Color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, a.toInt().coerceIn(0, 255))
    }

    override fun save() { stack.add(Triple(g2.transform, g2.clip, alpha to blend)) }
    override fun restore() { val (t, c, ab) = stack.removeAt(stack.size - 1); g2.transform = t; g2.clip = c; alpha = ab.first; setBlend(ab.second) }

    override fun setBlend(mode: Blend) {
        blend = mode
        g2.composite = if (mode == Blend.NORMAL) AlphaComposite.SrcOver else BlendComposite(mode)
    }

    override fun layer(key: String, l: Float, t: Float, r: Float, b: Float, version: Int, res: Float, blur: Float, grain: Float, draw: (Gfx) -> Unit) {
        chk(l, t, r, b)
        if (r <= l || b <= t) return
        if (inLayer) { super.layer(key, l, t, r, b, version, res, blur, grain, draw); return }
        val s = (minOf(pxScale, 1f) * res).coerceIn(0.1f, 1f)
        val pw = kotlin.math.ceil((r - l) * s).toInt().coerceAtLeast(1)
        val ph = kotlin.math.ceil((b - t) * s).toInt().coerceAtLeast(1)
        var lay = layers[key]
        if (lay == null || lay.version != version || lay.img.width != pw || lay.img.height != ph) {
            val sub = AwtGfx(pw, ph, s, width, true)
            sub.g2.translate(-l.toDouble(), -t.toDouble())
            draw(sub)
            if (blur > 0f || grain > 0f) {
                val px = sub.image.getRGB(0, 0, pw, ph, null, 0, pw)
                if (blur > 0f) ImageOps.blur(px, pw, ph, (blur * s).toInt().coerceAtLeast(1))
                if (grain > 0f) ImageOps.grain(px, pw, ph, grain)
                sub.image.setRGB(0, 0, pw, ph, px, 0, pw)
            }
            lay = Layer(sub.image, version)
            layers[key] = lay
            layersMade++
        }
        layersDrawn++
        val old = g2.composite
        if (blend == Blend.NORMAL && alpha < 0.999f) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.drawImage(lay.img, AffineTransform(((r - l) / pw).toDouble(), 0.0, 0.0, ((b - t) / ph).toDouble(), l.toDouble(), t.toDouble()), null)
        g2.composite = old
    }
    override fun translate(dx: Float, dy: Float) { chk(dx, dy); g2.translate(dx.toDouble(), dy.toDouble()) }
    override fun scale(sx: Float, sy: Float) { chk(sx, sy); g2.scale(sx.toDouble(), sy.toDouble()) }
    override fun rotate(radians: Float) { chk(radians); g2.rotate(radians.toDouble()) }
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) { chk(l, t, r, b); g2.clip(Rectangle2D.Float(l, t, r - l, b - t)) }
    override fun setAlpha(a: Float) { alpha = a.coerceIn(0f, 1f) }
    override fun getAlpha(): Float = alpha

    private fun fill(s: Shape, c: Int) { g2.color = col(c); g2.fill(s) }
    private fun stroke(s: Shape, w: Float, c: Int) { g2.color = col(c); g2.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g2.draw(s) }

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) { chk(l, t, r, b); fill(Rectangle2D.Float(l, t, r - l, b - t), color) }
    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) { chk(l, t, r, b, radius); fill(RoundRectangle2D.Float(l, t, r - l, b - t, radius * 2, radius * 2), color) }
    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, color: Int) { chk(l, t, r, b, radius, width); stroke(RoundRectangle2D.Float(l, t, r - l, b - t, radius * 2, radius * 2), width, color) }
    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) { chk(cx, cy, r); fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2), color) }
    override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) { chk(cx, cy, r, width); stroke(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2), width, color) }
    override fun fillOval(l: Float, t: Float, r: Float, b: Float, color: Int) { chk(l, t, r, b); fill(Ellipse2D.Float(l, t, r - l, b - t), color) }
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) { chk(x0, y0, x1, y1, width); stroke(Line2D.Float(x0, y0, x1, y1), width, color) }

    private fun path(pts: FloatArray, n: Int, close: Boolean): Path2D.Float {
        val p = Path2D.Float()
        for (i in 0 until n) { chk(pts[i * 2], pts[i * 2 + 1]); if (i == 0) p.moveTo(pts[0], pts[1]) else p.lineTo(pts[i * 2], pts[i * 2 + 1]) }
        if (close) p.closePath()
        return p
    }

    override fun fillPolygon(pts: FloatArray, n: Int, color: Int) = fill(path(pts, n, true), color)
    override fun polyline(pts: FloatArray, n: Int, width: Float, color: Int, closed: Boolean) = stroke(path(pts, n, closed), width, color)

    private fun deg(r: Float) = Math.toDegrees(r.toDouble())
    override fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, width: Float, color: Int) {
        chk(cx, cy, r, start, sweep, width)
        stroke(Arc2D.Float(cx - r, cy - r, r * 2, r * 2, (-deg(start)).toFloat(), (-deg(sweep)).toFloat(), Arc2D.OPEN), width, color)
    }
    override fun fillPie(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int) {
        chk(cx, cy, r, start, sweep)
        fill(Arc2D.Float(cx - r, cy - r, r * 2, r * 2, (-deg(start)).toFloat(), (-deg(sweep)).toFloat(), Arc2D.PIE), color)
    }
    override fun gradientRect(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        chk(l, t, r, b)
        g2.paint = GradientPaint(0f, t, col(top), 0f, b, col(bottom))
        g2.fill(Rectangle2D.Float(l, t, r - l, b - t))
    }
    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        chk(cx, cy, r)
        if (r <= 0.5f) return
        g2.paint = RadialGradientPaint(cx, cy, r, floatArrayOf(0f, 1f), arrayOf(col(color), col(color and 0xFFFFFF)))
        g2.fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
    }

    private fun radialPaint(gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) =
        RadialGradientPaint(gx, gy, gr.coerceAtLeast(0.01f), floatArrayOf(0f, 1f), arrayOf(col(inner), col(outer)))

    override fun fillCircleRadial(cx: Float, cy: Float, r: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) {
        chk(cx, cy, r, gx, gy, gr)
        if (r <= 0f) return
        g2.paint = radialPaint(gx, gy, gr, inner, outer)
        g2.fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
    }

    override fun fillRectRadial(l: Float, t: Float, r: Float, b: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) {
        chk(l, t, r, b, gx, gy, gr)
        if (r <= l || b <= t) return
        g2.paint = radialPaint(gx, gy, gr, inner, outer)
        g2.fill(Rectangle2D.Float(l, t, r - l, b - t))
    }

    override fun fillRoundRectGradient(l: Float, t: Float, r: Float, b: Float, radius: Float, top: Int, bottom: Int) {
        chk(l, t, r, b, radius)
        if (r <= l || b <= t) return
        g2.paint = GradientPaint(0f, t, col(top), 0f, b, col(bottom))
        g2.fill(RoundRectangle2D.Float(l, t, r - l, b - t, radius * 2, radius * 2))
    }

    override fun fillPolygonGradient(pts: FloatArray, n: Int, y0: Float, y1: Float, top: Int, bottom: Int) {
        chk(y0, y1)
        g2.paint = GradientPaint(0f, y0, col(top), 0f, if (y1 > y0) y1 else y0 + 0.01f, col(bottom))
        g2.fill(path(pts, n, true))
    }

    override fun fillPolygonLinear(pts: FloatArray, n: Int, x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int) {
        chk(x0, y0, x1, y1)
        val ex = if (kotlin.math.abs(x1 - x0) + kotlin.math.abs(y1 - y0) < 0.01f) x0 + 0.01f else x1
        g2.paint = GradientPaint(x0, y0, col(c0), ex, y1, col(c1))
        g2.fill(path(pts, n, true))
    }

    private fun font(size: Float, bold: Boolean) = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align, bold: Boolean, outline: Int, outlineWidth: Float) {
        chk(x, y, size)
        val f = font(size, bold)
        val fm = g2.getFontMetrics(f)
        val w = fm.stringWidth(s).toFloat()
        val bx = when (align) { Align.LEFT -> x; Align.CENTER -> x - w / 2; Align.RIGHT -> x - w }
        val by = y + (fm.ascent - fm.descent) / 2f
        if (outlineWidth > 0f && outline != 0) {
            val gv = f.createGlyphVector(g2.fontRenderContext, s)
            val shape = gv.getOutline(bx, by)
            stroke(shape, outlineWidth, outline)
        }
        g2.font = f
        g2.color = col(color)
        g2.drawString(s, bx, by)
    }

    override fun measureText(s: String, size: Float, bold: Boolean): Float = g2.getFontMetrics(font(size, bold)).stringWidth(s).toFloat()

    override fun image(img: ImageRef, l: Float, t: Float, r: Float, b: Float, mirror: Boolean) {
        chk(l, t, r, b)
        g2.paint = GradientPaint(l, t, Color(60, 70, 80), r, b, Color(30, 35, 45))
        g2.fill(Rectangle2D.Float(l, t, r - l, b - t))
        g2.color = Color(255, 255, 255, 60)
        g2.font = font(28f, false)
        g2.drawString("camera preview", l + 20, t + 40)
    }

    fun clear(c: Int = 0xFF000000.toInt()) {
        g2.composite = AlphaComposite.Src
        g2.color = Color(c, true)
        g2.fillRect(0, 0, 100000, 100000)
        g2.composite = AlphaComposite.SrcOver
    }

    fun writePng(file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
