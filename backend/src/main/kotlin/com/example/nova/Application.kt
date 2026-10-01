package com.example.nova

import com.auth0.jwt.JWT
import com.example.nova.auth.AuthService
import com.example.nova.auth.IdTokenVerifier
import com.example.nova.auth.Principal
import com.example.nova.auth.RequestContext
import com.example.nova.chat.AnthropicProvider
import com.example.nova.chat.ChatService
import com.example.nova.chat.LlmProvider
import com.example.nova.chat.MockProvider
import com.example.nova.chat.OpenAiCompatibleProvider
import com.example.nova.content.ContentService
import com.example.nova.db.Database
import com.example.nova.me.AccountService
import com.example.nova.messaging.DevPhoneChannel
import com.example.nova.messaging.EmailService
import com.example.nova.messaging.MailTransport
import com.example.nova.messaging.OnboardingScheduler
import com.example.nova.messaging.PhoneChannel
import com.example.nova.messaging.PhoneService
import com.example.nova.messaging.SmtpTransport
import com.example.nova.messaging.TwilioPhoneChannel
import com.example.nova.risk.GeoInfo
import com.example.nova.web.webRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/** Alle Dienste des Backends. Tests ersetzen einzelne Teile (z. B. Mail-Transport, Modell). */
class Services(
    val config: Config,
    val db: Database,
    val http: HttpClient,
    val content: ContentService,
    val email: EmailService,
    val phone: PhoneService,
    val auth: AuthService,
    val account: AccountService,
    val chat: ChatService,
    val onboarding: OnboardingScheduler,
) {
    companion object {
        fun create(
            config: Config,
            mailTransport: MailTransport? = null,
            phoneChannel: PhoneChannel? = null,
            llm: LlmProvider? = null,
            idTokens: IdTokenVerifier? = null,
        ): Services {
            val db = Database(config.databaseUrl, config.databaseUser, config.databasePassword).also { it.migrate() }
            val http = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 10 * 60 * 1000
                    connectTimeoutMillis = 10_000
                }
            }
            val content = ContentService(http, config.strapiUrl, config.strapiToken)
            val transport = mailTransport ?: SmtpTransport(config.smtpHost, config.smtpPort, config.smtpUser, config.smtpPassword, config.smtpStartTls, config.mailFrom)
            val email = EmailService(content, transport)
            val channel = phoneChannel ?: when (config.phoneProvider) {
                "twilio" -> TwilioPhoneChannel(
                    http,
                    config.twilioAccountSid ?: error("TWILIO_ACCOUNT_SID fehlt"),
                    config.twilioAuthToken ?: error("TWILIO_AUTH_TOKEN fehlt"),
                    config.twilioFrom ?: error("TWILIO_FROM fehlt"),
                )
                else -> DevPhoneChannel(transport)
            }
            val phone = PhoneService(content, channel)
            val auth = AuthService(config, db, email, phone, content, idTokens ?: IdTokenVerifier(config.googleClientIds, config.appleClientIds))
            val provider = llm ?: when (config.llmProvider) {
                "anthropic" -> AnthropicProvider(config.anthropicModel)
                "openai" -> OpenAiCompatibleProvider(http, config.openAiBaseUrl ?: error("OPENAI_BASE_URL fehlt"), config.openAiApiKey, config.openAiModels)
                else -> MockProvider()
            }
            return Services(
                config, db, http, content, email, phone, auth,
                AccountService(config, db, auth, email, phone, content),
                ChatService(db, provider, content),
                OnboardingScheduler(config, db, email),
            )
        }
    }
}

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun main() {
    val config = Config.fromEnv()
    val services = Services.create(config)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(services)
        if (config.onboardingEnabled) services.onboarding.start(this)
    }.start(wait = true)
}

fun Application.module(s: Services) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/health" }
    }
    // Nur hinter einem Load Balancer aktivieren – sonst könnten Clients ihre IP fälschen.
    if (s.config.trustGeoHeaders) install(XForwardedHeaders)
    install(ContentNegotiation) { json(ApiJson) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.code, e.message)) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Ungültige Anfrage."))
        }
        exception<Throwable> { call, e ->
            this@module.environment.log.error("Unerwarteter Fehler", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Da ist etwas schiefgelaufen. Bitte versuch es erneut."))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ApiError("unauthorized", "Nicht angemeldet"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ApiError("rate_limited", "Zu viele Anfragen. Bitte warte einen Moment."))
        }
    }
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { it.request.origin.remoteHost }
        }
        register(RateLimitName("chat")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { it.principal<JWTPrincipal>()?.subject ?: it.request.origin.remoteHost }
        }
    }
    install(io.ktor.server.auth.Authentication) {
        jwt("app") {
            verifier(JWT.require(s.auth.jwtAlgorithm).withIssuer(AuthService.ISSUER).withAudience(AuthService.AUDIENCE).build())
            validate { credential ->
                val userId = credential.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@validate null
                val deviceId = credential.payload.getClaim("did").asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@validate null
                val active = withContext(Dispatchers.IO) { s.auth.isSessionActive(userId, deviceId) }
                if (active) JWTPrincipal(credential.payload) else null
            }
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok", "llm" to s.config.llmProvider)) }

        get("/v1/content/{locale}") {
            val bundle = s.content.get(call.parameters["locale"] ?: "de").appBundle
            val etag = "\"${bundle.version}\""
            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            if (call.request.header(HttpHeaders.IfNoneMatch) == etag) call.respond(HttpStatusCode.NotModified)
            else call.respond(bundle)
        }

        post("/internal/content/invalidate") {
            val secret = System.getenv("STRAPI_WEBHOOK_SECRET")
            if (secret.isNullOrBlank() || call.request.header("X-Webhook-Secret") != secret) throw unauthorized()
            s.content.invalidate()
            call.respond(mapOf("ok" to true))
        }

        rateLimit(RateLimitName("auth")) {
            route("/v1/auth") {
                get("/providers") {
                    call.respond(mapOf("google" to s.auth.let { s.config.googleClientIds.isNotEmpty() }, "apple" to s.config.appleClientIds.isNotEmpty()))
                }
                post("/email/start") {
                    val req = call.receive<EmailStartRequest>()
                    call.respond(s.auth.startEmailLogin(req.email, req.marketingConsent, call.requestContext(s.config)))
                }
                post("/email/verify") {
                    val req = call.receive<EmailVerifyRequest>()
                    call.respond(s.auth.verifyEmailLogin(req.challengeId, req.code, req.device, call.requestContext(s.config)))
                }
                post("/google") {
                    val req = call.receive<IdTokenLoginRequest>()
                    call.respond(s.auth.loginWithIdToken("google", req.idToken, req.device, req.firstName, call.requestContext(s.config)))
                }
                post("/apple") {
                    val req = call.receive<IdTokenLoginRequest>()
                    call.respond(s.auth.loginWithIdToken("apple", req.idToken, req.device, req.firstName, call.requestContext(s.config)))
                }
                post("/step-up/verify") {
                    val req = call.receive<StepUpVerifyRequest>()
                    call.respond(s.auth.verifyStepUp(req.challengeId, req.code, req.device, call.requestContext(s.config)))
                }
                post("/refresh") {
                    val req = call.receive<RefreshRequest>()
                    call.respond(s.auth.refresh(req.refreshToken, req.deviceId, req.timestamp, req.signature, call.requestContext(s.config)))
                }
            }
        }

        authenticate("app") {
            appRoutes(s)
        }

        webRoutes(s)
    }
}

private fun Route.appRoutes(s: Services) {
    post("/v1/auth/logout") {
        s.auth.logout(call.appPrincipal())
        call.respond(mapOf("ok" to true))
    }

    route("/v1/me") {
        get { call.respond(s.account.me(call.appPrincipal())) }
        patch { call.respond(s.account.update(call.appPrincipal(), call.receive<UpdateMeRequest>())) }
        put("/anti-phishing") { call.respond(s.account.setAntiPhishingPhrase(call.appPrincipal(), call.receive<AntiPhishingRequest>().phrase)) }
        get("/security") { call.respond(s.account.securityStatus(call.appPrincipal())) }
        rateLimit(RateLimitName("auth")) {
            post("/phone/start") {
                val req = call.receive<PhoneStartRequest>()
                call.respond(s.account.startPhone(call.appPrincipal(), req.phone, req.channel, call.requestContext(s.config)))
            }
            post("/phone/verify") {
                val req = call.receive<CodeRequest>()
                call.respond(s.account.verifyPhone(call.appPrincipal(), req.challengeId, req.code))
            }
        }
    }

    route("/v1/devices") {
        get { call.respond(s.account.devices(call.appPrincipal())) }
        delete("/{id}") {
            s.account.revokeDevice(call.appPrincipal(), call.parameters["id"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/revoke-others") { call.respond(mapOf("revoked" to s.account.revokeOtherDevices(call.appPrincipal()))) }
    }

    get("/v1/models") { call.respond(s.chat.models) }

    route("/v1/conversations") {
        get { call.respond(s.chat.list(call.appPrincipal())) }
        post { call.respond(s.chat.create(call.appPrincipal(), call.receive<CreateConversationRequest>().model)) }
        get("/{id}") { call.respond(s.chat.get(call.appPrincipal(), call.parameters["id"]!!)) }
        delete("/{id}") {
            s.chat.delete(call.appPrincipal(), call.parameters["id"]!!)
            call.respond(mapOf("ok" to true))
        }
        rateLimit(RateLimitName("chat")) {
            post("/{id}/messages") {
                val turn = s.chat.prepare(call.appPrincipal(), call.parameters["id"]!!, call.receive<SendMessageRequest>().content)
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.response.header("X-Accel-Buffering", "no")
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    s.chat.stream(turn).collect { event ->
                        writeStringUtf8("data: ${ApiJson.encodeToString(StreamEvent.serializer(), event)}\n\n")
                        flush()
                    }
                }
            }
        }
    }
}

fun ApplicationCall.appPrincipal(): Principal {
    val jwt = principal<JWTPrincipal>() ?: throw unauthorized()
    return Principal(
        userId = UUID.fromString(jwt.subject),
        deviceId = UUID.fromString(jwt.payload.getClaim("did").asString()),
        workspaceId = UUID.fromString(jwt.payload.getClaim("ws").asString()),
        role = jwt.payload.getClaim("role").asString(),
    )
}

/** IP und ungefährer Standort. Standort-Header setzt der GCP-Load-Balancer ({client_region}, {client_city}, {client_city_lat_long}). */
fun ApplicationCall.requestContext(config: Config): RequestContext {
    val geo = if (config.trustGeoHeaders || config.devMode) {
        val latLong = request.header("X-Client-Geo-LatLong")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        GeoInfo(
            country = request.header("X-Client-Geo-Country")?.takeIf { it.isNotBlank() },
            city = request.header("X-Client-Geo-City")?.takeIf { it.isNotBlank() },
            latitude = latLong?.getOrNull(0),
            longitude = latLong?.getOrNull(1),
        )
    } else GeoInfo.UNKNOWN
    return RequestContext(request.origin.remoteHost, geo, request.header(HttpHeaders.UserAgent))
}
