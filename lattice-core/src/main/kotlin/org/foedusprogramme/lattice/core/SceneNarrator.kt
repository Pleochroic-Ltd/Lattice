package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Writes a scene hub's narration and the scene's thread-ledger changes in ONE model call ([MemoryTask.NARRATE]).
 *
 * Input: the scene's title and synopsis, its member memories numbered `[1]..[n]`, a truncated transcript (rendered
 * through [TranscriptRenderer.line] by the caller, so connecting phrases like "this is the surprise I promised" are
 * visible), the local date range, and the chat's candidate open threads.
 *
 * Two faithfulness modes ([org.foedusprogramme.lattice.SceneSettings.narrationCheck]), both kept long-term:
 *  - [Mode.ANCHORS]: one call; the faithfulness guard is the previous / next scene titles, shown with an
 *    explicit instruction that they only mark the boundaries and none of their facts may be written into this
 *    scene;
 *  - [Mode.SELF_CHECK]: after the narration a second call checks it against the member memories and the same
 *    transcript excerpt the narration may draw on, and a veto (or an unreadable verdict) fails the narration.
 * Both modes (and the self-check) are shown the neighbour titles: the imagined-world rule cites them as visible
 * evidence of an established shared imagined world.
 *
 * Voice: the narration is the assistant's own memory, in the first person; the assistant's persona names
 * ([selfAliases]) and the human's name ([userName]) ride in the input's "Who is who" block, so the system
 * prompts stay constant. No dates in the text (the scene line shows the range): a leading date clause the
 * model writes anyway is stripped before the gates ([stripLeadingDate]).
 *
 * Gates: the narration must be within [EpisodeNarrator.withinNarrationBand] and not a verbatim copy of
 * any member. Any gate miss, an unparseable reply or a model failure is a [Result.Failure]; the caller
 * counts it. A gate miss or a self-check veto on a parseable reply still carries the reply's ledger changes
 * ([Result.Failure.ledger]) — the threads the scene set up or settled are true whatever the narration's wording. The ledger changes are parsed leniently — a malformed ledger never fails a good narration, it
 * only yields fewer changes. Memory refs are mapped back to engram ids; unknown refs and thread ids that
 * were not among the candidates are dropped, an opened / advanced thread keeps at most [MAX_THREAD_REFS]
 * refs, and a close without a known resolve ref is dropped. An opened thread's short `name` (what recall
 * annotations call it) is optional: a missing, blank or over-long one is null and the description stands in.
 */
class SceneNarrator(
    private val model: MemoryModel,
    val mode: Mode = Mode.ANCHORS,
    /** Cap on the transcript excerpt shown to the model (chars); the middle is elided past it. */
    private val maxTranscriptChars: Int = DEFAULT_TRANSCRIPT_CHARS,
    /**
     * The assistant's names (e.g. Aria / 云舒 / 小舒): the transcript addresses the assistant by them, and the
     * narration — the assistant's own episodic memory — must turn them into the first person, as extraction does.
     */
    private val selfAliases: Set<String> = emptySet(),
    /** The human counterpart's name; the narration names them with it. */
    private val userName: String? = null,
) {
    enum class Mode {
        ANCHORS,
        SELF_CHECK,
        ;

        companion object {
            /** [org.foedusprogramme.lattice.SceneSettings.narrationCheck] → mode; anything but "selfCheck" is [ANCHORS]. */
            fun fromSetting(value: String?): Mode = if (value.equals("selfCheck", ignoreCase = true)) SELF_CHECK else ANCHORS
        }
    }

    /** One member memory as shown to the model: `[ref] content`. */
    data class Member(val ref: Int, val engramId: Long, val content: String)

    /** One open thread the ledger may advance, close or drop in this scene; shown as `id=<id> <name> — <description>`. */
    data class CandidateThread(val id: Long, val description: String, val name: String? = null)

    data class Input(
        val title: String?,
        val synopsis: String?,
        val members: List<Member>,
        /** Transcript lines, oldest first (already rendered and per-line capped). */
        val transcript: List<String> = emptyList(),
        val dateFrom: String? = null,
        val dateTo: String? = null,
        /** The scene is still OPEN (an over-long scene cut mid-way): the narration covers it so far. */
        val inProgress: Boolean = false,
        val candidateThreads: List<CandidateThread> = emptyList(),
        /** Shown in both modes, as boundaries only (see the class doc). */
        val previousTitle: String? = null,
        val nextTitle: String? = null,
        /**
         * The people of a scene the assistant takes no part in ([observedSpeakers]): the narration is then an
         * observer's, everyone in the third person by name. Empty = the assistant's own memory, in the first person.
         */
        val observed: List<String> = emptyList(),
    )

    /** A new thread set up in this scene, with the engram ids of its set-up memories and its short name (null = none). */
    data class OpenedThread(val description: String, val setupEngramIds: List<Long>, val name: String? = null)

    /** A candidate thread picked up again, with the engram ids of this scene's re-mention memories. */
    data class AdvancedThread(val threadId: Long, val engramIds: List<Long>)

    /** A candidate thread resolved here, with the engram ids of the memories that resolve it (may be empty). */
    data class ClosedThread(val threadId: Long, val resolveEngramIds: List<Long>)

    /** The scene's thread-ledger changes ([SceneThreadLedger] applies them). */
    data class LedgerChanges(
        val opened: List<OpenedThread> = emptyList(),
        val advanced: List<AdvancedThread> = emptyList(),
        val closed: List<ClosedThread> = emptyList(),
        val dropped: List<Long> = emptyList(),
    ) {
        val isEmpty: Boolean get() = opened.isEmpty() && advanced.isEmpty() && closed.isEmpty() && dropped.isEmpty()

        companion object {
            val NONE = LedgerChanges()
        }
    }

    sealed interface Result {
        data class Success(val narration: String, val ledger: LedgerChanges) : Result

        /**
         * [ledger] is the reply's ledger changes when the reply was parseable JSON (the narration itself failed a
         * gate, the self-check or — later — the graph gate); null when the call failed or the reply was
         * unreadable. The caller applies it only when it gives the scene up: a failed narration must not lose the
         * thread changes of the same call.
         */
        data class Failure(val reason: Reason, val detail: String, val ledger: LedgerChanges? = null) : Result
    }

    /** [SHUTDOWN]: the narration or self-check call was cut off by the shutdown — never counted as an attempt. */
    enum class Reason { NO_MEMBERS, UNAVAILABLE, UNPARSEABLE, BLANK, TOO_LONG, VERBATIM_COPY, SELF_CHECK_VETO, SHUTDOWN }

    fun narrate(input: Input): Result {
        if (input.members.isEmpty()) return Result.Failure(Reason.NO_MEMBERS, "scene has no member memories")
        val raw = try {
            model.complete(MemoryTask.NARRATE, SYSTEM_PROMPT, renderInput(input))
        } catch (e: Exception) {
            if (e.isMemoryModelShutdown() || e.isMemoryModelInterrupted()) {
                return Result.Failure(Reason.SHUTDOWN, "narration cut off by the shutdown")
            }
            return Result.Failure(Reason.UNAVAILABLE, "narrator unavailable: ${e.message}")
        }
        val root = NativeMemoryExtractor.parseLenientObject(raw)
            ?: return Result.Failure(Reason.UNPARSEABLE, "narration reply was not parseable JSON")
        // The scene line already shows the date range: a leading date clause the model wrote anyway is cut
        // here rather than failed (a retry would cost a call for a cosmetic miss).
        val narration = string(root["narration"])?.let(::clean)?.let(::stripLeadingDate).orEmpty()
        val ledger = parseLedger(root, input)
        gate(narration, input.members)?.let { return it.copy(ledger = ledger) }
        if (mode == Mode.SELF_CHECK) {
            selfCheck(narration, input)?.let { return it.copy(ledger = ledger) }
        }
        return Result.Success(narration, ledger)
    }

    private fun gate(narration: String, members: List<Member>): Result.Failure? = when {
        narration.length < MIN_NARRATION -> Result.Failure(Reason.BLANK, "narration blank or too short")
        !EpisodeNarrator.withinNarrationBand(narration) ->
            Result.Failure(
                Reason.TOO_LONG,
                "narration of size ${EpisodeNarrator.narrationSize(narration)} (${narration.length} chars) exceeds ${EpisodeNarrator.EPISODE_NARRATION_MAX}",
            )
        members.any { it.content.trim() == narration } -> Result.Failure(Reason.VERBATIM_COPY, "narration copies a member verbatim")
        else -> null
    }

    /** Mode B: a second call checks the narration against the member memories and transcript excerpt. Null = passed. */
    private fun selfCheck(narration: String, input: Input): Result.Failure? {
        val user = buildString {
            // The narration is told to anchor to the scene's dates and title, which are not in the memories:
            // show them as given facts, or every dated narration is vetoed.
            append("## Scene\n")
            append("Title: ").append(input.title?.takeIf { it.isNotBlank() } ?: "(untitled)").append('\n')
            // The imagined-world rule cites the synopsis and the neighbour titles as evidence.
            input.synopsis?.takeIf { it.isNotBlank() }?.let { append("Synopsis: ").append(it).append('\n') }
            dateRange(input)?.let { append("Dates: ").append(it).append('\n') }
            appendNeighbours(input)
            append('\n')
            // The narration speaks as the assistant ("我"), or as an observer: the checker must know which.
            appendWhoIsWho(input.observed)
            append('\n')
            append("## Memories\n")
            input.members.forEach { append('[').append(it.ref).append("] ").append(it.content.trim()).append('\n') }
            // The narration may use the transcript as well as the memories (SYSTEM_PROMPT): check it against the
            // same excerpt, or a transcript-grounded detail is vetoed as invented (once 5 of 14 scenes were abandoned).
            val transcript = excerpt(input.transcript)
            if (transcript.isNotEmpty()) {
                append("\n## Transcript excerpt\n")
                transcript.forEach { append(it).append('\n') }
            }
            append("\n## Narration\n").append(narration).append('\n')
        }
        val raw = try {
            model.complete(MemoryTask.NARRATE, SELF_CHECK_PROMPT, user)
        } catch (e: Exception) {
            if (e.isMemoryModelShutdown() || e.isMemoryModelInterrupted()) {
                return Result.Failure(Reason.SHUTDOWN, "self-check cut off by the shutdown")
            }
            // The checker could not be reached: an outage, not a verdict.
            return Result.Failure(Reason.UNAVAILABLE, "self-check unavailable: ${e.message}")
        }
        val root = NativeMemoryExtractor.parseLenientObject(raw)
            ?: return Result.Failure(Reason.SELF_CHECK_VETO, "self-check reply was not parseable JSON")
        val faithful = bool(root["faithful"]) ?: return Result.Failure(Reason.SELF_CHECK_VETO, "self-check gave no verdict")
        return if (faithful) null else Result.Failure(Reason.SELF_CHECK_VETO, "self-check veto: ${string(root["issue"]).orEmpty()}")
    }

    private fun parseLedger(root: JsonObject, input: Input): LedgerChanges {
        val ledger = root["threads"] as? JsonObject ?: root
        val engramByRef = input.members.associate { it.ref to it.engramId }
        val candidateIds = input.candidateThreads.mapTo(HashSet()) { it.id }

        fun refs(e: JsonElement?): List<Long> =
            (e as? JsonArray).orEmpty().mapNotNull { ref(it)?.let(engramByRef::get) }.distinct()

        fun threadId(obj: JsonObject): Long? =
            (ref(obj["id"]) ?: ref(obj["thread_id"]) ?: ref(obj["threadId"]))?.toLong()?.takeIf { it in candidateIds }

        fun objects(key: String): List<JsonObject> = (ledger[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        // At most MAX_THREAD_REFS memories per opened / advanced thread (a catch-all thread once collected 27).
        val opened = objects("opened").mapNotNull { o ->
            val description = string(o["description"]) ?: return@mapNotNull null
            val setup = refs(o["setup"] ?: o["refs"]).take(MAX_THREAD_REFS)
            if (setup.isEmpty()) null else OpenedThread(description, setup, threadName(o["name"]))
        }
        val advanced = objects("advanced").mapNotNull { o ->
            val id = threadId(o) ?: return@mapNotNull null
            val ids = refs(o["refs"] ?: o["mentions"]).take(MAX_THREAD_REFS)
            if (ids.isEmpty()) null else AdvancedThread(id, ids)
        }
        // A close must name the memories that resolve it; one without (or with only unknown refs) is dropped.
        val closed = objects("closed").mapNotNull { o ->
            val id = threadId(o) ?: return@mapNotNull null
            val resolve = refs(o["resolve"] ?: o["refs"])
            if (resolve.isEmpty()) null else ClosedThread(id, resolve)
        }
        val dropped = (ledger["dropped"] as? JsonArray).orEmpty().mapNotNull { e ->
            val id = (e as? JsonObject)?.let(::threadId) ?: ref(e)?.toLong()?.takeIf { it in candidateIds }
            id
        }.distinct()
        // A thread closed here is not also advanced or dropped in the same scene.
        val closedIds = closed.mapTo(HashSet()) { it.threadId }
        return LedgerChanges(
            opened = opened,
            advanced = advanced.filter { it.threadId !in closedIds },
            closed = closed.distinctBy { it.threadId },
            dropped = dropped.filter { it !in closedIds },
        )
    }

    private fun renderInput(input: Input): String = buildString {
        append("## Scene\n")
        append("Title: ").append(input.title?.takeIf { it.isNotBlank() } ?: "(untitled)").append('\n')
        append("Synopsis: ").append(input.synopsis?.takeIf { it.isNotBlank() } ?: "(none)").append('\n')
        val dates = dateRange(input)
        if (dates != null) append("Dates: ").append(dates).append('\n')
        if (input.inProgress) append("Status: still in progress — narrate what has happened so far.\n")
        appendNeighbours(input)
        append('\n')
        appendWhoIsWho(input.observed)
        append("\n## Memories of this scene\n")
        input.members.forEach { append('[').append(it.ref).append("] ").append(it.content.trim()).append('\n') }
        if (input.candidateThreads.isNotEmpty()) {
            append("\n## Open threads of this chat\n")
            input.candidateThreads.forEach { t ->
                val description = t.description.trim()
                val name = t.name?.trim()?.takeIf { it.isNotEmpty() && it != description }
                append("id=").append(t.id).append(' ')
                if (name != null) append(name).append(" — ")
                append(description).append('\n')
            }
        }
        val transcript = excerpt(input.transcript)
        if (transcript.isNotEmpty()) {
            append("\n## Transcript excerpt (context only)\n")
            transcript.forEach { append(it).append('\n') }
        }
    }

    /**
     * The neighbouring scenes' titles, in both modes (the imagined-world rule cites them as evidence of an
     * established world, so the narrator and the self-check must both see them) — as boundaries only, never as a
     * source of this scene's facts.
     */
    private fun StringBuilder.appendNeighbours(input: Input) {
        if (input.previousTitle == null && input.nextTitle == null) return
        append("\n## Neighbouring scenes (boundaries only)\n")
        append("Previous scene: ").append(input.previousTitle ?: "(none)").append('\n')
        append("Next scene: ").append(input.nextTitle ?: "(none)").append('\n')
        append(
            "These neighbour titles only mark where this scene begins and ends (and may show an established " +
                "shared imagined world). Never write their facts, places or events into this scene's narration.\n",
        )
    }

    /**
     * Who "我" is and who the human counterpart is, in the input rather than the system prompt (which stays
     * byte-stable across deployments). Names only when configured; nothing is invented.
     */
    private fun StringBuilder.appendWhoIsWho(observed: List<String>) {
        append("## Who is who\n")
        if (observed.isNotEmpty()) {
            // Two (or more) named people talking, no assistant line: there is no "I" in this scene to speak as.
            append("You take no part in this chat: it is between ").append(observedList(observed))
                .append(" (each line names its speaker). Write about each of them in the third person, by name.\n")
            return
        }
        append("You — the assistant, written in the first person")
        val aliases = selfAliases.map { it.trim() }.filter { it.isNotEmpty() }
        if (aliases.isNotEmpty()) append("; also addressed as ").append(aliases.joinToString(", ")).append(" (all of these are you)")
        append('\n')
        append("The human counterpart: ")
        append(userName?.trim()?.takeIf { it.isNotEmpty() } ?: "as named in the transcript")
        append('\n')
    }

    private fun dateRange(input: Input): String? = when {
        input.dateFrom == null -> null
        input.dateTo == null || input.dateTo == input.dateFrom -> input.dateFrom
        else -> "${input.dateFrom} – ${input.dateTo}"
    }

    /** Keep the head and the tail of the transcript within [maxTranscriptChars], eliding the middle. */
    private fun excerpt(lines: List<String>): List<String> {
        if (lines.sumOf { it.length + 1 } <= maxTranscriptChars) return lines
        val half = maxTranscriptChars / 2
        val head = ArrayList<String>()
        var used = 0
        for (l in lines) {
            if (used + l.length + 1 > half) break
            head += l
            used += l.length + 1
        }
        val tail = ArrayDeque<String>()
        used = 0
        for (l in lines.asReversed().take(lines.size - head.size)) {
            if (used + l.length + 1 > half) break
            tail.addFirst(l)
            used += l.length + 1
        }
        return head + listOf("…") + tail
    }

    companion object {
        const val MIN_NARRATION = 4

        /** Max memory refs per opened or advanced thread in one scene. */
        const val MAX_THREAD_REFS = 6
        const val DEFAULT_TRANSCRIPT_CHARS = 12_000

        // English scaffolding on purpose (see NativeMemoryExtractor): the narration's language follows the
        // conversation, not the instructions.
        internal val SYSTEM_PROMPT = """
            You write the episodic summary of ONE scene of a chat — a stretch of conversation about one topic —
            and keep a ledger of story threads (set-ups awaiting a pay-off).
            Narration rules:
            - One or two sentences on what happened in this scene. Use only the scene's memories and transcript;
              never invent facts.
            - The narration is YOUR own memory of the scene: you are the assistant of this chat (see "Who is
              who"). Write about yourself in the first person throughout — never as "the assistant", "AI"
              or "the model", in any language, and never by the names listed as yours in "Who is who" (those
              names are you; only keep one when the scene is about the name itself). Name the human counterpart
              by the name given there. Keep one voice from start to end.
              When "Who is who" says you take no part in the chat (other people talking), narrate as an observer
              instead: each person in the third person, by name, never "I" or "we" for any of them.
            - When the participants step into together a shared imagined scene or world — an imagined situation
              opened on purpose (e.g. "close your eyes…", a shared make-believe world, "pretend we're at the
              seaside") — and carry on inside it, narrate what happens there directly as
              lived first-person experience.
              Never frame it as "role-play", "in the role-play" or "pretend", in any language;
              mention the frame ONLY when the conversation itself talks about the frame (e.g. "step out of
              the role", "this isn't role-play") — that is a real event of the scene.
              This is only for such a shared imagined scene: what-ifs, hypotheticals, wishes and plans about
              real life ("suppose I…", "if I…", "someday we'll…") are never written as having happened —
              narrate them as the hypotheticals, wishes or plans they are.
              A shared imagined world already established earlier — the scene's title or synopsis, a
              neighbouring scene's title or an opener in the transcript names it — counts as shared
              even without an opener in this scene's transcript, but only when something visible establishes it.
              Both talking about places and what they would do there is not evidence on its own: a real trip
              plan ("next year we'll go to Kyoto and there we…") stays a plan.
              Idiomatic phrases such as "if I remember right" introduce real events, not
              hypotheticals.
            - Do not write dates, times or weekdays in the narration: the scene's dates are stored with it.
            - What the person tells you about their own life is their account: never narrate it as false, made up or
              doubtful, even when you doubted it in the conversation — leave your doubts out.
            - Write in the language the transcript excerpt is written in — the language of the participants'
              own sentences (without an excerpt, the language most of the memories use). Technical terms,
              code, product names or words borrowed from another language inside them do not change it; keep
              such terms as they are. The title, synopsis, neighbouring titles and memories are earlier output
              and never decide the language. Do not copy a memory verbatim.
            What a thread is:
            - A thread is ONE concrete, specific promise, plan, surprise, unanswered question or unresolved suspense
              that has an expected concrete outcome later — e.g. "a surprise promised for the weekend", "the promised
              winter ski trip", "the question he said he would answer tomorrow".
            - Never a discussion topic, theme, mood or relationship state ("work", "their growing trust",
              "talking about movies" are NOT threads). When in doubt, open nothing.
            - Keep a description short and specific: name the promise or question itself, in a few words.
            - Give every opened thread a name as well: noun-first, naming the promise or suspense itself —
              at most 12 characters in Chinese or Japanese (in other languages a few words, at most 20
              characters), e.g. "the weekend gift", "the winter ski trip". The name is what later
              recalls call the thread; the description may say more.
            Thread rules (refer to memories by their [number], to open threads by their id):
            - opened: a new thread set up in this scene — its name, a short description and the memories that
              set it up.
            - advanced: an open thread mentioned again here without being resolved — its id and the memories
              that mention it.
            - At most $MAX_THREAD_REFS memories per opened or advanced thread: the ones that state the promise
              or question most clearly.
            - closed: an open thread resolved in this scene — its id and the memories that resolve it.
              Never close a thread without the memories that resolve it.
            - dropped: ids of open threads this scene makes clear will never be followed up.
            - Leave a list empty when nothing applies. Only use the thread ids listed under "Open threads".
            Output JSON only, in this shape — "language" first: the language the transcript excerpt is written
            in, named in English (e.g. "English", "German", "Chinese"); the narration and every thread name
            and description are then written in it:
            {"language": "…", "narration": "…", "threads": {"opened": [{"name": "…", "description": "…", "setup": [1]}], "advanced": [{"id": 12, "refs": [2]}], "closed": [{"id": 7, "resolve": [3]}], "dropped": [9]}}
        """.trimIndent()

        internal val SELF_CHECK_PROMPT = """
            Check a scene narration against the scene's memories and its transcript excerpt. The narration is
            faithful when every fact in it is supported by the memories or the transcript excerpt — nothing
            added from elsewhere, no wrong place, person or outcome, nobody's words or acts given to someone else.
            The narration is the assistant's own memory, written in the first person: "I" is the
            assistant, who is also addressed by the names under "Who is who". Narrating the assistant's words or
            acts in the first person is correct attribution, not a reason to veto. When "Who is who" says the
            assistant takes no part in the chat, the narration is an observer's, every person in the third person
            by name: that is correct, and an "I" or "we" standing for one of them is a reason to veto.
            When the participants stepped together into a shared imagined scene or world (an imagined situation
            opened on purpose: "close your eyes…", a shared make-believe world, "pretend we're at the seaside"), telling
            what happened inside it as lived experience, without calling it imagined or role-play, is the
            required style — never a reason to veto. That covers only such a shared imagined scene: a real-life
            what-if, wish or plan ("suppose I…", "someday we'll…") narrated as if it had actually happened is
            unsupported, and that IS a reason to veto when the memories or the transcript excerpt show it as a
            real-life what-if, wish or plan (the excerpt may leave out the middle of a long scene; the memories
            cover all of it). A shared imagined world already established earlier (the scene's title or
            synopsis, a neighbouring scene's title or an opener in the memories or excerpt names it) counts as
            shared even without an opener in this excerpt, but only when something visible establishes it.
            Both talking about places and what they would do there is not evidence on its own: a real trip plan
            ("next year we'll go to Kyoto and there we…") stays a plan. Idiomatic phrases such as
            "if I remember right" introduce real events, not hypotheticals.
            The scene's title and dates are given facts: a time reference consistent with them is supported, and
            the narration need not mention the dates.
            Wording and level of detail may differ.
            Output JSON only: {"faithful": true, "issue": ""} or {"faithful": false, "issue": "what is unsupported"}
        """.trimIndent()

        private const val TIME_WORD = "(?:凌晨|清晨|早晨|早上|上午|中午|午后|下午|傍晚|黄昏|晚上|夜里|夜间|深夜|半夜|夜晚|白天|夜|晨|晚|早)"
        private const val ONE_DATE =
            "(?:\\d{4}-\\d{1,2}(?:-\\d{1,2})?" +
                "|(?:\\d{4}\\s*年\\s*)?\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*[日号])?" +
                "|\\d{1,2}\\s*[日号])" +
                "(?:\\s*[（(]?(?:周|星期)[一二三四五六日天][)）]?)?" +
                "(?:\\s*$TIME_WORD)?(?:\\s*\\d{1,2}[:：]\\d{2})?"

        /**
         * A leading date clause — one date or a range, optional time of day, then a separator: "2026-08-02，",
         * "2026年7月29日深夜至30日,", "On 2026-08-02, ". A date not followed by a separator ("7月的旅行") is
         * part of the sentence and stays.
         */
        private val LEADING_DATE = Regex(
            "^(?:(?:on|in|from)\\s+)?$ONE_DATE(?:\\s*(?:至|到|~|～|-|–|—)\\s*$ONE_DATE)?\\s*(?:[，,、:：;；]|\\s)\\s*",
            RegexOption.IGNORE_CASE,
        )

        /** Cut one leading date clause (see [LEADING_DATE]); the rest is returned trimmed. */
        internal fun stripLeadingDate(narration: String): String {
            val m = LEADING_DATE.find(narration) ?: return narration
            return narration.substring(m.range.last + 1).trim()
        }

        /**
         * The people of a stretch of chat the assistant takes no part in: the distinct speaker names of its said
         * messages when none is the assistant's and at least two people are named (LoCoMo's two friends, a group chat
         * the assistant only listens to); else empty, and the narration is the assistant's own, as always.
         */
        fun observedSpeakers(messages: List<org.foedusprogramme.lattice.ConversationMessage>): List<String> {
            val said = messages.filter { it.said }
            if (said.isEmpty() || said.any { it.role == org.foedusprogramme.lattice.ConversationMessage.Role.ASSISTANT }) return emptyList()
            val names = said.mapNotNull { m -> m.speakerName?.trim()?.takeIf { it.isNotEmpty() } }.distinct()
            return if (names.size >= 2) names else emptyList()
        }

        /** "A and B", "A, B and C". */
        internal fun observedList(names: List<String>): String =
            if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

        /** Max whitespace-separated words in a thread name (non-CJK languages). */
        const val MAX_THREAD_NAME_WORDS = 6

        /**
         * An opened thread's short name, or null when missing, blank or over-long (more than
         * [RecallFormatter.THREAD_NAME_CHARS] chars or [MAX_THREAD_NAME_WORDS] words) — the description is
         * then used in its place.
         */
        private fun threadName(e: JsonElement?): String? {
            val name = string(e)?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
            if (name.length > RecallFormatter.THREAD_NAME_CHARS) return null
            if (name.split(' ').size > MAX_THREAD_NAME_WORDS) return null
            return name
        }

        /** Strip quotes / list decoration and collapse whitespace. */
        private fun clean(s: String): String = s.trim().trim('"', '「', '」', '*', ' ').replace(Regex("\\s+"), " ").trim()

        private fun string(e: JsonElement?): String? =
            (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun bool(e: JsonElement?): Boolean? {
            val p = e as? JsonPrimitive ?: return null
            return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
                "true", "yes" -> true
                "false", "no" -> false
                else -> null
            }
        }

        /** A memory ref / thread id: `3`, `"3"`, `"#3"`, `"[3]"`, `"m3"`, `"T3"`. */
        private fun ref(e: JsonElement?): Int? {
            val p = e as? JsonPrimitive ?: return null
            return p.contentOrNull?.trim()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        }
    }
}
