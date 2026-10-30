package org.foedusprogramme.lattice.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.foedusprogramme.lattice.core.EmbeddingSpec
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** One unit of the outbox: a graph to create, or a batch of ops to post (one POST each). */
internal sealed interface OutboxEntry {
    data class Create(val spec: EmbeddingSpec) : OutboxEntry

    data class Ops(val ops: List<JsonObject>) : OutboxEntry
}

/** This device's identity in a graph: who it is and which id slot it claimed (null until it has one). */
internal data class DeviceState(val deviceId: String, val slot: Int?)

/** A graph's state as last seen: its seq and spec, and its rows as put ops. */
internal class CachedSnapshot(val seq: Long, val spec: EmbeddingSpec?, val ops: List<JsonObject>)

/**
 * A remote graph's files in the state directory, named after the graph: `<name>.device.json` (the device id and
 * slot), `<name>.outbox.jsonl` (writes not yet on the server, one POST per line, oldest first),
 * `<name>.rejected.jsonl` (writes the server refused as malformed, kept for inspection; past [maxRejectedBytes] it
 * becomes `<name>.rejected.jsonl.1`, replacing the one before) and `<name>.snapshot.ndjson` (the graph as last seen, for
 * opening without the network). Every rewrite goes through a temporary file and an atomic move.
 */
internal class GraphStateFiles(dir: Path, private val name: String, private val maxRejectedBytes: Long = 8L * 1024 * 1024) {
    private val logger = LoggerFactory.getLogger(GraphStateFiles::class.java)
    private val device = dir.resolve("$name.device.json")
    private val outbox = dir.resolve("$name.outbox.jsonl")
    private val rejected = dir.resolve("$name.rejected.jsonl")
    private val snapshot = dir.resolve("$name.snapshot.ndjson")

    init {
        Files.createDirectories(dir)
    }

    fun readDevice(): DeviceState? {
        if (!Files.exists(device)) return null
        val o = parse(Files.readString(device)) ?: return null
        val id = (o["deviceId"] as? JsonPrimitive)?.content ?: return null
        return DeviceState(id, (o["slot"] as? JsonPrimitive)?.intOrNull)
    }

    fun writeDevice(state: DeviceState) = replace(device) { out ->
        out.write(
            buildJsonObject {
                put("deviceId", state.deviceId)
                state.slot?.let { put("slot", it) }
            }.toString(),
        )
    }

    fun readOutbox(): List<OutboxEntry> {
        if (!Files.exists(outbox)) return emptyList()
        return Files.readAllLines(outbox, StandardCharsets.UTF_8).mapIndexedNotNull { i, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            val entry = parse(line)?.let(::entryOf)
            if (entry == null) logger.warn("Skipped line {} of {}: it isn't an outbox entry (a torn write?).", i + 1, outbox)
            entry
        }
    }

    fun appendOutbox(entries: List<OutboxEntry>) {
        if (entries.isEmpty()) return
        Files.newBufferedWriter(outbox, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out ->
            entries.forEach { out.write(lineOf(it).toString()); out.write("\n") }
        }
    }

    fun rewriteOutbox(entries: List<OutboxEntry>) {
        if (entries.isEmpty()) {
            Files.deleteIfExists(outbox)
        } else {
            replace(outbox) { out -> entries.forEach { out.write(lineOf(it).toString()); out.write("\n") } }
        }
    }

    fun appendRejected(entry: OutboxEntry, reason: String) {
        if (Files.exists(rejected) && Files.size(rejected) >= maxRejectedBytes) {
            Files.move(rejected, rejected.resolveSibling("${rejected.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.newBufferedWriter(rejected, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out ->
            out.write(
                buildJsonObject {
                    put("at", java.time.Instant.now().toString())
                    put("reason", reason)
                    put("entry", lineOf(entry))
                }.toString(),
            )
            out.write("\n")
        }
    }

    /**
     * Moves the outbox, with [unsaved] appended, and the copy aside as `<name>.outbox.gone-<millis>.jsonl` and
     * `<name>.snapshot.gone-<millis>.ndjson`, for a graph deleted on the service; returns where the outbox went, null when
     * there was nothing to keep.
     */
    fun setAside(unsaved: List<OutboxEntry>): Path? {
        appendOutbox(unsaved)
        val stamp = System.currentTimeMillis()
        if (Files.exists(snapshot)) {
            Files.move(snapshot, snapshot.resolveSibling("$name.snapshot.gone-$stamp.ndjson"), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!Files.exists(outbox)) return null
        val aside = outbox.resolveSibling("$name.outbox.gone-$stamp.jsonl")
        Files.move(outbox, aside, StandardCopyOption.REPLACE_EXISTING)
        return aside
    }

    fun readSnapshot(): CachedSnapshot? {
        if (!Files.exists(snapshot)) return null
        return try {
            Files.newBufferedReader(snapshot, StandardCharsets.UTF_8).use { reader ->
                val head = parse(reader.readLine() ?: return null) ?: return null
                val seq = (head["seq"] as? JsonPrimitive)?.longOrNull ?: return null
                val ops = reader.lineSequence().filter { it.isNotBlank() }.map { parse(it) ?: error("A cached op isn't JSON.") }.toList()
                CachedSnapshot(seq, GraphWire.embeddingOf(head["embedding"]), ops)
            }
        } catch (e: Exception) {
            logger.warn("Ignored the cached snapshot {}: {}", snapshot, e.message)
            null
        }
    }

    fun writeSnapshot(seq: Long, spec: EmbeddingSpec?, ops: Sequence<JsonObject>) = replace(snapshot) { out ->
        out.write(
            buildJsonObject {
                put("seq", seq)
                spec?.let { put("embedding", GraphWire.embedding(it)) }
            }.toString(),
        )
        out.write("\n")
        ops.forEach { out.write(it.toString()); out.write("\n") }
    }

    private fun replace(target: Path, write: (BufferedWriter) -> Unit) {
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use(write)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun lineOf(entry: OutboxEntry): JsonObject = when (entry) {
        is OutboxEntry.Create -> buildJsonObject {
            put("kind", "create")
            put("embedding", GraphWire.embedding(entry.spec))
        }
        is OutboxEntry.Ops -> buildJsonObject {
            put("kind", "ops")
            put("ops", JsonArray(entry.ops))
        }
    }

    private fun entryOf(o: JsonObject): OutboxEntry? = when ((o["kind"] as? JsonPrimitive)?.content) {
        "create" -> GraphWire.embeddingOf(o["embedding"])?.let { OutboxEntry.Create(it) }
        "ops" -> (o["ops"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.let { OutboxEntry.Ops(it) }
        else -> null
    }

    private fun parse(text: String): JsonObject? = try {
        JSON.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    }
}
