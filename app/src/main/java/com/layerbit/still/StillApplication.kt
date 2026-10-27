package com.layerbit.still

import android.app.Application
import com.layerbit.still.files.CrashLog
import com.layerbit.still.files.Exporter
import com.layerbit.still.files.Handoff
import com.layerbit.still.files.ImageStore
import com.layerbit.still.files.Workspace
import com.layerbit.still.image.Codecs
import com.layerbit.still.image.Renderer
import com.layerbit.still.jobs.JobRunner
import com.layerbit.still.prefs.Prompts
import com.layerbit.still.prefs.StillSettings
import com.layerbit.still.text.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app's object graph, assembled by hand. Nine singletons, none with a complicated
 * lifetime; a dependency-injection framework would add a build step to solve a problem that
 * has not appeared.
 */
class StillApplication : Application() {

    val renderer: Renderer by lazy { Renderer() }
    val codecs: Codecs by lazy { Codecs(renderer) }
    val store: ImageStore by lazy { ImageStore(this, renderer) }
    val workspace: Workspace by lazy { Workspace(this) }
    val exporter: Exporter by lazy { Exporter(this) }
    val handoff: Handoff by lazy { Handoff() }
    val jobRunner: JobRunner by lazy { JobRunner(this) }
    val settings: StillSettings by lazy { StillSettings(this) }
    val prompts: Prompts by lazy { Prompts(this) }

    /** For the handful of jobs that outlive any screen: the startup trim, a batch. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is written down where Settings can offer it.
        CrashLog.install(this)
        Strings.install(this)
        // Bounded scratch, at startup rather than on a timer, so there is no background job.
        applicationScope.launch { workspace.trimTo(Workspace.DEFAULT_LIMIT_BYTES) }
    }
}
