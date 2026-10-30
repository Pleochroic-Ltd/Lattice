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
    val retrievalLimit: Int = 6,
    val graphReadTimeout: Duration = 15.seconds,
    val shutdownTimeout: Duration = 30.seconds,
    /** Most model-judged pairs a sleep reviews. */
    val judgeBudget: Int = 1000,
    val judgeParallelism: Int = 4,
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

/** Scene capture, threads and periods; off until a host provides a conversation source. */
data class SceneSettings(
    val enabled: Boolean = false,
    /** Recall scene episodes alongside facts. */
    val sceneEpisodes: Boolean = false,
    /** "anchors" or "selfCheck". */
    val narrationCheck: String = "anchors",
    val segmentWindowMessages: Int = 200,
    val segmentLookaheadMessages: Int = 20,
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
)
