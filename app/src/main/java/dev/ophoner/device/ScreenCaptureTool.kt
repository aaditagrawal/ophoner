package dev.ophoner.device

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import dev.ophoner.tools.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureTool(private val access: DeviceAccess) : ToolExecutor {
    override val definition = deviceTool("screen_capture", "Capture the current display for visual reasoning. Requires enabled accessibility and screen images, Android 11+, and a model that accepts images. Protected windows cannot be captured. Images are sent to your selected model for this run, not saved to chat history.", listOf("capture"))
    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult = try {
        require(arguments.required("action") == "capture")
        access.requireEnabled(DeviceCapability.SCREEN)
        access.requireEnabled(DeviceCapability.ACCESSIBILITY)
        if (Build.VERSION.SDK_INT < 30) return ToolResult(toolUseId, "Screen capture requires Android 11+", true)
        val service = OphonerAccessibilityService.instance ?: error("Enable accessibility first")
        val bitmap = withContext(Dispatchers.Main) {
            service.overlay?.hideForCapture(true)
            try {
            delay(80) // Let the compositor remove our overlay before capturing the display.
            withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<Bitmap> { continuation ->
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("Cannot read screenshot")
                                val copy = try { hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("Cannot copy screenshot") } finally { hardware.recycle() }
                                if (continuation.isActive) continuation.resume(copy) { _, value, _ -> value.recycle() } else copy.recycle()
                            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                            finally { buffer.close() }
                        }
                        override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Screenshot unavailable (Android error $errorCode). Protected screens may block capture.")) }
                    })
                }
            } ?: error("Screenshot timed out. Try again after the screen settles.")
            } finally { service.overlay?.hideForCapture(false) }
        }
        withContext(Dispatchers.IO) {
            try {
                val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
                val imageWidth = (bitmap.width * scale).toInt()
                val imageHeight = (bitmap.height * scale).toInt()
                val resized = Bitmap.createScaledBitmap(bitmap, imageWidth, imageHeight, true)
                val bytes = ByteArrayOutputStream()
                try { check(resized.compress(Bitmap.CompressFormat.JPEG, 80, bytes)) } finally { if (resized !== bitmap) resized.recycle() }
                ToolResult(toolUseId, "Screenshot: original ${bitmap.width}x${bitmap.height}, attached image ${imageWidth}x$imageHeight. " +
                    "Convert image coordinates to screen pixels before gestures: x * ${bitmap.width} / $imageWidth, y * ${bitmap.height} / $imageHeight.",
                    images = listOf(ToolImage("image/jpeg", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))))
            } finally { bitmap.recycle() }
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { ToolResult(toolUseId, e.message ?: "Screenshot failed", true) }
}
