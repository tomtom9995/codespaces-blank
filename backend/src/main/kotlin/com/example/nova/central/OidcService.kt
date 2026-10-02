package com.example.nova.central

import com.example.nova.ApiException
import com.example.nova.Config
import com.example.nova.DeviceInfo
import com.example.nova.LoginResponse
import com.example.nova.auth.AuthService
import com.example.nova.auth.Crypto
import com.example.nova.auth.RequestContext
import com.example.nova.auth.SecurityLinks
import com.example.nova.auth.UserRow
import com.example.nova.auth.Users
import com.example.nova.db.Database
import com.example.nova.db.instantOrNull
import com.example.nova.db.queryOne
import com.example.nova.db.update
import com.example.nova.db.uuid
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Verschlüsselt gespeicherte Tokens (AES-256-GCM). Schlüssel aus dem Secret Manager. */
class TokenCrypto(secret: String) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }
}

data class OidcAccount(
    val userId: UUID,
    val subject: String,
    val username: String,
    val groups: List<String>,
    val accessToken: String?,
    val accessExpiresAt: Instant?,
    val refreshToken: String?,
)

/**
 * Login mit dem zentralen Chattia-Konto (Keycloak) nach dem Backend-for-Frontend-Muster:
 * Die App öffnet nur den Browser; Code-Austausch, Tokens und Nextcloud-Zugriff bleiben auf dem Server.
 *
 * 1. App öffnet  GET /v1/auth/oidc/start?redirect=nova://auth
 * 2. Keycloak    → GET /v1/auth/oidc/callback?code&state   (Server tauscht den Code, prüft das ID-Token)
 * 3. Server      → nova://auth?code=<Einmalcode>           (2 Minuten gültig, nur einmal nutzbar)
 * 4. App         POST /v1/auth/oidc/exchange {code, device} → normale Anmeldung inkl. Geräteschlüssel und Risikoprüfung
 */
class OidcService(
    private val config: Config,
    private val db: Database,
    private val http: HttpClient,
    private val auth: AuthService,
) {
    private val log = LoggerFactory.getLogger(OidcService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val crypto = TokenCrypto(config.tokenEncryptionKey)
    private val random = SecureRandom()
    private val refreshLocks = HashMap<UUID, Mutex>()

    val enabled: Boolean get() = config.oidcIssuer != null && config.oidcClientSecret != null

    private data class Endpoints(val authorization: String, val token: String, val jwks: String, val endSession: String?)

    @Volatile
    private var endpoints: Endpoints? = null

    private val processor by lazy {
        DefaultJWTProcessor<SecurityContext>().apply {
            val source = JWKSourceBuilder.create<SecurityContext>(URI(endpoints!!.jwks).toURL()).build()
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, source)
            jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
                config.oidcClientId,
                JWTClaimsSet.Builder().issuer(config.oidcIssuer).build(),
                setOf("sub", "exp", "iat", "nonce"),
            )
        }
    }

    private suspend fun endpoints(): Endpoints {
        endpoints?.let { return it }
        val text = http.get("${config.oidcIssuer}/.well-known/openid-configuration").bodyAsText()
        val doc = json.parseToJsonElement(text).jsonObject
        fun str(name: String) = (doc[name] as? JsonPrimitive)?.contentOrNull
        return Endpoints(str("authorization_endpoint")!!, str("token_endpoint")!!, str("jwks_uri")!!, str("end_session_endpoint"))
            .also { endpoints = it }
    }

    private fun requireEnabled() {
        if (!enabled) throw ApiException(HttpStatusCode.NotImplemented, "central_login_disabled", "Der zentrale Login ist nicht eingerichtet.")
    }

    private fun randomToken(bytes: Int = 32): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** Schritt 1: Weiterleitung zu Keycloak (PKCE + state + nonce). */
    suspend fun startUrl(appRedirect: String): String {
        requireEnabled()
        if (appRedirect !in config.oidcAppRedirects) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_redirect", "Unbekannte Rücksprungadresse.")
        }
        val state = randomToken()
        val verifier = randomToken(48)
        val nonce = randomToken()
        db.tx {
            update("DELETE FROM oidc_states WHERE expires_at < now()")
            update(
                "INSERT INTO oidc_states (state, code_verifier, nonce, app_redirect, expires_at) VALUES (?, ?, ?, ?, ?)",
                state, verifier, nonce, appRedirect, Instant.now().plusSeconds(600),
            )
        }
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return URLBuilder(endpoints().authorization).apply {
            parameters.append("client_id", config.oidcClientId)
            parameters.append("response_type", "code")
            parameters.append("scope", "openid email profile offline_access")
            parameters.append("redirect_uri", callbackUrl())
            parameters.append("state", state)
            parameters.append("nonce", nonce)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()
    }

    private fun callbackUrl() = "${config.publicBaseUrl}/v1/auth/oidc/callback"

    /** Schritt 2: Code tauschen, ID-Token prüfen, Konto verknüpfen. Gibt die Rücksprungadresse für die App zurück. */
    suspend fun callback(code: String?, state: String?, error: String?): String {
        requireEnabled()
        val saved = state?.let { s ->
            db.tx {
                queryOne("SELECT code_verifier, nonce, app_redirect FROM oidc_states WHERE state = ? AND expires_at > now()", s) {
                    Triple(it.getString(1), it.getString(2), it.getString(3))
                }.also { update("DELETE FROM oidc_states WHERE state = ?", s) }
            }
        } ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_state", "Die Anmeldung ist abgelaufen. Bitte starte sie erneut.")
        val (verifier, nonce, appRedirect) = saved
        if (error != null || code == null) return "$appRedirect?error=${error ?: "cancelled"}"

        val tokens = tokenRequest(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to callbackUrl(),
            "code_verifier" to verifier,
        )
        val idToken = (tokens["id_token"] as? JsonPrimitive)?.contentOrNull ?: error("Kein ID-Token erhalten")
        val claims = withContext(Dispatchers.IO) { processor.process(idToken, null) }
        if (claims.getStringClaim("nonce") != nonce) throw ApiException(HttpStatusCode.BadRequest, "invalid_nonce", "Ungültige Anmeldung.")
        val email = claims.getStringClaim("email") ?: throw ApiException(HttpStatusCode.BadRequest, "no_email", "Im Chattia-Konto ist keine E-Mail-Adresse hinterlegt.")
        if (claims.getBooleanClaim("email_verified") != true) {
            throw ApiException(HttpStatusCode.BadRequest, "email_not_verified", "Die E-Mail-Adresse des Chattia-Kontos ist nicht bestätigt.")
        }
        val username = claims.getStringClaim("preferred_username") ?: claims.subject
        val groups = (claims.getClaim("groups") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        val oneTimeCode = randomToken()
        db.tx {
            val user = Users.byEmail(this, email)
                ?: Users.create(this, email, "chattia", false, claims.getStringClaim("given_name"))
            storeTokens(this, user.id, claims.subject, username, groups, tokens)
            SecurityLinks.create(this, user.id, "oidc_login", Crypto.sha256Hex(oneTimeCode), 120)
        }
        log.info("Zentraler Login: {} ({} Gruppen)", username, groups.size)
        return "$appRedirect?code=$oneTimeCode"
    }

    /** Schritt 4: Einmalcode gegen eine normale Anmeldung (mit Geräteschlüssel) tauschen. */
    suspend fun exchange(code: String, device: DeviceInfo, ctx: RequestContext): LoginResponse {
        requireEnabled()
        val (user, isNew) = db.tx {
            val userId = SecurityLinks.redeem(this, Crypto.sha256Hex(code), "oidc_login") ?: return@tx null
            val user = Users.byId(this, userId)!!
            val firstLogin = queryOne("SELECT count(*) FROM auth_events WHERE user_id = ? AND type = 'login'", userId) { it.getInt(1) } == 0
            user to firstLogin
        } ?: throw ApiException(HttpStatusCode.BadRequest, "code_expired", "Der Anmeldecode ist abgelaufen. Bitte melde dich erneut an.")
        return auth.completeLogin(user, isNew, device, ctx)
    }

    fun account(c: java.sql.Connection, userId: UUID): OidcAccount? = c.queryOne(
        "SELECT * FROM oidc_accounts WHERE user_id = ?", userId,
    ) {
        OidcAccount(
            userId = it.uuid("user_id"),
            subject = it.getString("subject"),
            username = it.getString("username"),
            groups = it.getString("groups").split(',').filter(String::isNotBlank),
            accessToken = it.getString("access_token_enc")?.let(crypto::decrypt),
            accessExpiresAt = it.instantOrNull("access_expires_at"),
            refreshToken = it.getString("refresh_token_enc")?.let(crypto::decrypt),
        )
    }

    /** Gültiges Access-Token des Nutzers für Nextcloud – erneuert es bei Bedarf mit dem Refresh-Token. */
    suspend fun accessToken(userId: UUID): Pair<OidcAccount, String> {
        val lock = synchronized(refreshLocks) { refreshLocks.getOrPut(userId) { Mutex() } }
        return lock.withLock {
            val acc = db.tx { account(this, userId) }
                ?: throw ApiException(HttpStatusCode.Conflict, "central_account_required", "Melde dich mit deinem Chattia-Konto an, um deine Dateien zu sehen.")
            val token = acc.accessToken
            if (token != null && acc.accessExpiresAt != null && acc.accessExpiresAt.isAfter(Instant.now().plusSeconds(30))) {
                return@withLock acc to token
            }
            val refresh = acc.refreshToken ?: throw relogin()
            val tokens = runCatching { tokenRequest("grant_type" to "refresh_token", "refresh_token" to refresh) }
                .getOrElse { throw relogin() }
            db.tx { storeTokens(this, userId, acc.subject, acc.username, acc.groups, tokens) }
            acc to ((tokens["access_token"] as JsonPrimitive).content)
        }
    }

    private fun relogin() = ApiException(HttpStatusCode.Conflict, "central_login_expired", "Bitte melde dich erneut mit deinem Chattia-Konto an.")

    private fun storeTokens(c: java.sql.Connection, userId: UUID, subject: String, username: String, groups: List<String>, tokens: JsonObject) {
        val access = (tokens["access_token"] as? JsonPrimitive)?.contentOrNull
        val expiresIn = (tokens["expires_in"] as? JsonPrimitive)?.longOrNull ?: 300
        val refresh = (tokens["refresh_token"] as? JsonPrimitive)?.contentOrNull
        c.update(
            """INSERT INTO oidc_accounts (user_id, subject, username, groups, access_token_enc, access_expires_at, refresh_token_enc, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, now())
               ON CONFLICT (user_id) DO UPDATE SET subject = EXCLUDED.subject, username = EXCLUDED.username, groups = EXCLUDED.groups,
                 access_token_enc = EXCLUDED.access_token_enc, access_expires_at = EXCLUDED.access_expires_at,
                 refresh_token_enc = COALESCE(EXCLUDED.refresh_token_enc, oidc_accounts.refresh_token_enc), updated_at = now()""",
            userId, subject, username, groups.joinToString(","), access?.let(crypto::encrypt), Instant.now().plusSeconds(expiresIn),
            refresh?.let(crypto::encrypt),
        )
    }

    private suspend fun tokenRequest(vararg params: Pair<String, String>): JsonObject {
        val response = http.submitForm(endpoints().token, parameters { params.forEach { (k, v) -> append(k, v) } }) {
            basicAuth(config.oidcClientId, config.oidcClientSecret!!)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.warn("Token-Anfrage abgelehnt: {} {}", response.status.value, text.take(200))
            throw ApiException(HttpStatusCode.BadGateway, "central_login_failed", "Die Anmeldung beim Chattia-Konto ist fehlgeschlagen.")
        }
        return json.parseToJsonElement(text).jsonObject
    }
}
