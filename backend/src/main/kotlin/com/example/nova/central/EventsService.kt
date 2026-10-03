package com.example.nova.central

import com.example.nova.ApiException
import com.example.nova.Config
import com.example.nova.EventDto
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import org.w3c.dom.Element
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Termine aus den Kalendern des Corps (Nextcloud/CalDAV), gelesen mit dem Token des Nutzers:
 * Wer einen Kalender in der Cloud nicht sieht, sieht ihn auch in der App nicht.
 * Kalender werden über ihren Anzeigenamen gefunden (EVENTS_CALENDARS, Standard „Semesterprogramm*“).
 * Wiederkehrende Termine expandiert Nextcloud serverseitig (CalDAV expand).
 */
class EventsService(
    private val config: Config,
    private val http: HttpClient,
    private val oidc: OidcService,
    private val calendarPrefix: String = "Semesterprogramm",
) {
    val enabled: Boolean get() = config.nextcloudUrl != null && oidc.enabled

    suspend fun upcoming(userId: UUID, days: Long = 180): List<EventDto> {
        if (!enabled) throw ApiException(HttpStatusCode.NotImplemented, "events_disabled", "Termine sind nicht eingerichtet.")
        val (account, token) = oidc.accessToken(userId)
        val home = "${config.nextcloudUrl}/remote.php/dav/calendars/${account.username.encodeURLPathPart()}/"
        val calendars = dav(token, "PROPFIND", home, "1", CALENDARS_BODY)
            .let { parseCalendars(it) }
            .filter { it.second.startsWith(calendarPrefix) }
        val from = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val to = from.plus(days, ChronoUnit.DAYS)
        return calendars.flatMap { (href, name) ->
            val xml = dav(token, "REPORT", config.nextcloudUrl + href, "1", reportBody(from, to))
            parseCalendarData(xml).flatMap { parseEvents(it, name) }
        }.filter { !Instant.parse(it.end ?: it.start).isBefore(from) }.sortedBy { Instant.parse(it.start) }
    }

    private suspend fun dav(token: String, method: String, url: String, depth: String, body: String): String {
        val response = http.request(url) {
            this.method = HttpMethod(method)
            bearerAuth(token)
            header("Depth", depth)
            contentType(ContentType.Application.Xml)
            setBody(body)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ApiException(HttpStatusCode.Conflict, "central_login_expired", "Bitte melde dich erneut mit deinem Chattia-Konto an.")
        }
        if (!response.status.isSuccess()) throw ApiException(HttpStatusCode.BadGateway, "cloud_unavailable", "Die Cloud ist gerade nicht erreichbar.")
        return response.bodyAsText()
    }

    companion object {
        private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val BERLIN = ZoneId.of("Europe/Berlin")

        private const val CALENDARS_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>"""

        private fun reportBody(from: Instant, to: Instant) = """<?xml version="1.0"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><c:calendar-data><c:expand start="${UTC_STAMP.format(from)}" end="${UTC_STAMP.format(to)}"/></c:calendar-data></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
    <c:time-range start="${UTC_STAMP.format(from)}" end="${UTC_STAMP.format(to)}"/>
  </c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>"""

        private fun xml(text: String) = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(text.byteInputStream())

        /** (href, Anzeigename) aller Kalender-Sammlungen. */
        internal fun parseCalendars(text: String): List<Pair<String, String>> {
            val responses = xml(text).getElementsByTagNameNS("DAV:", "response")
            return (0 until responses.length).mapNotNull { i ->
                val r = responses.item(i) as Element
                if (r.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar").length == 0) return@mapNotNull null
                val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return@mapNotNull null
                val name = r.getElementsByTagNameNS("DAV:", "displayname").item(0)?.textContent ?: return@mapNotNull null
                href to name
            }
        }

        internal fun parseCalendarData(text: String): List<String> {
            val nodes = xml(text).getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
            return (0 until nodes.length).map { nodes.item(it).textContent }
        }

        /** Minimaler iCalendar-Leser für VEVENTs (gefaltete Zeilen, TZID, ganztägig, UTC). */
        internal fun parseEvents(ics: String, calendar: String): List<EventDto> {
            val unfolded = ics.replace(Regex("\r?\n[ \t]"), "")
            return Regex("BEGIN:VEVENT(.*?)END:VEVENT", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).mapNotNull { m ->
                val props = m.groupValues[1].lines().mapNotNull { line ->
                    Regex("^([A-Z-]+)((?:;[^:]*)?):(.*)$").find(line.trim())?.destructured?.let { (k, p, v) -> k to (p to v) }
                }.toMap()
                val (startParams, startValue) = props["DTSTART"] ?: return@mapNotNull null
                val allDay = startValue.length == 8
                val start = instant(startValue, startParams) ?: return@mapNotNull null
                val end = props["DTEND"]?.let { (p, v) -> instant(v, p) }
                val recurrence = props["RECURRENCE-ID"]?.second
                EventDto(
                    id = (props["UID"]?.second ?: start.toString()) + (recurrence?.let { "#$it" } ?: ""),
                    title = unescape(props["SUMMARY"]?.second ?: "Termin"),
                    start = start.toString(),
                    end = end?.toString(),
                    allDay = allDay,
                    location = props["LOCATION"]?.second?.let(::unescape)?.takeIf { it.isNotBlank() },
                    description = props["DESCRIPTION"]?.second?.let(::unescape)?.takeIf { it.isNotBlank() },
                    calendar = calendar,
                    internal = calendar.contains("intern", ignoreCase = true),
                )
            }.toList()
        }

        private fun instant(value: String, params: String): Instant? = runCatching {
            when {
                value.length == 8 -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(BERLIN).toInstant()
                value.endsWith("Z") -> LocalDateTime.parse(value.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC)
                else -> {
                    val zone = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)?.let { ZoneId.of(it) } ?: BERLIN
                    LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(zone).toInstant()
                }
            }
        }.getOrNull()

        private fun unescape(v: String) = v.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
    }
}
