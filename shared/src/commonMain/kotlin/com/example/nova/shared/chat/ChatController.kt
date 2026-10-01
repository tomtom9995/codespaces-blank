package com.example.nova.shared.chat

import com.example.nova.shared.api.ConversationDto
import com.example.nova.shared.api.NovaApi
import com.example.nova.shared.api.NovaApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val id: String, val role: String, val text: String, val streaming: Boolean = false, val failed: Boolean = false) {
    val isUser: Boolean get() = role == "user"
}

data class ChatState(
    val conversationId: String? = null,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Chat-Logik für beide Plattformen: Verlauf laden, Nachricht senden, Antwort live anzeigen, abbrechen. */
class ChatController(private val api: NovaApi, private val scope: CoroutineScope, private val newChatTitle: () -> String) {
    private val _conversations = MutableStateFlow<List<ConversationDto>>(emptyList())
    val conversations: StateFlow<List<ConversationDto>> = _conversations.asStateFlow()

    private val _state = MutableStateFlow(ChatState(title = newChatTitle()))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var localIds = 0

    fun refreshConversations() {
        scope.launch {
            runCatching { api.conversations() }.onSuccess { _conversations.value = it }
        }
    }

    fun newChat() {
        stop()
        _state.value = ChatState(title = newChatTitle())
    }

    fun open(conversationId: String) {
        stop()
        _state.value = ChatState(conversationId = conversationId, loading = true)
        scope.launch {
            try {
                val detail = api.conversation(conversationId)
                _state.value = ChatState(
                    conversationId = conversationId,
                    title = detail.conversation.title,
                    messages = detail.messages.map { ChatMessage(it.id, it.role, it.content) },
                )
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun delete(conversationId: String) {
        scope.launch {
            runCatching { api.deleteConversation(conversationId) }
            if (_state.value.conversationId == conversationId) newChat()
            refreshConversations()
        }
    }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.streaming) return
        val answerId = "local-${localIds++}"
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage("local-${localIds++}", "user", question) + ChatMessage(answerId, "assistant", "", streaming = true),
                streaming = true,
                error = null,
            )
        }
        streamJob = scope.launch {
            try {
                val conversationId = _state.value.conversationId ?: api.createConversation().id.also { id ->
                    _state.update { it.copy(conversationId = id) }
                }
                api.sendMessage(conversationId, question).collect { event ->
                    when (event.type) {
                        "start" -> event.conversationTitle?.let { title -> _state.update { it.copy(title = title) } }
                        "delta" -> appendTo(answerId, event.text.orEmpty())
                        "done" -> finish(answerId, event.messageId, failed = false, error = null)
                        "error" -> finish(answerId, event.messageId, failed = true, error = event.message)
                    }
                }
                finish(answerId, null, failed = false, error = null)
            } catch (e: NovaApiException) {
                finish(answerId, null, failed = true, error = e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finish(answerId, null, failed = false, error = null)
                throw e
            } catch (e: Exception) {
                finish(answerId, null, failed = true, error = e.message ?: "Keine Verbindung")
            } finally {
                refreshConversations()
            }
        }
    }

    /** Bricht die laufende Antwort ab; der bisherige Teil bleibt stehen und wird gespeichert. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun appendTo(id: String, chunk: String) = _state.update { state ->
        state.copy(messages = state.messages.map { if (it.id == id) it.copy(text = it.text + chunk) else it })
    }

    private fun finish(id: String, serverId: String?, failed: Boolean, error: String?) = _state.update { state ->
        state.copy(
            streaming = false,
            error = error ?: state.error,
            messages = state.messages.mapNotNull {
                when {
                    it.id != id -> it
                    it.text.isEmpty() && failed -> null
                    else -> it.copy(id = serverId ?: it.id, streaming = false, failed = failed)
                }
            },
        )
    }
}
