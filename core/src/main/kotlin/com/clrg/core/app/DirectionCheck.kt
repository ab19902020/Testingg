package com.clrg.core.app

import com.clrg.core.math.Rect
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.tracking.BodyMetrics
import kotlin.math.abs
import kotlin.math.sign

/**
 * Makes sure movements go the right way on screen.
 *
 * Phones differ in whether a camera delivers its picture mirrored, and a picture the wrong way round makes every
 * movement go backwards (move your hand right, the cursor goes left). So the first time someone raises a hand in
 * front of a camera, the game asks them to slide the hand towards an arrow on the right, then towards one on the
 * left, and watches which way the hand really moved in the picture. If both went the wrong way, the picture is
 * flipped for that camera and remembered.
 *
 * It also notices a picture that arrives upside down (everyone's shoulders below their hips) and turns it the right
 * way up for that camera.
 */
class DirectionCheck(private val app: GameApp) {
    enum class Stage { OFF, RIGHT, LEFT }

    var stage = Stage.OFF
        private set
    val active: Boolean get() = stage != Stage.OFF
    /** 0..1 how far the hand has moved in the current step. */
    var progress = 0f
        private set
    private var startX = Float.NaN
    private var stageTime = 0f
    private val results = ArrayList<Int>()
    private var attempts = 0
    private var forced = false
    private var upsideSec = 0.0
    private var lastFrameNs = Long.MIN_VALUE

    private fun cameraId(): String? = app.currentCamera()?.id

    fun needed(): Boolean {
        if (app.settings.simulatedInput) return false
        val id = cameraId() ?: return false
        return forced || id !in app.settings.directionChecked
    }

    /** Check again the next time someone raises a hand (e.g. from Settings). */
    fun request() { forced = true }

    /** The raised hand of a player being followed: (slot, hand), or null. */
    private fun raisedHand(): Pair<Int, Int>? {
        for (k in 0..1) {
            val s = app.tracker.slots[k]; val c = app.controls[k]
            if (!s.enabled || !s.isPlaying || !c.hasPose) continue
            val h = if (!c.handVisible[0]) 1 else if (!c.handVisible[1]) 0 else if (c.handY[0] > c.handY[1]) 0 else 1
            if (c.handVisible[h] && c.handY[h] > -0.3f) return k to h
        }
        return null
    }

    fun update(dt: Float, screenAllows: Boolean) {
        if (stage == Stage.OFF) {
            if (screenAllows && needed() && raisedHand() != null) begin(Stage.RIGHT)
            return
        }
        stageTime += dt
        val rh = raisedHand()
        if (rh == null) {
            startX = Float.NaN; progress = 0f
            if (stageTime > 30f) stage = Stage.OFF // nobody is doing it: ask again another time
            return
        }
        val x = app.controls[rh.first].handX[rh.second]
        if (startX.isNaN()) startX = x
        val dx = x - startX
        progress = (abs(dx) / MOVE).coerceAtMost(1f)
        if (abs(dx) >= MOVE) {
            val want = if (stage == Stage.RIGHT) 1f else -1f
            results.add(if (sign(dx) == want) 1 else -1)
            if (stage == Stage.RIGHT) begin(Stage.LEFT) else decide()
        }
    }

    private fun begin(s: Stage) { stage = s; stageTime = 0f; startX = Float.NaN; progress = 0f; if (s == Stage.RIGHT) results.clear() }

    private fun decide() {
        val id = cameraId()
        stage = Stage.OFF
        if (id == null) return
        when {
            results.all { it < 0 } -> {
                app.settings.mirrorByCamera[id] = !app.settings.mirrorFor(id)
                finish(id)
                app.resetTracking()
                app.notify("Fixed: the picture was the wrong way round")
            }
            results.all { it > 0 } -> { app.settings.mirrorByCamera[id] = app.settings.mirrorFor(id); finish(id); app.notify("Left and right are the right way round") }
            ++attempts < 2 -> begin(Stage.RIGHT) // mixed answers: once more
            else -> { finish(id); app.notify("Couldn't tell - if movements look backwards, use Flip left/right") }
        }
    }

    private fun finish(id: String) {
        app.settings.directionChecked.add(id)
        forced = false
        attempts = 0
        app.saveSettings()
    }

    /** Called for every camera pose frame: turns an upside-down picture the right way up. */
    fun observe(people: List<BodyMetrics>, frameNanos: Long) {
        val dt = if (lastFrameNs == Long.MIN_VALUE) 0.0 else ((frameNanos - lastFrameNs) / 1e9).coerceIn(0.0, 0.5)
        lastFrameNs = frameNanos
        val upright = people.count { it.hipsVisible && it.hipY > it.shoulderY }
        val inverted = people.count { it.hipsVisible && it.hipY < it.shoulderY - 0.3f * it.scale }
        upsideSec = if (inverted > 0 && upright == 0) upsideSec + dt else 0.0
        if (upsideSec > 1.5) {
            upsideSec = 0.0
            val id = cameraId() ?: return
            if (!app.settings.upsideDownCameras.remove(id)) app.settings.upsideDownCameras.add(id)
            app.saveSettings()
            app.resetTracking()
            app.notify("The camera picture was upside down - turned it the right way up")
        }
    }

    fun draw(g: Gfx) {
        if (!active) return
        g.fillRect(0f, 0f, g.width, g.height, 0xAA000000.toInt())
        val r = Rect(g.width / 2f - 640f, 200f, g.width / 2f + 640f, 820f)
        Draw.panel(g, r)
        g.text("Quick check: left and right", r.cx, r.top + 70f, 60f, Colors.GOLD)
        g.text("Hold a hand up in front of your chest,", r.cx, r.top + 150f, 40f, Colors.WHITE, bold = false)
        g.text("then slide it all the way towards the arrow", r.cx, r.top + 205f, 40f, Colors.WHITE, bold = false)
        val right = stage == Stage.RIGHT
        val ax = if (right) g.width - 170f else 170f
        val ay = 510f
        val d = if (right) 1f else -1f
        val pulse = (kotlin.math.sin(app.time * 6) * 12).toFloat()
        g.glow(ax, ay, 170f, Colors.withAlpha(Colors.GOLD, 0.35f))
        g.line(ax - d * (120f + pulse), ay, ax + d * 40f, ay, 34f, Colors.GOLD)
        Draw.tri(g, ax + d * 90f, ay, ax + d * 20f, ay - 60f, ax + d * 20f, ay + 60f, Colors.GOLD)
        g.text(if (right) "This way" else "Now this way", ax - d * 40f, ay + 110f, 40f, Colors.GOLD)
        // progress (no cursor: if the picture is backwards a cursor would go the wrong way)
        val bar = Rect(r.left + 160f, r.bottom - 150f, r.right - 160f, r.bottom - 100f)
        g.fillRoundRect(bar.left, bar.top, bar.right, bar.bottom, 25f, 0x44FFFFFF)
        g.fillRoundRect(bar.left, bar.top, bar.left + bar.width * progress, bar.bottom, 25f, Colors.GOOD)
        g.text("Step ${if (right) 1 else 2} of 2", r.cx, r.bottom - 50f, 32f, Colors.TEXT_DIM, bold = false)
    }

    companion object {
        /** How far (in reach units, 1 = a comfortable arm's reach) the hand must slide to count. */
        const val MOVE = 0.5f
    }
}
