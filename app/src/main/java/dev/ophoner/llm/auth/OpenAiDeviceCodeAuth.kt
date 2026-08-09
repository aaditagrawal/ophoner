package dev.ophoner.llm.auth

import android.util.Base64
import dev.ophoner.data.model.OAuthTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class DeviceLoginSession(
    val userCode: String,
    val verificationUrl: String,
    val deviceAuthId: String,
    val intervalSec: Int,
)

@Singleton
class OpenAiDeviceCodeAuth @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun startDeviceLogin(): DeviceLoginSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("client_id", CLIENT_ID) }
        val request = Request.Builder()
            .url("$ISSUER/api/accounts/deviceauth/usercode")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    if (response.code == 404) {
                        "Device-code login is not enabled for this Codex account"
                    } else {
                        "Device code request failed with HTTP ${response.code}: $raw"
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
            )
        }
    }

    suspend fun awaitDeviceLogin(session: DeviceLoginSession): OAuthTokens {
        val authorization = pollForAuthorization(session)
        return exchangeAuthorizationCode(authorization)
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

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Token refresh failed with HTTP ${response.code}: $raw")
            }
            parseTokenResponse(raw, fallbackRefreshToken = tokens.refreshToken)
        }
    }

    private suspend fun pollForAuthorization(session: DeviceLoginSession): AuthorizationGrant {
        val deadlineMs = System.currentTimeMillis() + MAX_WAIT_MS
        val pollBody = buildJsonObject {
            put("device_auth_id", session.deviceAuthId)
            put("user_code", session.userCode)
        }.toString()

        while (true) {
            coroutineContext.ensureActive()
            val grant = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$ISSUER/api/accounts/deviceauth/token")
                    .header("Content-Type", "application/json")
                    .post(pollBody.toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(request).execute().use { response ->
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
                            "Device authorization failed with HTTP ${response.code}: $raw",
                        )
                    }
                }
            }
            if (grant != null) return grant

            if (System.currentTimeMillis() >= deadlineMs) {
                throw IllegalStateException("Device-code login timed out after 15 minutes")
            }
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

            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Device-code token exchange failed with HTTP ${response.code}: $raw",
                    )
                }
                parseTokenResponse(raw, fallbackRefreshToken = null)
            }
        }

    private fun parseTokenResponse(raw: String, fallbackRefreshToken: String?): OAuthTokens {
        val obj = json.parseToJsonElement(raw).jsonObject
        val accessToken = obj.string("access_token")
            ?: throw IllegalStateException("Token response missing access_token")
        val refreshToken = obj.string("refresh_token") ?: fallbackRefreshToken.orEmpty()
        val idToken = obj.string("id_token").orEmpty()
        val expiresIn = obj.longLike("expires_in")
        val expiresAt = when {
            expiresIn != null && expiresIn > 0L -> System.currentTimeMillis() + expiresIn * 1000L
            else -> jwtExpEpochMs(accessToken) ?: jwtExpEpochMs(idToken) ?: 0L
        }
        val accountId = extractAccountId(idToken).ifBlank { extractAccountId(accessToken) }
        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresAtEpochMs = expiresAt,
            accountId = accountId,
        )
    }

    private fun extractAccountId(jwt: String): String {
        val payload = decodeJwtPayload(jwt) ?: return ""
        val auth = payload["https://api.openai.com/auth"]?.jsonObject
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
            val decoded = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
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

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ISSUER = "https://auth.openai.com"
        private const val DEFAULT_INTERVAL_SEC = 5
        private const val MAX_WAIT_MS = 15 * 60 * 1000L
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
