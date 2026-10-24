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
class AwtGfx(val pixelWidth: Int, val pixelHeight: Int) : Gfx {
    val image = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB)
    private val g2: Graphics2D = image.createGraphics()
    private val pxScale = pixelHeight / 1080f
    override val width: Float = pixelWidth / pxScale
    override val height: Float = 1080f
    private var alpha = 1f
    private val stack = ArrayList<Triple<AffineTransform, Shape?, Float>>()
    var calls = 0
        private set

    init {
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

    override fun save() { stack.add(Triple(g2.transform, g2.clip, alpha)) }
    override fun restore() { val (t, c, a) = stack.removeAt(stack.size - 1); g2.transform = t; g2.clip = c; alpha = a }
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
