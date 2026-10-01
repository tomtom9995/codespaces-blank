package com.example.nova.auth

import com.example.nova.db.instant
import com.example.nova.db.instantOrNull
import com.example.nova.db.query
import com.example.nova.db.queryOne
import com.example.nova.db.update
import com.example.nova.db.uuid
import com.example.nova.db.uuidOrNull
import com.example.nova.risk.GeoInfo
import com.example.nova.risk.PreviousLogin
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class UserRow(
    val id: UUID,
    val email: String,
    val firstName: String?,
    val useCase: String?,
    val phone: String?,
    val phoneVerifiedAt: Instant?,
    val antiPhishingPhrase: String?,
    val marketingConsent: Boolean,
    val loginMethod: String,
    val lockedAt: Instant?,
    val createdAt: Instant,
) {
    val verifiedPhone: String? get() = phone?.takeIf { phoneVerifiedAt != null }
}

data class MembershipRow(val workspaceId: UUID, val workspaceName: String, val role: String)

data class DeviceRow(
    val id: UUID,
    val userId: UUID,
    val publicKey: ByteArray,
    val fingerprint: String,
    val name: String,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val lastCountry: String?,
    val lastCity: String?,
    val revokedAt: Instant?,
)

data class ChallengeRow(
    val id: UUID,
    val kind: String,
    val userId: UUID?,
    val target: String,
    val codeHash: String,
    val attempts: Int,
    val maxAttempts: Int,
    val payload: String?,
    val expiresAt: Instant,
    val consumedAt: Instant?,
)

private fun ResultSet.toUser() = UserRow(
    id = uuid("id"),
    email = getString("email"),
    firstName = getString("first_name"),
    useCase = getString("use_case"),
    phone = getString("phone"),
    phoneVerifiedAt = instantOrNull("phone_verified_at"),
    antiPhishingPhrase = getString("anti_phishing_phrase"),
    marketingConsent = getBoolean("marketing_consent"),
    loginMethod = getString("login_method"),
    lockedAt = instantOrNull("locked_at"),
    createdAt = instant("created_at"),
)

private fun ResultSet.toDevice() = DeviceRow(
    id = uuid("id"),
    userId = uuid("user_id"),
    publicKey = getBytes("public_key"),
    fingerprint = getString("key_fingerprint"),
    name = getString("name"),
    platform = getString("platform"),
    createdAt = instant("created_at"),
    lastSeenAt = instant("last_seen_at"),
    lastCountry = getString("last_country"),
    lastCity = getString("last_city"),
    revokedAt = instantOrNull("revoked_at"),
)

private fun ResultSet.toChallenge() = ChallengeRow(
    id = uuid("id"),
    kind = getString("kind"),
    userId = uuidOrNull("user_id"),
    target = getString("target"),
    codeHash = getString("code_hash"),
    attempts = getInt("attempts"),
    maxAttempts = getInt("max_attempts"),
    payload = getString("payload"),
    expiresAt = instant("expires_at"),
    consumedAt = instantOrNull("consumed_at"),
)

object Users {
    fun byId(c: Connection, id: UUID): UserRow? = c.queryOne("SELECT * FROM users WHERE id = ?", id) { it.toUser() }
    fun byEmail(c: Connection, email: String): UserRow? =
        c.queryOne("SELECT * FROM users WHERE lower(email) = lower(?)", email) { it.toUser() }

    /** Legt Konto und persönlichen Workspace (Rolle Inhaber:in) an. */
    fun create(c: Connection, email: String, loginMethod: String, marketingConsent: Boolean, firstName: String?): UserRow {
        val id = UUID.randomUUID()
        c.update(
            "INSERT INTO users (id, email, login_method, marketing_consent, first_name) VALUES (?, ?, ?, ?, ?)",
            id, email.trim(), loginMethod, marketingConsent, firstName,
        )
        val ws = UUID.randomUUID()
        c.update("INSERT INTO workspaces (id, name) VALUES (?, ?)", ws, "Mein Bereich")
        c.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'owner')", ws, id)
        return byId(c, id)!!
    }

    fun membership(c: Connection, userId: UUID): MembershipRow = c.queryOne(
        """SELECT w.id, w.name, m.role FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
           WHERE m.user_id = ? ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 WHEN 'member' THEN 2 ELSE 3 END, m.created_at
           LIMIT 1""",
        userId,
    ) { MembershipRow(it.uuid("id"), it.getString("name"), it.getString("role")) } ?: error("Konto ohne Workspace: $userId")

    fun setLocked(c: Connection, userId: UUID, locked: Boolean) =
        c.update("UPDATE users SET locked_at = ? WHERE id = ?", if (locked) Instant.now() else null, userId)
}

object Devices {
    fun byId(c: Connection, id: UUID): DeviceRow? = c.queryOne("SELECT * FROM devices WHERE id = ?", id) { it.toDevice() }

    fun byFingerprint(c: Connection, userId: UUID, fingerprint: String): DeviceRow? =
        c.queryOne("SELECT * FROM devices WHERE user_id = ? AND key_fingerprint = ?", userId, fingerprint) { it.toDevice() }

    fun active(c: Connection, userId: UUID): List<DeviceRow> =
        c.query("SELECT * FROM devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC", userId) { it.toDevice() }

    /** Registriert ein Gerät oder reaktiviert es. Gibt (Gerät, war bereits bekannt) zurück. */
    fun upsert(
        c: Connection, userId: UUID, publicKey: ByteArray, fingerprint: String, name: String, platform: String,
        ip: String?, geo: GeoInfo, connection: String,
    ): Pair<DeviceRow, Boolean> {
        val existing = byFingerprint(c, userId, fingerprint)
        if (existing != null) {
            c.update(
                """UPDATE devices SET name = ?, platform = ?, last_seen_at = now(), last_ip = ?, last_country = ?, last_city = ?,
                   last_connection = ?, revoked_at = NULL WHERE id = ?""",
                name, platform, ip, geo.country, geo.city, connection, existing.id,
            )
            return byId(c, existing.id)!! to (existing.revokedAt == null)
        }
        val id = UUID.randomUUID()
        c.update(
            """INSERT INTO devices (id, user_id, public_key, key_fingerprint, name, platform, last_ip, last_country, last_city, last_connection)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, userId, publicKey, fingerprint, name.take(80), platform.take(20), ip, geo.country, geo.city, connection,
        )
        return byId(c, id)!! to false
    }

    fun touch(c: Connection, id: UUID, ip: String?, geo: GeoInfo) = c.update(
        "UPDATE devices SET last_seen_at = now(), last_ip = ?, last_country = COALESCE(?, last_country), last_city = COALESCE(?, last_city) WHERE id = ?",
        ip, geo.country, geo.city, id,
    )

    fun revoke(c: Connection, id: UUID) {
        c.update("UPDATE devices SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", id)
        c.update("UPDATE refresh_tokens SET revoked_at = now() WHERE device_id = ? AND revoked_at IS NULL", id)
    }

    fun revokeAll(c: Connection, userId: UUID, except: UUID? = null): Int {
        val ids = active(c, userId).map { it.id }.filter { it != except }
        ids.forEach { revoke(c, it) }
        return ids.size
    }
}

object Challenges {
    fun create(
        c: Connection, kind: String, userId: UUID?, target: String, codeHashFor: (UUID) -> String,
        ttlSeconds: Long, payload: String? = null, ip: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        c.update(
            "INSERT INTO challenges (id, kind, user_id, target, code_hash, payload, ip, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, kind, userId, target, codeHashFor(id), payload, ip, Instant.now().plusSeconds(ttlSeconds),
        )
        return id
    }

    fun byId(c: Connection, id: UUID): ChallengeRow? =
        c.queryOne("SELECT * FROM challenges WHERE id = ? FOR UPDATE", id) { it.toChallenge() }

    fun countRecent(c: Connection, target: String, kind: String, sinceSeconds: Long): Int = c.queryOne(
        "SELECT count(*) FROM challenges WHERE target = ? AND kind = ? AND created_at > ?",
        target, kind, Instant.now().minusSeconds(sinceSeconds),
    ) { it.getInt(1) } ?: 0

    fun incrementAttempts(c: Connection, id: UUID) = c.update("UPDATE challenges SET attempts = attempts + 1 WHERE id = ?", id)
    fun consume(c: Connection, id: UUID) = c.update("UPDATE challenges SET consumed_at = now() WHERE id = ?", id)
}

object AuthEvents {
    fun record(
        c: Connection, userId: UUID?, type: String, deviceId: UUID? = null, ip: String? = null,
        geo: GeoInfo = GeoInfo.UNKNOWN, connection: String? = null, risk: Int? = null, detail: String? = null,
    ) = c.update(
        """INSERT INTO auth_events (id, user_id, type, device_id, ip, country, city, latitude, longitude, connection, risk_score, detail)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        UUID.randomUUID(), userId, type, deviceId, ip, geo.country, geo.city, geo.latitude, geo.longitude, connection, risk, detail?.take(500),
    )

    fun recentFailures(c: Connection, userId: UUID, sinceSeconds: Long = 15 * 60): Int = c.queryOne(
        "SELECT count(*) FROM auth_events WHERE user_id = ? AND type IN ('login_failed', 'step_up_failed') AND created_at > ?",
        userId, Instant.now().minusSeconds(sinceSeconds),
    ) { it.getInt(1) } ?: 0

    fun knownCountries(c: Connection, userId: UUID): Set<String> = c.query(
        "SELECT DISTINCT country FROM auth_events WHERE user_id = ? AND type = 'login' AND country IS NOT NULL AND created_at > ?",
        userId, Instant.now().minusSeconds(90L * 24 * 3600),
    ) { it.getString(1) }.toSet()

    fun lastLogin(c: Connection, userId: UUID): PreviousLogin? = c.queryOne(
        "SELECT created_at, country, latitude, longitude FROM auth_events WHERE user_id = ? AND type = 'login' ORDER BY created_at DESC LIMIT 1",
        userId,
    ) {
        PreviousLogin(
            it.instant("created_at"), it.getString("country"),
            it.getObject("latitude") as Double?, it.getObject("longitude") as Double?,
        )
    }

    data class EventRow(val type: String, val at: Instant, val location: String?, val device: String?)

    fun recent(c: Connection, userId: UUID, limit: Int = 10): List<EventRow> = c.query(
        """SELECT e.type, e.created_at, e.city, e.country, d.name AS device FROM auth_events e
           LEFT JOIN devices d ON d.id = e.device_id WHERE e.user_id = ? ORDER BY e.created_at DESC LIMIT ?""",
        userId, limit,
    ) {
        EventRow(
            it.getString("type"), it.instant("created_at"),
            GeoInfo(it.getString("country"), it.getString("city"), null, null).label, it.getString("device"),
        )
    }
}

object RefreshTokens {
    data class Row(val id: UUID, val userId: UUID, val deviceId: UUID, val expiresAt: Instant, val revokedAt: Instant?)

    fun insert(c: Connection, userId: UUID, deviceId: UUID, tokenHash: String, ttlSeconds: Long) = c.update(
        "INSERT INTO refresh_tokens (id, user_id, device_id, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(), userId, deviceId, tokenHash, Instant.now().plusSeconds(ttlSeconds),
    )

    fun byHash(c: Connection, hash: String): Row? = c.queryOne(
        "SELECT id, user_id, device_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ? FOR UPDATE", hash,
    ) { Row(it.uuid("id"), it.uuid("user_id"), it.uuid("device_id"), it.instant("expires_at"), it.instantOrNull("revoked_at")) }

    fun revoke(c: Connection, id: UUID) = c.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", id)
}

object SecurityLinks {
    fun create(c: Connection, userId: UUID, kind: String, tokenHash: String, ttlSeconds: Long) = c.update(
        "INSERT INTO security_links (token_hash, user_id, kind, expires_at) VALUES (?, ?, ?, ?)",
        tokenHash, userId, kind, Instant.now().plusSeconds(ttlSeconds),
    )

    /** Löst einen Link genau einmal ein; gibt die Konto-ID zurück. */
    fun redeem(c: Connection, tokenHash: String, kind: String): UUID? {
        val userId = c.queryOne(
            "SELECT user_id FROM security_links WHERE token_hash = ? AND kind = ? AND used_at IS NULL AND expires_at > now() FOR UPDATE",
            tokenHash, kind,
        ) { it.uuid("user_id") } ?: return null
        c.update("UPDATE security_links SET used_at = now() WHERE token_hash = ?", tokenHash)
        return userId
    }
}
