package colourfilm.core

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Small fixed pool used to split per-pixel work across CPU cores. */
class Parallel(val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)) : AutoCloseable {
    private val pool: ExecutorService? =
        if (threads > 1) Executors.newFixedThreadPool(threads) { r -> Thread(r, "cf-worker").apply { isDaemon = true } } else null

    /** Runs [body] over row ranges [start, end) covering 0 until [rows]. */
    fun rows(rows: Int, body: (start: Int, end: Int) -> Unit) {
        val p = pool
        if (p == null || rows < 32) { body(0, rows); return }
        val chunks = threads * 2
        val step = (rows + chunks - 1) / chunks
        val tasks = (0 until chunks).mapNotNull { c ->
            val s = c * step
            val e = minOf(rows, s + step)
            if (s < e) Callable { body(s, e) } else null
        }
        p.invokeAll(tasks).forEach { it.get() }
    }

    override fun close() { pool?.shutdownNow() }
}
