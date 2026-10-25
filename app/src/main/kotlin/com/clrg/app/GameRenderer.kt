package com.clrg.app

import android.graphics.Canvas
import android.os.Process
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.clrg.core.app.GameApp
import com.clrg.core.math.Rect
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.Key
import com.clrg.core.ui.screens.PreviewView

/** Held while drawing to a surface; surface-destroyed callbacks take it so no frame is mid-draw on a dead surface. */
object SurfaceLock

/**
 * Render thread. Draws the game (virtual 1080-unit-high canvas) on the TV surface when the TV is used as a second
 * screen, otherwise on the phone surface (which is mirrored to the TV). Rendering is paced to the display and capped
 * at 60 fps; game simulation inside GameApp uses its own fixed time step.
 */
class GameRenderer(private val app: GameApp, private val router: DisplayRouter, private val power: android.os.PowerManager) {
    @Volatile private var phoneHolder: SurfaceHolder? = null
    @Volatile private var running = false
    @Volatile private var active = false
    private var thread: Thread? = null
    private val gameGfx = AndroidGfx()
    private val companionGfx = AndroidGfx()
    /** Phone-screen scale (pixels per virtual unit) while the phone shows the game. */
    @Volatile private var phoneScale = 1f
    @Volatile private var phoneShowsGame = true
    private var frame = 0L
    private val companionButtons = ArrayList<Pair<Rect, Key>>()
    @Volatile private var companionScale = 1f

    val phoneCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { synchronized(SurfaceLock) { phoneHolder = holder } }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { synchronized(SurfaceLock) { phoneHolder = holder } }
        override fun surfaceDestroyed(holder: SurfaceHolder) { synchronized(SurfaceLock) { phoneHolder = null } }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "render").also { it.start() }
    }

    fun resume() { active = true }
    fun pause() { active = false }

    fun shutdown() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var last = System.nanoTime()
        while (running) {
            try {
                if (!active) { Thread.sleep(30); last = System.nanoTime(); continue }
                var drew = false
                synchronized(SurfaceLock) {
                    val tv = router.tvHolder
                    val phone = phoneHolder
                    val target = tv ?: phone
                    if (target != null) {
                        phoneShowsGame = tv == null
                        drawGame(target, tv == null)
                        if (tv != null && phone != null && frame % 3 == 0L) drawCompanion(phone)
                        drew = true
                    }
                }
                if (!drew) { Thread.sleep(16); continue }
                frame++
                // cap at ~60 fps (TVs run at 60 Hz; saves power and heat on 120 Hz phones); 30 fps when the phone is hot
                val hot = power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
                val now = System.nanoTime()
                val wait = (if (hot) 33_000_000L else 16_000_000L) - (now - last)
                if (wait > 1_000_000L) Thread.sleep(wait / 1_000_000L)
                last = System.nanoTime()
            } catch (t: Throwable) {
                // never let one bad frame take the whole game down; slow down so a persistent error can't spin
                Log.e("GameRenderer", "render loop", t)
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun drawGame(holder: SurfaceHolder, isPhone: Boolean) {
        val canvas: Canvas = try { holder.lockHardwareCanvas() } catch (e: Exception) { null } ?: return
        try {
            val s = canvas.height / 1080f
            if (isPhone) phoneScale = s
            canvas.save()
            canvas.scale(s, s)
            gameGfx.begin(canvas, canvas.width / s, 1080f)
            app.frame(gameGfx, System.nanoTime())
            canvas.restore()
        } catch (t: Throwable) {
            Log.e("GameRenderer", "frame failed", t)
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    /** Phone screen while the game is on the TV: camera view, status and a touch D-pad for navigation/recovery. */
    private fun drawCompanion(holder: SurfaceHolder) {
        val canvas: Canvas = try { holder.lockHardwareCanvas() } catch (e: Exception) { null } ?: return
        try {
            val s = canvas.height / 1080f
            companionScale = s
            canvas.save(); canvas.scale(s, s)
            val g = companionGfx
            g.begin(canvas, canvas.width / s, 1080f)
            g.fillRect(0f, 0f, g.width, g.height, Colors.NIGHT_TOP)
            g.text("Game is on the TV", g.width / 2f, 70f, 60f, Colors.GOLD)
            PreviewView.draw(g, app, Rect(40f, 140f, g.width * 0.6f, 900f))
            g.text("Touch controls (also use hands-free cursors)", g.width * 0.8f, 160f, 30f, Colors.TEXT_DIM, bold = false)
            companionButtons.clear()
            val cx = g.width * 0.8f
            val cy = 520f
            fun btn(r: Rect, label: String, key: Key) {
                g.fillRoundRect(r.left, r.top, r.right, r.bottom, 24f, 0xFF1C3A66.toInt())
                g.strokeRoundRect(r.left, r.top, r.right, r.bottom, 24f, 4f, Colors.PANEL_BORDER)
                g.text(label, r.cx, r.cy, 44f, Colors.WHITE)
                companionButtons.add(r to key)
            }
            btn(Rect.ofCenter(cx, cy - 170f, 150f, 150f), "^", Key.UP)
            btn(Rect.ofCenter(cx, cy + 170f, 150f, 150f), "v", Key.DOWN)
            btn(Rect.ofCenter(cx - 170f, cy, 150f, 150f), "<", Key.LEFT)
            btn(Rect.ofCenter(cx + 170f, cy, 150f, 150f), ">", Key.RIGHT)
            btn(Rect.ofCenter(cx, cy, 150f, 150f), "OK", Key.ENTER)
            btn(Rect.ofCenter(cx, cy + 380f, 420f, 110f), "Back / Pause", Key.BACK)
            Draw.stringLights(g, 0f, g.width, 4f, app.time)
            canvas.restore()
        } catch (t: Throwable) {
            Log.e("GameRenderer", "companion failed", t)
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    /** Touches on the phone: direct UI touches when the phone shows the game, D-pad when the TV does. */
    fun onPhoneTouch(e: MotionEvent): Boolean {
        if (phoneShowsGame) {
            val s = phoneScale.coerceAtLeast(0.01f)
            val action = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> InputEvent.Touch.DOWN
                MotionEvent.ACTION_MOVE -> InputEvent.Touch.MOVE
                MotionEvent.ACTION_UP -> InputEvent.Touch.UP
                MotionEvent.ACTION_CANCEL -> InputEvent.Touch.CANCEL
                else -> return true
            }
            app.submitInput(InputEvent.Touch(action, e.x / s, e.y / s))
        } else if (e.actionMasked == MotionEvent.ACTION_UP) {
            val s = companionScale.coerceAtLeast(0.01f)
            val x = e.x / s; val y = e.y / s
            companionButtons.toList().firstOrNull { it.first.contains(x, y) }?.let { app.key(it.second) }
        }
        return true
    }
}
