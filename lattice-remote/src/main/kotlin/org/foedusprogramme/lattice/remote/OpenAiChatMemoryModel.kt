package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryModelInterruptedException
import org.foedusprogramme.lattice.core.MemoryModelShutdownException
import org.foedusprogramme.lattice.core.MemoryModelUnavailableException
import org.foedusprogramme.lattice.core.MemoryTask
import java.io.IOException
import java.net.http.HttpClient
import java.time.Duration

/**
 * A [MemoryModel] over any OpenAI-compatible `/chat/completions` endpoint (llama-server, LM Studio, OpenAI, an
 * OpenRouter-style gateway), with a model per [MemoryTask].
 *
 * Failures: whatever keeps the backend from answering (no connection, a timeout, any
 * HTTP error) is [MemoryModelUnavailableException], so the work is retried later rather than written off; an answer
 * that arrives is returned as it is, even empty, for the caller to judge. An interrupted call throws
 * [MemoryModelInterruptedException] and a call after [close] [MemoryModelShutdownException].
 */
class OpenAiChatMemoryModel(
    private val config: Config,
    httpClient: HttpClient? = null,
) : MemoryModel, AutoCloseable {
    data class Config(
        /** The OpenAI-compatible base, ending in `/v1`. */
        val baseUrl: String,
        /** The model for a task with no entry in [models]. */
        val defaultModel: String,
        val models: Map<MemoryTask, String> = emptyMap(),
        val apiKey: String? = null,
        /** Null leaves the server's default. */
        val temperature: Double? = null,
        /** Null sends no limit: a reasoning model can spend a small budget before it writes the answer. */
        val maxTokens: Int? = null,
        /** Fields merged into every request body, e.g. `reasoning_effort` or `top_p`. */
        val extraBody: Map<String, JsonElement> = emptyMap(),
        val connectTimeout: Duration = Duration.ofSeconds(10),
        /** Extraction over a long window can take minutes on a local model. */
        val requestTimeout: Duration = Duration.ofMinutes(5),
    ) {
        fun modelFor(task: MemoryTask): String = models[task] ?: defaultModel

        override fun toString(): String =
            "Config(baseUrl=$baseUrl, defaultModel=$defaultModel, models=$models, apiKey=${if (apiKey == null) "none" else "***"})"
    }

    private val http = HttpJson(config.baseUrl, config.apiKey, config.connectTimeout, config.requestTimeout, httpClient)

    @Volatile
    private var closed = false

    override fun complete(task: MemoryTask, system: String, user: String): String {
        if (closed) throw MemoryModelShutdownException("The memory model at ${http.baseUrl} is closed.")
        val model = config.modelFor(task)
        val answer = try {
            http.send("POST", "/chat/completions", requestBody(task, system, user))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MemoryModelInterruptedException("The ${task.wire} call to $model was interrupted.", e)
        } catch (e: IOException) {
            if (closed) throw MemoryModelShutdownException("The memory model at ${http.baseUrl} closed during the call.", e)
            throw MemoryModelUnavailableException(
                "The model server at ${http.baseUrl} couldn't be reached for the ${task.wire} call (${e::class.simpleName}: ${e.message}).",
                e,
            )
        }
        if (!answer.ok) {
            val detail = (answer.errorObject()?.get("message") as? JsonPrimitive)?.content ?: answer.body.take(300)
            throw MemoryModelUnavailableException(
                "The model server at ${http.baseUrl} answered HTTP ${answer.status} to the ${task.wire} call on $model: $detail",
            )
        }
        return contentOf(answer.json())
            ?: throw MemoryModelUnavailableException(
                "The model server at ${http.baseUrl} answered the ${task.wire} call without choices[0].message; check that it " +
                    "implements chat completions.",
            )
    }

    internal fun requestBody(task: MemoryTask, system: String, user: String): JsonObject = buildJsonObject {
        put("model", config.modelFor(task))
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                put("content", user)
            }
        }
        config.temperature?.let { put("temperature", it) }
        config.maxTokens?.let { put("max_tokens", it) }
        put("stream", false)
        config.extraBody.forEach { (name, value) -> put(name, value) }
    }

    override fun close() {
        closed = true
    }

    private companion object {
        /** `choices[0].message.content` as a string or an array of text parts; "" for a null content. */
        fun contentOf(root: JsonElement?): String? {
            val message = (((root as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                ?: return null
            return when (val content = message["content"]) {
                is JsonPrimitive -> if (content.isString) content.content else ""
                is JsonArray -> content.joinToString("") { part ->
                    ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
                }
                else -> ""
            }
        }
    }
}
