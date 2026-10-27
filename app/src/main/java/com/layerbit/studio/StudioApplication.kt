package com.layerbit.studio

import android.app.Application
import com.layerbit.studio.files.CrashLog
import com.layerbit.studio.files.Exporter
import com.layerbit.studio.files.Handoff
import com.layerbit.studio.files.ImageStore
import com.layerbit.studio.files.Workspace
import com.layerbit.studio.image.Codecs
import com.layerbit.studio.image.Renderer
import com.layerbit.studio.jobs.JobRunner
import com.layerbit.studio.prefs.Prompts
import com.layerbit.studio.prefs.StudioSettings
import com.layerbit.studio.text.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app's object graph, assembled by hand. Nine singletons, none with a complicated
 * lifetime; a dependency-injection framework would add a build step to solve a problem that
 * has not appeared.
 */
class StudioApplication : Application() {

    val renderer: Renderer by lazy { Renderer() }
    val codecs: Codecs by lazy { Codecs(renderer) }
    val store: ImageStore by lazy { ImageStore(this, renderer) }
    val workspace: Workspace by lazy { Workspace(this) }
    val exporter: Exporter by lazy { Exporter(this) }
    val handoff: Handoff by lazy { Handoff() }
    val jobRunner: JobRunner by lazy { JobRunner(this) }
    val settings: StudioSettings by lazy { StudioSettings(this) }
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
