package dev.ophoner.llm.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

data class PkcePair(
    val codeVerifier: String,
    val codeChallenge: String,
)

object Pkce {
    fun generate(length: Int = 64): PkcePair {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(128)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return PkcePair(codeVerifier = verifier, codeChallenge = challenge)
    }
}
