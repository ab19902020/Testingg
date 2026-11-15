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
    // ---- picture restoration (applied to the black-and-white image before colouring) ----
    /** Remove exposure flicker (brightness pumping from frame to frame). */
    val deflicker: Boolean = true,
    /** Restore faded contrast (grey blacks, dull whites), within limits. */
    val autoContrast: Boolean = true,
    /** Film grain / noise reduction strength. */
    val grain: Grain = Grain.LIGHT,
    /** Remove single-frame dust and blotches (delays processing by one frame). */
    val dust: Boolean = true,
    /** Light sharpening after grain reduction. */
    val sharpen: Boolean = true,
) {
    /** blend = how much of the motion-matched previous frame is mixed in where the picture is unchanged. */
    enum class Grain(val blend: Float) { OFF(0f), LIGHT(0.5f), STRONG(0.72f) }

    /** True when any restoration step is switched on. */
    val restores: Boolean get() = deflicker || autoContrast || grain != Grain.OFF || dust || sharpen

    /** The same settings with every restoration step switched off. */
    fun withoutRestoration() = copy(deflicker = false, autoContrast = false, grain = Grain.OFF, dust = false, sharpen = false)

    enum class Look(val saturation: Float) { NATURAL(1.0f), VIVID(1.3f) }

    /** maxStride = longest run of frames between model runs (1 = every frame). */
    enum class Speed(val maxStride: Int) { BEST(1), BALANCED(2), FAST(4) }

    /**
     * alpha = weight given to the colour an object already has (carried along its motion) against
     * the model's fresh guess for this frame. 0.9 means a new guess needs roughly ten frames of
     * agreement to replace an established colour, so outfits keep their colour while people move.
     */
    enum class Stability(val alpha: Float) {
        /** OFF = independent per-frame estimates; only used as a benchmark baseline. */
        OFF(0f), LIGHT(0.65f), STANDARD(0.9f), STRONG(0.96f)
    }

    val chromaGain: Float get() = intensity.coerceIn(0f, 2f) * look.saturation

    /** Stable textual fingerprint (used for segment reuse decisions). */
    fun fingerprint(): String =
        "i=${"%.2f".format(java.util.Locale.ROOT, intensity)};l=$look;s=$speed;t=$stability;e=$edgeRefine" +
            ";r=${if (deflicker) 1 else 0}${if (autoContrast) 1 else 0}${grain.ordinal}${if (dust) 1 else 0}${if (sharpen) 1 else 0}"
}
