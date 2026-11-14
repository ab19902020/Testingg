package colourfilm.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import colourfilm.app.ColourFilmApp
import colourfilm.app.MainActivity
import colourfilm.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Foreground service that runs colourisation jobs one at a time, so processing continues with
 * the screen off or the app in the background. Uses the `mediaProcessing` foreground-service
 * type on Android 15+ and `dataSync` on older versions.
 */
class ColouriseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cf-job").apply { priority = Thread.NORM_PRIORITY } }
    private val queue = ConcurrentLinkedQueue<String>()
    @Volatile private var stop: JobRunner.StopReason? = null
    @Volatile private var busy = false
    private var notifyJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        startInForeground(buildNotification("Preparing…", null, true))
        notifyJob = scope.launch {
            RunBus.state.collectLatest { s ->
                if (s == null || !busy) return@collectLatest
                val pct = s.fraction?.let { (it * 100).toInt() }
                val text = buildString {
                    append(s.message)
                    s.etaMs?.let { append(" · about ${formatDuration(it)} left") }
                    s.thermal?.let { append(" · $it") }
                }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, pct, s.fraction == null))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_JOB)?.let { id ->
                if (id != RunBus.runningJobId && id !in queue) queue.add(id)
                pump()
            }
            ACTION_PAUSE -> stop = JobRunner.StopReason.PAUSE
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_JOB)
                if (id == null || id == RunBus.runningJobId) stop = JobRunner.StopReason.CANCEL
                else queue.remove(id)
            }
        }
        if (!busy && queue.isEmpty()) stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun pump() {
        if (busy) return
        val id = queue.poll() ?: run { stopSelfSafely(); return }
        busy = true
        stop = null
        RunBus.runningJobId = id
        acquireWakeLock()
        worker.execute {
            val app = application as ColourFilmApp
            val store = app.jobs
            val job = store.load(id)
            if (job != null) {
                try {
                    JobRunner(this, store, app.models) { stop }.run(job)
                    notifyDone("Your colour video is ready", job.name)
                } catch (e: StopException) {
                    val cur = store.load(id) ?: job
                    when (e.reason) {
                        JobRunner.StopReason.CANCEL -> {
                            store.delete(id)
                            RunBus.state.value = RunBus.state.value?.copy(phase = RunState.Phase.CANCELLED, message = "Cancelled")
                        }
                        else -> {
                            store.save(cur.copy(status = JobStatus.PAUSED))
                            RunBus.state.value = RunBus.state.value?.copy(phase = RunState.Phase.PAUSED,
                                message = if (e.reason == JobRunner.StopReason.SYSTEM_TIMEOUT)
                                    "Android stopped background processing after its daily time limit. Tap Resume to continue." else "Paused")
                        }
                    }
                } catch (t: Throwable) {
                    val msg = when (t) {
                        is JobFailure -> t.message
                        is OutOfMemoryError -> "The phone ran out of memory while processing."
                        is java.io.IOException -> if (t.message?.contains("ENOSPC") == true || t.message?.contains("No space") == true)
                            "Storage is full. Free some space and tap Resume." else "Storage error: ${t.message}"
                        else -> "Processing failed: ${t.javaClass.simpleName}: ${t.message}"
                    } ?: "Processing failed"
                    android.util.Log.e("ColouriseService", "job failed", t)
                    (store.load(id) ?: job).let { store.save(it.copy(status = JobStatus.FAILED, error = msg)) }
                    RunBus.state.value = RunState(id, job.name, RunState.Phase.FAILED, msg, null, 0, job.durationUs, null, null, null, null, null)
                    notifyDone("Colourisation stopped", msg)
                }
            }
            RunBus.runningJobId = null
            busy = false
            scope.launch {
                releaseWakeLock()
                if (queue.isEmpty()) stopSelfSafely() else pump()
            }
        }
    }

    /** Android 15+: mediaProcessing services have a daily time budget. Pause cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stop = JobRunner.StopReason.SYSTEM_TIMEOUT
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ColourFilm:processing").apply { setReferenceCounted(false) }
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() } }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, percent: Int?, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, ColouriseService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 2, Intent(this, ColouriseService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ColourFilm")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .setProgress(100, percent ?: 0, indeterminate || percent == null)
            .addAction(0, "Pause", pause)
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, n) }
    }

    override fun onDestroy() {
        stop = stop ?: JobRunner.StopReason.PAUSE
        notifyJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "colourfilm.START"
        const val ACTION_PAUSE = "colourfilm.PAUSE"
        const val ACTION_CANCEL = "colourfilm.CANCEL"
        const val EXTRA_JOB = "job"
        private const val CHANNEL = "processing"
        private const val CHANNEL_DONE = "finished"
        private const val NOTIF_ID = 42
        private const val NOTIF_DONE_ID = 43

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Colourising", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished videos", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun start(ctx: Context, jobId: String) {
            ctx.startForegroundService(Intent(ctx, ColouriseService::class.java).setAction(ACTION_START).putExtra(EXTRA_JOB, jobId))
        }

        fun send(ctx: Context, action: String, jobId: String? = null) {
            ctx.startService(Intent(ctx, ColouriseService::class.java).setAction(action).apply { jobId?.let { putExtra(EXTRA_JOB, it) } })
        }

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60} min"
                else -> "${s / 3600} h ${(s % 3600) / 60} min"
            }
        }
    }
}
