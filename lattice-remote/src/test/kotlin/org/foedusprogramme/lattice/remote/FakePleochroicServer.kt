package org.foedusprogramme.lattice.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * standalone-service's Lattice endpoints in memory, as `docs/API.md` describes them: graphs as an op log compacted per
 * record, snapshots, `409` on a stale `baseSeq`, `410 snapshot_required` past the log's start, embeddings and memory
 * completions. Test hooks: [down] answers `503`, [failNextOps] fails the next posts, [rejectNextOps] refuses them as
 * malformed, [compact] drops the log up to now, and [requests] lists what was asked.
 */
class FakePleochroicServer(val token: String = "test-key", private val dimension: Int = 8) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val graphs = HashMap<String, Graph>()
    private val embedder = LocalHashingEmbedder(dimension)

    val requests: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var down = false

    @Volatile
    var failNextOps = 0

    @Volatile
    var rejectNextOps = 0

    @Volatile
    var unauthorizedNextOps = 0

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    fun client(): PleochroicClient = PleochroicClient(token, baseUrl)

    class Graph(val name: String, val embedding: JsonObject?) {
        var seq = 0L
        var compactedThrough = 0L
        val log = TreeMap<Long, JsonObject>()
        val state = LinkedHashMap<String, JsonObject>()
        private val logSeqOf = HashMap<String, Long>()

        fun append(op: JsonObject) {
            seq++
            val key = keyOf(op)
            logSeqOf.put(key, seq)?.let { log.remove(it) }
            log[seq] = op
            val kind = op["op"]!!.jsonPrimitive.content
            if (kind.startsWith("put")) state[key] = op else state.remove(key)
            if (kind == "deleteEngram") {
                val id = op["id"]!!.jsonPrimitive.content
                state.keys.filter { it.startsWith("edge:") && id in it.removePrefix("edge:").split(':') }.forEach(state::remove)
            }
        }

        fun records(type: String): List<JsonObject> =
            state.values.filter { it["op"]?.jsonPrimitive?.content == "putRecord" && it["type"]?.jsonPrimitive?.content == type }
                .map { it["data"]!!.jsonObject }

        val engramIds: Set<Long> get() = state.values.filter { it.kind == "putEngram" }.map { it["engram"]!!.jsonObject["id"]!!.jsonPrimitive.longOrNull!! }.toSet()

        val edgeCount: Int get() = state.values.count { it.kind == "putEdge" }

        companion object {
            private val JsonObject.kind get() = this["op"]?.jsonPrimitive?.content

            fun keyOf(op: JsonObject): String = when (val kind = op["op"]?.jsonPrimitive?.content) {
                "putEngram" -> "engram:${op["engram"]!!.jsonObject["id"]!!.jsonPrimitive.content}"
                "deleteEngram" -> "engram:${op["id"]!!.jsonPrimitive.content}"
                "putEdge", "deleteEdge" -> {
                    val a = op["a"]!!.jsonPrimitive.longOrNull!!
                    val b = op["b"]!!.jsonPrimitive.longOrNull!!
                    "edge:${minOf(a, b)}:${maxOf(a, b)}"
                }
                "putRecord", "deleteRecord" -> "record:${op["type"]!!.jsonPrimitive.content}:${op["key"]!!.jsonPrimitive.content}"
                else -> error("unknown op $kind")
            }
        }
    }

    init {
        server.createContext("/v1/") { exchange ->
            try {
                handle(exchange)
            } catch (e: Throwable) {
                respond(exchange, 500, error("internal", e.toString()))
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun graph(name: String): Graph? = synchronized(this) { graphs[name] }

    /** Deletes the graph [name], as its owner can on the service. */
    fun delete(name: String) = synchronized(this) { graphs.remove(name) }

    /** Drops every op logged so far, as compaction past the retention would. */
    fun compact(name: String) = synchronized(this) {
        val g = graphs.getValue(name)
        g.compactedThrough = g.seq
        g.log.clear()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath.removePrefix("/v1")
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val method = exchange.requestMethod
        requests += "$method $path"
        if (down) return respond(exchange, 503, error("upstream_error", "The service is down for the test."))
        if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
            return respond(exchange, 401, error("unauthorized", "No key, or an invalid one."))
        }
        val body = exchange.requestBody.readAllBytes().decodeToString().takeIf { it.isNotBlank() }?.let { JSON.parseToJsonElement(it).jsonObject }
        val parts = path.trim('/').split('/')
        synchronized(this) {
            when {
                method == "POST" && path == "/embeddings" -> embeddings(exchange, body!!)
                method == "POST" && path == "/lattice/complete" -> complete(exchange, body!!)
                method == "POST" && path == "/graphs" -> create(exchange, body!!)
                parts.size == 2 && parts[0] == "graphs" && method == "GET" -> {
                    val g = graphs[parts[1]] ?: return respond(exchange, 404, error("not_found", "No graph ${parts[1]}."))
                    respond(exchange, 200, info(g))
                }
                parts.size == 3 && parts[2] == "snapshot" -> {
                    val g = graphs[parts[1]] ?: return respond(exchange, 404, error("not_found", "No graph ${parts[1]}."))
                    // Records first, engrams last: a client must not depend on the order.
                    val ops = g.state.values.sortedBy { if (it["op"]!!.jsonPrimitive.content == "putEngram") 1 else 0 }
                    val text = buildString {
                        append(buildJsonObject { put("seq", g.seq) }).append('\n')
                        ops.forEach { append(it).append('\n') }
                    }
                    respondText(exchange, 200, text, "application/x-ndjson")
                }
                parts.size == 3 && parts[2] == "ops" && method == "GET" -> since(exchange, graphs[parts[1]], query)
                parts.size == 3 && parts[2] == "ops" && method == "POST" -> post(exchange, graphs[parts[1]], body!!)
                else -> respond(exchange, 404, error("not_found", "No route $method $path."))
            }
        }
    }

    private fun create(exchange: HttpExchange, body: JsonObject) {
        val name = body["name"]!!.jsonPrimitive.content
        if (name in graphs) return respond(exchange, 409, error("conflict", "Graph $name exists."))
        val g = Graph(name, body["embedding"] as? JsonObject)
        graphs[name] = g
        respond(exchange, 201, info(g))
    }

    private fun since(exchange: HttpExchange, g: Graph?, query: Map<String, String>) {
        g ?: return respond(exchange, 404, error("not_found", "No graph."))
        val since = query["since"]?.toLongOrNull() ?: 0
        val limit = query["limit"]?.toIntOrNull() ?: 1000
        if (since < g.compactedThrough) {
            return respond(exchange, 410, error("snapshot_required", "Load the snapshot again.") { put("oldestSeq", g.compactedThrough + 1) })
        }
        val tail = g.log.tailMap(since, false).entries.toList()
        val page = tail.take(limit)
        val more = tail.size > limit
        respond(
            exchange, 200,
            buildJsonObject {
                putJsonArray("ops") { page.forEach { (seq, op) -> add(JsonObject(op + ("seq" to JsonPrimitive(seq)))) } }
                put("seq", if (more) page.last().key else g.seq)
                put("more", more)
            },
        )
    }

    private fun post(exchange: HttpExchange, g: Graph?, body: JsonObject) {
        g ?: return respond(exchange, 404, error("not_found", "No graph."))
        if (failNextOps > 0) {
            failNextOps--
            return respond(exchange, 503, error("upstream_error", "Failed for the test."))
        }
        if (unauthorizedNextOps > 0) {
            unauthorizedNextOps--
            return respond(exchange, 401, error("unauthorized", "The key was revoked for the test."))
        }
        if (rejectNextOps > 0) {
            rejectNextOps--
            return respond(exchange, 400, error("invalid_request", "Refused for the test."))
        }
        val baseSeq = (body["baseSeq"] as? JsonPrimitive)?.longOrNull
        if (baseSeq != null && baseSeq != g.seq) {
            return respond(exchange, 409, error("conflict", "The graph moved on.") { put("seq", g.seq) })
        }
        val ops = body["ops"]!!.jsonArray.map { it.jsonObject }
        if (ops.size > 10_000 || body.toString().toByteArray().size > 8 * 1024 * 1024) {
            return respond(exchange, 413, error("too_large", "Too many ops."))
        }
        val dimension = (g.embedding?.get("dimension") as? JsonPrimitive)?.intOrNull
        for (op in ops) {
            runCatching { Graph.keyOf(op) }.onFailure { return respond(exchange, 400, error("invalid_request", it.message.orEmpty())) }
            val vector = (op["engram"] as? JsonObject)?.get("embedding")?.jsonPrimitive?.content
            if (vector != null && dimension != null && Base64.getDecoder().decode(vector).size != dimension * 4) {
                return respond(exchange, 400, error("invalid_request", "An embedding of another dimension."))
            }
        }
        ops.forEach(g::append)
        respond(exchange, 200, buildJsonObject { put("seq", g.seq) })
    }

    private fun embeddings(exchange: HttpExchange, body: JsonObject) {
        val inputs = when (val input = body["input"]) {
            is JsonArray -> input.map { it.jsonPrimitive.content }
            else -> listOf(input!!.jsonPrimitive.content)
        }
        if (inputs.size > 256) return respond(exchange, 400, error("invalid_request", "Too many inputs."))
        respond(
            exchange, 200,
            buildJsonObject {
                put("object", "list")
                put("model", "fake-embed-$dimension")
                putJsonArray("data") {
                    inputs.forEachIndexed { i, text ->
                        addJsonObject {
                            put("object", "embedding")
                            put("index", i)
                            putJsonArray("embedding") { embedder.embed(text).forEach { add(it * 3f) } }
                        }
                    }
                }
                putJsonObject("usage") {
                    put("prompt_tokens", inputs.size)
                    put("total_tokens", inputs.size)
                }
            },
        )
    }

    private fun complete(exchange: HttpExchange, body: JsonObject) {
        val task = body["task"]?.jsonPrimitive?.contentOrNull
        if (task !in setOf("extract", "judge", "abstract", "entities", "narrate", "scenes", "periods")) {
            return respond(exchange, 400, error("invalid_request", "Unknown task $task."))
        }
        respond(
            exchange, 200,
            buildJsonObject {
                put("text", "$task|${body["system"]!!.jsonPrimitive.content}|${body["user"]!!.jsonPrimitive.content}")
                put("model", "fake-$task")
                putJsonObject("usage") {
                    put("input", 12)
                    put("output", 3)
                }
            },
        )
    }

    private fun info(g: Graph): JsonObject = buildJsonObject {
        put("name", g.name)
        put("seq", g.seq)
        put("oldestSeq", g.compactedThrough + 1)
        put("engrams", g.engramIds.size)
        put("edges", g.edgeCount)
        put("embedding", g.embedding ?: kotlinx.serialization.json.JsonNull)
        put("size", 0)
        put("updatedAt", Instant.now().toString())
    }

    private fun error(code: String, message: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonElement =
        buildJsonObject {
            putJsonObject("error") {
                put("code", code)
                put("message", message)
                extra()
            }
        }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonElement) =
        respondText(exchange, status, body.toString(), "application/json")

    private fun respondText(exchange: HttpExchange, status: Int, text: String, type: String) {
        val bytes = text.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong().coerceAtLeast(1).takeIf { bytes.isNotEmpty() } ?: -1)
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}
