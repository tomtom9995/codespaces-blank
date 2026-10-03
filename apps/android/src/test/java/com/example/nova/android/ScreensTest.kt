package com.example.nova.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import com.example.nova.android.ui.NovaRoot
import com.example.nova.android.ui.NovaTheme
import com.example.nova.shared.NovaApp
import com.example.nova.shared.platform.DeviceIdentity
import com.example.nova.shared.platform.InMemoryStore
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.URI
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Software-Schlüssel statt Android Keystore (Robolectric hat keinen Keystore). */
internal class TestDevice : DeviceIdentity {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val deviceName = "Pixel 10 Pro"
    override val platform = "android"
    override fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(keys.public.encoded)
    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(message); sign() }
    override fun connectionType() = "wifi"
}

/**
 * Klickt die App wie ein Mensch durch – gegen das echte Backend – und speichert Screenshots nach docs/screenshots/android.
 * Start: NOVA_BACKEND_URL=http://localhost:8080 ./gradlew :apps:android:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = android.app.Application::class)
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = System.getenv("NOVA_BACKEND_URL").orEmpty()
    private val mailpit = System.getenv("MAILPIT_URL") ?: "http://localhost:8025"
    private fun get(url: String): String = URI(url).toURL().openStream().bufferedReader().use { it.readText() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("../../docs/screenshots/android/$name.png")

    private fun waitForText(text: String, timeout: Long = 20_000) {
        try {
            compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("--- Erwartet: $text ---\n" + compose.onRoot(useUnmergedTree = true).printToString())
            throw e
        }
    }

    /** Neueste Mail an diese Adresse aus Mailpit; liefert (Betreff, Text). */
    private fun latestMail(to: String): Pair<String, String> {
        val q = URLEncoder.encode("to:$to", Charsets.UTF_8)
        repeat(40) {
            val list = get("$mailpit/api/v1/search?query=$q")
            val first = json.parseToJsonElement(list).jsonObject["messages"]?.jsonArray?.firstOrNull()?.jsonObject
            if (first != null) {
                val id = first["ID"]!!.jsonPrimitive.content
                val msg = json.parseToJsonElement(get("$mailpit/api/v1/message/$id")).jsonObject
                return msg["Subject"]!!.jsonPrimitive.content to msg["Text"]!!.jsonPrimitive.content
            }
            Thread.sleep(250)
        }
        error("Keine Mail an $to")
    }

    @Test
    fun ersteNutzungVonAnmeldungBisChat() {
        assumeTrue("NOVA_BACKEND_URL nicht gesetzt", backend.isNotBlank())
        val nova = NovaApp(backend, TestDevice(), InMemoryStore())
        nova.start()
        compose.setContent { NovaTheme { NovaRoot(nova) } }

        waitForText("Dein KI-Assistent für jeden Tag")
        shot("01-willkommen")
        compose.onNodeWithText("Los geht's").performClick()

        waitForText("Wie möchtest du dich anmelden?")
        shot("02-anmeldemethode")
        compose.onNodeWithText("Mit E-Mail fortfahren").performClick()

        val email = "alexandra.${System.currentTimeMillis()}@example.org"
        waitForText("Deine E-Mail-Adresse")
        compose.onNode(hasSetTextAction()).performTextInput(email)
        shot("03-email")
        compose.onNodeWithText("Weiter").performClick()

        waitForText("Schau in dein Postfach")
        shot("04-email-code")
        val (subject, _) = latestMail(email)
        compose.onNode(hasSetTextAction()).performTextInput(Regex("\\d{6}").find(subject)!!.value)

        waitForText("Handynummer hinzufügen?")
        compose.onNode(hasSetTextAction()).performTextInput("+49 170 5550123")
        shot("05-telefon")
        compose.onNodeWithText("Code per SMS").performClick()

        waitForText("Code eingeben")
        shot("06-telefon-code")
        val (_, smsText) = latestMail("telefon-491705550123@dev.localhost")
        compose.onNode(hasSetTextAction()).performTextInput(smsText.trim().take(6))

        waitForText("Wie sollen wir dich nennen?")
        compose.onNode(hasSetTextAction()).performTextInput("Alexandra")
        compose.onNodeWithText("Arbeit").performClick()
        shot("07-personalisieren")
        compose.onNodeWithText("Weiter").performClick()

        waitForText("ist eine KI und kann Fehler machen")
        shot("08-ki-hinweis")
        compose.onNodeWithText("Weiter").performClick()

        waitForText("Hallo Alexandra! Womit kann ich helfen?")
        shot("09-chat-start")
        compose.onNodeWithText("Plane ein Abendessen für vier Personen in 30 Minuten").performClick()
        waitForText("Testmodus")
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Stoppen").fetchSemanticsNodes().isEmpty() }
        shot("10-chat-antwort")

        compose.onNodeWithContentDescription("Menü").performClick()
        waitForText("Plane ein Abendessen")
        compose.waitForIdle()
        shot("11-verlauf")
        compose.onNodeWithText("Einstellungen").performClick()

        waitForText("Sicherheitscheck")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Dieses Gerät", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("12-einstellungen")
        compose.onNodeWithText("Deine Geräte").performScrollTo()
        shot("13-geraete-und-aktivitaet")
    }
}
