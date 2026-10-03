package com.example.nova.shared.events

import com.example.nova.shared.api.EventDto
import com.example.nova.shared.api.NovaApi
import com.example.nova.shared.api.NovaApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventsState(
    val events: List<EventDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Konto nicht mit dem Chattia-Konto verbunden → Hinweis zum Verbinden. */
    val needsCentralLogin: Boolean = false,
)

/** Semesterprogramm (öffentlicher und interner Kalender) für beide Apps. */
class EventsController(private val api: NovaApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(EventsState())
    val state: StateFlow<EventsState> = _state.asStateFlow()

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val events = api.events()
                _state.value = EventsState(events = events)
            } catch (e: NovaApiException) {
                val central = e.code in setOf("central_account_required", "central_login_expired")
                _state.update { it.copy(loading = false, needsCentralLogin = central, error = if (central) null else e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Keine Verbindung. Bitte versuch es erneut.") }
            }
        }
    }

    /** Zu-/Absage: yes | no | maybe, [guests] Begleitpersonen (nur bei Zusage). */
    fun rsvp(event: EventDto, status: String, guests: Int = 0) {
        scope.launch {
            try {
                val updated = api.rsvp(event.id, status, guests)
                _state.update { s -> s.copy(events = s.events.map { if (it.id == updated.id) updated else it }, error = null) }
            } catch (e: NovaApiException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Keine Verbindung. Bitte versuch es erneut.") }
            }
        }
    }
}
