package com.layerbit.still.jobs

import com.layerbit.still.R
import com.layerbit.still.StillApplication
import com.layerbit.still.files.StillFile
import com.layerbit.still.ops.Op
import com.layerbit.still.ops.OpCancelled
import com.layerbit.still.ops.OpContext
import com.layerbit.still.ops.OpOutcome
import com.layerbit.still.ops.ToolId
import com.layerbit.still.text.Strings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the UI knows about a running or finished operation. Lives in [JobRunner], which is
 * app-scoped, so a batch survives the screen that started it and a rotation mid-way.
 */
sealed interface JobState {
    data object Idle : JobState

    data class Running(val tool: ToolId, val opTitle: String, val unitsDone: Int, val unitsTotal: Int, val label: String) : JobState {
        val fraction: Float get() = if (unitsTotal <= 0) 0f else (unitsDone.toFloat() / unitsTotal).coerceIn(0f, 1f)
    }

    data class Finished(val tool: ToolId, val opTitle: String, val outcomes: List<OpOutcome>) : JobState {
        val produced get() = outcomes.filterIsInstance<OpOutcome.Produced>()
        val failed get() = outcomes.filterIsInstance<OpOutcome.Failed>()
        val skipped get() = outcomes.filterIsInstance<OpOutcome.Skipped>()
        val allSucceeded get() = failed.isEmpty() && skipped.isEmpty()
    }

    data class Cancelled(val tool: ToolId) : JobState

    /** The job itself could not run, as opposed to pictures failing inside it. */
    data class Crashed(val tool: ToolId, val reason: String) : JobState
}

fun JobState.belongsTo(tool: ToolId?): Boolean = when (this) {
    is JobState.Running -> this.tool == tool
    is JobState.Finished -> this.tool == tool
    is JobState.Cancelled -> this.tool == tool
    is JobState.Crashed -> this.tool == tool
    JobState.Idle -> true
}

/**
 * Runs one operation at a time on the application scope and reports how it is going.
 *
 * In-process, with [BatchService] holding the app in the foreground while a job runs: the
 * shade shows "4 of 12" with a Cancel, and the batch keeps going while the user looks away.
 * The notification permission is asked once, before a batch of several pictures, never on the
 * first open; refused, the batch runs exactly as before, only without the shade.
 */
class JobRunner(private val app: StillApplication) {

    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    private var current: Job? = null
    @Volatile private var cancelled = false

    val isRunning: Boolean get() = _state.value is JobState.Running

    /** Starts [op] over [inputs]; refused with false while another job runs. */
    fun submit(op: Op, inputs: List<StillFile>): Boolean {
        if (isRunning) return false
        cancelled = false
        _state.value = JobState.Running(op.tool, op.title, 0, 0, "")
        BatchService.start(app)
        val context = OpContext(app.workspace, app.renderer, app.codecs) { cancelled }
        current = app.applicationScope.launch {
            try {
                val outcomes = op.run(inputs, context) { p ->
                    _state.value = JobState.Running(op.tool, op.title, p.unitsDone, p.unitsTotal, p.label)
                }
                _state.value = if (cancelled) JobState.Cancelled(op.tool) else JobState.Finished(op.tool, op.title, outcomes)
                if (!cancelled && outcomes.isNotEmpty() && outcomes.all { it is OpOutcome.Produced }) app.prompts.successfulJobs += 1
            } catch (_: OpCancelled) {
                _state.value = JobState.Cancelled(op.tool)
            } catch (e: CancellationException) {
                _state.value = JobState.Cancelled(op.tool)
                throw e
            } catch (t: Throwable) {
                val reason = if (t is OutOfMemoryError) Strings.get(R.string.engine_out_of_memory) else (t.message ?: Strings.get(R.string.app_job_stopped))
                _state.value = JobState.Crashed(op.tool, reason)
            }
        }
        return true
    }

    fun cancel() {
        cancelled = true
    }

    /** Clears a finished result so a screen returning starts clean. */
    fun acknowledge() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }
}
