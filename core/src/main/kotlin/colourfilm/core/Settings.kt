package colourfilm.core

/**
 * User-facing processing options. Every field changes the rendered output and is part of
 * the job fingerprint, so completed segments are only reused when all of them match.
 */
data class ColouriseSettings(
    /** Overall chroma multiplier chosen with the "Colour intensity" slider (0.5 .. 1.5). */
    val intensity: Float = 1.0f,
    /** NATURAL keeps the model's estimate; VIVID boosts saturation with gamut protection. */
    val look: Look = Look.NATURAL,
    /** How often the AI model must run; motion-tracked colour fills the frames in between. */
    val speed: Speed = Speed.BALANCED,
    /** How strongly colours are held steady from frame to frame along tracked motion. */
    val stability: Stability = Stability.STANDARD,
    /** Edge-aware refinement that snaps colour edges to the full-resolution picture. */
    val edgeRefine: Boolean = true,
) {
    enum class Look(val saturation: Float) { NATURAL(1.0f), VIVID(1.3f) }

    /** maxStride = longest run of frames between model runs (1 = every frame). */
    enum class Speed(val maxStride: Int) { BEST(1), BALANCED(2), FAST(4) }

    /** alpha = weight given to the motion-compensated previous colour at model frames. */
    enum class Stability(val alpha: Float) {
        /** OFF = independent per-frame estimates; only used as a benchmark baseline. */
        OFF(0f), LIGHT(0.4f), STANDARD(0.65f), STRONG(0.8f)
    }

    val chromaGain: Float get() = intensity.coerceIn(0f, 2f) * look.saturation

    /** Stable textual fingerprint (used for segment reuse decisions). */
    fun fingerprint(): String =
        "i=${"%.2f".format(java.util.Locale.ROOT, intensity)};l=$look;s=$speed;t=$stability;e=$edgeRefine"
}
