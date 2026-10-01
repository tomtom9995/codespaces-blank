package com.example.nova.me

import com.example.nova.ApiException
import com.example.nova.ChallengeResponse
import com.example.nova.Config
import com.example.nova.DeviceDto
import com.example.nova.SecurityEventDto
import com.example.nova.SecurityStatus
import com.example.nova.UpdateMeRequest
import com.example.nova.UserDto
import com.example.nova.auth.AuthEvents
import com.example.nova.auth.AuthService
import com.example.nova.auth.Challenges
import com.example.nova.auth.Crypto
import com.example.nova.auth.Devices
import com.example.nova.auth.Principal
import com.example.nova.auth.RequestContext
import com.example.nova.auth.Users
import com.example.nova.badRequest
import com.example.nova.content.ContentService
import com.example.nova.db.Database
import com.example.nova.db.update
import com.example.nova.messaging.EmailService
import com.example.nova.messaging.PhoneService
import com.example.nova.messaging.maskPhone
import com.example.nova.notFound
import com.example.nova.risk.GeoInfo
import com.example.nova.tooMany
import com.example.nova.unauthorized
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID

class AccountService(
    private val config: Config,
    private val db: Database,
    private val auth: AuthService,
    private val email: EmailService,
    private val phone: PhoneService,
    private val content: ContentService,
) {
    suspend fun me(p: Principal): UserDto {
        val (user, membership) = db.tx { (Users.byId(this, p.userId) ?: throw unauthorized()) to Users.membership(this, p.userId) }
        return auth.toDto(user, membership)
    }

    suspend fun update(p: Principal, req: UpdateMeRequest): UserDto {
        db.tx {
            req.firstName?.let { update("UPDATE users SET first_name = ? WHERE id = ?", it.trim().take(60).ifBlank { null }, p.userId) }
            req.useCase?.let { update("UPDATE users SET use_case = ? WHERE id = ?", it.take(40), p.userId) }
            req.marketingConsent?.let { update("UPDATE users SET marketing_consent = ? WHERE id = ?", it, p.userId) }
        }
        return me(p)
    }

    suspend fun setAntiPhishingPhrase(p: Principal, phrase: String): UserDto {
        val clean = phrase.trim()
        if (clean.length !in 3..40) throw badRequest("invalid_phrase", "Bitte wähle 3 bis 40 Zeichen.")
        if (clean.contains(Regex("[<>{}\\[\\]]")) || clean.contains("http")) throw badRequest("invalid_phrase", "Bitte nur Wörter, keine Sonderzeichen oder Links.")
        val user = db.tx {
            update("UPDATE users SET anti_phishing_phrase = ? WHERE id = ?", clean, p.userId)
            AuthEvents.record(this, p.userId, "anti_phishing_set", p.deviceId)
            Users.byId(this, p.userId)!!
        }
        val device = db.tx { Devices.byId(this, p.deviceId) }
        email.send(
            user.email, "email.security.factorChanged",
            mapOf(
                "firstName" to user.firstName, "changeSummary" to "Anti-Phishing-Code festgelegt",
                "device" to (device?.name ?: "unbekannt"), "dateTime" to AuthService.now(), "notMeUrl" to auth.createNotMeLink(user.id),
            ),
            user.antiPhishingPhrase,
        )
        return me(p)
    }

    // ---------- Telefonnummer per SMS oder Anruf ----------

    suspend fun startPhone(p: Principal, rawPhone: String, channel: String, ctx: RequestContext): ChallengeResponse {
        val number = normalizePhone(rawPhone) ?: throw badRequest("invalid_phone", "Bitte gib deine Nummer mit Ländervorwahl ein, z. B. +49 170 1234567.")
        if (channel !in setOf("sms", "call")) throw badRequest("invalid_channel", "Unbekannter Kanal.")
        val code = Crypto.numericCode()
        val id = db.tx {
            if (Challenges.countRecent(this, number, "phone_verify", 3600) >= 3) throw tooMany("Zu viele Codes angefordert. Bitte versuch es in einer Stunde erneut.")
            Challenges.create(this, "phone_verify", p.userId, number, { Crypto.hmac(config.codePepper, it.toString(), code) }, AuthService.CODE_TTL_SECONDS, ip = ctx.ip)
        }
        val ok = if (channel == "call") {
            phone.call(number, "voice.verifyCode", mapOf("codeSpoken" to code.toCharArray().joinToString(", ")))
        } else {
            phone.sendSms(number, "sms.verifyPhone", mapOf("code" to code))
        }
        if (!ok) throw ApiException(HttpStatusCode.BadGateway, "phone_send_failed", content.get().ui("error.generic"))
        return ChallengeResponse(id.toString(), AuthService.CODE_TTL_SECONDS.toInt(), maskPhone(number))
    }

    suspend fun verifyPhone(p: Principal, challengeId: String, code: String): UserDto {
        val id = runCatching { UUID.fromString(challengeId) }.getOrNull() ?: throw badRequest("code_invalid", "Ungültige Anfrage.")
        val texts = content.get()
        val (user, oldPhone) = db.tx {
            val c = Challenges.byId(this, id)?.takeIf { it.kind == "phone_verify" && it.userId == p.userId }
                ?: return@tx null
            if (c.consumedAt != null || c.expiresAt < Instant.now()) return@tx null
            if (c.attempts >= c.maxAttempts) return@tx Pair(null, "too_many")
            if (!Crypto.constantTimeEquals(c.codeHash, Crypto.hmac(config.codePepper, c.id.toString(), code.trim()))) {
                Challenges.incrementAttempts(this, c.id)
                return@tx Pair(null, "invalid:${c.maxAttempts - c.attempts - 1}")
            }
            Challenges.consume(this, c.id)
            val before = Users.byId(this, p.userId)!!
            update("UPDATE users SET phone = ?, phone_verified_at = now() WHERE id = ?", c.target, p.userId)
            AuthEvents.record(this, p.userId, "phone_verified", p.deviceId)
            Pair(Users.byId(this, p.userId), before.verifiedPhone)
        }?.let { (u, info) ->
            when {
                u != null -> u to info
                info == "too_many" -> throw ApiException(HttpStatusCode.TooManyRequests, "too_many_attempts", texts.ui("code.tooManyAttempts"))
                info?.startsWith("invalid:") == true -> {
                    val left = info.removePrefix("invalid:").toInt().coerceAtLeast(0)
                    if (left == 0) throw ApiException(HttpStatusCode.TooManyRequests, "too_many_attempts", texts.ui("code.tooManyAttempts"))
                    throw badRequest("code_invalid", texts.ui("code.invalid").replace("{attemptsLeft}", left.toString()))
                }
                else -> throw badRequest("code_expired", texts.ui("code.expired"))
            }
        } ?: throw badRequest("code_expired", texts.ui("code.expired"))

        val device = db.tx { Devices.byId(this, p.deviceId) }
        if (oldPhone != null && oldPhone != user.phone) {
            phone.sendSms(oldPhone, "sms.securityAlert", mapOf("changeShort" to "Telefonnummer geändert"))
            email.send(
                user.email, "email.security.factorChanged",
                mapOf(
                    "firstName" to user.firstName, "changeSummary" to "Telefonnummer geändert",
                    "device" to (device?.name ?: "unbekannt"), "dateTime" to AuthService.now(), "notMeUrl" to auth.createNotMeLink(user.id),
                ),
                user.antiPhishingPhrase,
            )
        } else if (oldPhone == null) {
            email.send(
                user.email, "email.security.mfaEnabled",
                mapOf(
                    "firstName" to user.firstName, "factorName" to "deine Handynummer",
                    "device" to (device?.name ?: "unbekannt"), "dateTime" to AuthService.now(), "notMeUrl" to auth.createNotMeLink(user.id),
                ),
                user.antiPhishingPhrase,
            )
        }
        return me(p)
    }

    // ---------- Geräte und Sicherheitsstatus ----------

    suspend fun devices(p: Principal): List<DeviceDto> = db.tx {
        Devices.active(this, p.userId).map {
            DeviceDto(
                id = it.id.toString(),
                name = it.name,
                platform = it.platform,
                lastSeenAt = it.lastSeenAt.toString(),
                location = GeoInfo(it.lastCountry, it.lastCity, null, null).label,
                current = it.id == p.deviceId,
            )
        }
    }

    suspend fun revokeDevice(p: Principal, deviceId: String) {
        val id = runCatching { UUID.fromString(deviceId) }.getOrNull() ?: throw notFound()
        db.tx {
            val device = Devices.byId(this, id)?.takeIf { it.userId == p.userId } ?: throw notFound()
            Devices.revoke(this, device.id)
            AuthEvents.record(this, p.userId, "device_revoked", p.deviceId, detail = device.name)
        }
    }

    suspend fun revokeOtherDevices(p: Principal): Int = db.tx {
        Devices.revokeAll(this, p.userId, except = p.deviceId).also {
            AuthEvents.record(this, p.userId, "devices_revoked_others", p.deviceId, detail = "$it Geräte")
        }
    }

    suspend fun securityStatus(p: Principal): SecurityStatus = db.tx {
        val user = Users.byId(this, p.userId) ?: throw unauthorized()
        SecurityStatus(
            hasPhone = user.verifiedPhone != null,
            hasAntiPhishingPhrase = user.antiPhishingPhrase != null,
            activeDevices = Devices.active(this, p.userId).size,
            recentEvents = AuthEvents.recent(this, p.userId).map { SecurityEventDto(it.type, it.at.toString(), it.location, it.device) },
        )
    }

    companion object {
        /** E.164: +, 8–15 Ziffern. Leerzeichen, Bindestriche und Klammern werden entfernt; 00 wird zu +. */
        fun normalizePhone(raw: String): String? {
            var s = raw.trim().replace(Regex("[\\s\\-()/]"), "")
            if (s.startsWith("00")) s = "+" + s.drop(2)
            return s.takeIf { Regex("^\\+[1-9][0-9]{7,14}$").matches(it) }
        }
    }
}
