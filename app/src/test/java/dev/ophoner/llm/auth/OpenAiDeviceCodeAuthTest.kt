package dev.ophoner.llm.auth

import dev.ophoner.data.model.OAuthTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class OpenAiDeviceCodeAuthTest {
    @Test fun `account email uses ID token then access token profile and tolerates missing claims`() {
        fun jwt(payload: String) = "header." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray()) + ".signature"
        val auth = OpenAiDeviceCodeAuth(client { error("Email display must not make a network request") })
        val access = jwt("""{"https://api.openai.com/profile":{"email":"fallback@example.com"}}""")
        assertEquals("person@example.com", auth.accountEmail(OAuthTokens(access,
            idToken = jwt("""{"email":"person@example.com"}"""))))
        assertEquals("fallback@example.com", auth.accountEmail(OAuthTokens(access, idToken = "malformed")))
        assertNull(auth.accountEmail(OAuthTokens("malformed", idToken = jwt("""{"email":{}}"""))))
    }

    private val accountJwt = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"https://api.openai.com/auth":{"chatgpt_account_id":"account-123"}}""".toByteArray(),
    ) + ".signature"

    @Test fun `device flow exchanges authorization code with its PKCE verifier`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val auth = OpenAiDeviceCodeAuth(client { request ->
            requests.add(request)
            when (request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> 200 to
                    """{"device_auth_id":"device-id","user_code":"ABCD-EFGH","interval":"1"}"""
                "/api/accounts/deviceauth/token" -> 200 to
                    """{"authorization_code":"auth-code","code_verifier":"pkce-verifier"}"""
                "/oauth/token" -> 200 to
                    """{"access_token":"access","refresh_token":"refresh","id_token":"$accountJwt","expires_in":3600}"""
                else -> error("Unexpected request")
            }
        })
        val session = auth.startDeviceLogin()
        assertEquals("ABCD-EFGH", session.userCode)
        assertEquals("https://auth.openai.com/codex/device", session.verificationUrl)
        assertEquals(1, session.intervalSec)
        val tokens = auth.awaitDeviceLogin(session)
        assertEquals("account-123", tokens.accountId)
        assertEquals("refresh", tokens.refreshToken)
        assertTrue(tokens.expiresAtEpochMs > System.currentTimeMillis())
        val form = body(requests.last())
        assertTrue(form.contains("code_verifier=pkce-verifier"))
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("redirect_uri=https%3A%2F%2Fauth.openai.com%2Fdeviceauth%2Fcallback"))
    }

    @Test fun `pending authorization is polled again`() = runBlocking {
        var polls = 0
        val auth = OpenAiDeviceCodeAuth(client { request ->
            if (request.url.encodedPath.endsWith("deviceauth/token")) {
                polls++
                if (polls == 1) 403 to "{}"
                else 200 to """{"authorization_code":"code","code_verifier":"verifier"}"""
            } else 200 to """{"access_token":"access","id_token":"$accountJwt"}"""
        })
        auth.awaitDeviceLogin(session())
        assertEquals(2, polls)
    }

    @Test fun `expired code never polls`() = runBlocking {
        val auth = OpenAiDeviceCodeAuth(client { error("Expired code must not be sent") })
        val error = runCatching { auth.awaitDeviceLogin(session().copy(expiresAtEpochMs = 1)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("expired"))
    }

    @Test fun `refresh preserves identity and refresh token when omitted`() = runBlocking {
        val auth = OpenAiDeviceCodeAuth(client { request ->
            val payload = Json.parseToJsonElement(body(request)).jsonObject
            assertEquals("old-refresh", payload["refresh_token"]?.jsonPrimitive?.content)
            200 to """{"access_token":"new-access","expires_in":3600}"""
        })
        val tokens = auth.refresh(OAuthTokens("old-access", "old-refresh", accountJwt, accountId = "account-123"))
        assertEquals("new-access", tokens.accessToken)
        assertEquals("old-refresh", tokens.refreshToken)
        assertEquals("account-123", tokens.accountId)
        assertEquals(accountJwt, tokens.idToken)
    }

    @Test fun `http errors never expose response bodies`() = runBlocking {
        val auth = OpenAiDeviceCodeAuth(client { 401 to "sensitive-token" })
        val failure = runCatching { auth.refresh(OAuthTokens("access", "refresh")) }.exceptionOrNull()
        assertFalse(failure?.message.orEmpty().contains("sensitive-token"))
        assertTrue(failure?.message.orEmpty().contains("Log in with ChatGPT again"))
    }

    @Test fun `missing account cannot produce a connected session`() = runBlocking {
        val auth = OpenAiDeviceCodeAuth(client { 200 to """{"access_token":"opaque"}""" })
        val failure = runCatching { auth.refresh(OAuthTokens("access", "refresh")) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("did not return an account"))
    }

    @Test fun `cancellation cancels in-flight authorization request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(Unit)
            while (!chain.call().isCanceled()) Thread.sleep(10)
            cancelled.complete(Unit)
            throw IOException("Canceled")
        }.build()
        val pending = async { OpenAiDeviceCodeAuth(client).awaitDeviceLogin(session()) }
        withTimeout(5_000) { started.await() }
        pending.cancelAndJoin()
        withTimeout(5_000) { cancelled.await() }
        assertTrue(pending.isCancelled)
    }

    @Test fun `DNS failure before code creation retries and clears connection status`() = runBlocking {
        var requests = 0
        val statuses = CopyOnWriteArrayList<String?>()
        val auth = OpenAiDeviceCodeAuth(client {
            if (++requests == 1) throw UnknownHostException("auth.openai.com")
            200 to """{"device_auth_id":"device","user_code":"SAME-CODE","expires_in":1800}"""
        })
        val session = withTimeout(5_000) { auth.startDeviceLogin { statuses.add(it) } }
        assertEquals(2, requests)
        assertEquals("SAME-CODE", session.userCode)
        assertTrue(statuses.first()!!.contains("Can't resolve auth.openai.com"))
        assertNull(statuses.last())
        assertTrue(session.expiresAtEpochMs > System.currentTimeMillis() + 1_700_000)
    }

    @Test fun `polling recovers from DNS and service failures without changing code`() = runBlocking {
        var polls = 0
        val auth = OpenAiDeviceCodeAuth(client { request ->
            if (request.url.encodedPath.endsWith("deviceauth/token")) {
                assertEquals("code", Json.parseToJsonElement(body(request)).jsonObject["user_code"]?.jsonPrimitive?.content)
                when (++polls) {
                    1 -> throw UnknownHostException("auth.openai.com")
                    2 -> 503 to "unavailable"
                    else -> 200 to """{"authorization_code":"code","code_verifier":"verifier"}"""
                }
            } else 200 to """{"access_token":"access","id_token":"$accountJwt"}"""
        })
        assertEquals("access", withTimeout(6_000) { auth.awaitDeviceLogin(session()) }.accessToken)
        assertEquals(3, polls)
    }

    @Test fun `offline polling still stops when the issued code expires`() = runBlocking {
        val auth = OpenAiDeviceCodeAuth(client { throw UnknownHostException("auth.openai.com") })
        val failure = runCatching {
            withTimeout(3_000) {
                auth.awaitDeviceLogin(session().copy(expiresAtEpochMs = System.currentTimeMillis() + 150))
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("expired"))
    }

    @Test fun `certificate failures are not retried`() = runBlocking {
        var requests = 0
        val auth = OpenAiDeviceCodeAuth(client {
            requests++
            throw SSLHandshakeException("Invalid certificate")
        })
        val failure = runCatching { auth.startDeviceLogin() }.exceptionOrNull()
        assertTrue(failure is SSLHandshakeException)
        assertEquals(1, requests)
    }

    private fun session() = DeviceLoginSession("code", "https://auth.openai.com/codex/device", "device", 1)

    private fun body(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    private fun client(respond: (Request) -> Pair<Int, String>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (status, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test response").body(body.toResponseBody()).build()
        }.build()
}
