package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The JSONL dead-letter sink: one line per failure, lengths and truncation, and never a throw. */
class MemoryDeadLetterTest {

    @Test
    fun theFileSinkAppendsOneLinePerFailureWithLengthsAndTruncation() {
        val path = Files.createTempDirectory("lattice-dl-").resolve("deadletter.jsonl")
        val sink = FileMemoryDeadLetter(path, maxFieldChars = 10)
        sink.record(DeadLetterEntry("poison", "capture", 42L, "2026-09-18T00:00:00Z", "T".repeat(30), "R".repeat(50)))
        sink.record(DeadLetterEntry("poison2", "capture", null, null, "short", "also short"))

        val lines = Files.readAllLines(path)
        assertEquals(2, lines.size)
        val first = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("capture", first["phase"]!!.jsonPrimitive.content)
        assertEquals(42, first["source_message_id"]!!.jsonPrimitive.int)
        assertEquals(30, first["transcript_len"]!!.jsonPrimitive.int, "the full length is recorded")
        assertEquals(10, first["transcript"]!!.jsonPrimitive.content.length, "the field is cut at maxFieldChars")
        assertEquals(50, first["raw_output_len"]!!.jsonPrimitive.int)
        assertEquals(10, first["raw_output"]!!.jsonPrimitive.content.length)
        val second = Json.parseToJsonElement(lines[1]).jsonObject
        assertNull(second["source_message_id"], "absent fields are omitted")
        assertNull(second["reference_time"])
    }

    @Test
    fun theFileSinkNeverThrows() {
        // The target is a directory, so the write fails: swallowed.
        val dir = Files.createTempDirectory("lattice-dl-")
        val sink = FileMemoryDeadLetter(Files.createDirectory(dir.resolve("occupied.jsonl")))
        sink.record(DeadLetterEntry("poison", "capture", 1L, null, "t", "r"))
    }
}
