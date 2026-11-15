package colourfilm.app

import android.content.Context

/**
 * Remembers what the app was doing so that, if the process dies (for any reason Android reports
 * or not), the next launch can avoid the component that was in use:
 * first the AI chip (NPU), then hardware video codecs.
 */
object SafeMode {
    private const val PREFS = "safemode"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Opt-in: the Qualcomm NPU is faster but could not be tested during development. */
    fun npuEnabled(c: Context) = prefs(c).getBoolean("npu", false)
    fun setNpuEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("npu", on).commit() }

    /** True after a crash with hardware codecs: use Android's software codecs instead. */
    fun softCodecs(c: Context) = prefs(c).getBoolean("soft_codecs", false)
    fun setSoftCodecs(c: Context, on: Boolean) { prefs(c).edit().putBoolean("soft_codecs", on).commit() }

    /** Written synchronously before each risky phase; "idle" when no job is running. */
    fun enter(c: Context, stage: String) { prefs(c).edit().putString("stage", stage).commit() }
    fun idle(c: Context) = enter(c, "idle")
    fun stage(c: Context): String = prefs(c).getString("stage", "idle") ?: "idle"

    /**
     * Call once at app start. If the last process died mid-job, switches off the riskiest
     * component that was in use and returns a sentence describing what was changed.
     */
    fun recover(c: Context): String? {
        val s = stage(c)
        if (s == "idle") return null
        idle(c)
        return when {
            "npu" in s -> { setNpuEnabled(c, false); "The app stopped while using the AI chip (stage: $s). The AI chip has been switched off." }
            "hw" in s && !softCodecs(c) -> { setSoftCodecs(c, true); "The app stopped while using the phone's hardware video codecs (stage: $s). Software codecs will be used from now on (slower, safer)." }
            else -> "The app stopped during: $s."
        }
    }
}
