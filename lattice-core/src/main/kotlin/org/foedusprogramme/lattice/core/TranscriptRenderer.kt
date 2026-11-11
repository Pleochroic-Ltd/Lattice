package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.Transcript
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns a [Transcript] into the text [NativeMemoryExtractor] reads, plus what ingestion needs to know about it. One
 * line per message, `[role] <text with whitespace collapsed>`, oldest first:
 *
 *  - every message is read WHOLE by default (a cut-off assistant answer lost the figures at its end); the extractor
 *    splits a transcript too long for one call. Callers that show an excerpt pass [line]'s `maxBodyChars`;
 *  - a user line that names its speaker ([Transcript.Line.speakerName]) carries a channel wrapper, `## Message
 *    Context - Sender Display Name: … - Message Time: … ## User Message …`: the extraction prompt tells the model to
 *    attribute each memory to the Sender Display Name it finds there (the shape the prompt was tuned on);
 *  - an optional rolling `[summary]` and already-remembered `[context]` lines go before a `[new]` header, so the model
 *    extracts only from the new lines and uses the rest to resolve references.
 *
 * Collapsing whitespace keeps every message on one line, so no message text can open a `[new]` or `[context]` section
 * of its own.
 */
object TranscriptRenderer {
    /** Opens the lines to extract from, when a summary or context precedes them. */
    const val NEW_HEADER = "[new] The conversation to extract memories from:"

    /** Opens already-remembered lines that are there only to resolve references. */
    const val CONTEXT_HEADER =
        "[context] Surrounding conversation (may be before or after this excerpt), for reference and pronoun " +
            "resolution ONLY — do NOT extract new memories from it:"

    /** Opens the line before a continued extraction call (see [NativeMemoryExtractor.splitTranscript]). */
    const val CONTINUED_CONTEXT_HEADER =
        "[context] The conversation just before this part, for reference ONLY — do NOT extract new memories from it:"

    /**
     * Messages per extraction call when a caller chunks a long transcript ([chunks]): small enough that the JSON reply
     * stays well within an extractor's output-token ceiling (one request for hundreds of messages truncated
     * mid-structure and failed to parse — a total loss).
     */
    const val CHUNK_LINES = 12

    internal const val MESSAGE_CONTEXT_HEADER = "## Message Context"
    internal const val USER_MESSAGE_HEADER = "## User Message"
    private val WHITESPACE = Regex("\\s+")
    private val MESSAGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX '['VV']'")

    /**
     * A transcript as the extractor reads it.
     *
     * @property text the extractor's input ([NativeMemoryExtractor.extract]'s `transcript`); "" when no line has text.
     * @property sourceMessageId the newest message id of the extracted lines: the anchor recall drills back to.
     * @property saidFrom the earliest time of the extracted lines: when the memories were first said.
     * @property saidTo the latest time of the extracted lines.
     * @property referenceTime what "today" resolves against: [Transcript.referenceTime], else [saidTo]; null when no
     *   line has a time (the caller then uses its clock, as [Transcript.referenceTime] says).
     * @property tainted [Transcript.tainted], carried along.
     * @property namedLines the extracted user lines that name their speaker, as (name, text): who said a quote.
     */
    data class Rendered(
        val text: String,
        val sourceMessageId: Long?,
        val saidFrom: Instant?,
        val saidTo: Instant?,
        val referenceTime: Instant?,
        val tainted: Boolean,
        val namedLines: List<Pair<String, String>> = emptyList(),
    )

    /**
     * The whole [transcript] as one extractor input. [summary] (a rolling summary of what came before) and [context]
     * (lines already captured) ride along before a `[new]` header; only [transcript]'s lines give the metadata.
     * [zone] is the zone message times are shown in.
     */
    fun render(
        transcript: Transcript,
        zone: ZoneId,
        summary: String? = null,
        context: List<Transcript.Line> = emptyList(),
    ): Rendered = rendered(transcript.lines, transcript, zone, summary, context)

    /**
     * [transcript] in chunks of at most [size] lines, each its own extractor input with its own said span, anchor
     * and reference time (the chunk's latest time unless the transcript fixes one). [summary] rides every chunk.
     */
    fun chunks(transcript: Transcript, zone: ZoneId, size: Int = CHUNK_LINES, summary: String? = null): List<Rendered> {
        require(size > 0) { "chunk size must be positive" }
        return transcript.lines.chunked(size).map { rendered(it, transcript, zone, summary, emptyList()) }
    }

    /**
     * One message as a transcript line. [maxBodyChars] caps the message text only, so a long wrapper can never push
     * the speaker's actual words past it.
     */
    fun line(line: Transcript.Line, zone: ZoneId, maxBodyChars: Int = Int.MAX_VALUE): String {
        val body = line.text.trim().take(maxBodyChars)
        val speaker = line.speakerName?.trim()?.takeIf { it.isNotEmpty() }
        val content = if (line.role == Transcript.Role.USER && speaker != null) {
            buildString {
                append(MESSAGE_CONTEXT_HEADER).append('\n')
                append("- Sender Display Name: ").append(speaker).append('\n')
                line.at?.let { append("- Message Time: ").append(MESSAGE_TIME.format(it.atZone(zone))).append('\n') }
                append('\n').append(USER_MESSAGE_HEADER).append('\n').append(body)
            }
        } else {
            body
        }
        return "[${label(line.role)}] ${content.replace(WHITESPACE, " ").trim()}"
    }

    /** The role label the extraction prompt names: `user`, `assistant`. */
    fun label(role: Transcript.Role): String = role.name.lowercase()

    private fun rendered(
        lines: List<Transcript.Line>,
        transcript: Transcript,
        zone: ZoneId,
        summary: String?,
        context: List<Transcript.Line>,
    ): Rendered {
        val targets = lines.filter { it.text.isNotBlank() }
        val contextLines = context.filter { it.text.isNotBlank() }
        val cleanSummary = summary?.replace(WHITESPACE, " ")?.trim().orEmpty()
        val text = if (targets.isEmpty()) "" else buildString {
            // The scaffolding is English on purpose: the model takes its language as a cue, and Chinese scaffolding
            // once gave English and German conversations Chinese memories.
            if (cleanSummary.isNotEmpty()) append("[summary] ").append(cleanSummary).append('\n')
            if (contextLines.isNotEmpty()) {
                append(CONTEXT_HEADER).append('\n')
                contextLines.joinTo(this, "\n") { line(it, zone) }
                append('\n')
            }
            // Mark the targets as [new] whenever a context-only preamble precedes them, so the model never re-mines
            // the summary or the context.
            if (cleanSummary.isNotEmpty() || contextLines.isNotEmpty()) append(NEW_HEADER).append('\n')
            targets.joinTo(this, "\n") { line(it, zone) }
        }
        val times = lines.mapNotNull { it.at }
        val saidTo = times.maxOrNull()
        return Rendered(
            text = text,
            sourceMessageId = lines.mapNotNull { it.messageId }.maxOrNull(),
            saidFrom = times.minOrNull(),
            saidTo = saidTo,
            referenceTime = transcript.referenceTime ?: saidTo,
            tainted = transcript.tainted,
            namedLines = targets.mapNotNull { l ->
                l.speakerName?.trim()?.takeIf { it.isNotEmpty() && l.role == Transcript.Role.USER }?.let { it to l.text }
            },
        )
    }
}
