package dev.ophoner.tools.impl

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
    private val lock = Any()
    private var localeKey: String? = null
    private var apps: List<LaunchableApp>? = null

    fun invalidate() {
        synchronized(lock) {
            apps = null
            localeKey = null
        }
    }

    fun snapshot(): List<LaunchableApp> {
        val key = locale()
        synchronized(lock) {
            val cached = apps
            if (cached != null && localeKey == key) return cached
        }
        val loaded = load()
        synchronized(lock) {
            if (locale() == key) {
                apps = loaded
                localeKey = key
            } else {
                apps = null
                localeKey = null
            }
        }
        return loaded
    }
}
