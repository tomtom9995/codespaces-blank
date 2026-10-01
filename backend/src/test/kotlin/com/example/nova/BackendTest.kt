package com.example.nova

import com.example.nova.auth.Crypto
import com.example.nova.chat.MockProvider
import com.example.nova.content.AppContentBundle
import com.example.nova.db.update
import com.example.nova.messaging.InMemoryTransport
import com.example.nova.messaging.PhoneChannel
import com.example.nova.risk.GeoInfo
import com.example.nova.risk.PreviousLogin
import com.example.nova.risk.RiskEngine
import com.example.nova.risk.RiskInput
import com.example.nova.risk.RiskResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecordingPhone : PhoneChannel {
    data class Sent(val to: String, val kind: String, val text: String)
    val sent = CopyOnWriteArrayList<Sent>()
    override suspend fun sms(to: String, text: String) { sent += Sent(to, "sms", text) }
    override suspend fun call(to: String, speech: String) { sent += Sent(to, "call", speech) }
}

/** Ein simuliertes Gerät mit eigenem Schlüsselpaar (wie Secure Enclave / Android Keystore). */
class TestDevice(val name: String = "Pixel 10", val connection: String = "wifi") {
    val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val info get() = DeviceInfo(Crypto.b64(keys.public.encoded), name, "android", connection)
    fun sign(message: String): String = Crypto.b64(Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private); update(message.toByteArray()); sign()
    })
}

class BackendTest {
    private val mails = InMemoryTransport()
    private val phone = RecordingPhone()
    private val config = Config.fromEnv(
        mapOf(
            "DEV_MODE" to "true",
            "DATABASE_URL" to (System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/nova_test"),
            "DATABASE_USER" to (System.getenv("DATABASE_USER") ?: "nova"),
            "DATABASE_PASSWORD" to (System.getenv("DATABASE_PASSWORD") ?: "nova"),
            "PUBLIC_BASE_URL" to "http://localhost:8080",
        ),
    )
    private lateinit var services: Services

    @BeforeTest
    fun setUp() {
        services = Services.create(config, mailTransport = mails, phoneChannel = phone, llm = MockProvider())
        services.db.txBlocking { update("TRUNCATE users, workspaces, challenges, auth_events CASCADE") }
    }

    @AfterTest
    fun tearDown() = services.db.close()

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(services) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        block(client)
    }

    private fun lastCode(to: String): String {
        val mail = mails.sent.last { it.to.equals(to, ignoreCase = true) && it.templateKey == "email.account.verifyEmail" }
        return Regex("\\b(\\d{6})\\b").find(mail.text)!!.groupValues[1]
    }

    private suspend fun HttpClient.login(email: String, device: TestDevice, geo: Map<String, String> = emptyMap()): LoginResponse {
        val start: ChallengeResponse = post("/v1/auth/email/start") {
            contentType(ContentType.Application.Json); setBody(EmailStartRequest(email, marketingConsent = true))
        }.body()
        return post("/v1/auth/email/verify") {
            contentType(ContentType.Application.Json)
            geo.forEach { (k, v) -> header(k, v) }
            setBody(EmailVerifyRequest(start.challengeId, lastCode(email), device.info))
        }.body()
    }

    @Test
    fun `E-Mail-Login legt Konto an, schickt Code und Willkommensmail`() = test { client ->
        val device = TestDevice()
        val result = client.login("Alex@Example.org", device)
        assertEquals("ok", result.status)
        assertTrue(result.isNewUser)
        assertEquals("owner", result.user!!.workspace.role)
        assertEquals("Inhaber:in", result.user.workspace.roleName)

        val codeMail = mails.sent.first { it.templateKey == "email.account.verifyEmail" }
        assertTrue(codeMail.subject.matches(Regex("\\d{6} ist dein Bestätigungscode für Nova")), codeMail.subject)
        assertTrue("auch nicht unser Support" in codeMail.text)
        assertTrue(mails.sent.any { it.templateKey == "email.onboarding.welcome" && "einem Code per E-Mail" in it.text })

        val me: UserDto = client.get("/v1/me") { bearerAuth(result.tokens!!.accessToken) }.body()
        assertEquals("alex@example.org", me.email)
    }

    @Test
    fun `falscher Code zaehlt Versuche und sperrt nach fuenf`() = test { client ->
        val start: ChallengeResponse = client.post("/v1/auth/email/start") {
            contentType(ContentType.Application.Json); setBody(EmailStartRequest("wrong@example.org"))
        }.body()
        val right = lastCode("wrong@example.org")
        val wrong = if (right == "000000") "111111" else "000000"
        val first = client.post("/v1/auth/email/verify") {
            contentType(ContentType.Application.Json); setBody(EmailVerifyRequest(start.challengeId, wrong, TestDevice().info))
        }
        assertEquals(HttpStatusCode.BadRequest, first.status)
        assertTrue("noch 4 Versuche" in first.bodyAsText(), first.bodyAsText())
        repeat(4) {
            client.post("/v1/auth/email/verify") {
                contentType(ContentType.Application.Json); setBody(EmailVerifyRequest(start.challengeId, wrong, TestDevice().info))
            }
        }
        val afterLimit = client.post("/v1/auth/email/verify") {
            contentType(ContentType.Application.Json); setBody(EmailVerifyRequest(start.challengeId, right, TestDevice().info))
        }
        assertEquals(HttpStatusCode.TooManyRequests, afterLimit.status)
    }

    @Test
    fun `Refresh braucht die Signatur des Geraets und erkennt Wiederverwendung`() = test { client ->
        val device = TestDevice()
        val tokens = client.login("refresh@example.org", device).tokens!!

        suspend fun refresh(token: String, signer: TestDevice): HttpResponse {
            val ts = Instant.now().epochSecond
            return client.post("/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(token, tokens.deviceId, ts, signer.sign("nova-refresh\n$token\n$ts")))
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, refresh(tokens.refreshToken, TestDevice()).status, "fremder Schlüssel")
        val ok = refresh(tokens.refreshToken, device)
        assertEquals(HttpStatusCode.OK, ok.status)
        val next: Tokens = ok.body()

        // Altes Token erneut benutzen = möglicher Diebstahl → Gerät wird abgemeldet.
        assertEquals(HttpStatusCode.Unauthorized, refresh(tokens.refreshToken, device).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(next.refreshToken, device).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(next.accessToken) }.status)
    }

    @Test
    fun `neues Geraet bekommt Warnmail und Das-war-ich-nicht sperrt alles`() = test { client ->
        val first = client.login("owner@example.org", TestDevice("Pixel 10")).tokens!!
        val second = client.login("owner@example.org", TestDevice("Unbekanntes Tablet"), mapOf("X-Client-Geo-Country" to "DE", "X-Client-Geo-City" to "Berlin"))
        assertEquals("ok", second.status)
        val warning = mails.sent.last { it.templateKey == "email.security.newLogin" }
        assertTrue("Unbekanntes Tablet" in warning.text && "Berlin, DE" in warning.text, warning.text)
        val link = Regex("http://localhost:8080/s/not-me\\?t=[A-Za-z0-9_-]+").find(warning.text)!!.value
        val token = link.substringAfter("t=")

        // GET zeigt nur die Bestätigungsseite (E-Mail-Scanner lösen nichts aus).
        assertEquals(HttpStatusCode.OK, client.get("/s/not-me?t=$token").status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(first.accessToken) }.status)

        val locked = client.submitForm("/s/not-me", parameters { append("t", token) })
        assertEquals(HttpStatusCode.OK, locked.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(first.accessToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(second.tokens!!.accessToken) }.status)
        assertTrue(mails.sent.any { it.templateKey == "email.security.accountLocked" })
        // Der Link funktioniert nur einmal.
        assertEquals(HttpStatusCode.BadRequest, client.submitForm("/s/not-me", parameters { append("t", token) }).status)
    }

    @Test
    fun `Telefon per SMS und Zusatzbestaetigung bei neuem Geraet`() = test { client ->
        val home = TestDevice("Pixel 10")
        val tokens = client.login("phone@example.org", home).tokens!!

        val start: ChallengeResponse = client.post("/v1/me/phone/start") {
            bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json)
            setBody(PhoneStartRequest("+49 170 1234567", "sms"))
        }.body()
        val sms = phone.sent.last()
        assertEquals("+491701234567", sms.to)
        assertTrue(sms.text.endsWith("@app.example.com #" + sms.text.take(6)), sms.text)
        val me: UserDto = client.post("/v1/me/phone/verify") {
            bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json)
            setBody(CodeRequest(start.challengeId, sms.text.take(6)))
        }.body()
        assertEquals("+49 *** *** 4567", me.phoneMasked)
        assertTrue(mails.sent.any { it.templateKey == "email.security.mfaEnabled" })

        // Anruf statt SMS liefert eine Ansage mit dem Code.
        client.post("/v1/me/phone/start") {
            bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json)
            setBody(PhoneStartRequest("+491701234567", "call"))
        }
        assertEquals("call", phone.sent.last().kind)
        assertTrue("Ich wiederhole" in phone.sent.last().text)

        // Unbekanntes Gerät (+30) → Zusatzbestätigung per SMS.
        val stranger = TestDevice("Fremdes Handy")
        val login = client.login("phone@example.org", stranger)
        assertEquals("step_up", login.status)
        val stepUpSms = phone.sent.last()
        assertTrue("Fremdes Handy" in stepUpSms.text)
        val done: LoginResponse = client.post("/v1/auth/step-up/verify") {
            contentType(ContentType.Application.Json)
            setBody(StepUpVerifyRequest(login.stepUp!!.challengeId, stepUpSms.text.take(6), stranger.info))
        }.body()
        assertEquals("ok", done.status)

        // Bekanntes Gerät braucht keine Zusatzbestätigung.
        assertEquals("ok", client.login("phone@example.org", home).status)
    }

    @Test
    fun `Chat streamt Antwort und speichert Verlauf`() = test { client ->
        val tokens = client.login("chat@example.org", TestDevice()).tokens!!
        val conv: ConversationDto = client.post("/v1/conversations") {
            bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json); setBody(CreateConversationRequest())
        }.body()
        val stream = client.post("/v1/conversations/${conv.id}/messages") {
            bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("Wie plane ich einen Umzug?"))
        }
        assertEquals(ContentType.Text.EventStream, stream.contentType()?.withoutParameters())
        val events = stream.bodyAsText().lines().filter { it.startsWith("data: ") }
            .map { ApiJson.decodeFromString(StreamEvent.serializer(), it.removePrefix("data: ")) }
        assertEquals("start", events.first().type)
        assertEquals("Wie plane ich einen Umzug?", events.first().conversationTitle)
        assertTrue(events.count { it.type == "delta" } > 10)
        assertEquals("done", events.last().type)

        val detail: ConversationDetailDto = client.get("/v1/conversations/${conv.id}") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(listOf("user", "assistant"), detail.messages.map { it.role })
        assertTrue("Testmodus" in detail.messages[1].content)

        // Fremde Chats sind nicht sichtbar.
        val other = client.login("other@example.org", TestDevice()).tokens!!
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/conversations/${conv.id}") { bearerAuth(other.accessToken) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/conversations/${conv.id}/messages") {
            bearerAuth(other.accessToken); contentType(ContentType.Application.Json); setBody(SendMessageRequest("hallo"))
        }.status)

        assertEquals(HttpStatusCode.OK, client.delete("/v1/conversations/${conv.id}") { bearerAuth(tokens.accessToken) }.status)
    }

    @Test
    fun `Inhalte mit ETag und Anti-Phishing-Code in E-Mails`() = test { client ->
        val first = client.get("/v1/content/de")
        val bundle: AppContentBundle = first.body()
        assertEquals("Mit Google fortfahren", bundle.uiTexts["auth.continueWithGoogle"])
        assertEquals(1, bundle.onboarding.first().order)
        val etag = first.headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NotModified, client.get("/v1/content/de") { header(HttpHeaders.IfNoneMatch, etag) }.status)

        val tokens = client.login("phrase@example.org", TestDevice()).tokens!!
        client.put("/v1/me/anti-phishing") {
            bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json); setBody(AntiPhishingRequest("Blaue Giraffe"))
        }
        client.post("/v1/auth/email/start") { contentType(ContentType.Application.Json); setBody(EmailStartRequest("phrase@example.org")) }
        val codeMail = mails.sent.last { it.templateKey == "email.account.verifyEmail" }
        assertTrue("Dein Anti-Phishing-Code: Blaue Giraffe" in codeMail.text, codeMail.text)
    }

    @Test
    fun `fremde Links in E-Mails werden nicht versendet`() = runBlocking {
        val before = mails.sent.size
        val ok = services.email.send("x@example.org", "email.onboarding.welcome", mapOf("firstName" to "https://evil.example.net/login", "loginMethod" to "x", "appUrl" to "https://app.example.com/open"))
        assertFalse(ok)
        assertEquals(before, mails.sent.size)
        assertTrue(services.email.send("x@example.org", "email.onboarding.welcome", mapOf("firstName" to "Kim", "loginMethod" to "x", "appUrl" to "https://app.example.com/open")))
    }

    @Test
    fun `Onboarding-Strecke sendet Sicherheitscheck an Tag 3 genau einmal`() = test { client ->
        val tokens = client.login("drip@example.org", TestDevice()).tokens!!
        services.db.tx { update("UPDATE users SET created_at = now() - interval '3 days 1 hour' WHERE lower(email) = 'drip@example.org'") }
        assertEquals(2, services.onboarding.runOnce(), "Tag 1 (Einwilligung) und Tag 3")
        assertEquals(0, services.onboarding.runOnce())
        val check = mails.sent.last { it.templateKey == "email.onboarding.day3SecurityCheck" }
        assertTrue("Sicherheitscheck" in check.subject)
        val tips = mails.sent.last { it.templateKey == "email.onboarding.day1Tips" }
        assertTrue("/email/unsubscribe?u=" in tips.text, "Abmeldelink in Marketing-Mail")
        assertNotNull(tokens)
    }

    @Test
    fun `Risikoregeln`() {
        val base = RiskInput(deviceKnown = true, isNewUser = false, geo = GeoInfo("DE", "München", 48.1, 11.6), connectionType = "wifi",
            recentFailures = 0, knownCountries = setOf("DE"), lastLogin = null)
        assertEquals(RiskResult.Decision.ALLOW, RiskEngine.evaluate(base).decision)
        assertEquals(RiskResult.Decision.STEP_UP, RiskEngine.evaluate(base.copy(deviceKnown = false)).decision)
        assertEquals(RiskResult.Decision.BLOCK, RiskEngine.evaluate(base.copy(deviceKnown = false, connectionType = "vpn", geo = GeoInfo("BR", "São Paulo", -23.5, -46.6))).decision)
        val travel = base.copy(
            deviceKnown = false,
            geo = GeoInfo("US", "New York", 40.7, -74.0),
            knownCountries = setOf("DE", "US"),
            lastLogin = PreviousLogin(Instant.now().minus(Duration.ofHours(1)), "DE", 48.1, 11.6),
        )
        assertTrue(RiskEngine.evaluate(travel).reasons.any { "unmögliche Reise" in it })
        assertEquals(RiskResult.Decision.ALLOW, RiskEngine.evaluate(base.copy(isNewUser = true, deviceKnown = false)).decision)
        assertEquals(UUID.randomUUID().toString().length, 36)
    }
}
