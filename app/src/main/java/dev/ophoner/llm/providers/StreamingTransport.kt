package dev.ophoner.llm.providers

import dev.ophoner.llm.LlmErrorKind
import dev.ophoner.llm.LlmResponseChunk
import java.io.BufferedReader
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Blocking socket reads run on IO; closing the collector immediately cancels the socket. */
internal fun streamResponse(
    client: OkHttpClient,
    request: suspend () -> Request,
    parse: suspend (BufferedReader, suspend (LlmResponseChunk) -> Unit) -> Unit,
): Flow<LlmResponseChunk> = callbackFlow {
    val activeCall = AtomicReference<Call?>()
    val worker = launch(Dispatchers.IO) {
        try {
            val call = client.newCall(request())
            activeCall.set(call)
            if (isClosedForSend) call.cancel()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    // Avoid copying untrusted HTML, echoed credentials, or huge bodies into chat.
                    val errorText = response.peekBody(8192).string().lowercase()
                    val contextLimit = errorText.contains("context_length_exceeded") ||
                        errorText.contains("prompt is too long") || errorText.contains("maximum context length")
                    send(LlmResponseChunk.Error(
                        if (contextLimit) "Provider context limit exceeded" else "Provider returned HTTP ${response.code}",
                        when {
                            contextLimit -> LlmErrorKind.CONTEXT_LIMIT
                            response.code in setOf(401, 403) -> LlmErrorKind.AUTHENTICATION
                            else -> LlmErrorKind.HTTP
                        },
                        response.code == 408 || response.code == 429 || response.code in 500..599,
                        retryAfterMillis(response.header("Retry-After")),
                    ))
                } else {
                    val body = response.body ?: throw StreamProtocolException("Empty response body")
                    body.charStream().buffered().use { reader -> parse(reader) { send(it) } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            send(LlmResponseChunk.Error("Network error: ${e.message ?: "connection interrupted"}", LlmErrorKind.NETWORK, true))
        } catch (e: Exception) {
            send(LlmResponseChunk.Error(e.message ?: "Invalid provider response"))
        } finally {
            close()
        }
    }
    awaitClose {
        activeCall.get()?.cancel()
        worker.cancel()
    }
}

internal class StreamInterruptedException(message: String) : IOException(message)

internal class StreamProtocolException(message: String) : IllegalStateException(message)

internal fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (value == null) return null
    value.toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
    return runCatching {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
            .coerceAtLeast(0)
    }.getOrNull()
}

/** SSE data may span lines; comments and event names are not JSON payloads. */
internal suspend fun readSse(
    reader: BufferedReader,
    consume: suspend (String) -> Boolean,
) {
    val data = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                if (!consume(data.toString().removeSuffix("\n"))) return
                data.setLength(0)
            }
        } else if (line.startsWith("data:")) {
            data.append(line.substring(5).removePrefix(" ")).append('\n')
            if (data.length > 4 * 1024 * 1024) throw StreamProtocolException("Provider event exceeded 4 MiB")
        }
    }
    // EOF is not an SSE event delimiter. The provider parser reports missing completion.
}
