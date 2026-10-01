package com.example.nova.shared.content

import com.example.nova.shared.api.AppContentBundle
import com.example.nova.shared.api.NovaApi
import com.example.nova.shared.api.OnboardingStep
import com.example.nova.shared.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Alle Texte der App. Start mit der eingebauten Grundfassung (bzw. dem letzten gespeicherten Stand),
 * danach Aktualisierung aus dem CMS über das Backend – ohne App-Update.
 */
class ContentRepository(
    private val api: NovaApi?,
    private val store: KeyValueStore,
    val locale: String = "de",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _bundle = MutableStateFlow(loadInitial())
    val bundle: StateFlow<AppContentBundle> = _bundle.asStateFlow()

    private fun loadInitial(): AppContentBundle {
        store.get(cacheKey())?.let { cached ->
            runCatching { return json.decodeFromString(AppContentBundle.serializer(), cached) }
        }
        return bundled(locale)
    }

    /** Fragt das Backend nach einer neuen Fassung (ETag). Gibt true zurück, wenn sich etwas geändert hat. */
    suspend fun refresh(): Boolean {
        val fresh = runCatching { api?.content(locale, _bundle.value.version) }.getOrNull() ?: return false
        _bundle.value = fresh
        store.set(cacheKey(), json.encodeToString(AppContentBundle.serializer(), fresh))
        return true
    }

    val appName: String get() = _bundle.value.appName

    /** Text mit Platzhaltern, z. B. text("code.invalid", "attemptsLeft" to "3"). Fehlt ein Text, erscheint der Schlüssel. */
    fun text(key: String, vararg values: Pair<String, String>): String = format(_bundle.value, key, values.toMap())

    /** Für Swift: Platzhalter als Dictionary. */
    fun textWith(key: String, values: Map<String, String>): String = format(_bundle.value, key, values)

    fun onboarding(key: String): OnboardingStep? = _bundle.value.onboarding.firstOrNull { it.key == key }

    fun roleName(role: String): String = _bundle.value.roles.firstOrNull { it.key == "workspace.$role" }?.name ?: role

    /** Ersetzt Platzhalter in einem beliebigen Text (z. B. Onboarding-Titel). */
    fun fill(text: String, values: Map<String, String> = emptyMap()): String = replace(text, values + ("appName" to appName))

    private fun cacheKey() = "nova.content.$locale"

    companion object {
        private val PLACEHOLDER = Regex("\\{([a-zA-Z][a-zA-Z0-9]*)}")

        fun bundled(locale: String): AppContentBundle {
            val raw = BUNDLED_CONTENT[locale] ?: BUNDLED_CONTENT.getValue("de")
            return Json { ignoreUnknownKeys = true }.decodeFromString(AppContentBundle.serializer(), raw)
        }

        fun format(bundle: AppContentBundle, key: String, values: Map<String, String>): String {
            val template = bundle.uiTexts[key] ?: return key
            return replace(template, values + ("appName" to bundle.appName))
        }

        private fun replace(text: String, values: Map<String, String>) =
            PLACEHOLDER.replace(text) { m -> values[m.groupValues[1]] ?: m.value }
    }
}
