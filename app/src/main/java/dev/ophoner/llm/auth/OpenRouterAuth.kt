package dev.ophoner.llm.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OpenRouterKeyResult(
    val key: String,
    @SerialName("user_id") val userId: String? = null,
)

/**
 * OpenRouter OAuth PKCE (headless / paste-code friendly).
 *
 * Omitting [callback_url] makes OpenRouter show a code the user can paste back into the app.
 * @see <a href="https://openrouter.ai/docs/guides/overview/auth/oauth">OpenRouter OAuth</a>
 */
@Singleton
class OpenRouterAuth @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    fun buildAuthorizeUrl(pkce: PkcePair, keyLabel: String = "ophoner"): String =
        "https://openrouter.ai/auth".toHttpUrl().newBuilder()
            .addQueryParameter("code_challenge", pkce.codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("key_label", keyLabel)
            .build()
            .toString()

    suspend fun exchangeCode(code: String, codeVerifier: String): OpenRouterKeyResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("code", code.trim())
                put("code_verifier", codeVerifier)
                put("code_challenge_method", "S256")
            }.toString().toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url(EXCHANGE_URL)
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("OpenRouter key exchange failed: HTTP ${response.code}: $raw")
                }
                runCatching { json.decodeFromString<OpenRouterKeyResult>(raw) }
                    .getOrElse {
                        throw IOException("OpenRouter key exchange returned unexpected body: $raw", it)
                    }
                    .also { result ->
                        if (result.key.isBlank()) {
                            throw IOException("OpenRouter key exchange returned an empty key")
                        }
                    }
            }
        }

    private companion object {
        const val EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
