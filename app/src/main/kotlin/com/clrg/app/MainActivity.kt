package com.clrg.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.clrg.core.app.GameApp
import com.clrg.core.audio.AudioEngine
import com.clrg.core.ui.Key

/**
 * Single activity. Landscape, full screen, screen kept on. Back (key, gesture or remote) arrives through
 * dispatchKeyEvent (the app does not opt in to predictive back), so it is handled exactly once. Camera frames are
 * processed only in memory on the
 * inference thread; nothing is recorded, stored or uploaded (the app does not even request INTERNET).
 */
class MainActivity : Activity() {
    private lateinit var store: PrefsStore
    private lateinit var audioEngine: AudioEngine
    private lateinit var audio: AndroidAudio
    private lateinit var camera: CameraSource
    private lateinit var pose: PoseEngine
    private lateinit var router: DisplayRouter
    private lateinit var platform: AndroidPlatform
    private lateinit var app: GameApp
    private lateinit var renderer: GameRenderer
    private var permissionDenied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val crashReport = try { CrashReporter.collect(this) } catch (t: Throwable) { null }
        store = PrefsStore(this)
        audioEngine = AudioEngine(48000)
        Thread({ audioEngine.prepare() }, "sfx-prepare").start()
        audio = AndroidAudio(audioEngine)
        camera = CameraSource(this)
        pose = PoseEngine(this, camera) { f -> if (::app.isInitialized) app.submitPoseFrame(f) }
        router = DisplayRouter(this) {
            updateRotation()
            if (::app.isInitialized) app.submitLifecycle("display")
        }
        platform = AndroidPlatform(this, store, camera, pose, router, crashReport)
        if (!platform.hasCameraPermission()) camera.setPermissionMissing(denied = false)
        app = GameApp(platform, audioEngine)
        renderer = GameRenderer(app, router, getSystemService(POWER_SERVICE) as android.os.PowerManager)
        val surface = SurfaceView(this)
        surface.holder.addCallback(renderer.phoneCallback)
        surface.setOnTouchListener { _, e -> renderer.onPhoneTouch(e) }
        surface.isFocusable = true
        surface.keepScreenOn = true
        setContentView(surface)
        renderer.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        router.start()
        updateRotation()
        // camera.start() is idempotent, so the permission result and onResume may both call it safely
        if (platform.hasCameraPermission()) camera.start() else camera.setPermissionMissing(permissionDenied)
        pose.start()
        audio.start()
        audio.setPaused(false)
        renderer.resume()
        app.submitLifecycle("resume")
    }

    override fun onPause() {
        app.submitLifecycle("pause")
        renderer.pause()
        audio.setPaused(true)
        pose.stop()
        camera.stop()
        router.stop()
        super.onPause()
    }

    override fun onDestroy() {
        pose.release()
        renderer.shutdown()
        audio.stop()
        camera.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AndroidPlatform.REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            camera.start()
        } else {
            // after a refusal Android may stop showing the dialog; offer the settings page then
            permissionDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            camera.setPermissionMissing(denied = permissionDenied)
        }
        app.submitLifecycle("camera")
    }

    @Suppress("DEPRECATION")
    private fun updateRotation() {
        val rot = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_90 else windowManager.defaultDisplay.rotation
        pose.displayRotationDeg = when (rot) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            else -> 270
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE -> Key.ENTER
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> Key.BACK
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START -> Key.MENU
            else -> return super.dispatchKeyEvent(event)
        }
        app.key(key)
        return true
    }
}
