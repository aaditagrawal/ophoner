package dev.ophoner.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.source
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ephemeral loopback redirect listener, closed on timeout, cancellation, and success. */
internal class LoopbackMcpOAuthBrowser(private val open: suspend (String) -> Unit) : McpOAuthBrowser {
    constructor(context: Context) : this({ url ->
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    })
    override suspend fun authorize(state: String, url: suspend (String) -> String): McpOAuthReply = withTimeout(300_000) {
        val listener = withContext(Dispatchers.IO) { ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) }
        try {
            val redirect = "http://127.0.0.1:${listener.localPort}/mcp/oauth/callback"
            val authorization = url(redirect)
            open(authorization)
            awaitCallback(listener, redirect, state)
        } finally { listener.close() }
    }
    private suspend fun awaitCallback(listener: ServerSocket, redirect: String, state: String): McpOAuthReply = suspendCancellableCoroutine { continuation ->
        val current = AtomicReference<Socket?>()
        continuation.invokeOnCancellation { runCatching { listener.close() }; runCatching { current.get()?.close() } }
        workers.execute {
            try {
                while (continuation.isActive) {
                    val socket = listener.accept()
                    current.set(socket)
                    if (!continuation.isActive) { socket.close(); break }
                    socket.use {
                        it.soTimeout = 3000
                        try {
                            val source = it.getInputStream().source().buffer()
                            val line = source.readUtf8LineStrict(8192).split(' ')
                            val target = line.getOrNull(1).orEmpty()
                            val callback = if (target.startsWith("/") && !target.startsWith("//")) "http://127.0.0.1:${listener.localPort}$target".toHttpUrl() else null
                            val valid = line.firstOrNull() == "GET" && callback?.encodedPath == "/mcp/oauth/callback" &&
                                callback.queryParameterValues("state") == listOf(state)
                            val code = callback?.queryParameterValues("code")?.singleOrNull()
                            val denied = callback?.queryParameter("error") != null
                            val success = valid && !denied && !code.isNullOrBlank() && code.length <= 8192
                            val text = if (success) "Sign-in received. Return to Ophoner." else "Sign-in could not be completed. Return to Ophoner."
                            val status = if (success) "200 OK" else "400 Bad Request"
                            it.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${text.toByteArray().size}\r\n\r\n$text").toByteArray())
                            if (valid && denied) throw OAuthDenied()
                            if (success) { continuation.resume(McpOAuthReply(redirect, checkNotNull(code), state)); return@execute }
                        } catch (e: OAuthDenied) { throw e } catch (_: IOException) { /* Ignore unrelated loopback probes. */ }
                    }
                }
            } catch (e: Exception) { continuation.resumeWithException(e) }
        }
    }
    private class OAuthDenied : IOException("OAuth sign-in was denied. No credentials were saved.")
    companion object {
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "mcp-oauth-callback").apply { isDaemon = true } }
    }
}
