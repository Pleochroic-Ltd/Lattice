package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.EmbedderUnavailableException
import java.io.IOException
import java.net.http.HttpClient
import java.time.Duration

/**
 * An [Embedder] over any OpenAI-compatible `/embeddings` endpoint: llama.cpp's `llama-server --embedding`, LM Studio,
 * OpenAI. Long texts are sliced and their vectors averaged, inputs are batched (see
 * [Config.maxInputs]), every vector is L2-normalized. Any failure throws [EmbedderUnavailableException].
 *
 * [dimension] is [Config.expectedDimension] or what the first answer showed; read before any call, it makes one small
 * call to learn it.
 */
class OpenAiEmbedder(
    private val config: Config,
    httpClient: HttpClient? = null,
) : Embedder {
    data class Config(
        /** The OpenAI-compatible base, ending in `/v1`, e.g. `http://127.0.0.1:8090/v1`. */
        val baseUrl: String,
        /** The model id sent with each call and named by [Embedder.model] (llama-server accepts any string). */
        val model: String,
        val apiKey: String? = null,
        /** The vector length the model must answer; null = learn it from the first answer. */
        val expectedDimension: Int? = null,
        /**
         * Characters per input, past which a text is sliced and its vectors averaged: 480 stays under llama-server's
         * 512-token batch even for dense CJK text.
         */
        val maxInputChars: Int = 480,
        /** Inputs per call. */
        val maxInputs: Int = 256,
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val requestTimeout: Duration = Duration.ofSeconds(60),
    ) {
        override fun toString(): String = "Config(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey == null) "none" else "***"})"
    }

    private val http = HttpJson(config.baseUrl, config.apiKey, config.connectTimeout, config.requestTimeout, httpClient)
    private val backend = "The embedding server at ${http.baseUrl}"
    private val batcher = EmbeddingBatcher(config.maxInputChars, config.maxInputs, config.expectedDimension, backend, ::post)

    override val model: String get() = config.model

    override val dimension: Int
        get() {
            batcher.probe()
            return batcher.dimension
        }

    override fun embed(text: String): FloatArray = batcher.embedAll(listOf(text)).single()

    override fun embedAll(texts: List<String>): List<FloatArray> = batcher.embedAll(texts)

    internal fun requestBody(inputs: List<String>): JsonObject = buildJsonObject {
        put("model", config.model)
        if (inputs.size == 1) put("input", inputs.single()) else putJsonArray("input") { inputs.forEach { add(it) } }
        put("encoding_format", "float")
    }

    private fun post(inputs: List<String>): EmbeddingsAnswer {
        val answer = try {
            http.send("POST", "/embeddings", requestBody(inputs))
        } catch (e: IOException) {
            throw EmbedderUnavailableException("$backend couldn't be reached (${e::class.simpleName}: ${e.message}); is it running?", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EmbedderUnavailableException("The embedding call to ${http.baseUrl} was interrupted.", e)
        }
        if (!answer.ok) {
            val detail = (answer.errorObject()?.get("message") as? JsonPrimitive)?.content ?: answer.body.take(200)
            throw EmbedderUnavailableException(
                "$backend answered HTTP ${answer.status}: $detail; check that it serves the embedding model ${config.model}.",
            )
        }
        return EmbeddingBatcher.parse(answer.json() as? JsonObject, backend)
    }
}
