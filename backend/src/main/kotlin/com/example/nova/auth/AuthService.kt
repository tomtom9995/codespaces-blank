package com.example.nova.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.nova.ApiException
import com.example.nova.ChallengeResponse
import com.example.nova.Config
import com.example.nova.DeviceInfo
import com.example.nova.LoginResponse
import com.example.nova.Tokens
import com.example.nova.UserDto
import com.example.nova.WorkspaceDto
import com.example.nova.badRequest
import com.example.nova.content.ContentService
import com.example.nova.db.Database
import com.example.nova.db.queryOne
import com.example.nova.db.update
import com.example.nova.messaging.EmailService
import com.example.nova.messaging.PhoneService
import com.example.nova.messaging.maskEmail
import com.example.nova.messaging.maskPhone
import com.example.nova.risk.GeoInfo
import com.example.nova.risk.RiskEngine
import com.example.nova.risk.RiskInput
import com.example.nova.risk.RiskResult
import com.example.nova.tooMany
import com.example.nova.unauthorized
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** Herkunft einer Anfrage, ermittelt aus IP und (vertrauenswürdigen) Load-Balancer-Headern. */
data class RequestContext(val ip: String?, val geo: GeoInfo, val userAgent: String?)

/** Angemeldete Person laut Access-Token. */
data class Principal(val userId: UUID, val deviceId: UUID, val workspaceId: UUID, val role: String)

class AuthService(
    private val config: Config,
    private val db: Database,
    private val email: EmailService,
    private val phone: PhoneService,
    private val content: ContentService,
    private val idTokens: IdTokenVerifier,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    val jwtAlgorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    @Serializable
    private data class EmailPayload(val marketingConsent: Boolean)

    @Serializable
    private data class StepUpPayload(val fingerprint: String, val isNewUser: Boolean, val risk: Int)

    // ---------- E-Mail-Code ----------

    suspend fun startEmailLogin(rawEmail: String, marketingConsent: Boolean, ctx: RequestContext): ChallengeResponse {
        val address = rawEmail.trim()
        if (!EMAIL.matches(address) || address.length > 254) throw badRequest("invalid_email", "Bitte gib eine gültige E-Mail-Adresse ein.")
        val target = address.lowercase()
        val code = Crypto.numericCode()
        val challengeId = db.tx {
            if (Challenges.countRecent(this, target, "email_login", 15 * 60) >= 5) {
                throw tooMany(content.bundled("de").ui("code.tooManyAttempts"))
            }
            Challenges.create(
                this, "email_login", null, target, { Crypto.hmac(config.codePepper, it.toString(), code) },
                ttlSeconds = CODE_TTL_SECONDS, payload = json.encodeToString(EmailPayload.serializer(), EmailPayload(marketingConsent)), ip = ctx.ip,
            )
        }
        val user = db.tx { Users.byEmail(this, target) }
        email.send(address, "email.account.verifyEmail", mapOf("code" to code, "validMinutes" to (CODE_TTL_SECONDS / 60).toString()), user?.antiPhishingPhrase)
        return ChallengeResponse(challengeId.toString(), CODE_TTL_SECONDS.toInt(), maskEmail(address))
    }

    suspend fun verifyEmailLogin(challengeId: String, code: String, device: DeviceInfo, ctx: RequestContext): LoginResponse {
        val challenge = consumeChallenge(challengeId, code, "email_login", ctx)
        val payload = challenge.payload?.let { json.decodeFromString(EmailPayload.serializer(), it) } ?: EmailPayload(false)
        val (user, isNew) = db.tx {
            Users.byEmail(this, challenge.target)?.let { it to false }
                ?: (Users.create(this, challenge.target, "email", payload.marketingConsent, null) to true)
        }
        return completeLogin(user, isNew, device, ctx)
    }

    // ---------- Google / Apple ----------

    suspend fun loginWithIdToken(provider: String, idToken: String, device: DeviceInfo, firstName: String?, ctx: RequestContext): LoginResponse {
        val identity = idTokens.verify(provider, idToken)
        val (user, isNew) = db.tx {
            Users.byEmail(this, identity.email)?.let { it to false }
                ?: (Users.create(this, identity.email, provider, false, firstName ?: identity.givenName) to true)
        }
        return completeLogin(user, isNew, device, ctx)
    }

    // ---------- Zusatzbestätigung ----------

    suspend fun verifyStepUp(challengeId: String, code: String, device: DeviceInfo, ctx: RequestContext): LoginResponse {
        val challenge = consumeChallenge(challengeId, code, "step_up", ctx)
        val payload = json.decodeFromString(StepUpPayload.serializer(), challenge.payload!!)
        val publicKey = decodeDeviceKey(device)
        if (Crypto.sha256Hex(publicKey) != payload.fingerprint) throw badRequest("device_mismatch", "Bitte bestätige auf demselben Gerät.")
        val user = db.tx { Users.byId(this, challenge.userId!!) } ?: throw unauthorized()
        db.tx { if (user.lockedAt != null) Users.setLocked(this, user.id, false) }
        return issueSession(user.copy(lockedAt = null), payload.isNewUser, device, publicKey, ctx, payload.risk, viaStepUp = true)
    }

    // ---------- Gemeinsamer Abschluss mit Risikoprüfung ----------

    internal suspend fun completeLogin(user: UserRow, isNewUser: Boolean, device: DeviceInfo, ctx: RequestContext): LoginResponse {
        val publicKey = decodeDeviceKey(device)
        val fingerprint = Crypto.sha256Hex(publicKey)
        val risk = db.tx {
            val known = Devices.byFingerprint(this, user.id, fingerprint)?.takeIf { it.revokedAt == null } != null
            RiskEngine.evaluate(
                RiskInput(
                    deviceKnown = known,
                    isNewUser = isNewUser,
                    geo = ctx.geo,
                    connectionType = device.connectionType,
                    recentFailures = AuthEvents.recentFailures(this, user.id),
                    knownCountries = AuthEvents.knownCountries(this, user.id),
                    lastLogin = AuthEvents.lastLogin(this, user.id),
                ),
            )
        }
        log.info("Anmeldung {}: Risiko {} {}", maskEmail(user.email), risk.score, risk.reasons)
        val stepUpPhone = user.verifiedPhone

        // Gesperrtes Konto: nur mit zweitem Faktor (falls vorhanden) wieder freigeben.
        val decision = if (user.lockedAt != null && stepUpPhone != null) RiskResult.Decision.STEP_UP else risk.decision

        when {
            decision == RiskResult.Decision.BLOCK -> {
                db.tx { AuthEvents.record(this, user.id, "login_blocked", null, ctx.ip, ctx.geo, device.connectionType, risk.score, risk.reasons.joinToString()) }
                email.send(
                    user.email, "email.security.loginBlocked",
                    mapOf("firstName" to user.firstName, "device" to device.name, "location" to (ctx.geo.label ?: unknownLocation()), "dateTime" to now()),
                    user.antiPhishingPhrase,
                )
                return LoginResponse(status = "blocked", message = content.get().ui("security.blocked.body"))
            }
            decision == RiskResult.Decision.STEP_UP && stepUpPhone != null -> {
                val code = Crypto.numericCode()
                val challengeId = db.tx {
                    Challenges.create(
                        this, "step_up", user.id, stepUpPhone, { Crypto.hmac(config.codePepper, it.toString(), code) },
                        ttlSeconds = CODE_TTL_SECONDS,
                        payload = json.encodeToString(StepUpPayload.serializer(), StepUpPayload(fingerprint, isNewUser, risk.score)),
                        ip = ctx.ip,
                    )
                }
                phone.sendSms(stepUpPhone, "sms.loginCode", mapOf("code" to code, "deviceShort" to device.name.take(24)))
                return LoginResponse(status = "step_up", stepUp = ChallengeResponse(challengeId.toString(), CODE_TTL_SECONDS.toInt(), maskPhone(stepUpPhone)))
            }
        }
        if (user.lockedAt != null) db.tx { Users.setLocked(this, user.id, false) }
        return issueSession(user.copy(lockedAt = null), isNewUser, device, publicKey, ctx, risk.score, viaStepUp = false)
    }

    private suspend fun issueSession(
        user: UserRow, isNewUser: Boolean, device: DeviceInfo, publicKey: ByteArray, ctx: RequestContext, risk: Int, viaStepUp: Boolean,
    ): LoginResponse {
        val fingerprint = Crypto.sha256Hex(publicKey)
        val refresh = Crypto.token("nrt_")
        val (deviceRow, wasKnown, membership) = db.tx {
            val (row, known) = Devices.upsert(this, user.id, publicKey, fingerprint, device.name, device.platform, ctx.ip, ctx.geo, device.connectionType)
            RefreshTokens.insert(this, user.id, row.id, Crypto.sha256Hex(refresh), REFRESH_TTL_SECONDS)
            AuthEvents.record(this, user.id, "login", row.id, ctx.ip, ctx.geo, device.connectionType, risk, if (viaStepUp) "step_up" else null)
            Triple(row, known, Users.membership(this, user.id))
        }

        if (isNewUser) {
            val method = when (user.loginMethod) {
                "google" -> "deinem Google-Konto"
                "apple" -> "deiner Apple-ID"
                else -> "einem Code per E-Mail"
            }
            email.send(user.email, "email.onboarding.welcome", mapOf("firstName" to user.firstName, "loginMethod" to method, "appUrl" to "${config.publicBaseUrl}/open"))
            db.tx { update("INSERT INTO email_log (user_id, template_key) VALUES (?, ?) ON CONFLICT DO NOTHING", user.id, "email.onboarding.welcome") }
        } else if (!wasKnown) {
            email.send(
                user.email, "email.security.newLogin",
                mapOf(
                    "firstName" to user.firstName,
                    "device" to "${device.name} (${device.platform})",
                    "deviceShort" to device.name,
                    "location" to (ctx.geo.label ?: unknownLocation()),
                    "connectionType" to connectionLabel(device.connectionType),
                    "dateTime" to now(),
                    "notMeUrl" to createNotMeLink(user.id),
                ),
                user.antiPhishingPhrase,
            )
        }
        return LoginResponse(
            status = "ok",
            tokens = Tokens(accessToken(user.id, deviceRow.id, membership), ACCESS_TTL_SECONDS.toInt(), refresh, deviceRow.id.toString()),
            user = toDto(user, membership),
            isNewUser = isNewUser,
        )
    }

    // ---------- Token-Erneuerung mit Geräteschlüssel ----------

    suspend fun refresh(refreshToken: String, deviceId: String, timestamp: Long, signature: String, ctx: RequestContext): Tokens {
        val deviceUuid = runCatching { UUID.fromString(deviceId) }.getOrNull() ?: throw unauthorized()
        if (abs(Instant.now().epochSecond - timestamp) > 300) throw unauthorized("Gerätezeit weicht zu stark ab.")
        // Sicherheitsereignisse werden auch bei Ablehnung gespeichert – daher erst nach der Transaktion werfen.
        val result: Tokens? = db.tx {
            val row = RefreshTokens.byHash(this, Crypto.sha256Hex(refreshToken)) ?: return@tx null
            if (row.revokedAt != null) {
                // Wiederverwendung eines alten Tokens: mögliches Abgreifen – Gerät vorsorglich abmelden.
                Devices.revoke(this, row.deviceId)
                AuthEvents.record(this, row.userId, "refresh_reuse_detected", row.deviceId, ctx.ip, ctx.geo)
                return@tx null
            }
            if (row.deviceId != deviceUuid || row.expiresAt < Instant.now()) return@tx null
            val device = Devices.byId(this, row.deviceId)?.takeIf { it.revokedAt == null } ?: return@tx null
            val user = Users.byId(this, row.userId)?.takeIf { it.lockedAt == null } ?: return@tx null
            val message = "nova-refresh\n$refreshToken\n$timestamp"
            val sig = runCatching { Crypto.unb64(signature) }.getOrNull()
            if (sig == null || !Crypto.verifyDeviceSignature(device.publicKey, message, sig)) {
                AuthEvents.record(this, user.id, "refresh_bad_signature", device.id, ctx.ip, ctx.geo)
                return@tx null
            }
            RefreshTokens.revoke(this, row.id)
            val next = Crypto.token("nrt_")
            RefreshTokens.insert(this, user.id, device.id, Crypto.sha256Hex(next), REFRESH_TTL_SECONDS)
            Devices.touch(this, device.id, ctx.ip, ctx.geo)
            Tokens(accessToken(user.id, device.id, Users.membership(this, user.id)), ACCESS_TTL_SECONDS.toInt(), next, device.id.toString())
        }
        return result ?: throw unauthorized()
    }

    suspend fun logout(principal: Principal) = db.tx {
        Devices.revoke(this, principal.deviceId)
        AuthEvents.record(this, principal.userId, "logout", principal.deviceId)
    }

    /** Wird bei jeder Anfrage geprüft: abgemeldete Geräte und gesperrte Konten verlieren sofort den Zugriff. */
    fun isSessionActive(userId: UUID, deviceId: UUID): Boolean = db.txBlocking {
        val device = Devices.byId(this, deviceId)
        val user = Users.byId(this, userId)
        device != null && device.revokedAt == null && device.userId == userId && user != null && user.lockedAt == null
    }

    // ---------- „Das war ich nicht“ ----------

    suspend fun createNotMeLink(userId: UUID): String {
        val token = Crypto.token()
        db.tx { SecurityLinks.create(this, userId, "not_me", Crypto.sha256Hex(token), 7L * 24 * 3600) }
        return "${config.publicBaseUrl}/s/not-me?t=$token"
    }

    /** Sperrt das Konto und meldet alle Geräte ab. Der Link kann nur sperren, nie entsperren. */
    suspend fun redeemNotMe(token: String, ctx: RequestContext): Boolean {
        val user = db.tx {
            val userId = SecurityLinks.redeem(this, Crypto.sha256Hex(token), "not_me") ?: return@tx null
            Devices.revokeAll(this, userId)
            Users.setLocked(this, userId, true)
            AuthEvents.record(this, userId, "account_locked_by_user", null, ctx.ip, ctx.geo)
            Users.byId(this, userId)
        } ?: return false
        email.send(user.email, "email.security.accountLocked", mapOf("firstName" to user.firstName), user.antiPhishingPhrase)
        user.verifiedPhone?.let { phone.sendSms(it, "sms.securityAlert", mapOf("changeShort" to "Konto vorsorglich gesperrt")) }
        return true
    }

    // ---------- Hilfsfunktionen ----------

    private suspend fun consumeChallenge(challengeId: String, code: String, kind: String, ctx: RequestContext): ChallengeRow {
        val id = runCatching { UUID.fromString(challengeId) }.getOrNull() ?: throw badRequest("code_invalid", "Ungültige Anfrage.")
        val texts = content.get()
        val outcome = db.tx {
            val c = Challenges.byId(this, id)?.takeIf { it.kind == kind }
                ?: return@tx Result.failure(ApiException(HttpStatusCode.BadRequest, "code_expired", texts.ui("code.expired")))
            when {
                c.consumedAt != null || c.expiresAt < Instant.now() ->
                    Result.failure(ApiException(HttpStatusCode.BadRequest, "code_expired", texts.ui("code.expired")))
                c.attempts >= c.maxAttempts ->
                    Result.failure(ApiException(HttpStatusCode.TooManyRequests, "too_many_attempts", texts.ui("code.tooManyAttempts")))
                !Crypto.constantTimeEquals(c.codeHash, Crypto.hmac(config.codePepper, c.id.toString(), code.trim())) -> {
                    Challenges.incrementAttempts(this, c.id)
                    val userId = c.userId ?: Users.byEmail(this, c.target)?.id
                    AuthEvents.record(this, userId, if (kind == "step_up") "step_up_failed" else "login_failed", null, ctx.ip, ctx.geo)
                    val left = c.maxAttempts - c.attempts - 1
                    if (left <= 0) Result.failure(ApiException(HttpStatusCode.TooManyRequests, "too_many_attempts", texts.ui("code.tooManyAttempts")))
                    else Result.failure(ApiException(HttpStatusCode.BadRequest, "code_invalid", texts.ui("code.invalid").replace("{attemptsLeft}", left.toString())))
                }
                else -> {
                    Challenges.consume(this, c.id)
                    Result.success(c)
                }
            }
        }
        return outcome.getOrThrow()
    }

    private fun decodeDeviceKey(device: DeviceInfo): ByteArray {
        val bytes = runCatching { Crypto.unb64(device.publicKey) }.getOrNull()
            ?: throw badRequest("invalid_device", "Ungültiger Geräteschlüssel.")
        runCatching { Crypto.parseDevicePublicKey(bytes) }.getOrElse { throw badRequest("invalid_device", "Ungültiger Geräteschlüssel.") }
        return bytes
    }

    fun accessToken(userId: UUID, deviceId: UUID, membership: MembershipRow): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withSubject(userId.toString())
        .withClaim("did", deviceId.toString())
        .withClaim("ws", membership.workspaceId.toString())
        .withClaim("role", membership.role)
        .withExpiresAt(Date(System.currentTimeMillis() + ACCESS_TTL_SECONDS * 1000))
        .sign(jwtAlgorithm)

    suspend fun toDto(user: UserRow, membership: MembershipRow): UserDto {
        val roleName = content.get().roles["workspace.${membership.role}"]?.name ?: membership.role
        val centralGroups = db.tx {
            queryOne("SELECT groups FROM oidc_accounts WHERE user_id = ?", user.id) { it.getString("groups") }
        }
        return UserDto(
            id = user.id.toString(),
            email = user.email,
            firstName = user.firstName,
            useCase = user.useCase,
            phoneMasked = user.verifiedPhone?.let(::maskPhone),
            hasAntiPhishingPhrase = user.antiPhishingPhrase != null,
            marketingConsent = user.marketingConsent,
            workspace = WorkspaceDto(membership.workspaceId.toString(), membership.workspaceName, membership.role, roleName),
            groups = centralGroups?.split(',')?.filter(String::isNotBlank) ?: emptyList(),
            centralAccount = centralGroups != null,
        )
    }

    companion object {
        const val ISSUER = "nova-backend"
        const val AUDIENCE = "nova-app"
        const val CODE_TTL_SECONDS = 600L
        const val ACCESS_TTL_SECONDS = 900L
        const val REFRESH_TTL_SECONDS = 60L * 24 * 3600
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")
        private val FORMAT = DateTimeFormatter.ofPattern("d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMAN).withZone(ZoneId.of("Europe/Berlin"))

        fun now(): String = FORMAT.format(Instant.now())
        fun format(instant: Instant): String = FORMAT.format(instant)
        fun unknownLocation() = "unbekannt"
        fun connectionLabel(type: String) = when (type.lowercase()) {
            "wifi" -> "WLAN"
            "cellular" -> "Mobilfunk"
            "vpn" -> "VPN"
            "ethernet" -> "Kabel"
            else -> "unbekannt"
        }
    }
}
