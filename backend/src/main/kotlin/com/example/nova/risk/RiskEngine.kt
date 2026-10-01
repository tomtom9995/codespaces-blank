package com.example.nova.risk

import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ungefährer Standort aus den Headern des Load Balancers (GCP: {client_region}, {client_city}, {client_city_lat_long}). */
data class GeoInfo(val country: String?, val city: String?, val latitude: Double?, val longitude: Double?) {
    val label: String? get() = listOfNotNull(city?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { null }

    companion object {
        val UNKNOWN = GeoInfo(null, null, null, null)
    }
}

data class PreviousLogin(val at: Instant, val country: String?, val latitude: Double?, val longitude: Double?)

data class RiskInput(
    val deviceKnown: Boolean,
    val isNewUser: Boolean,
    val geo: GeoInfo,
    val connectionType: String,
    val recentFailures: Int,
    val knownCountries: Set<String>,
    val lastLogin: PreviousLogin?,
    val now: Instant = Instant.now(),
)

data class RiskResult(val score: Int, val reasons: List<String>) {
    val decision: Decision
        get() = when {
            score >= BLOCK_THRESHOLD -> Decision.BLOCK
            score >= STEP_UP_THRESHOLD -> Decision.STEP_UP
            else -> Decision.ALLOW
        }

    enum class Decision { ALLOW, STEP_UP, BLOCK }

    companion object {
        const val STEP_UP_THRESHOLD = 30
        const val BLOCK_THRESHOLD = 60
    }
}

/**
 * Punktbasierte Risikoprüfung (Startwerte aus docs/plan/sicherheit.md, Abschnitt 8.4).
 * Neue Konten haben noch keine Historie – dort zählen nur Fehlversuche und Verbindung.
 */
object RiskEngine {
    fun evaluate(input: RiskInput): RiskResult {
        val reasons = mutableListOf<String>()
        var score = 0
        fun add(points: Int, reason: String) {
            score += points
            reasons += "$reason ($points)"
        }

        if (!input.isNewUser) {
            if (input.deviceKnown) add(-30, "bekanntes Gerät") else add(30, "unbekanntes Gerät")
            val country = input.geo.country
            if (country != null && input.knownCountries.isNotEmpty() && country !in input.knownCountries) add(25, "neues Land $country")
            val last = input.lastLogin
            if (last != null && last.latitude != null && last.longitude != null && input.geo.latitude != null && input.geo.longitude != null) {
                val km = distanceKm(last.latitude, last.longitude, input.geo.latitude, input.geo.longitude)
                val hours = Duration.between(last.at, input.now).toMinutes().coerceAtLeast(1) / 60.0
                if (km > 500 && km / hours > 800) add(50, "unmögliche Reise (${km.toInt()} km in ${"%.1f".format(hours)} h)")
            }
        }
        when (input.connectionType.lowercase()) {
            "vpn" -> add(15, "VPN")
            "tor" -> add(40, "Tor")
        }
        if (input.recentFailures >= 3) add(20, "${input.recentFailures} Fehlversuche")
        return RiskResult(score, reasons)
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
