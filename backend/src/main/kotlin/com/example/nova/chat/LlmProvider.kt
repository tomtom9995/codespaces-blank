package com.example.nova.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.example.nova.ModelDto
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ChatTurn(val role: String, val content: String)

/** Ein Modellanbieter liefert die Antwort als Strom von Textstücken. */
interface LlmProvider {
    val models: List<ModelDto>
    fun stream(model: String, system: String, history: List<ChatTurn>): Flow<String>
}

class ModelRefusedException(message: String) : Exception(message)

/**
 * Claude über das offizielle Anthropic-Java-SDK. Zugangsdaten aus ANTHROPIC_API_KEY (oder ANTHROPIC_AUTH_TOKEN).
 * Serverseitige Fallbacks sind aktiv: Lehnt das Modell eine Anfrage aus Sicherheitsgründen ab,
 * übernimmt automatisch ein passendes anderes Modell im selben Aufruf.
 */
class AnthropicProvider(private val model: String) : LlmProvider {
    private val client: AnthropicClient = AnthropicOkHttpClient.fromEnv()

    override val models = listOf(ModelDto(model, displayName(model), "anthropic"))

    override fun stream(model: String, system: String, history: List<ChatTurn>): Flow<String> = flow {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .addBeta(AnthropicBeta.of("server-side-fallback-2026-07-01"))
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .apply {
                history.forEach { turn ->
                    if (turn.role == "assistant") addAssistantMessage(turn.content) else addUserMessage(turn.content)
                }
            }
            .build()
        var refused = false
        client.beta().messages().createStreaming(params).use { response ->
            val events = response.stream().iterator()
            while (events.hasNext()) {
                currentCoroutineContext().ensureActive()
                val event = events.next()
                val text = event.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse(null)
                if (event.messageDelta().flatMap { it.delta().stopReason() }.map { it == BetaStopReason.REFUSAL }.orElse(false)) refused = true
                if (!text.isNullOrEmpty()) emit(text)
            }
        }
        if (refused) throw ModelRefusedException("Das Modell hat diese Anfrage abgelehnt.")
    }.flowOn(Dispatchers.IO)

    private fun displayName(id: String) = when (id) {
        "claude-opus-5-5" -> "Claude Opus 5.5"
        "claude-sonnet-5-5" -> "Claude Sonnet 5.5"
        "claude-fable-5-1" -> "Claude Fable 5.1"
        else -> id
    }
}

/**
 * OpenAI-kompatible Schnittstelle (/v1/chat/completions), z. B. LiteLLM als Gateway zu Vertex AI/Gemini
 * oder ein lokales Ollama.
 */
class OpenAiCompatibleProvider(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String?,
    modelIds: List<String>,
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }
    override val models = modelIds.ifEmpty { listOf("default") }.map { ModelDto(it, it, "openai-compatible") }

    override fun stream(model: String, system: String, history: List<ChatTurn>): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                history.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.content) }) }
            })
        }
        http.preparePost("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            apiKey?.let { bearerAuth(it) }
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) error("Modell-Gateway: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val choice = json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val text = (choice["delta"]?.jsonObject?.get("content") as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) emit(text)
            }
        }
    }
}

/** Testmodus ohne Schlüssel: antwortet mit einem festen Text, damit App und Streaming ohne Kosten testbar sind. */
class MockProvider : LlmProvider {
    override val models = listOf(ModelDto("nova-test", "Testmodus (ohne KI)", "mock"))

    override fun stream(model: String, system: String, history: List<ChatTurn>): Flow<String> = flow {
        val question = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val answer = buildString {
            append("**Testmodus:** Hier antwortet noch kein echtes KI-Modell.\n\n")
            append("Deine Nachricht war:\n\n> ${question.take(300).replace("\n", "\n> ")}\n\n")
            append("So aktivierst du echte Antworten im Backend:\n\n")
            append("1. `ANTHROPIC_API_KEY` setzen (Claude, Standardmodell `claude-opus-5-5`), oder\n")
            append("2. `OPENAI_BASE_URL` auf ein OpenAI-kompatibles Gateway wie LiteLLM (z. B. für Gemini über Vertex AI) setzen.\n\n")
            append("Streaming, Verlauf und Speichern funktionieren bereits genauso wie später mit dem echten Modell.")
        }
        for (chunk in answer.split(Regex("(?<=\\s)"))) {
            emit(chunk)
            delay(18)
        }
    }
}
