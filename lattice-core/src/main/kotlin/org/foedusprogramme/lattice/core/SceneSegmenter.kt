package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.Transcript
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Splits a window of messages into SCENES — contiguous stretches about one topic or activity — with a model call
 * ([MemoryTask.SCENES]). The scene is the unit the scene pipeline extracts and narrates.
 *
 * Input:
 *  - the messages to segment, each rendered with its position and local time through [TranscriptRenderer.line] and
 *    cut to [maxBodyChars];
 *  - an optional read-only LOOKAHEAD — the messages right after the window, shown only so the model can judge whether
 *    the last scene has ended. Lookahead lines carry no position, and a position outside the window in the reply is a
 *    validation failure, so a lookahead message can never enter a scene (positions can have gaps: one inside the
 *    window's range that names no message snaps inward, see [validate]);
 *  - an optional still-OPEN scene (title, synopsis, last few messages) the window may continue.
 *
 * Output is validated strictly: the scenes must be in order, contiguous, and cover every window message exactly once.
 * Anything else throws [SceneSegmentationException] — a caller never gets a partial result. Only the model's semantic
 * judgement is tolerated loosely: a scene followed by another scene has ended by definition, and a last scene without
 * an `ended` flag is treated as not ended.
 */
class SceneSegmenter(
    private val model: MemoryModel,
    /** The zone each line's local time is shown in. */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Max to-segment messages per call; a larger window is a caller bug. */
    private val windowMessages: Int = DEFAULT_WINDOW_MESSAGES,
    /** Max lookahead messages shown; extra ones are dropped. */
    private val lookaheadMessages: Int = DEFAULT_LOOKAHEAD_MESSAGES,
    /** Max trailing messages of an open scene shown; older ones are dropped. */
    private val openTailMessages: Int = DEFAULT_OPEN_TAIL_MESSAGES,
    /** Per-message body cap (chars). */
    private val maxBodyChars: Int = DEFAULT_BODY_CHARS,
    /** Where an UNPARSEABLE or INVALID reply is kept (raw output and the rendered window) for diagnosis. */
    private val deadLetter: MemoryDeadLetter = MemoryDeadLetter.NoOp,
    /**
     * The assistant's names, so a title or synopsis can name the assistant instead of "assistant"/"AI". Empty (with
     * no [userName]) = the "Who is who" block is left out.
     */
    private val selfAliases: Set<String> = emptySet(),
    /** The human counterpart's name; null or blank = left out, nothing invented. */
    private val userName: String? = null,
) {

    /** A still-open scene the window may continue: shown to the model as context only. */
    data class OpenScene(
        val title: String?,
        val synopsis: String?,
        /** The scene's last messages (oldest first); only the last [openTailMessages] are shown. */
        val tail: List<ConversationMessage> = emptyList(),
    )

    /** One scene: an inclusive position range of the window. */
    data class Scene(
        val startId: Long,
        val endId: Long,
        val title: String?,
        val synopsis: String?,
        /** The scene finished before the lookahead (always true for every scene but the last). */
        val ended: Boolean,
    )

    data class Segmentation(
        /** In order, contiguous, covering every window message exactly once. */
        val scenes: List<Scene>,
        /** The first scene continues the [OpenScene] passed in (always false when none was passed). */
        val continuesOpenScene: Boolean,
    )

    /**
     * Segment [messages] (oldest first, non-empty, at most [windowMessages]). Throws [SceneSegmentationException] on an
     * unreachable model, an unparseable reply, or a reply that fails validation; a shutdown or an interrupt of the
     * model call surfaces as an [SceneSegmentationException.Kind.UNAVAILABLE] whose cause says so.
     */
    fun segment(
        messages: List<ConversationMessage>,
        lookahead: List<ConversationMessage> = emptyList(),
        openScene: OpenScene? = null,
    ): Segmentation {
        require(messages.isNotEmpty()) { "nothing to segment" }
        require(messages.size <= windowMessages) { "window of ${messages.size} messages exceeds $windowMessages" }
        val input = renderInput(messages, lookahead.take(lookaheadMessages), openScene)
        val raw = try {
            model.complete(MemoryTask.SCENES, SYSTEM_PROMPT, input)
        } catch (e: Exception) {
            throw SceneSegmentationException(
                SceneSegmentationException.Kind.UNAVAILABLE,
                "scene segmentation: model unavailable: ${e.message}",
                cause = e,
            )
        }
        try {
            return parse(raw, messages, openScene)
        } catch (e: SceneSegmentationException) {
            // The model answered but not usably: keep what it said next to what it was shown. Fail-soft sink.
            runCatching {
                deadLetter.record(
                    DeadLetterEntry(
                        reason = "${e.kind}: ${e.message}",
                        phase = DEAD_LETTER_PHASE,
                        sourceMessageId = messages.first().position,
                        referenceTime = null,
                        transcript = input,
                        rawOutput = raw,
                    ),
                )
            }
            throw e
        }
    }

    private fun parse(raw: String, messages: List<ConversationMessage>, openScene: OpenScene?): Segmentation {
        val root = NativeMemoryExtractor.parseLenientObject(raw)
            ?: throw SceneSegmentationException(
                SceneSegmentationException.Kind.UNPARSEABLE,
                "scene segmentation: model output was not parseable JSON",
                rawOutput = raw,
            )
        val scenesNode = root["scenes"] as? JsonArray
            ?: throw SceneSegmentationException(
                SceneSegmentationException.Kind.UNPARSEABLE,
                "scene segmentation: no \"scenes\" array in the model output",
                rawOutput = raw,
            )
        val scenes = validate(scenesNode, messages.map { it.position }, raw)
        val continues = openScene != null &&
            (bool(root["continues_open_scene"]) ?: bool(root["continuesOpenScene"]) ?: false)
        return Segmentation(scenes, continues)
    }

    /**
     * [ids] are the window's positions, ascending. Positions can have gaps (806 → 811), and the model sometimes names
     * one inside a gap: a `start_id` there snaps to the first window message after it, an `end_id` to the last one
     * before it — both inward, so a scene never takes a message the model left out. A position outside the window's
     * range stays INVALID. The seam slack below applies to the snapped positions.
     */
    private fun validate(node: JsonArray, ids: List<Long>, raw: String): List<Scene> {
        fun invalid(why: String): Nothing =
            throw SceneSegmentationException(SceneSegmentationException.Kind.INVALID, "scene segmentation: $why", rawOutput = raw)

        if (node.isEmpty()) invalid("no scenes")
        val position = ids.withIndex().associate { (i, id) -> id to i }
        val range = ids.first()..ids.last()
        fun startPosition(id: Long): Int? = position[id] ?: if (id in range) ids.indexOfFirst { it >= id } else null
        fun endPosition(id: Long): Int? = position[id] ?: if (id in range) ids.indexOfLast { it <= id } else null
        var expectedStart = 0
        val scenes = ArrayList<Scene>(node.size)
        node.forEachIndexed { i, element ->
            val obj = element as? JsonObject ?: invalid("scene #$i is not an object")
            val startId = long(obj["start_id"]) ?: invalid("scene #$i has no numeric start_id")
            val endId = long(obj["end_id"]) ?: invalid("scene #$i has no numeric end_id")
            var start = startPosition(startId) ?: invalid("scene #$i start_id $startId is outside the window")
            val end = endPosition(endId) ?: invalid("scene #$i end_id $endId is outside the window")
            if (end < start) invalid("scene #$i ends ($endId) before it starts ($startId)")
            // Models often slip a message or two at a seam. A small gap is folded into the previous scene (or, before
            // the first scene, into this one); a small overlap is clipped. Anything larger is still a wrong reading of
            // the window and stays INVALID.
            val drift = start - expectedStart
            when {
                drift == 0 -> Unit
                drift in 1..SEAM_SLACK && scenes.isEmpty() -> start = expectedStart
                drift in 1..SEAM_SLACK -> scenes[scenes.lastIndex] = scenes.last().copy(endId = ids[start - 1])
                drift in -SEAM_SLACK..-1 && end >= expectedStart -> start = expectedStart
                else -> invalid("scene #$i starts at $startId, expected ${ids[expectedStart]} (gap, overlap or out of order)")
            }
            expectedStart = end + 1
            val last = i == node.lastIndex
            scenes += Scene(
                startId = ids[start],
                endId = ids[end],
                title = string(obj["title"]),
                synopsis = string(obj["synopsis"]),
                ended = if (last) bool(obj["ended"]) ?: false else true,
            )
        }
        val missing = ids.size - expectedStart
        when {
            missing == 0 -> Unit
            missing in 1..SEAM_SLACK -> scenes[scenes.lastIndex] = scenes.last().copy(endId = ids.last())
            else -> invalid("scenes end at ${ids[expectedStart - 1]}, window ends at ${ids.last()}")
        }
        return scenes
    }

    private fun renderInput(
        messages: List<ConversationMessage>,
        lookahead: List<ConversationMessage>,
        openScene: OpenScene?,
    ): String = buildString {
        appendWhoIsWho()
        if (openScene != null) {
            append("## Open scene (context only; do not segment)\n")
            append("Title: ").append(openScene.title?.takeIf { it.isNotBlank() } ?: "(untitled)").append('\n')
            append("Synopsis: ").append(openScene.synopsis?.takeIf { it.isNotBlank() } ?: "(none)").append('\n')
            val tail = openScene.tail.takeLast(openTailMessages)
            if (tail.isNotEmpty()) {
                append("Last messages:\n")
                tail.forEach { append("- ").append(line(it)).append('\n') }
            }
            append('\n')
        }
        append("## To segment\n")
        messages.forEach { append("id=").append(it.position).append(' ').append(line(it)).append('\n') }
        if (lookahead.isNotEmpty()) {
            append("\n## Lookahead (context only; do not segment)\n")
            lookahead.forEach { append("- ").append(line(it)).append('\n') }
        }
    }

    /**
     * Who the assistant is and who the human counterpart is, in the input rather than the system prompt (which stays
     * byte-stable across deployments). Left out entirely when neither is configured — nothing invented.
     */
    private fun StringBuilder.appendWhoIsWho() {
        val aliases = selfAliases.map { it.trim() }.filter { it.isNotEmpty() }
        val name = userName?.trim()?.takeIf { it.isNotEmpty() }
        if (aliases.isEmpty() && name == null) return
        append("## Who is who\n")
        if (aliases.isNotEmpty()) append("The assistant, also addressed as ").append(aliases.joinToString(", ")).append('\n')
        if (name != null) append("The human counterpart: ").append(name).append('\n')
        append('\n')
    }

    private fun line(m: ConversationMessage): String =
        LOCAL_TIME.format(m.at.atZone(zone)) + " " + TranscriptRenderer.line(m.transcriptLine(), zone, maxBodyChars)

    companion object {
        const val DEFAULT_WINDOW_MESSAGES = 200
        const val DEFAULT_LOOKAHEAD_MESSAGES = 20
        const val DEFAULT_OPEN_TAIL_MESSAGES = 6
        const val DEFAULT_BODY_CHARS = 300

        /** [DeadLetterEntry.phase] of a segmentation reply that could not be used. */
        const val DEAD_LETTER_PHASE = "scene-segment"

        /** Largest seam slip (gap, overlap, missing edge) repaired instead of rejected, in messages. */
        const val SEAM_SLACK = 3

        private val LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        // English scaffolding on purpose (see NativeMemoryExtractor): the title/synopsis language follows the
        // conversation, not the instructions.
        internal val SYSTEM_PROMPT = """
            You split a chat log into SCENES for an episodic memory. A scene is a stretch of conversation about
            one continuous topic or activity — what a person would later remember as "the time we talked about X".
            Rules:
            - Segment by CONTENT, not by date or time gaps. A scene can span several days when the same topic is
              picked up again, and one day can hold several scenes.
            - Do not over-fragment. A short aside that returns to the same topic stays in its scene. A scene is
              typically 10–80 messages; make a shorter one only when the topic really changes.
            - Segment every line under "To segment" (the lines with an id). Scenes are in order and contiguous:
              the first starts at the first id, each next scene starts at the id right after the previous
              scene's end_id, and the last ends at the last id. No gaps, no overlaps.
            - Lines under "Lookahead" come AFTER the log and are context only. Never put them in a scene. Use
              them only to decide whether the LAST scene has ended: ended=true when the lookahead moves on to a
              different topic, false when it continues the same one. Without a lookahead, judge from the content
              (false when the last topic looks unfinished). Every scene but the last has ended.
            - If an "Open scene" is given, the conversation right before the log was in that scene. Set
              continues_open_scene=true when the first scene continues it (same topic), otherwise false.
            - title: a short noun phrase; synopsis: one or two sentences on what happened. Write both in the
              language the lines under "To segment" are written in — the language of the participants' own
              sentences. Technical terms, code, product names or words borrowed from another language inside
              them do not change it; keep such terms as they are. The Open scene's title and synopsis are
              earlier output and never decide the language.
            - Name the people involved, using the names under "Who is who" when given: never write "the user",
              "the assistant", "AI" or "the model", in any language, in a title or synopsis. Without a name, describe by role
              instead (e.g. "the trip", "a work problem") — never a generic person label.
            - Imagined situations: ONLY a shared imagined scene or world the participants deliberately
              step into together and carry on inside (an imagined situation opened on purpose, e.g. "close
              your eyes…", a shared make-believe world, "pretend we're at the seaside"): title and synopsis describe
              what happens there as a real event of the conversation, not as fiction. NEVER frame it as
              "role-play", "in the role-play" or "pretend", in any language.
              Mention the frame ONLY when the participants themselves talk about the frame (e.g. "step out of
              the role", "this isn't role-play") — that exchange is a real event too and may be
              titled/summarized as one.
              NOT such a scene: what-ifs, hypotheticals, wishes and plans about real life ("suppose I…", "if I…",
              "someday we'll…") are never written as having happened — title and summarize them as
              the hypotheticals, wishes or plans they are.
              A shared imagined world already established earlier — the "Open scene" (its title, synopsis or
              messages, opener included) shows it — counts as shared even without an opener in this log, but
              only when something visible establishes it. Both talking about places and what
              they would do there is not evidence on its own: a real trip plan ("next year we'll go to Kyoto and there we…")
              stays a plan. Idiomatic phrases such as "if I remember right"
              introduce real events, not hypotheticals.
            Output JSON only, in this shape — "language" first: the language the lines under "To segment" are
            written in, named in English (e.g. "English", "German", "Chinese"); every title and synopsis is
            then written in it:
            {"language": "…", "continues_open_scene": false, "scenes": [{"start_id": 101, "end_id": 142, "title": "…", "synopsis": "…", "ended": true}]}
        """.trimIndent()

        private fun primitive(e: JsonElement?): JsonPrimitive? = e as? JsonPrimitive

        private fun long(e: JsonElement?): Long? {
            val p = primitive(e) ?: return null
            return p.longOrNull ?: p.contentOrNull?.trim()?.toLongOrNull()
        }

        private fun bool(e: JsonElement?): Boolean? {
            val p = primitive(e) ?: return null
            return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
                "true", "yes" -> true
                "false", "no" -> false
                else -> null
            }
        }

        private fun string(e: JsonElement?): String? =
            primitive(e)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Scene segmentation failed; the caller gets no scenes at all. [kind] tells the failures apart:
 *  - [Kind.UNAVAILABLE]: the model could not be reached (outage, timeout, shutdown, interrupt — see the cause);
 *  - [Kind.UNPARSEABLE]: the model answered but not with the expected JSON;
 *  - [Kind.INVALID]: the JSON parsed but its ranges are out of the window, overlap, leave a gap, are out of order or
 *    do not cover the window.
 * [rawOutput] is the model's reply (empty for [Kind.UNAVAILABLE]), kept for diagnosis.
 */
class SceneSegmentationException(
    val kind: Kind,
    message: String,
    val rawOutput: String = "",
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    enum class Kind { UNAVAILABLE, UNPARSEABLE, INVALID }
}

/** A said message as a transcript line: what segmentation, extraction and narration show the models. */
internal fun ConversationMessage.transcriptLine(): Transcript.Line = Transcript.Line(
    role = if (role == ConversationMessage.Role.ASSISTANT) Transcript.Role.ASSISTANT else Transcript.Role.USER,
    text = text,
    at = at,
    messageId = position,
    speakerName = speakerName,
)
