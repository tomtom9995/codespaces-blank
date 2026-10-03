package com.example.nova.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import com.example.nova.android.ui.NovaRoot
import com.example.nova.android.ui.NovaTheme
import com.example.nova.shared.NovaApp
import com.example.nova.shared.platform.InMemoryStore
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Login mit dem Chattia-Konto und Dateien aus der Cloud – gegen infra/hosting + Nova-Backend.
 * Den Browser-Teil (Custom Tab) übernimmt ein HTTP-Client: Keycloak-Formular ausfüllen, Rücksprung nova://auth?code=… abfangen.
 * Start: NOVA_BACKEND_URL=http://localhost:8080 CHATTIA_CA_CERT=infra/hosting/caddy-local-root.crt ./gradlew :apps:android:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = android.app.Application::class)
class CentralLoginTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = System.getenv("NOVA_BACKEND_URL").orEmpty()
    private val caCert = System.getenv("CHATTIA_CA_CERT").orEmpty()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("../../docs/screenshots/android/$name.png")

    private fun waitForText(text: String, timeout: Long = 30_000) {
        try {
            compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("--- Erwartet: $text ---\n" + compose.onRoot(useUnmergedTree = true).printToString())
            throw e
        }
    }

    /** Was der Custom Tab tut: Start → Keycloak-Anmeldung → Rücksprung-Adresse der App. */
    private fun browserLogin(startUrl: String, user: String, password: String): String {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("chattia", CertificateFactory.getInstance("X.509").generateCertificate(File(caCert).inputStream()))
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }.trustManagers, null)
        }
        val cookies = linkedMapOf<String, String>()

        /** Eine Anfrage ohne automatische Weiterleitung; liefert (Status, Location, Inhalt). */
        fun request(url: URI, form: String? = null): Triple<Int, String?, String> {
            val c = url.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            if (c is HttpsURLConnection) c.sslSocketFactory = ssl.socketFactory
            c.instanceFollowRedirects = false
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray()) }
            }
            val status = c.responseCode
            c.headerFields["Set-Cookie"]?.forEach { header ->
                val pair = header.substringBefore(';')
                cookies[pair.substringBefore('=')] = pair.substringAfter('=')
            }
            val body = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            return Triple(status, c.getHeaderField("Location"), body)
        }

        fun follow(start: URI, form: String? = null): Pair<URI, String> {
            var url = start
            var (status, location, body) = request(url, form)
            repeat(10) {
                if (status !in 300..399 || location == null) return url to body
                url = url.resolve(location)
                if (url.scheme == "nova") return url to ""
                request(url).let { status = it.first; location = it.second; body = it.third }
            }
            error("Zu viele Weiterleitungen")
        }

        val (_, loginPage) = follow(URI(startUrl))
        val action = Regex("""<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"""").find(loginPage)?.groupValues?.get(1)
            ?.replace("&amp;", "&") ?: error("Keycloak-Anmeldeformular nicht gefunden")
        val form = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}&credentialId="
        val (target, _) = follow(URI(action), form)
        check(target.scheme == "nova") { "Kein Rücksprung zur App, sondern $target" }
        return target.toString()
    }

    @Test
    fun anmeldungMitChattiaKontoUndDateien() {
        assumeTrue("NOVA_BACKEND_URL/CHATTIA_CA_CERT nicht gesetzt", backend.isNotBlank() && caCert.isNotBlank())
        val nova = NovaApp(backend, TestDevice(), InMemoryStore())
        val callback = MutableStateFlow<String?>(null)
        nova.start()
        compose.setContent { NovaTheme { NovaRoot(nova, callback) } }

        waitForText("Dein KI-Assistent für jeden Tag")
        compose.onNodeWithText("Los geht's").performClick()
        waitForText("Mit Chattia-Konto anmelden")
        shot("20-anmeldung-chattia-konto")

        // Custom Tab: Keycloak-Login, danach öffnet Android die App mit nova://auth?code=…
        callback.value = browserLogin(nova.auth.centralLoginUrl(), "bursch", "Chattia-Test-2026!")

        waitForText("Womit kann ich helfen?")
        compose.onNodeWithContentDescription("Menü").performClick()
        waitForText("Dateien")
        compose.onNodeWithText("Dateien").performClick()

        waitForText("Corps")
        shot("21-dateien")
        compose.onNodeWithText("Corps").performClick()
        waitForText("Satzung.md")
        shot("22-dateien-ordner")

        compose.onNodeWithContentDescription("Neuer Ordner").performClick()
        waitForText("Datei hochladen")
        shot("23-neu-menue")
        // Texteingabe in Dialogen ist unter Robolectric nicht stabil – Ordner über denselben Controller anlegen wie der Dialog.
        androidx.test.espresso.Espresso.pressBack()
        nova.files.createFolder("Kneipe WS 2026")
        waitForText("Kneipe WS 2026")
        compose.waitForIdle()
        shot("24-ordner-angelegt")

        // Aufräumen, damit der Test wiederholbar ist
        runBlocking { nova.api.deleteFile("/Corps/Kneipe WS 2026") }
    }
}
