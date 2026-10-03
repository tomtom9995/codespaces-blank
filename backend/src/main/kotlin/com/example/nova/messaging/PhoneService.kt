package com.example.nova.messaging

import com.example.nova.content.ContentService
import com.example.nova.content.render
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import org.slf4j.LoggerFactory

/** Versand von SMS und automatischen Code-Anrufen. Texte kommen immer aus dem CMS. */
interface PhoneChannel {
    suspend fun sms(to: String, text: String)
    suspend fun call(to: String, speech: String)
}

/** Entwicklung: SMS und Anrufe landen als E-Mail in Mailpit (http://localhost:8025). */
class DevPhoneChannel(private val transport: MailTransport) : PhoneChannel {
    private val log = LoggerFactory.getLogger(DevPhoneChannel::class.java)

    override suspend fun sms(to: String, text: String) {
        log.info("[DEV-SMS an {}] {}", to, text.replace("\n", " ⏎ "))
        transport.send(OutgoingMail(devAddress(to), "SMS an $to", "<pre style=\"font-size:16px\">${text.escapeHtml()}</pre>", text, "dev.sms"))
    }

    override suspend fun call(to: String, speech: String) {
        log.info("[DEV-ANRUF an {}] {}", to, speech)
        transport.send(OutgoingMail(devAddress(to), "Anruf an $to (Ansage)", "<p style=\"font-size:16px\">🔊 ${speech.escapeHtml()}</p>", speech, "dev.call"))
    }

    private fun devAddress(phone: String) = "telefon-${phone.filter { it.isDigit() }}@dev.localhost"
    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Produktion: Twilio Programmable Messaging und Voice (TwiML <Say>). */
class TwilioPhoneChannel(
    private val http: HttpClient,
    private val accountSid: String,
    private val authToken: String,
    private val from: String,
) : PhoneChannel {
    private val base = "https://api.twilio.com/2010-04-01/Accounts/$accountSid"

    override suspend fun sms(to: String, text: String) {
        val response = http.submitForm("$base/Messages.json", parameters {
            append("To", to)
            append("From", from)
            append("Body", text)
        }) { basicAuth(accountSid, authToken) }
        check(response.status.isSuccess()) { "Twilio SMS: HTTP ${response.status.value} ${response.bodyAsText().take(200)}" }
    }

    override suspend fun call(to: String, speech: String) {
        val twiml = "<Response><Say language=\"de-DE\">${speech.replace("&", "&amp;").replace("<", "&lt;")}</Say></Response>"
        val response = http.submitForm("$base/Calls.json", parameters {
            append("To", to)
            append("From", from)
            append("Twiml", twiml)
        }) { basicAuth(accountSid, authToken) }
        check(response.status.isSuccess()) { "Twilio Anruf: HTTP ${response.status.value} ${response.bodyAsText().take(200)}" }
    }
}

class PhoneService(private val content: ContentService, private val channel: PhoneChannel) {
    private val log = LoggerFactory.getLogger(PhoneService::class.java)

    suspend fun sendSms(to: String, templateKey: String, values: Map<String, String?> = emptyMap(), locale: String = "de"): Boolean {
        val bundle = content.get(locale)
        val template = bundle.sms[templateKey] ?: return false.also { log.error("SMS-Vorlage {} fehlt", templateKey) }
        return runCatching { channel.sms(to, bundle.settings.render(template, values)) }
            .onFailure { log.error("SMS {} fehlgeschlagen: {}", templateKey, it.message) }.isSuccess
    }

    suspend fun call(to: String, templateKey: String, values: Map<String, String?> = emptyMap(), locale: String = "de"): Boolean {
        val bundle = content.get(locale)
        val template = bundle.voice[templateKey] ?: return false.also { log.error("Ansage {} fehlt", templateKey) }
        return runCatching { channel.call(to, bundle.settings.render(template, values)) }
            .onFailure { log.error("Anruf {} fehlgeschlagen: {}", templateKey, it.message) }.isSuccess
    }
}
