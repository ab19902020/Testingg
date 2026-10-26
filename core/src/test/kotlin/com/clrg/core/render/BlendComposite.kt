package com.clrg.core.render

import java.awt.Composite
import java.awt.CompositeContext
import java.awt.RenderingHints
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster

/** Java2D version of the [Blend] modes the phone gets from android.graphics.BlendMode (tests and screenshots only). */
class BlendComposite(private val mode: Blend) : Composite {
    override fun createContext(src: ColorModel, dst: ColorModel, hints: RenderingHints?): CompositeContext = object : CompositeContext {
        override fun dispose() = Unit
        override fun compose(s: Raster, dIn: Raster, dOut: WritableRaster) {
            val w = minOf(s.width, dIn.width); val h = minOf(s.height, dIn.height)
            val sp = IntArray(4); val dp = IntArray(4)
            for (y in 0 until h) for (x in 0 until w) {
                src.getComponents(s.getDataElements(s.minX + x, s.minY + y, null), sp, 0)
                dst.getComponents(dIn.getDataElements(dIn.minX + x, dIn.minY + y, null), dp, 0)
                val sa = sp[3] / 255f
                for (c in 0..2) {
                    val sc = sp[c] / 255f; val dc = dp[c] / 255f
                    val mixed = when (mode) {
                        Blend.ADD -> dc + sc * sa
                        Blend.SCREEN -> dc + sc * sa * (1f - dc)
                        Blend.MULTIPLY -> dc * (1f - sa + sc * sa)
                        Blend.NORMAL -> dc * (1f - sa) + sc * sa
                    }
                    dp[c] = (mixed.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                }
                if (mode == Blend.NORMAL) dp[3] = maxOf(dp[3], sp[3])
                dOut.setDataElements(dOut.minX + x, dOut.minY + y, dst.getDataElements(dp, 0, null))
            }
        }
    }
}
