package colourfilm.app.model

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import colourfilm.app.ColourFilmApp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests the Qualcomm NPU in a separate process (":npuprobe"). If the vendor driver crashes,
 * hangs or misbehaves, only that process is lost and the app carries on with the CPU.
 */
object NpuProbe {
    data class Result(val usable: Boolean, val reason: String)

    private const val TIMEOUT_MIN = 6L
    fun resultFile(context: Context) = File(context.filesDir, "npu_probe_result.txt")

    fun run(context: Context): Result {
        val file = resultFile(context)
        file.delete()
        val gone = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
            override fun onServiceDisconnected(name: ComponentName?) { gone.countDown() }
            override fun onBindingDied(name: ComponentName?) { gone.countDown() }
            override fun onNullBinding(name: ComponentName?) { gone.countDown() }
        }
        val app = context.applicationContext
        val bound = try {
            app.bindService(Intent(app, NpuProbeService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { false }
        if (!bound) return Result(false, "AI chip test couldn't be started")
        try {
            // The probe process exits when finished, which fires onServiceDisconnected.
            val finished = gone.await(TIMEOUT_MIN, TimeUnit.MINUTES)
            val text = runCatching { file.readText() }.getOrNull()
            return when {
                text != null && text.startsWith("npu|") -> Result(true, text.substringAfter('|'))
                text != null && text.startsWith("cpu|") -> Result(false, text.substringAfter('|'))
                !finished -> Result(false, "AI chip test didn't finish within $TIMEOUT_MIN minutes")
                else -> Result(false, "AI chip test crashed, so the CPU is used instead")
            }
        } finally {
            runCatching { app.unbindService(conn) }
            // Make sure a hung test can't keep holding the AI chip while the CPU does the work.
            runCatching {
                app.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                    ?.filter { it.processName.endsWith(":npuprobe") }?.forEach { android.os.Process.killProcess(it.pid) }
            }
            file.delete()
        }
    }
}

class NpuProbeService : Service() {
    private var started = false

    override fun onBind(intent: Intent?): IBinder {
        if (!started) {
            started = true
            Thread({
                val out = NpuProbe.resultFile(this)
                try {
                    val models = (application as ColourFilmApp).models
                    val r = ModelSessions.probeNpuInThisProcess(this, models.modelFile.absolutePath)
                    val tmp = File(out.path + ".tmp"); tmp.writeText(r); tmp.renameTo(out)
                } catch (t: Throwable) {
                    Log.e("NpuProbe", "probe failed", t)
                    runCatching { out.writeText("cpu|AI chip test failed: ${t.message?.take(160)}") }
                } finally {
                    // Always end the process: releases the NPU and signals the app.
                    Process.killProcess(Process.myPid())
                }
            }, "npu-probe").start()
        }
        return Binder()
    }
}
