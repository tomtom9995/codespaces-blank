package com.example.nova.messaging

import com.example.nova.Config
import com.example.nova.db.Database
import com.example.nova.db.query
import com.example.nova.db.update
import com.example.nova.db.uuid
import com.example.nova.web.unsubscribeUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Onboarding-Strecke aus docs/plan/onboarding.md (Tag 1, 3, 7, 14).
 * Jede E-Mail geht höchstens einmal (email_log) und nur, wenn ihre Bedingung zum Versandzeitpunkt erfüllt ist.
 */
class OnboardingScheduler(
    private val config: Config,
    private val db: Database,
    private val email: EmailService,
    private val interval: Duration = Duration.ofMinutes(10),
) {
    private val log = LoggerFactory.getLogger(OnboardingScheduler::class.java)

    data class Step(val templateKey: String, val afterDays: Int, val condition: String)

    private val steps = listOf(
        Step("email.onboarding.day1Tips", 1, "u.marketing_consent"),
        Step("email.onboarding.day3SecurityCheck", 3, "(u.phone_verified_at IS NULL OR u.anti_phishing_phrase IS NULL)"),
        Step("email.onboarding.day7Features", 7, "u.marketing_consent"),
        Step(
            "email.onboarding.day14Feedback", 14,
            "u.marketing_consent AND (SELECT count(*) FROM conversations c WHERE c.user_id = u.id) >= 3",
        ),
    )

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { runOnce() }.onFailure { log.error("Onboarding-Lauf fehlgeschlagen: {}", it.message) }
            delay(interval.toMillis())
        }
    }

    /** Ein Durchlauf; gibt die Anzahl versendeter E-Mails zurück. */
    suspend fun runOnce(): Int {
        var sent = 0
        for (step in steps) {
            data class Due(val id: UUID, val email: String, val firstName: String?, val phrase: String?)
            val due = db.tx {
                query(
                    """SELECT u.id, u.email, u.first_name, u.anti_phishing_phrase FROM users u
                       WHERE u.locked_at IS NULL
                         AND u.created_at <= now() - make_interval(days => ?)
                         AND u.created_at > now() - make_interval(days => ?)
                         AND ${step.condition}
                         AND NOT EXISTS (SELECT 1 FROM email_log l WHERE l.user_id = u.id AND l.template_key = ?)
                       LIMIT 500""",
                    step.afterDays, step.afterDays + 30, step.templateKey,
                ) { Due(it.uuid("id"), it.getString("email"), it.getString("first_name"), it.getString("anti_phishing_phrase")) }
            }
            for (user in due) {
                // Erst eintragen, dann senden: lieber eine E-Mail verlieren als doppelt senden.
                val claimed = db.tx {
                    update("INSERT INTO email_log (user_id, template_key) VALUES (?, ?) ON CONFLICT DO NOTHING", user.id, step.templateKey)
                } == 1
                if (!claimed) continue
                val ok = email.send(
                    user.email, step.templateKey,
                    mapOf(
                        "firstName" to user.firstName,
                        "appUrl" to "${config.publicBaseUrl}/open",
                        "securityCheckUrl" to "${config.publicBaseUrl}/open/security",
                        "surveyUrl" to "${config.publicBaseUrl}/feedback",
                    ),
                    antiPhishingPhrase = user.phrase,
                    unsubscribeUrl = unsubscribeUrl(config, user.id),
                )
                if (ok) sent++
            }
        }
        if (sent > 0) log.info("Onboarding: {} E-Mails versendet", sent)
        return sent
    }
}
