package com.clrg.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Remembers why the previous run ended abnormally so the game can show it (and the player can share it) on the next
 * launch. Everything stays on the phone; nothing is sent anywhere unless the player taps Share.
 *
 * - Java/Kotlin crashes: caught by a default uncaught-exception handler, written to a file, then passed on to
 *   Android as usual.
 * - Native crashes (GPU drivers, TensorFlow Lite) cannot be caught; risky native steps write a marker first and
 *   remove it afterwards, so a marker found at startup names the step that was running. On Android 11+ the system's
 *   own exit record (crash / native crash / not responding) is added.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_FILE = "last_crash.txt"
    private const val STEP_FILE = "native_step.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_SEEN_EXIT = "seen_exit_ts"

    const val STEP_GPU_INIT = "starting body tracking on the GPU"
    const val STEP_GPU_RUN = "running body tracking on the GPU"
    const val STEP_CPU_INIT = "starting body tracking on the CPU"

    @Volatile private var dir: File? = null
    @Volatile private var header = ""
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        dir = context.filesDir
        header = header(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(CRASH_FILE, "${header}When: ${now()}\nWhat happened: the game stopped because of an error in the '${thread.name}' thread\n\n${stackText(error)}")
            } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
    }

    /** Marks the start of a native step that could take the whole app down without a Java exception. */
    fun beginStep(name: String) {
        try { write(STEP_FILE, name) } catch (_: Throwable) {}
    }

    fun endStep() {
        try { dir?.let { File(it, STEP_FILE).delete() } } catch (_: Throwable) {}
    }

    /**
     * Report about the previous run, or null when it ended normally. Clears the stored state, so each problem is
     * reported once. Also turns the GPU off for good when the previous run died in a GPU step.
     */
    fun collect(context: Context): String? {
        val d = context.filesDir
        val crashFile = File(d, CRASH_FILE)
        val stepFile = File(d, STEP_FILE)
        val javaCrash = crashFile.takeIf { it.exists() }?.readText()
        val step = stepFile.takeIf { it.exists() }?.readText()?.trim()
        crashFile.delete()
        stepFile.delete()
        val exit = systemExitRecord(context)
        // a step marker alone may mean the app was simply closed during that step; on Android 11+ require a crash
        val stepCrashed = step != null && (Build.VERSION.SDK_INT < 30 || exit?.crashed == true)
        if (stepCrashed && step!!.contains("GPU")) PoseEngine.disableGpuForGood(context)
        if (javaCrash == null && !stepCrashed && exit?.crashed != true) return null
        val sb = StringBuilder()
        if (javaCrash != null) sb.append(javaCrash.trimEnd()).append("\n")
        else sb.append(header(context))
        if (stepCrashed) {
            sb.append("\nThe game stopped while $step.\n")
            if (step!!.contains("GPU")) sb.append("Body tracking will use the CPU from now on (slower, but avoids this phone's GPU problem).\n")
        }
        if (exit != null && exit.crashed) sb.append("\nAndroid's record: ${exit.text}\n")
        return sb.toString().take(6000)
    }

    private class ExitRecord(val crashed: Boolean, val text: String)

    private fun systemExitRecord(context: Context): ExitRecord? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (info.timestamp <= prefs.getLong(KEY_SEEN_EXIT, 0L)) return null
            prefs.edit().putLong(KEY_SEEN_EXIT, info.timestamp).apply()
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native code)"
                ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "closed for low memory"
                else -> return ExitRecord(false, "")
            }
            val crashed = info.reason == ApplicationExitInfo.REASON_CRASH || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                info.reason == ApplicationExitInfo.REASON_ANR
            val sb = StringBuilder("$kind at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))}")
            info.description?.takeIf { it.isNotBlank() }?.let { sb.append(" - ").append(it.take(300)) }
            if (Build.VERSION.SDK_INT >= 31 && (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_ANR)) {
                traceSummary(info, info.reason == ApplicationExitInfo.REASON_ANR)?.let { sb.append("\n").append(it) }
            }
            ExitRecord(crashed, sb.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "exit info unavailable", t)
            null
        }
    }

    /**
     * Native crash: the tombstone is a protobuf; its readable strings (library and function names, signal, abort
     * message) are enough to see where it died. ANR: the start of the main thread's stack.
     */
    private fun traceSummary(info: ApplicationExitInfo, anr: Boolean): String? {
        val bytes = info.traceInputStream?.use { s -> s.readNBytesCompat(1 shl 19) } ?: return null
        if (anr) {
            val text = String(bytes)
            val i = text.indexOf("\"main\"")
            if (i < 0) return null
            return text.substring(i).lineSequence().take(18).joinToString("\n")
        }
        val strings = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= 5) {
                val s = cur.toString()
                if ((s.contains(".so") || s.startsWith("SIG") || s.contains("abort", true) || s.contains("Abort") || s.contains("tflite") ||
                        s.contains("Delegate") || s.contains("com.clrg")) && s !in strings) strings.add(s)
            }
            cur.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) cur.append(c.toChar()) else flush()
            if (strings.size >= 30) break
        }
        flush()
        return if (strings.isEmpty()) null else "Native details:\n" + strings.joinToString("\n") { "  " + it.take(160) }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun stackText(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        // keep it readable on a TV: the first lines of each cause are what matter
        val lines = sw.toString().lines()
        val out = ArrayList<String>()
        var sinceHeader = 0
        for (l in lines) {
            val isHeader = !l.startsWith("\t") && !l.startsWith(" ")
            if (isHeader) sinceHeader = 0 else sinceHeader++
            if (sinceHeader <= 12) out.add(l.replace("\t", "  "))
            if (out.size > 60) break
        }
        return out.joinToString("\n")
    }

    private fun header(context: Context): String {
        val version = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else 0})"
        } catch (_: Throwable) { "?" }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "not reported"
        return "Christmas Living Room Games $version\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), chip: $soc\n"
    }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun write(name: String, text: String) {
        val d = dir ?: return
        File(d, name).writeText(text)
    }
}
