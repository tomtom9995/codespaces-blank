package com.example.nova.shared.files

import com.example.nova.shared.api.FileEntryDto
import com.example.nova.shared.api.NovaApi
import com.example.nova.shared.api.NovaApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilesState(
    val path: String = "/",
    val entries: List<FileEntryDto> = emptyList(),
    val loading: Boolean = false,
    /** Kurzer Hinweis nach einer Aktion, z. B. „Ordner angelegt“. */
    val notice: String? = null,
    val error: String? = null,
    /** true, wenn das Konto nicht mit dem zentralen Login verbunden ist → Hinweis „Mit Chattia-Konto anmelden“. */
    val needsCentralLogin: Boolean = false,
) {
    val isRoot: Boolean get() = path == "/"
    /** Anzeigename des aktuellen Ordners. */
    val title: String get() = if (isRoot) "" else path.trimEnd('/').substringAfterLast('/')
    /** Brotkrumen: ["/", "/Corps", "/Corps/Protokolle"]. */
    val breadcrumbs: List<String> get() {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return listOf("/") + parts.indices.map { "/" + parts.take(it + 1).joinToString("/") }
    }
}

/** Dateien aus der Chattia-Cloud (Nextcloud) für beide Apps: blättern, hochladen, Ordner anlegen, löschen. */
class FilesController(private val api: NovaApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun open(path: String = "/") {
        loadJob?.cancel()
        _state.update { it.copy(path = normalize(path), loading = true, error = null, notice = null) }
        loadJob = scope.launch { load(normalize(path)) }
    }

    fun refresh() = open(_state.value.path)

    /** Eine Ebene nach oben; false, wenn schon im Hauptordner (dann darf „Zurück“ die Ansicht verlassen). */
    fun up(): Boolean {
        val current = _state.value
        if (current.isRoot) return false
        open(current.path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" })
        return true
    }

    fun createFolder(name: String) = action("Ordner „${name.trim()}“ angelegt") {
        api.createFolder(child(name))
    }

    fun upload(name: String, bytes: ByteArray, contentType: String?) = action("„${name.trim()}“ hochgeladen") {
        api.uploadFile(child(name), bytes, contentType)
    }

    fun delete(entry: FileEntryDto) = action("„${entry.name}“ gelöscht – im Papierkorb der Cloud noch 30 Tage wiederherstellbar") {
        api.deleteFile(entry.path)
    }

    /** Lädt eine Datei; das Speichern/Öffnen übernimmt die Plattform (Teilen-Dialog, Vorschau). */
    @Throws(Exception::class)
    suspend fun download(entry: FileEntryDto): ByteArray = api.downloadFile(entry.path)

    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    private fun child(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty() && '/' !in clean && clean != "." && clean != "..") { "Ungültiger Name" }
        return _state.value.path.trimEnd('/') + "/" + clean
    }

    private fun action(success: String, block: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            try {
                block()
                load(_state.value.path)
                _state.update { it.copy(notice = success) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun load(path: String) {
        try {
            val listing = api.listFiles(path)
            _state.update { it.copy(path = listing.path, entries = listing.entries, loading = false, needsCentralLogin = false) }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        val central = e is NovaApiException && e.code in setOf("central_account_required", "central_login_expired")
        _state.update {
            it.copy(
                loading = false,
                needsCentralLogin = central,
                error = when {
                    central -> null
                    e is NovaApiException -> e.message
                    e is IllegalArgumentException -> e.message
                    else -> "Keine Verbindung. Bitte versuch es erneut."
                },
            )
        }
    }

    private fun normalize(path: String) = "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")
}
