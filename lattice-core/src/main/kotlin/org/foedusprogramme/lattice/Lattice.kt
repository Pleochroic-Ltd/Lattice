package org.foedusprogramme.lattice

import org.foedusprogramme.lattice.core.ChatTimeline
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.Reinforce
import java.time.Instant

/**
 * One memory graph and the work around it: recall, capture, explicit saves and the nightly sleep. Its calls
 * suspend rather than block.
 *
 * All graph access is serialized on one thread the instance owns; model and embedder calls run on its capture and
 * narration threads, never on the caller's. A call made after [close] throws [IllegalStateException].
 */
interface Lattice {
    /** The graph's name, e.g. the agent it belongs to. */
    val name: String

    /**
     * Recalls what [cue] brings back and formats it as one block for a prompt. An empty [Recall.text] means nothing
     * passed the emit gate. Throws [GraphBusyException] when the graph stays busy past
     * [LatticeSettings.graphReadTimeout].
     */
    suspend fun recall(cue: String, options: RecallOptions = RecallOptions()): Recall

    /** Writes one memory as given (a `remember` tool call) and returns its id. */
    suspend fun remember(content: String, options: RememberOptions = RememberOptions()): Long

    /**
     * Extracts memories from [transcript] and encodes them; returns how many engrams were written or reinforced.
     * Throws when the model can't extract (after recording a dead letter for an unparseable answer).
     *
     * With scenes on (a [LatticeDependencies.conversations] source, [SceneSettings.enabled] and a model), a transcript
     * whose [Transcript.Source.conversationId] is set is not extracted here: the call tells the scene pipeline that the
     * conversation has new messages and returns 0 at once. The pipeline reads the messages back from the source, cuts
     * them into scenes and extracts each scene in the background, so [transcript]'s lines are ignored. A transcript
     * without a conversation id is extracted as above.
     */
    suspend fun capture(transcript: Transcript): Int

    /**
     * The host sealed conversation [conversationId] (it takes no more messages): the scene pipeline segments and
     * extracts what is left of it, ends its last scene and narrates it, in the background. Returns at once; a no-op
     * without scenes.
     */
    suspend fun conversationSealed(conversationId: String)

    /**
     * Saves a whole conversation's summary as an episode node; null when episode nodes are off or it was a duplicate.
     * [tainted]: the summary was written over a conversation that read untrusted content, so it is kept tainted.
     */
    suspend fun saveEpisode(summary: String, source: Transcript.Source? = null, tainted: Boolean = false): Long?

    /**
     * Runs the consolidation passes in [phases], in [SleepPhase] order, each fail-soft (a failed phase is reported and
     * the next one runs). One sleep at a time per graph: a call made while another sleep runs returns at once with
     * every requested phase skipped. The report lists the requested phases only.
     */
    suspend fun sleep(phases: Set<SleepPhase> = SleepPhase.entries.toSet()): SleepReport

    suspend fun stats(): LatticeStats

    /**
     * The period catalogue last published for chat [chatKey] in [language] (null = [LatticeSettings.language]): its eras
     * and periods in a few lines, for a prompt. Null when the chat has none yet, or when the period layer doesn't run
     * (scenes off, no conversation source or no model). An in-memory read; it never waits for the graph. A period's
     * name shows only when the scenes it was named from are trusted ([SceneTrustPolicy]), else its dates.
     */
    suspend fun catalogue(chatKey: String, language: Language? = null): String?

    /**
     * Chat [chatKey]'s eras, periods and scenes, each in chat order; null when the period layer doesn't run (as
     * [catalogue]). Waits for the graph at most [LatticeSettings.graphReadTimeout], then throws [GraphBusyException].
     */
    suspend fun timeline(chatKey: String): ChatTimeline?

    /** Runs [block] on the graph thread with the live graph; for tools and inspection that need more than the above. */
    suspend fun <T> withGraph(block: (LatticeGraph) -> T): T

    /** Finishes queued capture and narration work (up to the settings' shutdown timeout), then closes the store. */
    suspend fun close()
}

/** The graph thread stayed busy past the read timeout; the live path should go on without memory. */
class GraphBusyException(message: String) : RuntimeException(message)

data class RecallOptions(
    /** Most memories to bring back; null = [LatticeSettings.searchLimit] for an explicit search, else [LatticeSettings.retrievalLimit]. */
    val limit: Int? = null,
    /** How the recall feeds back; [Reinforce.NONE] leaves the graph and the focus stack ("back then") untouched. */
    val reinforce: Reinforce = Reinforce.SOFT,
    /**
     * An explicit search (a tool call) rather than the automatic per-turn recall: no emit gate, aggregative questions
     * allowed, and the reinforcement counts as a genuine replay.
     */
    val explicit: Boolean = false,
    /** The language of the markers; null = the settings' default. */
    val language: Language? = null,
    /** The chat being answered, for scene episodes and period recall; null = none. */
    val chatKey: String? = null,
)

data class Recall(
    /** The formatted block, markers included; "" when nothing came back. */
    val text: String,
    val hits: List<RecallHit>,
    /** Some hit came from a tainted turn; the host must treat [text] as untrusted. */
    val tainted: Boolean,
)

data class RecallHit(
    val id: Long,
    val content: String,
    val activation: Double,
    val origin: Engram.Origin?,
    val eventDate: String?,
    val dueDate: String?,
    val saidFrom: Instant?,
    val saidTo: Instant?,
)

data class RememberOptions(
    val salience: Double = 0.5,
    val layer: Engram.Layer = Engram.Layer.EPISODIC,
    val origin: Engram.Origin = Engram.Origin.MANUAL,
    val tainted: Boolean = false,
)

/** A slice of conversation to capture, oldest line first. */
data class Transcript(
    val lines: List<Line>,
    val source: Source = Source(),
    /** What "today" and "yesterday" in the lines resolve against; null = the time of the last line, else now. */
    val referenceTime: Instant? = null,
    /** The turns read untrusted content; every memory taken from it is tainted. */
    val tainted: Boolean = false,
) {
    data class Line(
        val role: Role,
        val text: String,
        val at: Instant? = null,
        /** The host's position of the message in its conversation (monotonic), for provenance and scenes. */
        val messageId: Long? = null,
        /** Who spoke, when the role alone doesn't say (a group chat member). */
        val speakerName: String? = null,
    )

    enum class Role { USER, ASSISTANT }

    data class Source(
        val chatKey: String? = null,
        val conversationId: String? = null,
    )
}

/** The sleep passes, in the order they run. */
enum class SleepPhase {
    /** Scene capture of anything left uncaptured (scenes on only). */
    CATCH_UP,

    /** Period formation and the catalogue (scenes on only). */
    PERIODS,

    /** Promotion to the semantic layer, then geometric duplicate merges. */
    CONSOLIDATE,

    /** Model-judged merges of near-duplicates, within the judge budget. */
    JUDGED_DEDUP,

    /** Parent gists over clusters. */
    SYNTHESIZE,

    /** Entity resolution of unresolved engrams (entity extraction on only). */
    ENTITIES,

    /** Alias grouping of entities (entity extraction on only). */
    CANONICALIZE_ENTITIES,

    /** Same-day episode hubs (episodic binding and same-day episodes on only). */
    EPISODES,

    /** Salience and weight decay, then pruning. */
    DECAY,
}

data class SleepReport(
    val phases: List<PhaseReport>,
    val engramsBefore: Int,
    val engramsAfter: Int,
) {
    data class PhaseReport(
        val phase: SleepPhase,
        /**
         * Skipped: turned off by settings, it needs a model and there is none, the embedder was down and the phase needs
         * it, another sleep was running, or the graph started closing; [skipReason] says which.
         */
        val skipped: Boolean,
        val count: Int,
        val millis: Long,
        val error: String? = null,
        /** Why a skipped phase was skipped, as a sentence; null when it ran. */
        val skipReason: String? = null,
    )
}

data class LatticeStats(
    val engrams: Int,
    val edges: Int,
    val entities: Int,
    val scenes: Int,
    val periods: Int,
    val embeddingModel: String?,
    val embeddingDimension: Int?,
)
