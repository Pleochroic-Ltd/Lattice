package org.foedusprogramme.lattice.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An OpenAI-compatible server for tests: `/v1/embeddings` answers `[0, 3, 4, …]`-shaped vectors (one per input, the
 * input's length in the first component), `/v1/chat/completions` echoes the model and the user message. [status]
 * makes every call fail with that status; [bodies] records what came in.
 */
class FakeOpenAiServer(private val dimension: Int = 3) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val bodies: MutableList<JsonObject> = CopyOnWriteArrayList()
    val authorizations: MutableList<String?> = CopyOnWriteArrayList()

    @Volatile
    var status = 200

    @Volatile
    var answerDimension = dimension

    @Volatile
    var chatContent: String? = null

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/") { exchange ->
            try {
                val body = JSON.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                bodies += body
                authorizations += exchange.requestHeaders.getFirst("Authorization")
                val answer = when {
                    status != 200 -> buildJsonObject { putJsonObject("error") { put("message", "failing for the test") } }
                    exchange.requestURI.path.endsWith("/embeddings") -> embeddings(body)
                    else -> chat(body)
                }
                val bytes = answer.toString().toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    private fun embeddings(body: JsonObject): JsonObject {
        val inputs = when (val input = body["input"]) {
            is JsonArray -> input.map { it.jsonPrimitive.content }
            else -> listOf(input!!.jsonPrimitive.content)
        }
        return buildJsonObject {
            put("object", "list")
            put("model", body["model"]!!.jsonPrimitive.content)
            putJsonArray("data") {
                // Answered in reverse index order: a client must sort by index.
                inputs.indices.reversed().forEach { i ->
                    addJsonObject {
                        put("index", i)
                        putJsonArray("embedding") {
                            add(inputs[i].length.toFloat())
                            add(3f)
                            repeat(answerDimension - 2) { add(4f) }
                        }
                    }
                }
            }
        }
    }

    private fun chat(body: JsonObject): JsonObject = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    val user = (body["messages"] as JsonArray)[1].jsonObject["content"]!!.jsonPrimitive.content
                    put("content", chatContent ?: "${body["model"]!!.jsonPrimitive.content}:$user")
                }
            }
        }
    }

    override fun close() = server.stop(0)
}
