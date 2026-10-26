package com.clrg.core.dev

import com.clrg.core.gesture.AvatarPose
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.PlayerColors
import com.clrg.core.render.Prop
import java.io.File
import kotlin.test.Test

/** Developer tool (-Pclrg.charshots=<dir>): the player characters drawn large in a few poses, for reviewing the artwork. */
class CharShots {
    @Test
    fun shots() {
        val arg = System.getProperty("clrg.charshots")
        org.junit.jupiter.api.Assumptions.assumeTrue(arg != null, "developer tool: run with -Pclrg.charshots=<dir>")
        val dir = File(arg!!).also { it.mkdirs() }
        for (sporty in listOf(true, false)) {
            val g = AwtGfx(1920, 1080)
            g.gradientRect(0f, 0f, g.width, g.height, 0xFF2B3A55.toInt(), 0xFF7A8BA8.toInt())
            g.fillRect(0f, 860f, g.width, g.height, 0xFF5D6B7D.toInt())
            val poses = listOf(
                AvatarPose(),
                AvatarPose().apply { upper[0] = -2.6f; fore[0] = -2.4f; upper[1] = 0.5f; fore[1] = 1.2f; tilt = 0.12f },
                AvatarPose().apply { upper[0] = 2.6f; fore[0] = 2.0f; upper[1] = 0.4f; fore[1] = 0.6f; head = -0.2f },
                AvatarPose().apply { upper[0] = -1.0f; fore[0] = 0.8f; upper[1] = -2.1f; fore[1] = 2.3f; rise = -0.4f },
            )
            val exprs = listOf(Expression.HAPPY, Expression.EXCITED, Expression.FOCUSED, Expression.OOPS)
            val props = listOf(Prop.NONE, Prop.RACKET, Prop.GLOVES, Prop.NONE)
            for (i in 0 until 4) {
                val x = 260f + i * 470f
                Draw.person(g, sporty, x, 900f, 700f, PlayerColors.get(i), poses[i], exprs[i], props[i], t = 1.0 + i, look = i * 3)
            }
            g.writePng(File(dir, if (sporty) "athletes.png" else "buddies.png"))
        }
    }
}
