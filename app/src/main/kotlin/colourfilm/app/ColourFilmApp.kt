package colourfilm.app

import android.app.Application
import colourfilm.app.model.ModelManager
import colourfilm.app.work.ColouriseService
import colourfilm.app.work.JobStore

class ColourFilmApp : Application() {
    lateinit var jobs: JobStore; private set
    lateinit var models: ModelManager; private set

    override fun onCreate() {
        super.onCreate()
        jobs = JobStore(this)
        models = ModelManager(this)
        // A fresh process means no job can be running: anything marked running was interrupted
        // (app killed, phone restarted, …) and is offered for resumption from its last segment.
        jobs.markOrphansInterrupted(null)
        ColouriseService.createChannel(this)
    }
}
