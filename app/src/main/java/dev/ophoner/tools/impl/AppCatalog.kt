package dev.ophoner.tools.impl

import java.util.concurrent.CountDownLatch

/**
 * Launchable-app selection.
 *
 * Package-manager label lookups dominate this tool. The cache keeps one snapshot
 * and drops it on package or locale invalidation, which is the event that changes
 * labels. Filtering runs before sorting so excluded rows are not ordered.
 */
internal data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activity: String,
) {
    val sortKey: String = label.lowercase()

    fun matches(query: String): Boolean =
        sortKey.contains(query) || packageName.lowercase().contains(query)
}

internal data class AppListPage(val shown: List<LaunchableApp>, val total: Int)

internal fun selectLaunchableApps(apps: List<LaunchableApp>, query: String, limit: Int): AppListPage {
    val matched = if (query.isEmpty()) apps else apps.filter { it.matches(query) }
    val cap = limit.coerceAtLeast(0)
    val shown = when {
        cap == 0 -> emptyList()
        matched.size <= 1 -> matched.take(cap)
        else -> matched.sortedWith(compareBy({ it.sortKey }, { it.packageName }, { it.activity })).take(cap)
    }
    return AppListPage(shown, matched.size)
}

internal class LaunchableAppCache(
    private val locale: () -> String,
    private val load: () -> List<LaunchableApp>,
) {
    private class Flight(val generation: Int, val key: String) {
        val latch = CountDownLatch(1)
        var value: List<LaunchableApp>? = null
        var error: Throwable? = null
    }

    private val lock = Any()
    private var localeKey: String? = null
    private var apps: List<LaunchableApp>? = null
    private var generation = 0
    private var flight: Flight? = null

    fun invalidate() {
        synchronized(lock) {
            apps = null
            localeKey = null
            generation++
        }
    }

    fun snapshot(): List<LaunchableApp> {
        val key = locale()
        val mine: Flight
        val leader: Boolean
        synchronized(lock) {
            val cached = apps
            if (cached != null && localeKey == key) return cached
            val current = flight
            if (current != null && current.key == key && current.generation == generation) {
                mine = current
                leader = false
            } else {
                mine = Flight(generation, key).also { flight = it }
                leader = true
            }
        }
        if (!leader) {
            mine.latch.await()
            mine.error?.let { throw it }
            return checkNotNull(mine.value)
        }
        try {
            val loaded = load()
            synchronized(lock) {
                if (flight === mine) flight = null
                if (generation == mine.generation && locale() == key) {
                    apps = loaded
                    localeKey = key
                }
            }
            mine.value = loaded
            mine.latch.countDown()
            return loaded
        } catch (error: Throwable) {
            synchronized(lock) { if (flight === mine) flight = null }
            mine.error = error
            mine.latch.countDown()
            throw error
        }
    }
}
