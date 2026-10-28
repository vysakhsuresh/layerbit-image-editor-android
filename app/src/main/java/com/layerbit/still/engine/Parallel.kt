package com.layerbit.still.engine

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Row bands across the cores, for the stages that touch every pixel independently of its
 * neighbours in other rows: the colour pipeline, each pass of a separable blur, the vignette,
 * the grain, the paper cleanup and the cut-out compositing. Plain JDK threads, so the engine
 * stays free of Android and the same code runs in the JVM tests, where [enabled] can be
 * switched off to prove the bands give the sequential answer pixel for pixel.
 *
 * A band that itself asks for bands runs them inline: the pool is never waited on from inside
 * the pool, so nothing can deadlock, and the work is already spread.
 */
object Parallel {
    /** Four is enough for a phone: beyond it, memory bandwidth is the limit, not arithmetic. */
    val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Tests flip this to compare against the sequential path; the app leaves it on. */
    @Volatile var enabled: Boolean = true

    private const val THREAD_PREFIX = "still-render-"
    private val counter = AtomicInteger()
    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(workers) { r ->
            Thread(r, THREAD_PREFIX + counter.incrementAndGet()).apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
        }
    }

    /** Runs [block] over [from, to) bands that together cover [0, count); sequentially under [minimum] rows. */
    fun bands(count: Int, minimum: Int = 192, block: (from: Int, to: Int) -> Unit) {
        if (count <= 0) return
        val inside = Thread.currentThread().name.startsWith(THREAD_PREFIX)
        if (!enabled || workers == 1 || count < minimum || inside) { block(0, count); return }
        val chunk = (count + workers - 1) / workers
        val tasks = ArrayList<Callable<Unit>>(workers)
        var from = 0
        while (from < count) {
            val f = from; val t = min(count, from + chunk)
            tasks.add(Callable { block(f, t) })
            from = t
        }
        val futures = pool.invokeAll(tasks)
        var failure: Throwable? = null
        for (future in futures) {
            try { future.get() } catch (e: ExecutionException) { if (failure == null) failure = e.cause ?: e }
        }
        failure?.let { throw it }
    }
}
