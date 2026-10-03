package com.example.nova.central

import com.example.nova.ApiException
import com.example.nova.Config
import com.example.nova.FileEntryDto
import com.example.nova.FolderListing
import com.example.nova.auth.Principal
import com.example.nova.badRequest
import com.example.nova.notFound
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Dateien des Nutzers in Nextcloud (WebDAV), immer im Namen des Nutzers mit seinem Token aus dem zentralen Login.
 * Das Backend hat keine eigenen Rechte auf Nextcloud – es sieht genau das, was der Nutzer auch im Browser sieht.
 * Beim ersten Zugriff legt Nextcloud das Konto selbst an (user_oidc mit auto_provision und Bearer-Prüfung).
 */
class FilesService(
    private val config: Config,
    private val http: HttpClient,
    private val oidc: OidcService,
) {
    private val log = LoggerFactory.getLogger(FilesService::class.java)

    val enabled: Boolean get() = config.nextcloudUrl != null && oidc.enabled

    private fun requireEnabled() {
        if (!enabled) throw ApiException(HttpStatusCode.NotImplemented, "files_disabled", "Die Dateiablage ist nicht eingerichtet.")
    }

    private fun davRoot(username: String) = "${config.nextcloudUrl}/remote.php/dav/files/${username.encodeURLPathPart()}"

    private fun davUrl(username: String, path: String): String {
        val segments = normalize(path)
        return davRoot(username) + "/" + segments.joinToString("/") { it.encodeURLPathPart() }
    }

    private suspend fun dav(
        p: Principal,
        method: HttpMethod,
        path: String,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = dav(p.userId, method, path, configure)

    private suspend fun dav(
        userId: java.util.UUID,
        method: HttpMethod,
        path: String,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        requireEnabled()
        val (account, token) = oidc.accessToken(userId)
        val response = http.request(davUrl(account.username, path)) {
            this.method = method
            bearerAuth(token)
            configure()
        }
        checkStatus(response, path)
        return response
    }

    private suspend fun checkStatus(response: HttpResponse, path: String) {
        if (response.status.isSuccess() || response.status == HttpStatusCode.MultiStatus) return
        val detail = runCatching { response.bodyAsText().take(300) }.getOrDefault("")
        throw when (response.status) {
            HttpStatusCode.NotFound -> notFound("Datei oder Ordner nicht gefunden: /${normalize(path).joinToString("/")}")
            HttpStatusCode.Unauthorized -> {
                log.warn("Nextcloud lehnt das Token ab: {}", detail)
                ApiException(HttpStatusCode.Conflict, "central_login_expired", "Bitte melde dich erneut mit deinem Chattia-Konto an.")
            }
            HttpStatusCode.Forbidden -> ApiException(HttpStatusCode.Forbidden, "forbidden", "Dafür fehlt dir in der Cloud die Berechtigung.")
            HttpStatusCode.MethodNotAllowed -> ApiException(HttpStatusCode.Conflict, "already_exists", "Der Ordner existiert bereits.")
            HttpStatusCode.InsufficientStorage -> ApiException(HttpStatusCode.InsufficientStorage, "quota_exceeded", "Dein Speicherplatz in der Cloud ist voll.")
            else -> {
                log.warn("Nextcloud-Fehler {}: {}", response.status.value, detail)
                ApiException(HttpStatusCode.BadGateway, "cloud_unavailable", "Die Cloud ist gerade nicht erreichbar.")
            }
        }
    }

    suspend fun list(p: Principal, path: String): FolderListing = list(p.userId, path)

    suspend fun list(userId: java.util.UUID, path: String): FolderListing {
        val response = dav(userId, HttpMethod("PROPFIND"), path) {
            header("Depth", "1")
            contentType(ContentType.Application.Xml)
            setBody(PROPFIND_BODY)
        }
        val (account, _) = oidc.accessToken(userId)
        val prefix = "/remote.php/dav/files/${account.username}/"
        val requested = normalize(path).joinToString("/")
        val entries = parseMultiStatus(response.bodyAsText(), prefix)
            .filter { it.path.trim('/') != requested }
            .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        return FolderListing("/$requested", entries)
    }

    /** Lädt eine Datei; [block] bekommt die Antwort von Nextcloud zum Durchreichen (Streaming, kein Zwischenspeichern). */
    suspend fun <T> download(p: Principal, path: String, block: suspend (HttpResponse) -> T): T {
        requireEnabled()
        if (normalize(path).isEmpty()) throw badRequest("invalid_path", "Bitte eine Datei angeben.")
        val (account, token) = oidc.accessToken(p.userId)
        return http.prepareRequest(davUrl(account.username, path)) {
            method = HttpMethod.Get
            bearerAuth(token)
        }.execute { response ->
            checkStatus(response, path)
            block(response)
        }
    }

    /** Textinhalt einer Datei (für den Assistenten), höchstens [maxBytes]. */
    suspend fun readText(userId: java.util.UUID, path: String, maxBytes: Int = 200_000): String {
        val response = dav(userId, HttpMethod.Get, path)
        val bytes = response.body<ByteArray>()
        return bytes.copyOf(minOf(bytes.size, maxBytes)).decodeToString()
    }

    suspend fun upload(p: Principal, path: String, body: ByteReadChannel, type: ContentType?, length: Long?) {
        if (normalize(path).isEmpty()) throw badRequest("invalid_path", "Bitte einen Dateinamen angeben.")
        dav(p, HttpMethod.Put, path) {
            if (type != null) contentType(type)
            if (length != null) header("Content-Length", length.toString())
            setBody(body)
        }
    }

    suspend fun createFolder(p: Principal, path: String) {
        if (normalize(path).isEmpty()) throw badRequest("invalid_path", "Bitte einen Ordnernamen angeben.")
        dav(p, HttpMethod("MKCOL"), path)
    }

    suspend fun delete(p: Principal, path: String) {
        if (normalize(path).isEmpty()) throw badRequest("invalid_path", "Der Hauptordner kann nicht gelöscht werden.")
        dav(p, HttpMethod.Delete, path)
    }

    companion object {
        private val PROPFIND_BODY = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop><d:getlastmodified/><d:getcontentlength/><d:getcontenttype/><d:resourcetype/><oc:size/><oc:permissions/></d:prop>
</d:propfind>"""

        /** Zerlegt einen Pfad in Segmente und weist alles zurück, was aus dem eigenen Bereich herausführen könnte. */
        fun normalize(path: String): List<String> {
            if (path.length > 4096) throw badRequest("invalid_path", "Der Pfad ist zu lang.")
            val segments = path.split('/').filter { it.isNotEmpty() }
            for (s in segments) {
                if (s == "." || s == ".." || s.contains('\\') || s.any { it.code < 0x20 || it.code == 0x7f } || s.length > 255) {
                    throw badRequest("invalid_path", "Ungültiger Pfad.")
                }
            }
            return segments
        }

        internal fun parseMultiStatus(xml: String, hrefPrefix: String): List<FileEntryDto> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Schutz vor XXE: keine externen Entitäten, keine DOCTYPE-Deklarationen
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            return (0 until responses.length).mapNotNull { i ->
                val r = responses.item(i) as Element
                val href = URLDecoder.decode(r.text("DAV:", "href") ?: return@mapNotNull null, Charsets.UTF_8)
                val relative = href.substringAfter(hrefPrefix, missingDelimiterValue = "").trimEnd('/')
                val isFolder = r.getElementsByTagNameNS("DAV:", "collection").length > 0
                FileEntryDto(
                    name = relative.substringAfterLast('/'),
                    path = "/$relative",
                    isFolder = isFolder,
                    size = (r.text("http://owncloud.org/ns", "size") ?: r.text("DAV:", "getcontentlength"))?.toLongOrNull(),
                    modified = r.text("DAV:", "getlastmodified")?.let {
                        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull()
                    },
                    contentType = if (isFolder) null else r.text("DAV:", "getcontenttype"),
                    // Nextcloud: D = löschen erlaubt (fehlt z. B. beim Wurzelordner eines Team-Ordners)
                    canDelete = r.text("http://owncloud.org/ns", "permissions")?.contains('D') ?: true,
                )
            }
        }

        private fun Element.text(ns: String, name: String): String? =
            getElementsByTagNameNS(ns, name).item(0)?.textContent?.takeIf { it.isNotBlank() }
    }
}
