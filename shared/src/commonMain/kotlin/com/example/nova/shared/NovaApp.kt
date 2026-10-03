package com.example.nova.shared

import com.example.nova.shared.api.NovaApi
import com.example.nova.shared.api.SessionStore
import com.example.nova.shared.auth.AuthController
import com.example.nova.shared.chat.ChatController
import com.example.nova.shared.content.ContentRepository
import com.example.nova.shared.files.FilesController
import com.example.nova.shared.platform.DeviceIdentity
import com.example.nova.shared.platform.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Einstiegspunkt für beide Apps: bündelt API, Texte, Anmeldung und Chat.
 * Android hält eine Instanz in der Application, iOS im App-Struct.
 */
class NovaApp(
    baseUrl: String,
    device: DeviceIdentity,
    store: KeyValueStore,
    locale: String = "de",
    val scope: CoroutineScope = MainScope(),
) {
    val api = NovaApi(baseUrl, SessionStore(store), device)
    val content = ContentRepository(api, store, locale)
    val auth = AuthController(api, store)
    val chat = ChatController(api, scope) { content.text("chat.newChat") }
    val files = FilesController(api, scope)

    /** Für Swift (Standardwerte von Kotlin-Parametern sind dort nicht sichtbar). */
    constructor(baseUrl: String, device: DeviceIdentity, store: KeyValueStore) : this(baseUrl, device, store, "de", MainScope())

    /** Beim Start: Texte aktualisieren und gespeicherte Sitzung prüfen. */
    fun start() {
        scope.launch { content.refresh() }
        scope.launch { auth.restore() }
    }
}

/** Für Swift: StateFlow beobachten, ohne Coroutines zu kennen. Rückgabewert mit cancel() beenden. */
class Observation internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

fun <T> StateFlow<T>.observe(onEach: (T) -> Unit): Observation {
    val job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { collect { onEach(it) } }
    return Observation(job)
}
