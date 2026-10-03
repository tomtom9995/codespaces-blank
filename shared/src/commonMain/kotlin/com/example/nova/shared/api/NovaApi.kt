package com.example.nova.shared.api

import com.example.nova.shared.platform.DeviceIdentity
import com.example.nova.shared.platform.KeyValueStore
import com.example.nova.shared.platform.currentEpochSeconds
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** Rücksprungadresse der Apps nach dem zentralen Login (im Backend in OIDC_APP_REDIRECTS erlaubt). */
const val CENTRAL_LOGIN_REDIRECT = "nova://auth"

class NovaApiException(val status: Int, val code: String, override val message: String) : Exception(message)

/** Gespeicherte Anmeldung. Das Refresh-Token ist ohne den Geräteschlüssel wertlos. */
@Serializable
data class StoredSession(val accessToken: String, val refreshToken: String, val deviceId: String)

class SessionStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): StoredSession? = store.get(KEY)?.let { runCatching { json.decodeFromString(StoredSession.serializer(), it) }.getOrNull() }
    fun save(session: StoredSession?) = store.set(KEY, session?.let { json.encodeToString(StoredSession.serializer(), it) })
    private companion object { const val KEY = "nova.session" }
}

/**
 * Schnittstelle zum Nova-Backend. Erneuert abgelaufene Access-Tokens automatisch –
 * jede Erneuerung wird mit dem hardwaregebundenen Geräteschlüssel signiert.
 */
class NovaApi(
    baseUrl: String,
    private val sessions: SessionStore,
    private val device: DeviceIdentity,
    engine: HttpClientEngine? = null,
    /** Wird aufgerufen, wenn die Sitzung endgültig ungültig ist (z. B. Gerät abgemeldet). */
    var onSessionExpired: () -> Unit = {},
) {
    private val baseUrl = baseUrl.trimEnd('/')
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val refreshLock = Mutex()

    private val client: HttpClient = (if (engine != null) HttpClient(engine) { configure() } else HttpClient { configure() })

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        expectSuccess = false
        install(ContentNegotiation) { json(this@NovaApi.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60 * 1000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 2 * 60 * 1000
        }
    }

    fun deviceInfo() = DeviceInfo(device.publicKeyBase64(), device.deviceName, device.platform, device.connectionType())

    val isLoggedIn: Boolean get() = sessions.load() != null

    // ---------- Inhalte ----------

    /** Gibt null zurück, wenn sich nichts geändert hat (ETag). */
    @Throws(Exception::class)
    suspend fun content(locale: String, currentVersion: String?): AppContentBundle? {
        val response = client.request("$baseUrl/v1/content/$locale") {
            method = HttpMethod.Get
            currentVersion?.takeIf { it != "bundled" }?.let { header(HttpHeaders.IfNoneMatch, "\"$it\"") }
        }
        if (response.status == HttpStatusCode.NotModified) return null
        return response.decode()
    }

    @Throws(Exception::class)
    suspend fun providers(): AuthProviders = send(HttpMethod.Get, "/v1/auth/providers", auth = false)

    // ---------- Anmeldung ----------

    @Throws(Exception::class)
    suspend fun startEmail(email: String, marketingConsent: Boolean): ChallengeResponse =
        send(HttpMethod.Post, "/v1/auth/email/start", enc(EmailStartRequest(email, marketingConsent)), auth = false)

    @Throws(Exception::class)
    suspend fun verifyEmail(challengeId: String, code: String): LoginResponse =
        send<LoginResponse>(HttpMethod.Post, "/v1/auth/email/verify", enc(EmailVerifyRequest(challengeId, code, deviceInfo())), auth = false).also(::store)

    @Throws(Exception::class)
    suspend fun verifyStepUp(challengeId: String, code: String): LoginResponse =
        send<LoginResponse>(HttpMethod.Post, "/v1/auth/step-up/verify", enc(StepUpVerifyRequest(challengeId, code, deviceInfo())), auth = false).also(::store)

    @Throws(Exception::class)
    suspend fun loginWithIdToken(provider: String, idToken: String, firstName: String?): LoginResponse =
        send<LoginResponse>(HttpMethod.Post, "/v1/auth/$provider", enc(IdTokenLoginRequest(idToken, deviceInfo(), firstName)), auth = false).also(::store)

    /** Adresse, die die App im Browser öffnet (Custom Tab / ASWebAuthenticationSession). */
    fun centralLoginUrl(appRedirect: String = CENTRAL_LOGIN_REDIRECT): String =
        "$baseUrl/v1/auth/oidc/start?redirect=" + appRedirect.encodeURLParameter()

    @Throws(Exception::class)
    suspend fun exchangeCentralLogin(code: String): LoginResponse =
        send<LoginResponse>(HttpMethod.Post, "/v1/auth/oidc/exchange", enc(OidcExchangeRequest(code, deviceInfo())), auth = false).also(::store)

    @Throws(Exception::class)
    suspend fun logout() {
        runCatching { send<Map<String, Boolean>>(HttpMethod.Post, "/v1/auth/logout") }
        sessions.save(null)
    }

    private fun store(response: LoginResponse) {
        response.tokens?.let { sessions.save(StoredSession(it.accessToken, it.refreshToken, it.deviceId)) }
    }

    // ---------- Konto ----------

    @Throws(Exception::class)
    suspend fun me(): UserDto = send(HttpMethod.Get, "/v1/me")
    @Throws(Exception::class)
    suspend fun updateMe(request: UpdateMeRequest): UserDto = send(HttpMethod.Patch, "/v1/me", enc(request))
    @Throws(Exception::class)
    suspend fun setAntiPhishingPhrase(phrase: String): UserDto = send(HttpMethod.Put, "/v1/me/anti-phishing", enc(AntiPhishingRequest(phrase)))
    @Throws(Exception::class)
    suspend fun securityStatus(): SecurityStatus = send(HttpMethod.Get, "/v1/me/security")
    @Throws(Exception::class)
    suspend fun startPhone(phone: String, channel: String): ChallengeResponse = send(HttpMethod.Post, "/v1/me/phone/start", enc(PhoneStartRequest(phone, channel)))
    @Throws(Exception::class)
    suspend fun verifyPhone(challengeId: String, code: String): UserDto = send(HttpMethod.Post, "/v1/me/phone/verify", enc(CodeRequest(challengeId, code)))
    @Throws(Exception::class)
    suspend fun devices(): List<DeviceDto> = send(HttpMethod.Get, "/v1/devices")
    @Throws(Exception::class)
    suspend fun revokeDevice(id: String) { send<Map<String, Boolean>>(HttpMethod.Delete, "/v1/devices/$id") }
    @Throws(Exception::class)
    suspend fun revokeOtherDevices() { send<Map<String, Int>>(HttpMethod.Post, "/v1/devices/revoke-others") }

    // ---------- Termine (Kalender des Corps) ----------

    @Throws(Exception::class)
    suspend fun events(days: Int = 180): List<EventDto> = send(HttpMethod.Get, "/v1/events?days=$days")

    // ---------- Dateien (Chattia-Cloud) ----------

    @Throws(Exception::class)
    suspend fun listFiles(path: String): FolderListing = send(HttpMethod.Get, "/v1/files?path=${path.encodeURLParameter()}")

    @Throws(Exception::class)
    suspend fun downloadFile(path: String): ByteArray =
        sendRaw(HttpMethod.Get, "/v1/files/content?path=${path.encodeURLParameter()}").readRawBytes()

    @Throws(Exception::class)
    suspend fun uploadFile(path: String, bytes: ByteArray, contentType: String?) {
        val type = contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.OctetStream
        sendRaw(HttpMethod.Put, "/v1/files/content?path=${path.encodeURLParameter()}", ByteArrayContent(bytes, type))
    }

    @Throws(Exception::class)
    suspend fun createFolder(path: String) {
        sendRaw(HttpMethod.Post, "/v1/files/folder", TextContent(enc(CreateFolderRequest(path)), ContentType.Application.Json))
    }

    @Throws(Exception::class)
    suspend fun deleteFile(path: String) {
        sendRaw(HttpMethod.Delete, "/v1/files?path=${path.encodeURLParameter()}")
    }

    // ---------- Chat ----------

    @Throws(Exception::class)
    suspend fun models(): List<ModelDto> = send(HttpMethod.Get, "/v1/models")
    @Throws(Exception::class)
    suspend fun conversations(): List<ConversationDto> = send(HttpMethod.Get, "/v1/conversations")
    @Throws(Exception::class)
    suspend fun createConversation(model: String? = null): ConversationDto = send(HttpMethod.Post, "/v1/conversations", enc(CreateConversationRequest(model)))
    @Throws(Exception::class)
    suspend fun conversation(id: String): ConversationDetailDto = send(HttpMethod.Get, "/v1/conversations/$id")
    @Throws(Exception::class)
    suspend fun deleteConversation(id: String) { send<Map<String, Boolean>>(HttpMethod.Delete, "/v1/conversations/$id") }

    /** Antwort als Strom (Server-Sent Events). */
    fun sendMessage(conversationId: String, text: String): Flow<StreamEvent> = flow {
        suspend fun open(token: String?, block: suspend (HttpResponse) -> Unit) =
            client.preparePost("$baseUrl/v1/conversations/$conversationId/messages") {
                header(HttpHeaders.Accept, "text/event-stream")
                token?.let { bearerAuth(it) }
                setBody(TextContent(enc(SendMessageRequest(text)), ContentType.Application.Json))
            }.execute(block)

        var retried = false
        while (true) {
            var unauthorized = false
            open(sessions.load()?.accessToken) { response ->
                when {
                    response.status == HttpStatusCode.Unauthorized && !retried -> unauthorized = true
                    !response.status.isSuccess() -> throw response.toException()
                    else -> {
                        val channel = response.bodyAsChannel()
                        while (true) {
                            val line = channel.readUTF8Line() ?: break
                            if (line.startsWith("data:")) emit(json.decodeFromString(StreamEvent.serializer(), line.removePrefix("data:").trim()))
                        }
                    }
                }
            }
            if (unauthorized && refresh()) { retried = true; continue }
            if (unauthorized) expire()
            break
        }
    }

    // ---------- Technik ----------

    private inline fun <reified B> enc(value: B): String = json.encodeToString(value)

    private suspend inline fun <reified T> send(method: HttpMethod, path: String, body: String? = null, auth: Boolean = true): T {
        val build: HttpRequestBuilder.(String?) -> Unit = { token ->
            this.method = method
            if (auth && token != null) bearerAuth(token)
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }
        var response = client.request("$baseUrl$path") { build(sessions.load()?.accessToken) }
        if (auth && response.status == HttpStatusCode.Unauthorized) {
            if (refresh()) response = client.request("$baseUrl$path") { build(sessions.load()?.accessToken) }
            if (response.status == HttpStatusCode.Unauthorized) expire()
        }
        return response.decode()
    }

    /** Wie send, aber mit beliebigem Inhalt und roher Antwort (Dateien). */
    private suspend fun sendRaw(method: HttpMethod, path: String, body: OutgoingContent? = null): HttpResponse {
        val build: HttpRequestBuilder.(String?) -> Unit = { token ->
            this.method = method
            if (token != null) bearerAuth(token)
            if (body != null) setBody(body)
        }
        var response = client.request("$baseUrl$path") { build(sessions.load()?.accessToken) }
        if (response.status == HttpStatusCode.Unauthorized) {
            if (refresh()) response = client.request("$baseUrl$path") { build(sessions.load()?.accessToken) }
            if (response.status == HttpStatusCode.Unauthorized) expire()
        }
        if (!response.status.isSuccess()) throw response.toException()
        return response
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
        if (!status.isSuccess()) throw toException()
        return json.decodeFromString(bodyAsText())
    }

    private suspend fun HttpResponse.toException(): NovaApiException {
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val error = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
        return NovaApiException(status.value, error?.error ?: "http_${status.value}", error?.message ?: "Da ist etwas schiefgelaufen. Bitte versuch es erneut.")
    }

    private fun expire() {
        sessions.save(null)
        onSessionExpired()
    }

    /** Erneuert die Tokens; bei mehreren gleichzeitigen 401 nur einmal. */
    private suspend fun refresh(): Boolean {
        val before = sessions.load() ?: return false
        return refreshLock.withLock {
            val current = sessions.load() ?: return@withLock false
            if (current.accessToken != before.accessToken) return@withLock true
            val timestamp = currentEpochSeconds()
            val signature = Base64.encode(device.sign("nova-refresh\n${current.refreshToken}\n$timestamp".encodeToByteArray()))
            val response = client.request("$baseUrl/v1/auth/refresh") {
                method = HttpMethod.Post
                setBody(TextContent(enc(RefreshRequest(current.refreshToken, current.deviceId, timestamp, signature)), ContentType.Application.Json))
            }
            if (!response.status.isSuccess()) return@withLock false
            val tokens = json.decodeFromString(Tokens.serializer(), response.bodyAsText())
            sessions.save(StoredSession(tokens.accessToken, tokens.refreshToken, tokens.deviceId))
            true
        }
    }
}
