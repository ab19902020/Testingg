package com.clrg.core.game

import com.clrg.core.gesture.PlayerControls
import kotlin.math.abs

/**
 * How late the game learns about a player's movement, so that timing is judged by when a movement was *made*, not
 * by when it reached the game.
 *
 * Two delays add up between "the ball reaches your racket on the TV" and "the game receives your swing":
 *  1. **Camera to game** ([pipeline]): exposure, the camera's image processing and body tracking. Measured for every
 *     camera frame from the camera's own capture timestamp.
 *  2. **Game to your eyes** ([displayDelay]): drawing, the phone's display pipeline and the TV's picture processing.
 *     The phone cannot see the TV, so this comes from the timing test in Settings (or a typical default).
 *
 * Gestures carry the camera time of the moment they were made; [age] turns that into "how long ago", and the game
 * subtracts the display delay as well, because the player was reacting to a picture that was that much older.
 *
 * Disabled (all zeros) unless the app turns it on, so scripted tests and simulated input are judged as they arrive.
 */
class InputTiming {
    /** Compensation on/off (off for simulated input and in tests). */
    @Volatile var enabled = false
    /** Display delay in seconds: game frame -> picture on the screen the players watch. */
    @Volatile var displayDelay = DEFAULT_DISPLAY_DELAY
    /** "Now" on the camera/pose clock (seconds), updated every frame. NaN until known. */
    @Volatile var poseNow = Double.NaN

    /** Smoothed camera-to-game delay (s). */
    var pipeline = 0.0
        private set
    /** A high estimate of the camera-to-game delay (mean + 2 x typical deviation), for deadlines (s). */
    var pipelineHigh = 0.0
        private set
    private var dev = 0.0
    var samples = 0L
        private set

    /** One measured camera-to-game delay (seconds). Implausible values (clock mismatch) are ignored. */
    fun observe(latencySec: Double) {
        if (latencySec.isNaN() || latencySec < 0.0 || latencySec > MAX_AGE) return
        if (samples == 0L) { pipeline = latencySec; dev = 0.01 } else {
            pipeline += 0.05 * (latencySec - pipeline)
            dev += 0.05 * (abs(latencySec - pipeline) - dev)
        }
        samples++
        pipelineHigh = pipeline + 2.0 * dev
    }

    fun reset() { pipeline = 0.0; pipelineHigh = 0.0; dev = 0.0; samples = 0 }

    /** How long ago (s) something stamped at camera time [t] really happened; 0 when unknown or implausible. */
    fun age(t: Double): Double {
        if (!enabled || poseNow.isNaN()) return 0.0
        val a = poseNow - t
        return if (a in 0.0..MAX_AGE) a else 0.0
    }

    /** Seconds to subtract from "now" to get the game moment the player was reacting to for an event stamped [t]. */
    fun rewind(t: Double): Double = if (!enabled) 0.0 else age(t) + displayDelay

    /**
     * How long a game should keep waiting after a deadline for a gesture that may still be on its way: the player
     * saw the deadline [displayDelay] late, and their movement needs [pipelineHigh] plus a little detection time
     * to arrive.
     */
    val grace: Double get() = if (!enabled) 0.0 else (pipelineHigh + DETECT_SLACK + displayDelay).coerceIn(0.0, MAX_GRACE)

    /**
     * How far the avatar and hand positions a game reads lag behind the player's real body, after the on-screen
     * prediction has moved them forward (s).
     */
    val avatarLag: Double get() = if (!enabled) 0.0 else (pipeline - PlayerControls.MAX_LEAD * PlayerControls.LEAD_GAIN).coerceAtLeast(0.0)

    companion object {
        /** Typical phone + TV delay until the timing test has been run (phone ~25 ms, TV in game mode ~20-40 ms). */
        const val DEFAULT_DISPLAY_DELAY = 0.06
        const val MAX_AGE = 0.5
        const val MAX_GRACE = 0.45
        /** Swings are recognised a little after the moment they are stamped with (their fastest point). */
        const val DETECT_SLACK = 0.06
    }
}
