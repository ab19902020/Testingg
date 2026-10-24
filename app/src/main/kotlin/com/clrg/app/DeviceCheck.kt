package com.clrg.app

import android.os.Build
import com.clrg.core.app.DeviceInfo

/**
 * Informational check against the minimum target (Qualcomm Snapdragon 8 Gen 3 or demonstrably equivalent/newer).
 * The app never refuses to run; it only shows a note when the chipset is known to be below the target.
 */
object DeviceCheck {
    // Build.SOC_MODEL values (Android 12+). Lists are deliberately conservative; unknown chips are reported as unknown.
    private val meets = mapOf(
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8750" to "Snapdragon 8 Elite",
        "SM8850" to "Snapdragon 8 Elite Gen 5",
        "MT6989" to "Dimensity 9300",
        "MT6991" to "Dimensity 9400",
        "S5E9945" to "Exynos 2400",
        "S5E9955" to "Exynos 2500",
    )
    private val below = mapOf(
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8635" to "Snapdragon 8s Gen 3",
        "SM8350" to "Snapdragon 888",
        "SM7675" to "Snapdragon 7+ Gen 3",
        "MT6985" to "Dimensity 9200",
        "S5E9935" to "Exynos 2300",
    )

    fun info(): DeviceInfo {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL?.uppercase() else null
        val socMfr = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null
        val known = soc?.let { s -> meets.entries.firstOrNull { s.startsWith(it.key) } }
        val weak = soc?.let { s -> below.entries.firstOrNull { s.startsWith(it.key) } }
        val (ok, note) = when {
            known != null -> true to "${known.value}: meets the recommended target"
            weak != null -> false to "${weak.value}: below the recommended Snapdragon 8 Gen 3 class"
            soc.isNullOrBlank() || soc == "UNKNOWN" -> null to "Chipset not reported - performance not verified"
            else -> null to "Chipset $soc not in the verified list - performance not verified"
        }
        return DeviceInfo(Build.MANUFACTURER, Build.MODEL, listOfNotNull(socMfr, soc).joinToString(" ").ifBlank { null }, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, ok, note)
    }
}
