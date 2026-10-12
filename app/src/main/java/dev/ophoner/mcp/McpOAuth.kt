package dev.ophoner.mcp

import dev.ophoner.data.repository.SecureStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface McpSecrets {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}
internal class EncryptedMcpSecrets(private val storage: SecureStorage) : McpSecrets {
    override fun read(key: String) = storage.getString("mcp.$key")
    override fun write(key: String, value: String?) = storage.putStringDurably("mcp.$key", value)
}
internal class MemoryMcpSecrets : McpSecrets {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

data class McpOAuthReply(val redirectUri: String, val code: String, val state: String)
fun interface McpOAuthBrowser {
    suspend fun authorize(state: String, url: suspend (redirectUri: String) -> String): McpOAuthReply
}

/** Public-client authorization code flow. Secrets and callback URLs never enter the conversation. */
class McpOAuth(
    client: OkHttpClient,
    private val json: Json,
    private val secrets: McpSecrets,
    private val browser: McpOAuthBrowser,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503) response.newBuilder().header("Retry-After", "60").build() else response
        }.callTimeout(30, TimeUnit.SECONDS).build()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun lock(server: McpServer) = locks.getOrPut(server.name) { Mutex() }

    @Serializable private data class Credentials(
        val resource: String, val registrationId: String, val tokenEndpoint: String, val clientId: String,
        val accessToken: String, val refreshToken: String? = null, val expiresAt: Long,
    )
    private fun key(server: McpServer) = "oauth.${server.name}"
    private fun credentials(server: McpServer): Credentials? = secrets.read(key(server))?.let {
        runCatching { json.decodeFromString<Credentials>(it) }.getOrElse { error("Stored MCP credentials could not be read. Sign in again.") }.takeIf { c -> c.resource == server.url && c.registrationId == server.registrationId }
    }
    suspend fun forget(server: McpServer) = lock(server).withLock { if (credentials(server) != null) secrets.write(key(server), null) }
    suspend fun invalidate(server: McpServer) = lock(server).withLock {
        credentials(server)?.let { secrets.write(key(server), json.encodeToString(it.copy(accessToken = "", expiresAt = 0))) }
    }
    suspend fun token(server: McpServer): String? = lock(server).withLock {
        val old = credentials(server) ?: return@withLock null
        if (old.accessToken.isNotEmpty() && old.expiresAt > now() + 60_000) return@withLock old.accessToken
        val refresh = old.refreshToken ?: throw McpAuthorizationRequired()
        val response = request(old.tokenEndpoint, FormBody.Builder()
            .add("grant_type", "refresh_token").add("refresh_token", refresh)
            .add("client_id", old.clientId).add("resource", old.resource).build())
        if (response.first !in 200..299) {
            if (response.second.string("error") == "invalid_grant") secrets.write(key(server), null)
            throw IOException("MCP token refresh failed. Sign in again with mcp_manage authenticate.")
        }
        val next = tokens(response.second, server, old.tokenEndpoint, old.clientId, refresh)
        secrets.write(key(server), json.encodeToString(next))
        next.accessToken
    }

    suspend fun authenticate(server: McpServer) = lock(server).withLock {
        require(server.transport == "http") { "OAuth is for HTTP servers; stdio uses environment credentials." }
        // Obtain the advertised metadata location when present; never send existing credentials to discovery.
        val challenge = challenge(server.url)
        val advertised = oauthMetadataUrl(challenge) ?: server.authorizationMetadataUrl
        val resourceUrl = server.url.toHttpUrl()
        val candidates = if (advertised != null) listOf(advertised) else listOf(
            resourceUrl.newBuilder().encodedPath("/.well-known/oauth-protected-resource" + resourceUrl.encodedPath.trimEnd('/')).query(null).build().toString(),
            resourceUrl.newBuilder().encodedPath("/.well-known/oauth-protected-resource").query(null).build().toString(),
        ).distinct()
        val resource = discover(candidates)
        require(resource.string("resource")?.let { validateMcpUrl(it) } == server.url) { "OAuth metadata resource does not match this MCP endpoint." }
        val issuer = (resource["authorization_servers"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content
            ?: error("MCP resource metadata omitted its authorization server.")
        val issuerUrl = secureUrl(issuer).toHttpUrl()
        require(issuerUrl.query == null) { "OAuth issuer must not contain a query." }
        val issuerPath = issuerUrl.encodedPath.trimEnd('/')
        val metadata = discover(listOf(
            issuerUrl.newBuilder().encodedPath("/.well-known/oauth-authorization-server$issuerPath").build().toString(),
            issuerUrl.newBuilder().encodedPath("/.well-known/openid-configuration$issuerPath").build().toString(),
            issuerUrl.newBuilder().encodedPath("$issuerPath/.well-known/openid-configuration").build().toString(),
        ).distinct())
        require(metadata.string("issuer")?.let { secureUrl(it) } == issuerUrl.toString()) { "OAuth issuer metadata mismatch." }
        require((metadata["code_challenge_methods_supported"] as? JsonArray)?.contains(JsonPrimitive("S256")) == true) { "OAuth server must advertise PKCE S256." }
        val authorization = secureUrl(metadata.string("authorization_endpoint") ?: error("Missing authorization endpoint"))
        val endpoint = secureUrl(metadata.string("token_endpoint") ?: error("Missing token endpoint"))
        val state = random()
        val verifier = random()
        val challengeValue = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val scope = server.scope ?: Regex("(?:^|[,\\s])scope=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(challenge.orEmpty())?.groupValues?.get(1)
            ?: (resource["scopes_supported"] as? JsonArray)?.joinToString(" ") { it.jsonPrimitive.content }
        // Registration needs the listener's exact redirect URI. Run it before opening the browser.
        var clientId = server.clientId
        var redirect: String? = null
        val registration = metadata.string("registration_endpoint")
        val reply = browser.authorize(state) { redirectUri ->
            if (clientId == null) {
                val registrationUrl = registration ?: error("This OAuth server requires a pre-registered public client_id.")
                val registered = request(registrationUrl, buildJsonObject {
                    put("client_name", "Ophoner"); put("token_endpoint_auth_method", "none")
                    putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
                    putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
                    putJsonArray("response_types") { add(JsonPrimitive("code")) }
                }.toString().toRequestBody("application/json".toMediaType()))
                require(registered.first in 200..299 && registered.second.string("token_endpoint_auth_method") in listOf(null, "none")) { "OAuth public-client registration failed." }
                clientId = registered.second.string("client_id") ?: error("Registration omitted client_id.")
            }
            require(checkNotNull(clientId).length in 1..2048) { "Invalid OAuth client ID." }
            redirect = redirectUri
            authorization.toHttpUrl().newBuilder().apply {
                    listOf("response_type", "client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "resource", "scope").forEach { removeAllQueryParameters(it) }
                }
                .addQueryParameter("response_type", "code").addQueryParameter("client_id", checkNotNull(clientId))
                .addQueryParameter("redirect_uri", redirectUri).addQueryParameter("state", state)
                .addQueryParameter("code_challenge", challengeValue).addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("resource", server.url).apply { scope?.let { addQueryParameter("scope", it) } }.build().toString()
        }
        require(reply.state == state && reply.redirectUri == redirect && reply.code.isNotBlank()) { "OAuth callback did not match the pending sign-in." }
        val id = checkNotNull(clientId)
        val response = request(endpoint, FormBody.Builder().add("grant_type", "authorization_code")
            .add("code", reply.code).add("client_id", id).add("redirect_uri", reply.redirectUri)
            .add("code_verifier", verifier).add("resource", server.url).build())
        require(response.first in 200..299) { "OAuth code exchange failed. Start sign-in again." }
        secrets.write(key(server), json.encodeToString(tokens(response.second, server, endpoint, id, null)))
    }

    private fun tokens(value: JsonObject, server: McpServer, endpoint: String, clientId: String, previousRefresh: String?): Credentials {
        require(value.string("token_type").equals("Bearer", true)) { "OAuth server did not issue a Bearer token." }
        val access = value.string("access_token") ?: error("OAuth response omitted access token.")
        require(access.isNotBlank() && access.length <= 16_384 && access.all { it.code in 0x21..0x7e }) { "Invalid OAuth access token." }
        val seconds = (value["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
        require(seconds in 1..31_536_000) { "Invalid OAuth token expiry." }
        val refresh = value.string("refresh_token") ?: previousRefresh
        require(refresh == null || refresh.length <= 16_384) { "Invalid refresh token." }
        return Credentials(server.url, server.registrationId, endpoint, clientId, access, refresh, now() + seconds * 1000)
    }
    private suspend fun discover(urls: List<String>): JsonObject {
        for (url in urls) {
            val response = request(url)
            if (response.first in 200..299) return response.second
            require(response.first == 404 || response.first == 405) { "OAuth metadata discovery failed (HTTP ${response.first})." }
        }
        error("OAuth metadata not found. This server must publish protected-resource and authorization-server metadata.")
    }
    private fun secureUrl(url: String): String = validateMcpUrl(url).also { require(it.toHttpUrl().isHttps) { "OAuth endpoints must use HTTPS." } }
    private suspend fun request(url: String, body: RequestBody? = null): Pair<Int, JsonObject> = exchange(
        Request.Builder().url(secureUrl(url)).header("Accept", "application/json").apply { body?.let { post(it) } }.build(),
    ) { response ->
        val source = response.body.source()
        require(!source.request(131_073)) { "OAuth response exceeds size limit." }
        val raw = source.readUtf8()
        response.code to (runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
    }
    private suspend fun challenge(url: String): String? = exchange(Request.Builder().url(validateMcpUrl(url)).get().build()) { it.header("WWW-Authenticate") }
    private suspend fun <T> exchange(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(IOException("OAuth network request failed.", e)) }
            override fun onResponse(call: Call, response: Response) {
                try { continuation.resume(response.use(read)) } catch (e: Exception) { continuation.resumeWithException(e) }
            }
        })
    }
    private fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}

internal fun oauthMetadataUrl(challenge: String?): String? =
    Regex("(?:^|[,\\s])resource_metadata=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(challenge.orEmpty())?.groupValues?.get(1)
