package colourfilm.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import colourfilm.app.model.ModelSessions
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why the app last closed unexpectedly, so the next launch can show a report the
 * user can copy or share. Nothing is sent anywhere automatically.
 */
object CrashReporter {
    private const val PREFS = "crash"
    private fun javaFile(c: Context) = File(c.filesDir, "last_java_crash.txt")
    private fun breadcrumbFile(c: Context) = File(c.filesDir, "breadcrumbs.txt")
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                javaFile(context).writeText("Thread: ${t.name}\n$sw")
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Short notes about what the app was doing; the last few are included in a crash report. */
    fun breadcrumb(text: String) {
        val c = appContext ?: return
        runCatching {
            val f = breadcrumbFile(c)
            if (f.length() > 16_000) f.writeText(f.readText().takeLast(8_000))
            f.appendText("${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())} $text\n")
        }
    }

    /** Returns a report if the main process crashed since the last time this was called. */
    fun collect(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0L)
        var reason: String? = null
        var newest = seen
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            for (info in am.getHistoricalProcessExitReasons(context.packageName, 0, 8)) {
                if (info.timestamp <= seen) continue
                newest = maxOf(newest, info.timestamp)
                if (info.processName != context.packageName || reason != null) continue
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "App crash (Java)"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (signal ${info.status})"
                    ApplicationExitInfo.REASON_ANR -> "App not responding"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: low memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by Android: excessive resource use"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialisation failure"
                    else -> null
                }
                if (kind != null) reason = "$kind at ${Date(info.timestamp)}\n${info.description ?: ""}"
            }
        }
        val java = javaFile(context).takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
        prefs.edit().putLong("seen", maxOf(newest, System.currentTimeMillis() - 1)).apply()
        if (reason == null && java == null) return null
        val crumbs = runCatching { breadcrumbFile(context).readLines().takeLast(25).joinToString("\n") }.getOrDefault("")
        return buildString {
            appendLine("ColourFilm ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE}")
            appendLine("AI backend decision: ${ModelSessions.describeVerdict(context)}")
            reason?.let { appendLine(); appendLine(it.trim()) }
            java?.let { appendLine(); appendLine(it.trim().take(6000)) }
            if (crumbs.isNotBlank()) { appendLine(); appendLine("Last steps:"); appendLine(crumbs) }
        }
    }
}

