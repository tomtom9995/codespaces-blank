package com.example.nova.web

import com.example.nova.Config
import com.example.nova.Services
import com.example.nova.auth.Crypto
import com.example.nova.db.update
import com.example.nova.requestContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/** Signierter Abmeldelink für Marketing-E-Mails (ohne Datenbankeintrag). */
fun unsubscribeUrl(config: Config, userId: UUID): String =
    "${config.publicBaseUrl}/email/unsubscribe?u=$userId&s=${Crypto.hmac(config.codePepper, "unsubscribe", userId.toString()).take(32)}"

private fun validUnsubscribe(config: Config, u: String?, sig: String?): UUID? {
    val id = u?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
    val expected = Crypto.hmac(config.codePepper, "unsubscribe", id.toString()).take(32)
    return id.takeIf { sig != null && Crypto.constantTimeEquals(expected, sig) }
}

/**
 * Kleine Webseiten für Links aus E-Mails. Aktionen laufen immer über POST mit Bestätigungsknopf,
 * damit Link-Scanner von E-Mail-Programmen nichts auslösen.
 */
fun Route.webRoutes(s: Services) {
    get("/s/not-me") {
        val token = call.request.queryParameters["t"].orEmpty()
        call.page(
            "Das war ich nicht",
            """<p>Wir melden dann <strong>alle Geräte ab</strong> und sperren dein Konto vorsorglich.
               Danach meldest du dich in der App neu an und bestätigst, dass du es bist.</p>
               <form method="post"><input type="hidden" name="t" value="${token.escape()}">
               <button type="submit">Konto jetzt sichern</button></form>""",
        )
    }
    post("/s/not-me") {
        val token = call.receiveParameters()["t"].orEmpty()
        val ok = token.isNotBlank() && s.auth.redeemNotMe(token, call.requestContext(s.config))
        if (ok) call.page("Konto gesichert", "<p>Alle Geräte sind abgemeldet und dein Konto ist vorsorglich gesperrt. Wir haben dir eine E-Mail mit den nächsten Schritten geschickt.</p>")
        else call.page("Link ungültig", "<p>Dieser Link ist abgelaufen oder wurde schon benutzt. Öffne die App unter <strong>Einstellungen › Sicherheit › Geräte</strong>.</p>", HttpStatusCode.BadRequest)
    }

    get("/email/unsubscribe") {
        val u = call.request.queryParameters["u"]
        val sig = call.request.queryParameters["s"]
        if (validUnsubscribe(s.config, u, sig) == null) {
            call.page("Link ungültig", "<p>Dieser Abmeldelink ist ungültig. Du kannst Tipps-E-Mails auch in der App unter Einstellungen abbestellen.</p>", HttpStatusCode.BadRequest)
            return@get
        }
        call.page(
            "Tipps abbestellen",
            """<p>Möchtest du keine Tipps und Neuigkeiten mehr per E-Mail bekommen? Sicherheitsmeldungen zu deinem Konto bekommst du weiterhin.</p>
               <form method="post"><input type="hidden" name="u" value="${u!!.escape()}"><input type="hidden" name="s" value="${sig!!.escape()}">
               <button type="submit">Abbestellen</button></form>""",
        )
    }
    post("/email/unsubscribe") {
        val params = call.receiveParameters()
        val id = validUnsubscribe(s.config, params["u"], params["s"])
        if (id == null) {
            call.page("Link ungültig", "<p>Dieser Abmeldelink ist ungültig.</p>", HttpStatusCode.BadRequest)
            return@post
        }
        s.db.tx { update("UPDATE users SET marketing_consent = FALSE WHERE id = ?", id) }
        call.page("Abbestellt", "<p>Erledigt. Du bekommst keine Tipps-E-Mails mehr.</p>")
    }

    // Platzhalter für App-Links (Universal Links / App Links). In Produktion öffnet das Betriebssystem direkt die App.
    for (path in listOf("/open", "/open/{...}", "/feedback")) {
        get(path) {
            val appName = s.content.get().settings.appName
            call.page(appName, "<p>Öffne die <strong>$appName</strong>-App auf deinem Smartphone, um fortzufahren.</p>")
        }
    }
}

private fun String.escape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private suspend fun io.ktor.server.application.ApplicationCall.page(title: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(
        """<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
           <title>${title.escape()}</title><style>
           body{margin:0;background:#f5f5f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#1d1d1f}
           main{max-width:480px;margin:48px auto;background:#fff;border-radius:18px;padding:32px 24px}
           h1{font-size:24px;margin:0 0 16px}p{line-height:1.5}
           button{background:#0071e3;color:#fff;border:0;border-radius:999px;padding:12px 22px;font-size:16px;font-weight:600;cursor:pointer}
           </style></head><body><main><h1>${title.escape()}</h1>$body</main></body></html>""",
        ContentType.Text.Html,
        status,
    )
}
