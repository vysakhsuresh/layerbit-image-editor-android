package com.layerbit.still.ops

import com.layerbit.still.files.StillFile
import com.layerbit.still.files.Workspace
import com.layerbit.still.image.Codecs
import com.layerbit.still.image.Renderer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Every batch tool, expressed as one shape:
 *
 *     List<StillFile> -> Op -> List<StillFile>
 *
 * Plural on both sides, as in Sheaf. Resize is one-to-one, a collage is many-to-one, and a
 * batch is the same operation handed more inputs - so "process fifty photos at once", which
 * the competition sells as the paid tier, is what the type already means. Resist any pull
 * towards a convenient `fun run(input: StillFile): StillFile`.
 */
interface Op {

    val tool: ToolId

    /** What this run is called, including its settings: "Under 50 KB". */
    val title: String

    val arity: Arity

    /**
     * Runs the operation. Implementations write every output through [OpContext.workspace] and
     * never modify an input. A failure for one picture is an [OpOutcome.Failed], not a throw, so
     * one unreadable file in a batch of forty does not discard the other thirty-nine.
     *
     * @throws OpCancelled when the job was cancelled, the one case where unwinding is right.
     */
    suspend fun run(inputs: List<StillFile>, context: OpContext, onProgress: (Progress) -> Unit): List<OpOutcome>

    sealed interface Arity {
        /** Each input on its own. Resize, convert, strip. */
        data object EachIndependently : Arity

        /** At least [min] inputs, consumed together. Collage. */
        data class Together(val min: Int) : Arity
    }
}

/** What an operation is given to work with. Assembled once per job. */
class OpContext(
    val workspace: Workspace,
    val renderer: Renderer,
    val codecs: Codecs,
    private val cancelled: () -> Boolean = { false }
) {
    val isCancelled: Boolean get() = cancelled()

    /** Throws if the job was cancelled. Call at each natural checkpoint. */
    fun checkCancelled() {
        if (isCancelled) throw OpCancelled()
    }

    val check: () -> Unit = { checkCancelled() }
}

sealed interface OpOutcome {
    /** @param detail what actually happened, in the user's terms: "4.2 MB to 48 KB, 1200 × 900". */
    data class Produced(val file: StillFile, val detail: String? = null, /** The input's bytes, when the step changed the size. */ val before: Long? = null) : OpOutcome
    data class Failed(val inputName: String, val reason: String) : OpOutcome
    data class Skipped(val inputName: String, val reason: String) : OpOutcome
}

data class Progress(val unitsDone: Int, val unitsTotal: Int, val label: String) {
    val fraction: Float get() = if (unitsTotal <= 0) 0f else (unitsDone.toFloat() / unitsTotal).coerceIn(0f, 1f)
}

/** Thrown out of [Op.run] when the user cancelled. A CancellationException, so coroutines treat it as one. */
class OpCancelled : CancellationException("Cancelled")

/**
 * Runs an op over its inputs one at a time with the shared bookkeeping: progress per file,
 * cancellation between files, and every exception from one file turned into a [OpOutcome.Failed]
 * for that file alone. Each op supplies only [one].
 */
abstract class PerFileOp : Op {
    override val arity: Op.Arity get() = Op.Arity.EachIndependently

    /** The outcome for one input. May throw; a throw becomes a Failed outcome for this file. */
    internal abstract suspend fun one(input: StillFile, context: OpContext): OpOutcome

    override suspend fun run(inputs: List<StillFile>, context: OpContext, onProgress: (Progress) -> Unit): List<OpOutcome> {
        val outcomes = ArrayList<OpOutcome>(inputs.size)
        for ((i, input) in inputs.withIndex()) {
            context.checkCancelled()
            onProgress(Progress(i, inputs.size, input.displayName))
            outcomes += try {
                one(input, context)
            } catch (e: OpCancelled) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                OpOutcome.Failed(input.displayName, com.layerbit.still.text.Strings.get(com.layerbit.still.R.string.engine_out_of_memory))
            } catch (e: Exception) {
                OpOutcome.Failed(input.displayName, e.message ?: com.layerbit.still.text.Strings.get(com.layerbit.still.R.string.engine_unreadable))
            }
        }
        onProgress(Progress(inputs.size, inputs.size, ""))
        return outcomes
    }
}
