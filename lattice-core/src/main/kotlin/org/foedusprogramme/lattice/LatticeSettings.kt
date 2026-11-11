package org.foedusprogramme.lattice

import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.MemoryDeadLetter
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryTexts
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a graph does: the host-facing settings (the backend choices live with the host, which
 * builds [LatticeDependencies]). The graph's ~90 tuning constants stay inside [org.foedusprogramme.lattice.core.LatticeGraph]
 * as the bge-m3 preset; these are the switches a host or operator turns.
 */
data class LatticeSettings(
    /** The user's name, so extraction writes "Alice likes tea" rather than "the user likes tea"; null = "the user". */
    val userName: String? = null,
    /**
     * The names the assistant goes by in the conversation. Extraction writes the assistant's own memories in the first
     * person under them ("I cried", not "Mia cried"), and entity resolution never admits them as entities.
     */
    val assistantNames: Set<String> = emptySet(),
    /** The zone dates in memories are written and read in. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** The language of recall markers when a call names none. */
    val language: Language = Language.EN,
    /** Most memories the automatic per-turn recall brings back. */
    val retrievalLimit: Int = 8,
    /** Most memories an explicit search brings back when the call names no limit. */
    val searchLimit: Int = 16,
    /**
     * With scene episodes on, a recall block writes its dates in words ("19 May 2023"; said days with their weekday)
     * and its dates legend says that a relative time inside a memory counts from the day it was said. false = ISO
     * dates.
     */
    val recallDatesInWords: Boolean = true,
    val graphReadTimeout: Duration = 15.seconds,
    val shutdownTimeout: Duration = 30.seconds,
    /** Most model-judged pairs a sleep reviews. */
    val judgeBudget: Int = 1000,
    val judgeParallelism: Int = 4,
    /**
     * Scene extraction calls in flight at a time. Above 1 the model calls of a conversation's chunks run ahead on a pool
     * of this many threads, each also embedding the memories it extracted in one call, while their results are still
     * written one at a time, in chunk order, so the graph ends up as a serial capture would leave it (the ordered
     * apply of [org.foedusprogramme.lattice.core.ScenePipeline]). 1 = one call at a time, on the capture thread: an interactive host's default, which never sends
     * a burst of calls at its model.
     */
    val extractParallelism: Int = 1,
    val valueAwareFold: Boolean = true,
    val judgedMergesOnly: Boolean = false,
    val episodicSessionNodes: Boolean = true,
    val temporalLayer: Boolean = true,
    val temporalRetrieval: Boolean = true,
    val periodRecall: Boolean = true,
    val temporalRerank: Boolean = true,
    val episodicBinding: Boolean = true,
    val sameDayEpisodes: Boolean = false,
    val entityExtraction: Boolean = false,
    val entityRecall: Boolean = false,
    val entityCoEncodeGate: Boolean = false,
    val scenes: SceneSettings = SceneSettings(),
)

/** Scene capture, threads and periods; they run once a host provides a conversation source and a model. */
data class SceneSettings(
    val enabled: Boolean = true,
    /** Recall scene episodes alongside facts. */
    val sceneEpisodes: Boolean = true,
    /** "anchors" or "selfCheck". */
    val narrationCheck: String = "anchors",
    val segmentWindowMessages: Int = 200,
    val segmentLookaheadMessages: Int = 20,
    /**
     * Messages a live conversation gathers past its last scene, besides the lookahead it keeps back, before they are
     * segmented: one segmentation call per this many messages. A seal and the sleep's catch-up segment whatever is
     * left.
     */
    val segmentBatchMessages: Int = 60,
    val openTailMessages: Int = 6,
    val segmentBodyChars: Int = 300,
    val cutMaxShiftRatio: Double = 0.30,
    val cutTimeout: Duration = 45.seconds,
)

/** What a graph runs on; the host builds these from its own configuration. */
data class LatticeDependencies(
    val store: LatticeStore,
    val embedder: Embedder,
    /** Null = no model: capture and every model-backed sleep phase are skipped, recall and remember still work. */
    val model: MemoryModel?,
    /** The merge judge's model id, folded into the verdict cache key so a model change re-judges. */
    val judgeModelId: String = "default",
    val texts: MemoryTexts,
    val deadLetter: MemoryDeadLetter = MemoryDeadLetter.NoOp,
    val clock: Clock = Clock.systemUTC(),
    /** The host's conversations, read back for scenes; null = no scenes, whatever [SceneSettings.enabled] says. */
    val conversations: ConversationSource? = null,
    /** Whose words and which tools' results a scene's content may be trusted with (periods name trusted scenes only). */
    val sceneTrust: SceneTrustPolicy = SceneTrustPolicy.Off,
)
