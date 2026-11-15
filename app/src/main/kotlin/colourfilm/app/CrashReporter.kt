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
 * Keeps a small local log of what the app was doing and why it last closed, so a report can be
 * copied or shared from the Home screen. Nothing is sent anywhere automatically.
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

    /** Short notes about what the app is doing, written to disk immediately. */
    @Synchronized
    fun breadcrumb(text: String) {
        val c = appContext ?: return
        runCatching {
            val f = breadcrumbFile(c)
            if (f.length() > 24_000) f.writeText(f.readText().takeLast(12_000))
            f.appendText("${SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())} $text\n")
        }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason $r"
    }

    /** Full diagnostics text; available at any time from Home. */
    fun diagnostics(context: Context, headline: String? = null): String = buildString {
        headline?.let { appendLine(it); appendLine() }
        appendLine("ColourFilm ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE}")
        appendLine("AI chip enabled: ${SafeMode.npuEnabled(context)}; decision: ${ModelSessions.describeVerdict(context)}")
        appendLine("Software codecs: ${SafeMode.softCodecs(context)}; stage: ${SafeMode.stage(context)}")
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
            if (infos.isNotEmpty()) { appendLine(); appendLine("Recent process exits:") }
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
            for (i in infos) appendLine("${fmt.format(Date(i.timestamp))} ${i.processName.removePrefix(context.packageName).ifEmpty { "main" }} " +
                "${reasonName(i.reason)} status=${i.status} importance=${i.importance} ${i.description ?: ""}")
        }
        javaFile(context).takeIf { it.exists() }?.let { appendLine(); appendLine("Java crash:"); appendLine(it.readText().trim().take(6000)) }
        val crumbs = runCatching { breadcrumbFile(context).readLines().takeLast(45).joinToString("\n") }.getOrDefault("")
        if (crumbs.isNotBlank()) { appendLine(); appendLine("Last steps:"); appendLine(crumbs) }
    }

    /**
     * Call once at app start. Returns a report when the previous run ended unexpectedly: a Java
     * crash was recorded, Android reports a crash-like exit, or a job was cut off ([recovered]).
     */
    fun collect(context: Context, recovered: String?): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0L)
        var crashed = recovered != null || javaFile(context).exists()
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            for (info in am.getHistoricalProcessExitReasons(context.packageName, 0, 6)) {
                if (info.timestamp <= seen || info.processName != context.packageName) continue
                if (info.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)) crashed = true
            }
        }
        prefs.edit().putLong("seen", System.currentTimeMillis()).apply()
        if (!crashed) return null
        val text = diagnostics(context, recovered)
        javaFile(context).delete()
        return text
    }
}
