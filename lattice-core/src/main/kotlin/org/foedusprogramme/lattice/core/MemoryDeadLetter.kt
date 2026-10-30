package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Records "poison window" extraction failures — a memory-extraction call whose model output did not parse
 * as JSON ([UnparseableExtractionException]). Before this, such a window was only logged as
 * "not parseable JSON" with NO trace of WHAT the model actually emitted (a moralizing preamble? a refusal?
 * a JSON body truncated at the output-token ceiling?), so the failure was undiagnosable after the fact and
 * the window's content was effectively lost until the next sleep re-tried it blindly.
 *
 * The sink captures the exact transcript that went in and the raw output that came back, so a persistent
 * poison window can be inspected and the root cause (overflow vs content-balk vs transient) told apart.
 * It is a seam ([record]) so the recording is unit-testable offline and defaults to [NoOp] (byte-identical
 * behavior when no sink is wired).
 */
fun interface MemoryDeadLetter {
    fun record(entry: DeadLetterEntry)

    /** Default: record nothing (keeps extraction behavior byte-identical when no sink is configured). */
    object NoOp : MemoryDeadLetter {
        override fun record(entry: DeadLetterEntry) {}
    }
}

/**
 * One poison-window failure. [transcript] and [rawOutput] are the diagnostic payload; both are truncated by
 * the sink so a giant window can never bloat the log. [phase] names the code path that hit it
 * ("capture" = [org.foedusprogramme.lattice.Lattice.capture]; the scene pipeline adds its own).
 */
data class DeadLetterEntry(
    val reason: String,
    val phase: String,
    val sourceMessageId: Long?,
    val referenceTime: String?,
    val transcript: String,
    val rawOutput: String,
)

/**
 * Append-only JSONL sink. Each failure is one line at [path]. Fail-soft: a sink that cannot write (bad
 * path, full disk, permissions) must NEVER break extraction — a dead-letter write failure is logged and
 * swallowed. Fields are capped at [maxFieldChars] head so the raw output (where a refusal/preamble shows up
 * first, and where a truncated JSON is obviously cut) is preserved without unbounded growth.
 */
class FileMemoryDeadLetter(
    private val path: Path,
    private val maxFieldChars: Int = 8_000,
) : MemoryDeadLetter {
    private val logger = LoggerFactory.getLogger(FileMemoryDeadLetter::class.java)
    private val json = Json { encodeDefaults = false }

    override fun record(entry: DeadLetterEntry) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            val obj = buildJsonObject {
                put("ts", Instant.now().toString())
                put("phase", entry.phase)
                put("reason", entry.reason)
                entry.sourceMessageId?.let { put("source_message_id", it) }
                entry.referenceTime?.let { put("reference_time", it) }
                put("transcript_len", entry.transcript.length)
                put("transcript", entry.transcript.take(maxFieldChars))
                put("raw_output_len", entry.rawOutput.length)
                put("raw_output", entry.rawOutput.take(maxFieldChars))
            }
            Files.writeString(
                path,
                json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure { logger.warn("lattice dead-letter write failed (swallowed): {}", it.message) }
    }
}
