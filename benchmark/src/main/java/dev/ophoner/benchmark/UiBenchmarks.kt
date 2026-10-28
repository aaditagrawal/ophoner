package dev.ophoner.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "dev.ophoner.benchmark"
private fun fixtureIntent(streaming: Boolean = false, solid: Boolean = false) = Intent().apply {
    component = ComponentName(PACKAGE, "dev.ophoner.PerformanceActivity")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    putExtra("streaming", streaming)
    putExtra("solid", solid)
}

private fun UiDevice.awaitText(text: String) = check(wait(Until.hasObject(By.text(text)), 15_000)) { "Missing $text" }
private fun UiDevice.openThread(title: String) {
    awaitText(title)
    findObject(By.text(title)).click()
    check(wait(Until.gone(By.text(title)), 5_000)) { "Conversation list did not close" }
    check(wait(Until.hasObject(By.textStartsWith("Perf message ")), 10_000)) { "History did not load" }
}
private fun UiDevice.chats() {
    val button = wait(Until.findObject(By.desc("Conversations").pkg(PACKAGE)), 5_000)
        ?: error("Chat controls unavailable; another app may have interrupted the benchmark")
    button.click()
    awaitText("Chats")
}
private fun UiDevice.scrollHistory() {
    repeat(5) { swipe(displayWidth / 2, displayHeight / 3, displayWidth / 2, displayHeight * 3 / 4, 20) }
}

@RunWith(AndroidJUnit4::class)
class UiBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun threadSwitching() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 1),
        setupBlock = { startActivityAndWait(fixtureIntent()); device.awaitText("Perf chat A") },
    ) {
        device.openThread("Perf chat A")
        device.chats()
        device.openThread("Perf chat B")
        device.chats()
    }

    @Test fun oldHistoryScroll() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 1),
        setupBlock = { startActivityAndWait(fixtureIntent()); device.openThread("Perf chat A") },
    ) { device.scrollHistory() }

    @Test fun projectExpansion() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 1),
        setupBlock = { startActivityAndWait(fixtureIntent()); device.awaitText("Perf project") },
    ) {
        device.findObject(By.text("Perf project")).click()
        check(device.wait(Until.hasObject(By.textStartsWith("Project chat")), 5_000))
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 30)
    }

    @Test fun streamingFrosted() = streaming(solid = false)
    @Test fun streamingSolid() = streaming(solid = true)
    private fun streaming(solid: Boolean) = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 1),
        setupBlock = { startActivityAndWait(fixtureIntent(streaming = true, solid = solid)); device.awaitText("Streaming benchmark") },
    ) { device.scrollHistory() }
}

@RunWith(AndroidJUnit4::class)
class BaselineProfiles {
    @get:Rule val baseline = BaselineProfileRule()

    @Test fun criticalJourneys() = baseline.collect(packageName = PACKAGE) {
        startActivityAndWait(Intent().apply {
            component = ComponentName(PACKAGE, "dev.ophoner.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        startActivityAndWait(fixtureIntent())
        device.openThread("Perf chat A")
        device.scrollHistory()
        device.chats()
        device.openThread("Perf chat B")
        device.chats()
        device.findObject(By.text("Perf project")).click()
    }
}

@RunWith(AndroidJUnit4::class)
class NavigationChecks {
    @Test fun restoresReadingPositionAndReleasesOldDestinations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am force-stop $PACKAGE")
        instrumentation.context.startActivity(fixtureIntent())
        device.openThread("Perf chat A")
        repeat(10) { device.scrollHistory() }
        val headers = device.findObjects(By.textStartsWith("Perf message ").pkg(PACKAGE)).map { it.text }.toSet()
        check(headers.isNotEmpty() && "Perf message 999" !in headers) { "Did not scroll into history" }
        device.chats()
        device.openThread("Perf chat B")
        device.chats()
        device.openThread("Perf chat A")
        val restored = device.findObjects(By.textStartsWith("Perf message ").pkg(PACKAGE)).map { it.text }.toSet()
        check(headers.intersect(restored).isNotEmpty()) { "Reading position lost: $headers -> $restored" }
        device.pressBack()
        check(device.wait(Until.gone(By.pkg(PACKAGE)), 5_000)) { "Old chat destinations remain on the back stack" }
    }
}
