package com.clrg.core.pose

/**
 * One detected person in *frame units* (see [FrameGeometry]): x in [0, aspect], y in [0, 1], mirrored so the
 * screen acts like a mirror. Landmark semantics are unchanged: LEFT_WRIST is the person's own left wrist.
 */
class PersonPose(
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val visibility: FloatArray,
    val presence: FloatArray,
    /** Pose presence score from the landmark model (0..1). */
    val score: Float,
    /** Pipeline track id (temporal continuity hint only; identity is decided by the PlayerTracker). */
    val trackHint: Int,
    val appearance: FloatArray?,
    /** An object held in one hand (when held-object tracking is on and one was seen), else null. */
    val held: HeldObservation? = null,
    /** Finger landmarks per hand (0 = left, 1 = right) when finger tracking ran for this person, else null. */
    val hands: Array<HandObservation?>? = null,
) {
    fun vis(i: Int): Float = visibility[i] * presence[i]
    fun visible(i: Int, threshold: Float = 0.5f): Boolean = vis(i) >= threshold

    companion object {
        fun fromRaw(raw: RawPose, geo: FrameGeometry): PersonPose {
            val n = Lm.COUNT
            val lm = raw.landmarks
            return PersonPose(
                FloatArray(n) { geo.toFrameX(lm.x[it]) },
                FloatArray(n) { geo.toFrameY(lm.y[it]) },
                FloatArray(n) { lm.z[it] / geo.uprightHeight },
                FloatArray(n) { lm.visibility[it] },
                FloatArray(n) { lm.presence[it] },
                raw.presence, raw.trackId, raw.appearance,
                raw.held?.mapped({ geo.toFrameX(it) }, { geo.toFrameY(it) }),
                raw.hands?.let { hs -> Array(2) { h -> hs[h]?.mapped({ geo.toFrameX(it) }, { geo.toFrameY(it) }, 1f / geo.uprightHeight) } },
            )
        }
    }
}

/** All people seen in one camera frame. */
class PoseFrame(
    /** Capture timestamp (nanoseconds, monotonic clock shared with the game loop). */
    val timestampNanos: Long,
    /** Frame aspect ratio (width / height) in frame units. */
    val aspect: Float,
    val persons: List<PersonPose>,
    /** Wall time at which inference finished (for latency measurement). */
    val processedNanos: Long = timestampNanos,
    /** True when produced by the developer simulated-input mode rather than the camera. */
    val simulated: Boolean = false,
)
