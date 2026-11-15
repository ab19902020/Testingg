package colourfilm.app

import android.app.Application
import colourfilm.app.model.ModelManager
import colourfilm.app.work.ColouriseService
import colourfilm.app.work.JobStore

class ColourFilmApp : Application() {
    lateinit var jobs: JobStore; private set
    lateinit var models: ModelManager; private set

    /** Report about the previous unexpected close, shown once on Home. */
    val lastCrash = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        jobs = JobStore(this)
        models = ModelManager(this)
        // The NPU test runs in its own short-lived process, which needs nothing below.
        if (getProcessName() != packageName) return
        CrashReporter.install(this)
        val report = CrashReporter.collect(this)
        // Only blame the AI chip when the app really crashed while it was in use.
        val npuCrash = colourfilm.app.model.ModelSessions.recoverFromNpuCrash(this, crashed = report != null)
        lastCrash.value = report?.let {
            if (npuCrash) "$it\nThe AI chip was in use, so ColourFilm has switched to the CPU on this phone.\n" else it
        }
        CrashReporter.breadcrumb("app start")
        // A fresh process means no job can be running: anything marked running was interrupted
        // (app killed, phone restarted, …) and is offered for resumption from its last segment.
        jobs.markOrphansInterrupted(null)
        ColouriseService.createChannel(this)
    }
}
