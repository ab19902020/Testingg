package com.clrg.core.tracking

import com.clrg.core.math.clamp
import com.clrg.core.pose.Lm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A player's calibrated body: neutral position, body size and comfortable reach. All gesture thresholds are scaled
 * by these numbers, so a child, an adult, someone seated and someone with limited reach all get controls that fit.
 */
data class Calibration(
    val neutralShoulderX: Float,
    val neutralShoulderY: Float,
    /** Body scale S at calibration (frame units). */
    val bodyScale: Float,
    val shoulderWidth: Float,
    /** Torso length / shoulder width, used when the hips are hidden. */
    val torsoRatio: Float,
    /** Comfortable sideways reach of the wrists from the shoulder centre, in S units. */
    val reachSide: Float,
    /** Comfortable overhead reach of the wrists above the shoulder centre, in S units. */
    val reachUp: Float,
    val dominantHand: Hand,
    val mode: MovementMode,
    val seated: Boolean,
) {
    /** Radius of the player's comfortable reach space in S units. */
    val reach: Float get() = clamp((reachSide + reachUp) / 2f, 0.6f, 2.2f)

    companion object {
        fun defaultReach(mode: MovementMode) = when (mode) {
            MovementMode.STANDARD -> 1.5f
            MovementMode.REDUCED -> 1.0f
            MovementMode.SEATED -> 1.25f
        }

        /** Quick calibration from a single neutral observation (used by "skip" and by tests). */
        fun fromNeutral(m: BodyMetrics, hand: Hand = Hand.RIGHT, mode: MovementMode = MovementMode.STANDARD): Calibration {
            val r = defaultReach(mode)
            val ratio = if (m.hipsVisible && m.shoulderWidth > 1e-3f) clamp(m.torso / m.shoulderWidth, 0.9f, 2.2f) else BodyMetrics.DEFAULT_TORSO_RATIO
            return Calibration(m.shoulderX, m.shoulderY, m.scale, m.shoulderWidth, ratio, r, r, hand, mode, mode == MovementMode.SEATED || !m.hipsVisible)
        }
    }
}

enum class CalibrationStep(val title: String, val instruction: String) {
    NEUTRAL("Stand still", "Relax with your arms down"),
    ARMS_OUT("Arms out wide", "Stretch both arms out to the sides - only as far as is comfortable"),
    REACH_UP("Reach up", "Reach both hands up - only as high as is comfortable"),
    PLAYING_HAND("Pick your hand", "Raise the hand you want to play with"),
    DONE("Ready!", "Calibrated"),
}

/**
 * Runs the calibration steps for one player. Feed it the player's smoothed body every pose update; each step
 * completes on its own, so two players calibrate side by side at their own pace.
 */
class CalibrationProcess(val mode: MovementMode, private val forcedHand: Hand? = null) {
    var step = CalibrationStep.NEUTRAL
        private set
    /** 0..1 progress of the current step (drives the on-screen ring). */
    var progress = 0f
        private set
    var result: Calibration? = null
        private set
    var stepTime = 0f
        private set

    private var stillTime = 0f
    private var lastX = Float.NaN
    private var lastY = 0f
    private var sumX = 0f; private var sumY = 0f; private var sumS = 0f; private var sumW = 0f; private var sumRatio = 0f
    private var nSamples = 0; private var nRatio = 0
    private var hipsSeen = 0; private var hipsMissing = 0
    private var neutralS = 0f
    private var maxSide = 0f
    private var maxUp = 0f
    private var holdTime = 0f
    private var handVotesL = 0f
    private var handVotesR = 0f

    private val outThreshold = if (mode == MovementMode.STANDARD) 0.75f else 0.4f
    private val upThreshold = if (mode == MovementMode.STANDARD) 0.6f else 0.25f

    val isDone: Boolean get() = step == CalibrationStep.DONE

    /** @param slot the player's tracker slot (uses smoothed landmarks); dt seconds since last update. */
    fun update(slot: PlayerSlot, dt: Float) {
        val m = slot.metrics ?: return
        if (step == CalibrationStep.DONE) return
        stepTime += dt
        val sp = slot.smooth
        val s = slot.scale
        val sx = (sp.x[Lm.LEFT_SHOULDER] + sp.x[Lm.RIGHT_SHOULDER]) / 2f
        val sy = (sp.y[Lm.LEFT_SHOULDER] + sp.y[Lm.RIGHT_SHOULDER]) / 2f
        val lwx = (sp.x[Lm.LEFT_WRIST] - sx) / s
        val rwx = (sp.x[Lm.RIGHT_WRIST] - sx) / s
        val lwy = (sy - sp.y[Lm.LEFT_WRIST]) / s
        val rwy = (sy - sp.y[Lm.RIGHT_WRIST]) / s
        val noseY = (sy - sp.y[Lm.NOSE]) / s
        val wristsSeen = sp.vis[Lm.LEFT_WRIST] > 0.4f && sp.vis[Lm.RIGHT_WRIST] > 0.4f

        when (step) {
            CalibrationStep.NEUTRAL -> {
                val moved = if (lastX.isNaN()) 0f else hypot(sx - lastX, sy - lastY) / max(s, 1e-3f)
                lastX = sx; lastY = sy
                val handsLow = !wristsSeen || (lwy < -0.1f && rwy < -0.1f) || mode == MovementMode.SEATED
                if (moved < 0.04f && handsLow) {
                    stillTime += dt
                    sumX += sx; sumY += sy; sumS += m.scale; sumW += m.shoulderWidth; nSamples++
                    if (m.hipsVisible && m.shoulderWidth > 1e-3f) { sumRatio += m.torso / m.shoulderWidth; nRatio++; hipsSeen++ } else hipsMissing++
                } else {
                    stillTime = 0f; sumX = 0f; sumY = 0f; sumS = 0f; sumW = 0f; nSamples = 0; sumRatio = 0f; nRatio = 0
                    hipsSeen = 0; hipsMissing = 0
                }
                progress = clamp(stillTime / 1.2f, 0f, 1f)
                if (stillTime >= 1.2f && nSamples > 0) {
                    neutralS = sumS / nSamples
                    next(CalibrationStep.ARMS_OUT)
                }
            }
            CalibrationStep.ARMS_OUT -> {
                if (wristsSeen) {
                    val side = (abs(lwx) + abs(rwx)) / 2f
                    if (side > maxSide) maxSide = side
                    holdTime = if (side >= outThreshold && side >= 0.85f * maxSide) holdTime + dt else max(0f, holdTime - dt)
                }
                progress = clamp(holdTime / 0.8f, 0f, 1f)
                if (holdTime >= 0.8f || (stepTime > 10f && maxSide > 0.25f)) next(CalibrationStep.REACH_UP)
            }
            CalibrationStep.REACH_UP -> {
                if (wristsSeen) {
                    val up = (lwy + rwy) / 2f
                    if (up > maxUp) maxUp = up
                    holdTime = if (up >= upThreshold && up >= 0.85f * maxUp) holdTime + dt else max(0f, holdTime - dt)
                }
                progress = clamp(holdTime / 0.8f, 0f, 1f)
                if (holdTime >= 0.8f || (stepTime > 10f && maxUp > 0.1f)) {
                    if (forcedHand != null) finish(forcedHand) else next(CalibrationStep.PLAYING_HAND)
                }
            }
            CalibrationStep.PLAYING_HAND -> {
                val lUp = sp.vis[Lm.LEFT_WRIST] > 0.4f && lwy > noseY - 0.1f && lwy > rwy + 0.3f
                val rUp = sp.vis[Lm.RIGHT_WRIST] > 0.4f && rwy > noseY - 0.1f && rwy > lwy + 0.3f
                if (lUp) handVotesL += dt else handVotesL = max(0f, handVotesL - dt)
                if (rUp) handVotesR += dt else handVotesR = max(0f, handVotesR - dt)
                progress = clamp(max(handVotesL, handVotesR) / 0.8f, 0f, 1f)
                if (handVotesL >= 0.8f) finish(Hand.LEFT) else if (handVotesR >= 0.8f) finish(Hand.RIGHT)
            }
            CalibrationStep.DONE -> Unit
        }
    }

    private fun next(s: CalibrationStep) {
        step = s
        progress = 0f
        holdTime = 0f
        stepTime = 0f
    }

    private fun finish(hand: Hand) {
        val n = max(1, nSamples)
        val seated = mode == MovementMode.SEATED || hipsMissing > hipsSeen
        val ratio = if (nRatio > 0) clamp(sumRatio / nRatio, 0.9f, 2.2f) else BodyMetrics.DEFAULT_TORSO_RATIO
        val minReach = if (mode == MovementMode.STANDARD) 0.7f else 0.4f
        result = Calibration(
            neutralShoulderX = sumX / n,
            neutralShoulderY = sumY / n,
            bodyScale = if (neutralS > 0f) neutralS else sumS / n,
            shoulderWidth = sumW / n,
            torsoRatio = ratio,
            reachSide = clamp(maxSide * 0.92f, minReach, 2.4f),
            reachUp = clamp(maxUp * 0.92f + 0.3f, minReach, 2.4f),
            dominantHand = hand,
            mode = mode,
            seated = seated,
        )
        next(CalibrationStep.DONE)
        progress = 1f
    }

    /** Skips the remaining steps using defaults (touch fallback). Needs at least the neutral pose. */
    fun skipWithDefaults(slot: PlayerSlot, hand: Hand) {
        val m = slot.metrics ?: return
        if (nSamples == 0) {
            sumX = m.shoulderX; sumY = m.shoulderY; sumS = m.scale; sumW = m.shoulderWidth; nSamples = 1
            neutralS = m.scale
            if (m.hipsVisible) { sumRatio = m.torso / m.shoulderWidth; nRatio = 1; hipsSeen = 1 } else hipsMissing = 1
        }
        if (maxSide <= 0f) maxSide = Calibration.defaultReach(mode)
        if (maxUp <= 0f) maxUp = Calibration.defaultReach(mode) - 0.3f
        finish(hand)
    }
}
