package com.example.nova.shared.platform

/**
 * Hardwaregebundener Geräteschlüssel (EC P-256).
 * Android: Android Keystore/StrongBox. iOS: Secure Enclave. Der private Schlüssel verlässt das Gerät nie.
 */
interface DeviceIdentity {
    /** z. B. „Pixel 10“ oder „iPhone 17 Pro“ – erscheint in Geräteliste und Sicherheits-E-Mails. */
    val deviceName: String
    /** android | ios */
    val platform: String
    /** Öffentlicher Schlüssel als X.509 SubjectPublicKeyInfo (DER), Base64. */
    fun publicKeyBase64(): String
    /** ECDSA-SHA256-Signatur im DER-Format. */
    fun sign(message: ByteArray): ByteArray
    /** wifi | cellular | vpn | ethernet | unknown */
    fun connectionType(): String
}

/** Einfacher Schlüssel-Wert-Speicher (Android: SharedPreferences, iOS: Keychain). */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/** Speicher nur im Arbeitsspeicher, z. B. für Tests. */
class InMemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

expect fun currentEpochSeconds(): Long
