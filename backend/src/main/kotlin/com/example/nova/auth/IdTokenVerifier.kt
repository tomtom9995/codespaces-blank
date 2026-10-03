package com.example.nova.auth

import com.example.nova.ApiException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

data class VerifiedIdentity(val provider: String, val subject: String, val email: String, val givenName: String?)

/** Prüft ID-Tokens von „Mit Google/Apple anmelden“ gegen die öffentlichen Schlüssel der Anbieter. */
class IdTokenVerifier(
    private val googleClientIds: List<String>,
    private val appleClientIds: List<String>,
    private val sources: Map<String, JWKSource<SecurityContext>> = mapOf(
        "google" to JWKSourceBuilder.create<SecurityContext>(URI("https://www.googleapis.com/oauth2/v3/certs").toURL()).build(),
        "apple" to JWKSourceBuilder.create<SecurityContext>(URI("https://appleid.apple.com/auth/keys").toURL()).build(),
    ),
) {
    private val issuers = mapOf(
        "google" to setOf("https://accounts.google.com", "accounts.google.com"),
        "apple" to setOf("https://appleid.apple.com"),
    )

    fun isEnabled(provider: String) = when (provider) {
        "google" -> googleClientIds.isNotEmpty()
        "apple" -> appleClientIds.isNotEmpty()
        else -> false
    }

    suspend fun verify(provider: String, idToken: String): VerifiedIdentity = withContext(Dispatchers.IO) {
        if (!isEnabled(provider)) throw ApiException(HttpStatusCode.NotImplemented, "provider_disabled", "Diese Anmeldemethode ist noch nicht eingerichtet.")
        val audiences = if (provider == "google") googleClientIds else appleClientIds
        val processor = DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, sources.getValue(provider))
            jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(null, JWTClaimsSet.Builder().build(), setOf("sub", "iss", "aud", "exp", "email"))
        }
        val claims = runCatching { processor.process(idToken, null) }.getOrElse {
            throw ApiException(HttpStatusCode.Unauthorized, "invalid_id_token", "Die Anmeldung konnte nicht bestätigt werden.")
        }
        if (claims.issuer !in issuers.getValue(provider) || claims.audience.none { it in audiences }) {
            throw ApiException(HttpStatusCode.Unauthorized, "invalid_id_token", "Die Anmeldung konnte nicht bestätigt werden.")
        }
        val verified = when (val v = claims.getClaim("email_verified")) {
            is Boolean -> v
            is String -> v.toBoolean()
            else -> false
        }
        if (!verified) throw ApiException(HttpStatusCode.Unauthorized, "email_not_verified", "Deine E-Mail-Adresse ist beim Anbieter nicht bestätigt.")
        VerifiedIdentity(provider, claims.subject, claims.getStringClaim("email"), runCatching { claims.getStringClaim("given_name") }.getOrNull())
    }
}
