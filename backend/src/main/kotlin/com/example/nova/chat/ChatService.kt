package com.example.nova.chat

import com.example.nova.ConversationDetailDto
import com.example.nova.ConversationDto
import com.example.nova.MessageDto
import com.example.nova.ModelDto
import com.example.nova.StreamEvent
import com.example.nova.auth.Principal
import com.example.nova.auth.Users
import com.example.nova.central.KnowledgeService
import com.example.nova.badRequest
import com.example.nova.content.ContentService
import com.example.nova.db.Database
import com.example.nova.db.instant
import com.example.nova.db.query
import com.example.nova.db.queryOne
import com.example.nova.db.update
import com.example.nova.db.uuid
import com.example.nova.notFound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

class ChatService(
    private val db: Database,
    private val provider: LlmProvider,
    private val content: ContentService,
    /** Chattia-Assistent: Wissen aus der Cloud des Nutzers (optional). */
    private val knowledge: KnowledgeService? = null,
) {
    private val log = LoggerFactory.getLogger(ChatService::class.java)

    val models: List<ModelDto> get() = provider.models

    suspend fun list(p: Principal): List<ConversationDto> = db.tx {
        query("SELECT id, title, model, updated_at FROM conversations WHERE user_id = ? ORDER BY updated_at DESC LIMIT 200", p.userId) {
            ConversationDto(it.uuid("id").toString(), it.getString("title"), it.getString("model"), it.instant("updated_at").toString())
        }
    }

    suspend fun create(p: Principal, model: String?): ConversationDto {
        val chosen = model?.takeIf { m -> provider.models.any { it.id == m } } ?: provider.models.first().id
        val id = UUID.randomUUID()
        val title = content.get().ui("chat.newChat")
        db.tx {
            update("INSERT INTO conversations (id, user_id, workspace_id, title, model) VALUES (?, ?, ?, ?, ?)", id, p.userId, p.workspaceId, title, chosen)
        }
        return get(p, id.toString()).conversation
    }

    suspend fun get(p: Principal, conversationId: String): ConversationDetailDto = db.tx {
        val id = owned(this, p, conversationId)
        val conv = queryOne("SELECT id, title, model, updated_at FROM conversations WHERE id = ?", id) {
            ConversationDto(it.uuid("id").toString(), it.getString("title"), it.getString("model"), it.instant("updated_at").toString())
        }!!
        val messages = query("SELECT id, role, content, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at", id) {
            MessageDto(it.uuid("id").toString(), it.getString("role"), it.getString("content"), it.instant("created_at").toString())
        }
        ConversationDetailDto(conv, messages)
    }

    suspend fun delete(p: Principal, conversationId: String) = db.tx {
        val id = owned(this, p, conversationId)
        update("DELETE FROM conversations WHERE id = ?", id)
    }

    /** Prüft und speichert die Frage. Fehler hier werden noch als normale HTTP-Fehler beantwortet. */
    suspend fun prepare(p: Principal, conversationId: String, text: String): PreparedTurn {
        val question = text.trim()
        if (question.isEmpty()) throw badRequest("empty_message", "Die Nachricht ist leer.")
        if (question.length > 100_000) throw badRequest("message_too_long", "Die Nachricht ist zu lang.")
        val prepared = db.tx {
            val id = owned(this, p, conversationId)
            val model = queryOne("SELECT model FROM conversations WHERE id = ?", id) { it.getString(1) }!!
            update("INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, 'user', ?)", UUID.randomUUID(), id, question)
            val history = query(
                "SELECT role, content FROM (SELECT role, content, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 60) t ORDER BY created_at",
                id,
            ) { ChatTurn(it.getString("role"), it.getString("content")) }
            val user = Users.byId(this, p.userId)
            PreparedTurn(id, model, history, user?.firstName, if (history.size == 1) makeTitle(question) else null, p.userId)
        }
        if (prepared.newTitle != null) db.tx { update("UPDATE conversations SET title = ? WHERE id = ?", prepared.newTitle, prepared.conversationId) }
        return prepared
    }

    /** Streamt die Antwort und speichert sie am Ende (auch Teilantworten bei Abbruch). */
    fun stream(turn: PreparedTurn): Flow<StreamEvent> = flow {
        emit(StreamEvent("start", conversationTitle = turn.newTitle))
        val answer = StringBuilder()
        var failure: String? = null
        try {
            val question = turn.history.lastOrNull { it.role == "user" }?.content.orEmpty()
            val snippets = if (knowledge != null && turn.userId != null) knowledge.search(turn.userId, question) else emptyList()
            val system = systemPrompt(turn.firstName) + if (snippets.isEmpty()) "" else KnowledgeService.contextBlock(snippets)
            provider.stream(turn.model, system, turn.history).collect { chunk ->
                answer.append(chunk)
                emit(StreamEvent("delta", text = chunk))
            }
            if (snippets.isNotEmpty()) {
                // Quellen sichtbar machen – auch im gespeicherten Verlauf
                val sources = "\n\n---\n" + content.get().ui("assistant.sources") + "\n" +
                    snippets.map { it.path }.distinct().joinToString("\n") { "- `$it`" }
                answer.append(sources)
                emit(StreamEvent("delta", text = sources))
            }
        } catch (e: CancellationException) {
            saveAnswer(turn.conversationId, answer.toString(), turn.model)
            throw e
        } catch (e: ModelRefusedException) {
            failure = e.message
        } catch (e: Exception) {
            log.error("Modellfehler: {}", e.message)
            failure = content.get().ui("error.generic")
        }
        val messageId = saveAnswer(turn.conversationId, answer.toString(), turn.model)
        if (failure != null) emit(StreamEvent("error", message = failure, messageId = messageId?.toString()))
        else emit(StreamEvent("done", messageId = messageId?.toString(), conversationTitle = turn.newTitle))
    }

    data class PreparedTurn(
        val conversationId: UUID,
        val model: String,
        val history: List<ChatTurn>,
        val firstName: String?,
        val newTitle: String?,
        val userId: UUID? = null,
    )

    private suspend fun saveAnswer(conversationId: UUID, text: String, model: String): UUID? {
        if (text.isBlank()) return null
        val id = UUID.randomUUID()
        db.tx {
            update("INSERT INTO messages (id, conversation_id, role, content, model) VALUES (?, ?, 'assistant', ?, ?)", id, conversationId, text, model)
            update("UPDATE conversations SET updated_at = now() WHERE id = ?", conversationId)
        }
        return id
    }

    private fun owned(c: Connection, p: Principal, conversationId: String): UUID {
        val id = runCatching { UUID.fromString(conversationId) }.getOrNull() ?: throw notFound()
        c.queryOne("SELECT 1 FROM conversations WHERE id = ? AND user_id = ?", id, p.userId) { true } ?: throw notFound()
        return id
    }

    private suspend fun systemPrompt(firstName: String?): String {
        val appName = content.get().settings.appName
        return buildString {
            append("Du bist $appName, ein hilfreicher, ehrlicher KI-Assistent in einer Smartphone-App. ")
            append("Antworte in der Sprache, in der du angeschrieben wirst, klar und gut lesbar auf einem Handybildschirm. ")
            append("Nutze Markdown sparsam (kurze Absätze, Listen, Codeblöcke für Code). ")
            append("Wenn du etwas nicht sicher weißt, sag das offen. Bei Gesundheit, Recht und Finanzen weise darauf hin, wichtige Entscheidungen fachlich prüfen zu lassen. ")
            append("Frage niemals nach Passwörtern, Codes oder Wiederherstellungscodes und fordere nie dazu auf, solche Daten weiterzugeben.")
            if (!firstName.isNullOrBlank()) append("\n\nDie Person heißt $firstName.")
        }
    }

    companion object {
        fun makeTitle(question: String): String {
            val oneLine = question.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length <= 48) oneLine else oneLine.take(47).substringBeforeLast(' ', oneLine.take(47)) + "…"
        }
    }
}
