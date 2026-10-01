package com.example.nova.messaging

import com.example.nova.content.ContentService
import com.example.nova.content.ContentSettings
import com.example.nova.content.render
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

data class OutgoingMail(val to: String, val subject: String, val html: String, val text: String, val templateKey: String)

interface MailTransport {
    suspend fun send(mail: OutgoingMail)
}

class SmtpTransport(
    private val host: String,
    private val port: Int,
    private val user: String?,
    private val password: String?,
    private val startTls: Boolean,
    private val from: String,
) : MailTransport {
    private val session: Session = Session.getInstance(Properties().apply {
        put("mail.smtp.host", host)
        put("mail.smtp.port", port.toString())
        put("mail.smtp.auth", (user != null).toString())
        put("mail.smtp.starttls.enable", startTls.toString())
        put("mail.smtp.connectiontimeout", "10000")
        put("mail.smtp.timeout", "10000")
    })

    override suspend fun send(mail: OutgoingMail) = withContext(Dispatchers.IO) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(this@SmtpTransport.from))
            addRecipient(Message.RecipientType.TO, InternetAddress(mail.to))
            setSubject(mail.subject, "UTF-8")
            setHeader("X-Template", mail.templateKey)
            setContent(MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(mail.text, "UTF-8") })
                addBodyPart(MimeBodyPart().apply { setContent(mail.html, "text/html; charset=UTF-8") })
            })
        }
        if (user != null) Transport.send(message, user, password) else Transport.send(message)
    }
}

/** Für Tests: merkt sich alle E-Mails. */
class InMemoryTransport : MailTransport {
    val sent = CopyOnWriteArrayList<OutgoingMail>()
    override suspend fun send(mail: OutgoingMail) {
        sent += mail
    }
}

class UnsafeLinkException(message: String) : Exception(message)

/**
 * Rendert E-Mail-Vorlagen aus dem CMS in das feste Layout und verschickt sie.
 * Sicherheitsregel: Links dürfen nur auf erlaubte Domains zeigen – sonst wird nicht versendet.
 */
class EmailService(
    private val content: ContentService,
    private val transport: MailTransport,
) {
    private val log = LoggerFactory.getLogger(EmailService::class.java)
    private val parser = Parser.builder().build()
    private val html = HtmlRenderer.builder().softbreak("<br />").escapeHtml(true).build()

    suspend fun send(
        to: String,
        templateKey: String,
        values: Map<String, String?> = emptyMap(),
        antiPhishingPhrase: String? = null,
        unsubscribeUrl: String? = null,
        locale: String = "de",
    ): Boolean {
        val bundle = content.get(locale)
        val template = bundle.emails[templateKey] ?: run {
            log.error("E-Mail-Vorlage {} fehlt", templateKey)
            return false
        }
        val s = bundle.settings
        val v = values + mapOf("antiPhishingPhrase" to antiPhishingPhrase, "unsubscribeUrl" to unsubscribeUrl)

        val subject = s.render(template.subject, v)
        val preheader = s.render(template.preheader, v)
        val greetingFixed = fixGreeting(s.render(template.bodyMarkdown, v))
        val ctaUrl = template.ctaUrl?.let { s.render(it, v) }?.takeIf { it.isNotBlank() }
        val ctaLabel = template.ctaLabel?.let { s.render(it, v) }

        val footer = buildList {
            add(
                if (antiPhishingPhrase != null) s.render(bundle.ui("email.footer.antiPhishing"), v)
                else s.render(bundle.ui("email.footer.antiPhishingMissing"), v)
            )
            add(s.render(bundle.ui("email.footer.neverShare"), v))
            if (template.category == "marketing" && unsubscribeUrl != null) add(s.render(bundle.ui("email.footer.unsubscribe"), v))
            add(s.render(bundle.ui("email.footer.signature"), v))
        }

        try {
            checkLinks(s, listOf(greetingFixed, ctaUrl.orEmpty()) + footer)
        } catch (e: UnsafeLinkException) {
            log.error("Versand von {} blockiert: {}", templateKey, e.message)
            return false
        }

        val bodyHtml = html.render(parser.parse(greetingFixed))
        val footerHtml = footer.joinToString("") { "<p style=\"margin:0 0 8px\">${html.render(parser.parse(it)).removeSurrounding("<p>", "</p>\n")}</p>" }
        val mail = OutgoingMail(
            to = to,
            subject = subject,
            html = layout(s.appName, preheader, bodyHtml, ctaLabel, ctaUrl, footerHtml, antiPhishingPhrase != null),
            text = buildString {
                append(greetingFixed.replace("**", "").replace("## ", ""))
                if (ctaUrl != null) append("\n\n$ctaLabel: $ctaUrl")
                append("\n\n--\n")
                append(footer.joinToString("\n") { it.replace("**", "") })
            },
            templateKey = templateKey,
        )
        return try {
            transport.send(mail)
            log.info("E-Mail {} an {} versendet", templateKey, maskEmail(to))
            true
        } catch (e: Exception) {
            log.error("E-Mail {} an {} fehlgeschlagen: {}", templateKey, maskEmail(to), e.message)
            false
        }
    }

    /** „Hallo ,“ wird zu „Hallo,“, wenn kein Vorname bekannt ist. */
    private fun fixGreeting(text: String) = text.replace(Regex("^Hallo ,", RegexOption.MULTILINE), "Hallo,")

    private fun checkLinks(settings: ContentSettings, texts: List<String>) {
        val urlPattern = Regex("https?://[^\\s)\\]>\"]+")
        for (text in texts) for (match in urlPattern.findAll(text)) {
            val host = runCatching { URI(match.value).host }.getOrNull()?.lowercase()
            val allowed = host != null && (settings.allowedLinkDomains.any { host == it || host.endsWith(".$it") } || isLocalDev(host))
            if (!allowed) throw UnsafeLinkException("Link auf nicht erlaubte Domain: ${match.value}")
        }
    }

    private fun isLocalDev(host: String) = host == "localhost" || host == "127.0.0.1" || host.endsWith(".localhost")

    private fun layout(
        appName: String,
        preheader: String,
        bodyHtml: String,
        ctaLabel: String?,
        ctaUrl: String?,
        footerHtml: String,
        hasPhrase: Boolean,
    ): String {
        val cta = if (ctaUrl != null && ctaLabel != null) """
            <p style="margin:28px 0"><a href="${escape(ctaUrl)}" style="background:#0071e3;color:#fff;text-decoration:none;padding:12px 22px;border-radius:999px;font-weight:600;display:inline-block">${escape(ctaLabel)}</a></p>
        """.trimIndent() else ""
        val footerBorder = if (hasPhrase) "#0071e3" else "#d2d2d7"
        return """
            <!doctype html>
            <html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(appName)}</title></head>
            <body style="margin:0;background:#f5f5f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1d1d1f">
            <span style="display:none;max-height:0;overflow:hidden">${escape(preheader)}</span>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:32px 16px">
            <table role="presentation" width="100%" style="max-width:560px;background:#ffffff;border-radius:18px" cellpadding="0" cellspacing="0"><tr><td style="padding:32px 32px 8px">
            <p style="font-size:20px;font-weight:700;margin:0 0 24px">${escape(appName)}</p>
            <div style="font-size:16px;line-height:1.55">$bodyHtml</div>
            $cta
            </td></tr><tr><td style="padding:8px 32px 28px">
            <div style="border-top:3px solid $footerBorder;padding-top:16px;font-size:13px;line-height:1.5;color:#6e6e73">$footerHtml</div>
            </td></tr></table>
            </td></tr></table>
            </body></html>
        """.trimIndent()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 1) return "***" + email.substring(maxOf(at, 0))
    return email.first() + "***" + email.substring(at)
}

fun maskPhone(phone: String): String =
    if (phone.length <= 6) "***" else phone.take(3) + " *** *** " + phone.takeLast(4)
