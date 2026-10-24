package com.clrg.core.math

import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012): smooths jitter strongly when a signal is slow and lets fast motion
 * through with little lag. Used for landmark smoothing so controls stay responsive.
 *
 * @param minCutoff cutoff frequency (Hz) at zero speed - lower = smoother when still.
 * @param beta      speed coefficient - higher = less lag during fast movement.
 * @param dCutoff   cutoff for the derivative estimate.
 */
class OneEuroFilter(
    var minCutoff: Float = 1.2f,
    var beta: Float = 0.6f,
    var dCutoff: Float = 1.0f,
) {
    private var initialised = false
    private var prevValue = 0f
    private var prevDeriv = 0f

    var value: Float = 0f
        private set
    /** Smoothed derivative (units per second). */
    var derivative: Float = 0f
        private set

    fun reset() {
        initialised = false
    }

    fun filter(x: Float, dt: Float): Float {
        if (!initialised || dt <= 0f) {
            initialised = true
            prevValue = x
            prevDeriv = 0f
            value = x
            derivative = 0f
            return x
        }
        val dx = (x - prevValue) / dt
        val ad = alpha(dt, dCutoff)
        val edx = prevDeriv + ad * (dx - prevDeriv)
        val cutoff = minCutoff + beta * abs(edx)
        val a = alpha(dt, cutoff)
        val result = prevValue + a * (x - prevValue)
        prevValue = result
        prevDeriv = edx
        value = result
        derivative = edx
        return result
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (TAU * cutoff)
        return 1f / (1f + tau / dt)
    }
}
