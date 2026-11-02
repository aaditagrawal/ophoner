package dev.ophoner.agent

/**
 * Groups a model turn into bounded read batches and single mutations.
 *
 * Independent reads overlap up to [maxParallel]. Mutations stay alone so their
 * effects stay in call order. The cap is kept at the measured bound: four
 * concurrent reads already hide individual latency, and a larger fan-out waits
 * on the same content provider or network. Raising it needs a new measurement.
 */
internal data class ToolBatch<T>(val calls: List<T>, val parallel: Boolean)

internal fun <T> planToolBatches(
    calls: List<T>,
    maxParallel: Int,
    parallelSafe: (T) -> Boolean,
): List<ToolBatch<T>> {
    val width = maxParallel.coerceAtLeast(1)
    val batches = ArrayList<ToolBatch<T>>()
    var index = 0
    while (index < calls.size) {
        if (parallelSafe(calls[index])) {
            val group = ArrayList<T>(width)
            while (index < calls.size && group.size < width && parallelSafe(calls[index])) {
                group.add(calls[index])
                index++
            }
            batches.add(ToolBatch(group, parallel = true))
        } else {
            batches.add(ToolBatch(listOf(calls[index]), parallel = false))
            index++
        }
    }
    return batches
}

/** Fixed-size trace of recent tool latencies. Tests read it; production does not branch on it. */
internal object ToolBatchTiming {
    data class Sample(val name: String, val nanos: Long, val batchSize: Int)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    fun record(name: String, nanos: Long, batchSize: Int) {
        synchronized(lock) {
            if (samples.size == CAPACITY) samples.removeFirst()
            samples.addLast(Sample(name, nanos, batchSize))
        }
    }

    fun snapshot(): List<Sample> = synchronized(lock) { samples.toList() }

    fun clear() {
        synchronized(lock) { samples.clear() }
    }

    private const val CAPACITY = 64
}
