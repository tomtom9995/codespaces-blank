package com.example.nova.content

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class EmailTemplate(
    val key: String,
    val category: String,
    val subject: String,
    val preheader: String,
    val bodyMarkdown: String,
    val ctaLabel: String?,
    val ctaUrl: String?,
)

data class PushTemplate(val key: String, val title: String, val body: String)

@Serializable
data class OnboardingStep(
    val key: String,
    val order: Int,
    val title: String,
    val body: String,
    val primaryCta: String? = null,
    val secondaryCta: String? = null,
    val tertiaryCta: String? = null,
    val skippable: Boolean = false,
)

@Serializable
data class RoleText(val key: String, val scope: String, val name: String, val description: String)

data class ContentSettings(
    val appName: String,
    val domain: String,
    val supportEmail: String,
    val allowedLinkDomains: List<String>,
)

data class ContentBundle(
    val locale: String,
    val source: String,
    val settings: ContentSettings,
    val uiTexts: Map<String, String>,
    val onboarding: List<OnboardingStep>,
    val emails: Map<String, EmailTemplate>,
    val sms: Map<String, String>,
    val voice: Map<String, String>,
    val push: Map<String, PushTemplate>,
    val roles: Map<String, RoleText>,
) {
    /** Das Paket, das die Apps bekommen: nur Oberflächentexte, keine Nachrichtenvorlagen. */
    val appBundle: AppContentBundle by lazy {
        val withoutVersion = AppContentBundle("", locale, settings.appName, uiTexts.toSortedMap(), onboarding.sortedBy { it.order }, roles.values.toList())
        val version = sha256(Json.encodeToString(AppContentBundle.serializer(), withoutVersion)).take(16)
        withoutVersion.copy(version = version)
    }

    fun ui(key: String): String = uiTexts[key] ?: key
}

@Serializable
data class AppContentBundle(
    val version: String,
    val locale: String,
    val appName: String,
    val uiTexts: Map<String, String>,
    val onboarding: List<OnboardingStep>,
    val roles: List<RoleText>,
)

private val PLACEHOLDER = Regex("\\{([a-zA-Z][a-zA-Z0-9]*)}")

/** Setzt Platzhalter ein. Fehlende Werte werden leer, globale Werte ({appName} usw.) kommen aus den Einstellungen. */
fun ContentSettings.render(text: String, values: Map<String, String?>): String =
    PLACEHOLDER.replace(text) { m ->
        val name = m.groupValues[1]
        values[name] ?: when (name) {
            "appName" -> appName
            "domain" -> domain
            "supportEmail" -> supportEmail
            else -> ""
        }
    }

/**
 * Lädt Texte aus Strapi (wenn konfiguriert) und fällt auf die eingebaute Grundfassung aus
 * cms/content/<locale> zurück. Ergebnisse werden 5 Minuten zwischengespeichert.
 */
class ContentService(
    private val http: HttpClient,
    private val strapiUrl: String?,
    private val strapiToken: String?,
    private val cacheMillis: Long = 5 * 60 * 1000,
) {
    private val log = LoggerFactory.getLogger(ContentService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, ContentBundle>>()

    suspend fun get(locale: String = "de"): ContentBundle {
        val loc = if (locale in SUPPORTED) locale else "de"
        cache[loc]?.let { (at, bundle) -> if (System.currentTimeMillis() - at < cacheMillis) return bundle }
        val bundle = (if (strapiUrl != null) runCatching { fromStrapi(loc) }.onFailure {
            log.warn("Strapi nicht erreichbar, nutze eingebaute Texte: {}", it.message)
        }.getOrNull() else null) ?: bundled(loc)
        cache[loc] = System.currentTimeMillis() to bundle
        return bundle
    }

    fun invalidate() = cache.clear()

    fun bundled(locale: String): ContentBundle {
        fun read(file: String): JsonElement {
            val stream = javaClass.getResourceAsStream("/content/$locale/$file")
                ?: javaClass.getResourceAsStream("/content/de/$file")
                ?: error("Eingebaute Inhaltsdatei $file fehlt")
            return json.parseToJsonElement(stream.bufferedReader().readText())
        }
        return build(
            locale = locale,
            source = "bundled",
            settings = read("settings.json").jsonObject,
            uiTexts = read("ui-texts.json").jsonArray,
            onboarding = read("onboarding-steps.json").jsonArray,
            emails = read("email-templates.json").jsonArray,
            sms = read("sms-templates.json").jsonArray,
            voice = read("voice-templates.json").jsonArray,
            push = read("push-templates.json").jsonArray,
            roles = read("roles.json").jsonArray,
        )
    }

    private suspend fun fromStrapi(locale: String): ContentBundle {
        suspend fun fetch(path: String): JsonElement {
            val sep = if ('?' in path) '&' else '?'
            val response = http.get("$strapiUrl/api/$path${sep}locale=$locale&pagination[pageSize]=1000") {
                strapiToken?.let { header("Authorization", "Bearer $it") }
            }
            check(response.status.isSuccess()) { "Strapi $path: HTTP ${response.status.value}" }
            return json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]
                ?: error("Strapi $path: kein data-Feld")
        }
        val bundle = build(
            locale = locale,
            source = "strapi",
            settings = fetch("setting").jsonObject,
            uiTexts = fetch("ui-texts").jsonArray,
            onboarding = fetch("onboarding-steps").jsonArray,
            emails = fetch("email-templates").jsonArray,
            sms = fetch("sms-templates").jsonArray,
            voice = fetch("voice-templates").jsonArray,
            push = fetch("push-templates").jsonArray,
            roles = fetch("roles").jsonArray,
        )
        // Unvollständige Inhalte (z. B. frisch angelegtes CMS) mit der Grundfassung auffüllen.
        val base = bundled(locale)
        return bundle.copy(
            uiTexts = base.uiTexts + bundle.uiTexts,
            onboarding = bundle.onboarding.ifEmpty { base.onboarding },
            emails = base.emails + bundle.emails,
            sms = base.sms + bundle.sms,
            voice = base.voice + bundle.voice,
            push = base.push + bundle.push,
            roles = base.roles + bundle.roles,
        )
    }

    private fun build(
        locale: String,
        source: String,
        settings: JsonObject,
        uiTexts: JsonArray,
        onboarding: JsonArray,
        emails: JsonArray,
        sms: JsonArray,
        voice: JsonArray,
        push: JsonArray,
        roles: JsonArray,
    ): ContentBundle {
        fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.strList(name: String): List<String> = when (val v = this[name]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> v.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            else -> emptyList()
        }
        fun JsonObject.body(name: String): String = when (val v = this[name]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n\n")
            is JsonPrimitive -> v.contentOrNull ?: ""
            else -> ""
        }
        val objects = { arr: JsonArray -> arr.map { it.jsonObject }.filter { it.str("key") != null } }

        return ContentBundle(
            locale = locale,
            source = source,
            settings = ContentSettings(
                appName = settings.str("appName") ?: "Nova",
                domain = settings.str("domain") ?: "app.example.com",
                supportEmail = settings.str("supportEmail") ?: "support@example.com",
                allowedLinkDomains = settings.strList("allowedLinkDomains").ifEmpty { listOf("example.com") },
            ),
            uiTexts = objects(uiTexts).associate { it.str("key")!! to (it.str("text") ?: "") },
            onboarding = objects(onboarding).map {
                OnboardingStep(
                    key = it.str("key")!!,
                    order = (it["order"] as? JsonPrimitive)?.intOrNull ?: 0,
                    title = it.str("title") ?: "",
                    body = it.str("body") ?: "",
                    primaryCta = it.str("primaryCta"),
                    secondaryCta = it.str("secondaryCta"),
                    tertiaryCta = it.str("tertiaryCta"),
                    skippable = (it["skippable"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            },
            emails = objects(emails).associate {
                it.str("key")!! to EmailTemplate(
                    key = it.str("key")!!,
                    category = it.str("category") ?: "transactional",
                    subject = it.str("subject") ?: "",
                    preheader = it.str("preheader") ?: "",
                    bodyMarkdown = it.body("body"),
                    ctaLabel = it.str("ctaLabel"),
                    ctaUrl = it.str("ctaUrl"),
                )
            },
            sms = objects(sms).associate { it.str("key")!! to (it.str("text") ?: "") },
            voice = objects(voice).associate { it.str("key")!! to (it.str("text") ?: "") },
            push = objects(push).associate { it.str("key")!! to PushTemplate(it.str("key")!!, it.str("title") ?: "", it.str("body") ?: "") },
            roles = objects(roles).associate {
                it.str("key")!! to RoleText(it.str("key")!!, it.str("scope") ?: "workspace", it.str("name") ?: "", it.str("description") ?: "")
            },
        )
    }

    companion object {
        val SUPPORTED = setOf("de", "en")
    }
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
