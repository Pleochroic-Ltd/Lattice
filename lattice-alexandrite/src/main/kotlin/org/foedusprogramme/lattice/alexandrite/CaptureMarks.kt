package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * What capture knows of each conversation still open, kept in [file] across restarts: a summary written after a restart
 * must be judged by the turns before it. One line per mark, and one when a seal forgets the conversation; the file is
 * written again without the forgotten ones once they outnumber the rest. It keeps the latest [maxEntries]
 * conversations; the oldest, long sealed, are forgotten.
 */
internal class CaptureMarks(private val file: Path, private val maxEntries: Int = MAX_CONVERSATIONS) {
    enum class Mark {
        /** A turn that [MemoryAccess] admits was captured from it. */
        ADMITTED,

        /** Anyone but an operator had a turn in it, so its summaries are kept tainted. */
        FOREIGN,
    }

    @Serializable
    private class Line(val conversation: String, val mark: String? = null, val forget: Boolean = false)

    private val lock = Any()
    private var loaded: LinkedHashMap<String, MutableSet<Mark>>? = null
    private var lines = 0

    fun has(conversation: String, mark: Mark): Boolean = synchronized(lock) { marks()[conversation]?.contains(mark) == true }

    /** Marks [conversation] with [mark], saving it when it is new. */
    fun mark(conversation: String, mark: Mark): Unit = synchronized(lock) {
        val marks = marks()
        if (marks[conversation]?.contains(mark) == true) return
        marks.getOrPut(conversation) { mutableSetOf() } += mark
        append(Line(conversation, mark.name))
        if (marks.size > maxEntries) {
            val oldest = marks.keys.take(marks.size - maxEntries)
            oldest.forEach(marks::remove)
            rewrite(marks)
        }
    }

    /** Forgets [conversation], which a seal finished. */
    fun forget(conversation: String): Unit = synchronized(lock) {
        val marks = marks()
        if (marks.remove(conversation) == null) return
        append(Line(conversation, forget = true))
        if (lines > 2 * marks.values.sumOf { it.size } + SLACK) rewrite(marks)
    }

    private fun append(line: Line) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, Json.encodeToString(Line.serializer(), line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            lines++
        } catch (e: IOException) {
            logger.warn("Cannot save what capture knows of conversation {} to {}; it is lost at a restart", line.conversation, file, e)
        }
    }

    private fun rewrite(marks: Map<String, Set<Mark>>) {
        val all = marks.flatMap { (conversation, set) -> set.map { Line(conversation, it.name) } }
        try {
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, all.joinToString("") { Json.encodeToString(Line.serializer(), it) + "\n" })
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            lines = all.size
        } catch (e: IOException) {
            logger.warn("Cannot compact {}; it is tried again at the next seal", file, e)
        }
    }

    private fun marks(): LinkedHashMap<String, MutableSet<Mark>> = loaded ?: read().also { loaded = it }

    private fun read(): LinkedHashMap<String, MutableSet<Mark>> {
        val marks = LinkedHashMap<String, MutableSet<Mark>>()
        if (!file.exists()) return marks
        val text = try {
            file.readLines()
        } catch (e: IOException) {
            logger.warn("Cannot read {}, so capture knows no conversation from before the restart", file, e)
            return marks
        }
        for (raw in text) {
            if (raw.isBlank()) continue
            lines++
            val line = try {
                Json.decodeFromString(Line.serializer(), raw)
            } catch (e: SerializationException) {
                logger.warn("Skipping a line of {} that does not read as a conversation mark", file)
                continue
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping a line of {} that does not read as a conversation mark", file)
                continue
            }
            if (line.forget) {
                marks.remove(line.conversation)
                continue
            }
            val mark = Mark.entries.firstOrNull { it.name == line.mark } ?: continue
            marks.getOrPut(line.conversation) { mutableSetOf() } += mark
        }
        return marks
    }

    private companion object {
        const val MAX_CONVERSATIONS = 20_000
        const val SLACK = 64
    }
}

private val logger = LoggerFactory.getLogger(CaptureMarks::class.java)
