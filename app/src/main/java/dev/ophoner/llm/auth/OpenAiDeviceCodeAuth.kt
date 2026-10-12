package dev.ophoner.llm.auth

import java.util.Base64
import dev.ophoner.data.model.OAuthTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import java.net.UnknownHostException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext

data class DeviceLoginSession(
    val userCode: String,
    val verificationUrl: String,
    val deviceAuthId: String,
    val intervalSec: Int,
    val expiresAtEpochMs: Long = System.currentTimeMillis() + 15 * 60 * 1000L,
)

@Singleton
class OpenAiDeviceCodeAuth @Inject constructor(
    httpClient: OkHttpClient,
) {
    private val httpClient = httpClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun startDeviceLogin(onNetworkStatus: (String?) -> Unit = {}): DeviceLoginSession =
        withTimeoutOrNull(START_WAIT_MS) {
            retryLoginRequest(onNetworkStatus) { requestDeviceLogin() }
        } ?: throw IllegalStateException(
            "Could not reach auth.openai.com after two minutes. Check your internet connection, VPN or Private DNS, then try again.",
        )

    private suspend fun requestDeviceLogin(): DeviceLoginSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("client_id", CLIENT_ID) }
        val request = Request.Builder()
            .url("$ISSUER/api/accounts/deviceauth/usercode")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        execute(request).use { response ->
            response.checkTemporaryFailure()
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    if (response.code == 404) {
                        "Enable device code login in ChatGPT Settings > Security, or ask your workspace admin to enable it."
                    } else {
                        "Could not start ChatGPT login (HTTP ${response.code}). Please try again."
                    },
                )
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            val deviceAuthId = obj.string("device_auth_id")
                ?: throw IllegalStateException("Device code response missing device_auth_id")
            val userCode = obj.string("user_code") ?: obj.string("usercode")
                ?: throw IllegalStateException("Device code response missing user_code")
            val intervalSec = obj.intLike("interval")?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_SEC
            DeviceLoginSession(
                userCode = userCode,
                verificationUrl = "$ISSUER/codex/device",
                deviceAuthId = deviceAuthId,
                intervalSec = intervalSec,
                expiresAtEpochMs = System.currentTimeMillis() +
                    (obj.longLike("expires_in")?.takeIf { it > 0 }?.coerceAtMost(86_400) ?: 900) * 1000L,
            )
        }
    }

    suspend fun awaitDeviceLogin(
        session: DeviceLoginSession,
        onNetworkStatus: (String?) -> Unit = {},
    ): OAuthTokens {
        val remaining = session.expiresAtEpochMs - System.currentTimeMillis()
        return withTimeoutOrNull(remaining.coerceAtLeast(1)) {
            if (remaining <= 0) return@withTimeoutOrNull null
            val grant = pollForAuthorization(session, onNetworkStatus)
            retryLoginRequest(onNetworkStatus) { exchangeAuthorizationCode(grant) }
        } ?: throw IllegalStateException("This login code expired. Try again to get a new code.")
    }

    suspend fun refresh(tokens: OAuthTokens): OAuthTokens = withContext(Dispatchers.IO) {
        require(tokens.refreshToken.isNotBlank()) { "Missing refresh_token" }
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", tokens.refreshToken)
        }
        val request = Request.Builder()
            .url("$ISSUER/oauth/token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        execute(request).use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    if (response.code == 400 || response.code == 401) {
                        "Your ChatGPT session expired. Log in with ChatGPT again in Settings."
                    } else {
                        "Could not refresh ChatGPT login (HTTP ${response.code}). Please try again."
                    },
                )
            }
            parseTokenResponse(raw, previous = tokens)
        }
    }

    private suspend fun pollForAuthorization(
        session: DeviceLoginSession,
        onNetworkStatus: (String?) -> Unit,
    ): AuthorizationGrant {
        val pollBody = buildJsonObject {
            put("device_auth_id", session.deviceAuthId)
            put("user_code", session.userCode)
        }.toString()

        while (true) {
            coroutineContext.ensureActive()
            val grant = retryLoginRequest(onNetworkStatus, session.intervalSec.coerceAtLeast(1) * 1000L) {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$ISSUER/api/accounts/deviceauth/token")
                        .header("Content-Type", "application/json")
                        .post(pollBody.toRequestBody(JSON_MEDIA))
                        .build()
                    execute(request).use { response ->
                        response.checkTemporaryFailure()
                        val raw = response.body?.string().orEmpty()
                        when {
                            response.isSuccessful -> {
                                val obj = json.parseToJsonElement(raw).jsonObject
                                val code = obj.string("authorization_code")
                                    ?: throw IllegalStateException("Missing authorization_code")
                                val verifier = obj.string("code_verifier")
                                    ?: throw IllegalStateException("Missing code_verifier")
                                AuthorizationGrant(code, verifier)
                            }
                            response.code == 403 || response.code == 404 -> null
                            else -> throw IllegalStateException(
                                "ChatGPT authorization failed (HTTP ${response.code}). Please try again.",
                            )
                        }
                    }
                }
            }
            if (grant != null) return grant

            coroutineContext.ensureActive()
            delay(session.intervalSec.coerceAtLeast(1) * 1000L)
        }
    }

    private suspend fun exchangeAuthorizationCode(grant: AuthorizationGrant): OAuthTokens =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", grant.authorizationCode)
                .add("redirect_uri", "$ISSUER/deviceauth/callback")
                .add("client_id", CLIENT_ID)
                .add("code_verifier", grant.codeVerifier)
                .build()
            val request = Request.Builder()
                .url("$ISSUER/oauth/token")
                .post(form)
                .build()

            execute(request).use { response ->
                response.checkTemporaryFailure()
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Could not finish ChatGPT login (HTTP ${response.code}). Please try again.",
                    )
                }
                parseTokenResponse(raw, previous = null)
            }
        }

    private fun parseTokenResponse(raw: String, previous: OAuthTokens?): OAuthTokens {
        val obj = json.parseToJsonElement(raw).jsonObject
        val accessToken = obj.string("access_token")
            ?: throw IllegalStateException("Token response missing access_token")
        val refreshToken = obj.string("refresh_token") ?: previous?.refreshToken.orEmpty()
        val idToken = obj.string("id_token") ?: previous?.idToken.orEmpty()
        val expiresIn = obj.longLike("expires_in")
        val expiresAt = when {
            expiresIn != null && expiresIn > 0L -> System.currentTimeMillis() + expiresIn * 1000L
            else -> jwtExpEpochMs(accessToken) ?: jwtExpEpochMs(idToken) ?: 0L
        }
        val accountId = extractAccountId(idToken).ifBlank { extractAccountId(accessToken) }
            .ifBlank { previous?.accountId.orEmpty() }
        check(accountId.isNotBlank()) { "ChatGPT did not return an account. Please try signing in again." }
        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresAtEpochMs = expiresAt,
            accountId = accountId,
        )
    }

    /** Display metadata only; these claims are not used to authorize requests. */
    fun accountEmail(tokens: OAuthTokens): String? =
        sequenceOf(tokens.idToken, tokens.accessToken).mapNotNull { token ->
            val payload = decodeJwtPayload(token) ?: return@mapNotNull null
            val profile = payload["https://api.openai.com/profile"] as? JsonObject
            (payload["email"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: (profile?.get("email") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
        }.firstOrNull()

    private fun extractAccountId(jwt: String): String {
        val payload = decodeJwtPayload(jwt) ?: return ""
        val auth = payload["https://api.openai.com/auth"] as? JsonObject
        return auth?.string("chatgpt_account_id").orEmpty()
            .ifBlank { payload.string("chatgpt_account_id").orEmpty() }
    }

    private fun jwtExpEpochMs(jwt: String): Long? {
        val payload = decodeJwtPayload(jwt) ?: return null
        val exp = payload["exp"]?.jsonPrimitive?.longOrNull ?: return null
        return exp * 1000L
    }

    private fun decodeJwtPayload(jwt: String): JsonObject? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val padded = parts[1].padBase64Url()
            val decoded = Base64.getUrlDecoder().decode(padded)
            json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    private fun String.padBase64Url(): String {
        val pad = (4 - length % 4) % 4
        return this + "=".repeat(pad)
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intLike(key: String): Int? {
        val el = this[key] ?: return null
        el.jsonPrimitive.intOrNull?.let { return it }
        return el.jsonPrimitive.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun JsonObject.longLike(key: String): Long? {
        val el = this[key] ?: return null
        el.jsonPrimitive.longOrNull?.let { return it }
        return el.jsonPrimitive.contentOrNull?.trim()?.toLongOrNull()
    }

    private data class AuthorizationGrant(
        val authorizationCode: String,
        val codeVerifier: String,
    )

    private class TemporaryLoginFailure(val retryAfterMs: Long?) : IOException()

    private fun Response.checkTemporaryFailure() {
        if (code == 429 || code in 500..599) {
            throw TemporaryLoginFailure(
                header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400)?.times(1000),
            )
        }
    }

    private suspend fun <T> retryLoginRequest(
        onNetworkStatus: (String?) -> Unit,
        minimumDelayMs: Long = 2_000,
        request: suspend () -> T,
    ): T {
        var backoffMs = minimumDelayMs
        while (true) {
            coroutineContext.ensureActive()
            try {
                return request().also { onNetworkStatus(null) }
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                // Certificate and protocol failures need intervention, not repeated requests.
                if (error is SSLException || error is ProtocolException) throw error
                onNetworkStatus(when (error) {
                    is UnknownHostException -> "Can't resolve auth.openai.com. Check your connection, VPN or Private DNS. Retrying automatically…"
                    is TemporaryLoginFailure -> "ChatGPT is temporarily unavailable. Retrying automatically…"
                    else -> "Connection interrupted. Retrying automatically…"
                })
                val retryAfterMs = (error as? TemporaryLoginFailure)?.retryAfterMs ?: 0L
                delay(maxOf(backoffMs, retryAfterMs))
                backoffMs = maxOf(minimumDelayMs, (backoffMs * 2).coerceAtMost(15_000))
            }
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ISSUER = "https://auth.openai.com"
        private const val DEFAULT_INTERVAL_SEC = 5
        private const val START_WAIT_MS = 2 * 60 * 1000L
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
