package dev.ophoner.tools.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogTest {
    private val apps = listOf(
        LaunchableApp("Zeta", "com.zeta", "Main"),
        LaunchableApp("Alpha", "com.alpha", "Main"),
        LaunchableApp("Alpine", "com.alpine", "Main"),
    )

    @Test fun filterRunsBeforeSortAndKeepsMatchOrder() {
        val page = selectLaunchableApps(apps, query = "alp", limit = 10)
        assertEquals(listOf("Alpha", "Alpine"), page.shown.map { it.label })
        assertEquals(2, page.total)
        assertEquals(listOf("Alpha"), selectLaunchableApps(apps, "alp", limit = 1).shown.map { it.label })
    }

    @Test fun cacheReloadsWhenLocaleChangesOrItIsInvalidated() {
        var locale = "en"
        var loads = 0
        val cache = LaunchableAppCache(locale = { locale }, load = {
            loads++
            apps
        })
        assertEquals(3, cache.snapshot().size)
        assertEquals(3, cache.snapshot().size)
        assertEquals(1, loads)
        locale = "fr"
        assertEquals(3, cache.snapshot().size)
        assertEquals(2, loads)
        cache.invalidate()
        cache.snapshot()
        assertEquals(3, loads)
    }
}
