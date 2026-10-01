package com.example.nova.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.nova.shared.api.AppContentBundle
import com.example.nova.shared.api.OnboardingStep
import com.example.nova.shared.content.ContentRepository

/** Zugriff auf die CMS-Texte. Ändert sich ein Text im CMS, aktualisiert sich die Oberfläche automatisch. */
class Texts(private val bundle: AppContentBundle) {
    operator fun invoke(key: String, vararg values: Pair<String, String>): String = ContentRepository.format(bundle, key, values.toMap())
    fun step(key: String): OnboardingStep? = bundle.onboarding.firstOrNull { it.key == key }
    fun fill(text: String?, vararg values: Pair<String, String>): String =
        ContentRepository.format(bundle.copy(uiTexts = mapOf("_" to (text ?: ""))), "_", values.toMap())
    fun role(role: String): String = bundle.roles.firstOrNull { it.key == "workspace.$role" }?.name ?: role
    val appName: String get() = bundle.appName
}

val LocalTexts = staticCompositionLocalOf<Texts> { error("Texts nicht gesetzt") }

@Composable
fun ProvideTexts(content: ContentRepository, block: @Composable () -> Unit) {
    val bundle by content.bundle.collectAsState()
    CompositionLocalProvider(LocalTexts provides Texts(bundle), content = block)
}
