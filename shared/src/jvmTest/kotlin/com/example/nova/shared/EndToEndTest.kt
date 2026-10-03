package com.example.nova.shared

import com.example.nova.shared.api.NovaApi
import com.example.nova.shared.api.SessionStore
import com.example.nova.shared.api.StoredSession
import com.example.nova.shared.auth.AuthController
import com.example.nova.shared.auth.LoginResult
import com.example.nova.shared.auth.SessionState
import com.example.nova.shared.chat.ChatController
import com.example.nova.shared.content.ContentRepository
import com.example.nova.shared.platform.DeviceIdentity
import com.example.nova.shared.platform.InMemoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Gerät mit Software-Schlüssel (in der App: Android Keystore bzw. Secure Enclave). */
class JvmDevice(override val deviceName: String = "JVM-Testgerät") : DeviceIdentity {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val platform = "android"
    override fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(keys.public.encoded)
    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(message); sign() }
    override fun connectionType() = "wifi"
}

/**
 * Läuft nur, wenn NOVA_BACKEND_URL gesetzt ist (z. B. http://localhost:8080) und Mailpit unter MAILPIT_URL erreichbar ist.
 * ./gradlew :shared:jvmTest -DNOVA_E2E=1
 */
class EndToEndTest {
    private val backend = System.getenv("NOVA_BACKEND_URL")
    private val mailpit = System.getenv("MAILPIT_URL") ?: "http://localhost:8025"
    private val http = HttpClient.newHttpClient()

    private fun latestCodeFor(email: String): String {
        val body = http.send(HttpRequest.newBuilder(URI("$mailpit/api/v1/search?query=to:$email")).build(), HttpResponse.BodyHandlers.ofString()).body()
        val id = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.first().jsonObject["ID"]!!.jsonPrimitive.content
        val msg = http.send(HttpRequest.newBuilder(URI("$mailpit/api/v1/message/$id")).build(), HttpResponse.BodyHandlers.ofString()).body()
        val subject = Json.parseToJsonElement(msg).jsonObject["Subject"]!!.jsonPrimitive.content
        return Regex("\\d{6}").find(subject)!!.value
    }

    @Test
    fun anmeldenChattenUndTokenErneuern() = runBlocking {
        if (backend.isNullOrBlank()) return@runBlocking
        val store = InMemoryStore()
        val api = NovaApi(backend, SessionStore(store), JvmDevice())
        val content = ContentRepository(api, store)
        val auth = AuthController(api, store)

        assertEquals("Mit Google fortfahren", content.text("auth.continueWithGoogle"), "eingebaute Grundfassung")
        content.refresh()
        assertTrue(content.bundle.value.version != "bundled", "Fassung aus dem Backend")
        assertEquals("Der Code stimmt nicht. Du hast noch 2 Versuche.", content.text("code.invalid", "attemptsLeft" to "2"))

        val email = "e2e-${System.currentTimeMillis()}@example.org"
        val challenge = auth.startEmail(email, marketingConsent = false)
        delay(300)
        val result = auth.verifyEmail(challenge.challengeId, latestCodeFor(email))
        assertIs<LoginResult.Success>(result)
        assertTrue(result.isNewUser)
        assertIs<SessionState.LoggedIn>(auth.state.value)

        // Access-Token ungültig machen → Client erneuert automatisch mit Geräteschlüssel.
        val sessions = SessionStore(store)
        sessions.save(sessions.load()!!.let { StoredSession("kaputt", it.refreshToken, it.deviceId) })
        assertEquals(email, api.me().email)
        assertTrue(sessions.load()!!.accessToken != "kaputt")

        val chat = ChatController(api, this) { "Neuer Chat" }
        chat.send("Hallo Nova, wie geht's?")
        withTimeout(30_000) { while (chat.state.value.streaming) delay(50) }
        val state = chat.state.value
        assertEquals(null, state.error)
        assertEquals("Hallo Nova, wie geht's?", state.title)
        assertEquals(2, state.messages.size)
        assertTrue(state.messages.last().text.contains("Testmodus"), state.messages.last().text)
        chat.refreshConversations()
        withTimeout(5_000) { while (chat.conversations.value.isEmpty()) delay(50) }

        auth.logout()
        assertIs<SessionState.LoggedOut>(auth.state.value)
    }
}
