package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.EmbedderUnavailableException
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryModelInterruptedException
import org.foedusprogramme.lattice.core.MemoryModelShutdownException
import org.foedusprogramme.lattice.core.MemoryModelUnavailableException
import org.foedusprogramme.lattice.core.MemoryTask

/**
 * Pleochroic's `POST /embeddings`: the server picks the model, so [model] and [dimension] are what its answers say
 * (read before any call, they make one small call to learn them). Long texts are sliced and averaged and inputs
 * batched as [OpenAiEmbedder] does, at most 256 per call (the service's limit). Any failure throws
 * [EmbedderUnavailableException].
 */
class PleochroicEmbedder(
    private val client: PleochroicClient,
    expectedDimension: Int? = null,
    maxInputChars: Int = 480,
) : Embedder {
    private val backend = "Pleochroic's embeddings at ${client.baseUrl}"
    private val batcher = EmbeddingBatcher(maxInputChars, MAX_INPUTS, expectedDimension, backend, ::post)

    override val model: String
        get() {
            batcher.probeModel()
            return batcher.model ?: throw EmbedderUnavailableException("$backend answered without naming its model.")
        }

    override val dimension: Int
        get() {
            batcher.probe()
            return batcher.dimension
        }

    override fun embed(text: String): FloatArray = batcher.embedAll(listOf(text)).single()

    override fun embedAll(texts: List<String>): List<FloatArray> = batcher.embedAll(texts)

    private fun post(inputs: List<String>): EmbeddingsAnswer {
        val body = buildJsonObject {
            putJsonArray("input") { inputs.forEach { add(it) } }
            put("encoding_format", "float")
        }
        val answer = try {
            client.call("POST", "/embeddings", body)
        } catch (e: PleochroicApiException) {
            throw EmbedderUnavailableException("$backend refused the call: ${e.message}", e)
        } catch (e: PleochroicUnreachableException) {
            throw EmbedderUnavailableException(e.message ?: "$backend couldn't be reached.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EmbedderUnavailableException("The embedding call to ${client.baseUrl} was interrupted.", e)
        }
        return EmbeddingBatcher.parse(answer as? JsonObject, backend)
    }

    private companion object {
        const val MAX_INPUTS = 256
    }
}

/**
 * Pleochroic's `POST /lattice/complete`: `{task, system, user}` → `{text, model, usage}`, the server picking the model
 * per [MemoryTask.wire]. A prompt over the service's 512 KiB is refused here with [IllegalArgumentException] (a bad
 * input, not an outage); any other failure is [MemoryModelUnavailableException], an interrupted call
 * [MemoryModelInterruptedException], and a call after [close] [MemoryModelShutdownException].
 */
class PleochroicMemoryModel(private val client: PleochroicClient) : MemoryModel, AutoCloseable {
    @Volatile
    private var closed = false

    /** The model and token usage of the last answer, for status displays. */
    @Volatile
    var lastAnswer: Answer? = null
        private set

    data class Answer(val task: MemoryTask, val model: String, val inputTokens: Long, val outputTokens: Long)

    override fun complete(task: MemoryTask, system: String, user: String): String {
        if (closed) throw MemoryModelShutdownException("The Pleochroic memory model is closed.")
        val bytes = system.toByteArray().size + user.toByteArray().size
        require(bytes <= MAX_PROMPT_BYTES) {
            "The ${task.wire} prompt is $bytes bytes, over Pleochroic's $MAX_PROMPT_BYTES; send a shorter window."
        }
        val body = buildJsonObject {
            put("task", task.wire)
            put("system", system)
            put("user", user)
        }
        val answer = try {
            client.call("POST", "/lattice/complete", body) as? JsonObject
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MemoryModelInterruptedException("The ${task.wire} call to Pleochroic was interrupted.", e)
        } catch (e: PleochroicApiException) {
            if (closed) throw MemoryModelShutdownException("The Pleochroic memory model closed during the call.", e)
            if (e.status == 413) throw IllegalArgumentException("Pleochroic refused the ${task.wire} prompt as too large: ${e.message}", e)
            throw MemoryModelUnavailableException("Pleochroic couldn't run the ${task.wire} call: ${e.message}", e)
        } catch (e: PleochroicUnreachableException) {
            if (closed) throw MemoryModelShutdownException("The Pleochroic memory model closed during the call.", e)
            throw MemoryModelUnavailableException(e.message ?: "Pleochroic couldn't be reached.", e)
        }
        val text = (answer?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw MemoryModelUnavailableException("Pleochroic answered the ${task.wire} call without a text; the service may be mid-upgrade.")
        val usage = answer["usage"] as? JsonObject
        lastAnswer = Answer(
            task,
            (answer["model"] as? JsonPrimitive)?.content.orEmpty(),
            (usage?.get("input") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
            (usage?.get("output") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
        )
        return text
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val MAX_PROMPT_BYTES = 512 * 1024
    }
}
