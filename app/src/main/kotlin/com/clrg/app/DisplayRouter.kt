package com.clrg.app

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.clrg.core.app.DisplayStatus

/**
 * Watches external displays (USB-C/HDMI, or a wireless display). With mirroring, the phone screen *is* the game
 * screen. When the phone exposes the TV as a presentation display and the user prefers it, the game is drawn there
 * as a second screen and the phone shows a companion view. Disconnection is reported so the game can pause.
 */
class DisplayRouter(private val activity: Activity, private val onChange: () -> Unit) {
    private val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile var status = DisplayStatus()
        private set
    @Volatile var preferPresentation = true
        set(v) { field = v; main.post { if (started) refresh() } }
    private var started = false
    private var presentation: GamePresentation? = null
    /** Surface of the TV when presenting (null otherwise). */
    @Volatile var tvHolder: SurfaceHolder? = null
        private set

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    /** Call from onResume (the activity window must exist before a Presentation can be shown). */
    fun start() {
        started = true
        dm.registerDisplayListener(listener, main)
        refresh()
    }

    fun stop() {
        started = false
        dm.unregisterDisplayListener(listener)
        dismiss()
        updateStatus()
    }

    private fun externalDisplay(): Display? =
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }

    fun refresh() {
        val ext = externalDisplay()
        if (ext == null || !preferPresentation) dismiss()
        if (started && ext != null && preferPresentation && presentation == null && !activity.isFinishing) {
            try {
                presentation = GamePresentation(activity, ext) { holder -> synchronized(SurfaceLock) { tvHolder = holder }; updateStatus() }.also { it.show() }
            } catch (e: RuntimeException) { // InvalidDisplayException / BadTokenException
                Log.w("DisplayRouter", "cannot present on ${ext.name}", e)
                presentation = null
            }
        }
        updateStatus()
    }

    private fun updateStatus() {
        val ext = externalDisplay()
        val mode = ext?.mode
        status = DisplayStatus(
            externalConnected = ext != null,
            externalName = ext?.name,
            externalWidth = mode?.physicalWidth ?: 0,
            externalHeight = mode?.physicalHeight ?: 0,
            externalRefreshHz = ext?.refreshRate ?: 0f,
            presentationActive = presentation != null && tvHolder != null,
            phoneIsGameScreen = presentation == null || tvHolder == null,
        )
        onChange()
    }

    private fun dismiss() {
        synchronized(SurfaceLock) { tvHolder = null }
        presentation?.let { try { it.dismiss() } catch (_: Exception) {} }
        presentation = null
    }

    /** Full-screen SurfaceView on the external display. */
    private class GamePresentation(ctx: Context, display: Display, private val onSurface: (SurfaceHolder?) -> Unit) : Presentation(ctx, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val view = SurfaceView(context)
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = onSurface(holder)
                override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null) // waits for any frame in progress
            })
            setContentView(view)
        }
    }
}
