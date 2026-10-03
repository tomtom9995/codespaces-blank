package com.example.nova.auth

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val random = SecureRandom()

    /** 6-stelliger Code mit führenden Nullen. */
    fun numericCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    /** Zufälliges, URL-sicheres Token (256 Bit). */
    fun token(prefix: String = ""): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray())

    /** Codes werden nur als HMAC gespeichert, gebunden an die Challenge-ID. */
    fun hmac(pepper: String, vararg parts: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(), "HmacSHA256"))
        return mac.doFinal(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Liest einen öffentlichen EC-P-256-Schlüssel (X.509 SubjectPublicKeyInfo, DER). */
    fun parseDevicePublicKey(der: ByteArray): ECPublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        require(key is ECPublicKey && key.params.curve.field.fieldSize == 256) { "Nur EC P-256 wird unterstützt" }
        return key
    }

    /** Prüft eine ECDSA-SHA256-Signatur im DER-Format (Android Keystore und iOS CryptoKit liefern beide DER). */
    fun verifyDeviceSignature(publicKeyDer: ByteArray, message: String, signatureDer: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(parseDevicePublicKey(publicKeyDer))
            update(message.toByteArray())
            verify(signatureDer)
        }
    }.getOrDefault(false)

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())
}
