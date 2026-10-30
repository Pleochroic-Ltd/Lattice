package org.foedusprogramme.lattice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.lattice.core.Activation
import org.foedusprogramme.lattice.core.ChatTimeline
import org.foedusprogramme.lattice.core.DeadLetterEntry
import org.foedusprogramme.lattice.core.EmbeddingSpec
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.Extraction
import org.foedusprogramme.lattice.core.EntityAliasResolver
import org.foedusprogramme.lattice.core.EntityResolver
import org.foedusprogramme.lattice.core.EpisodeNarrator
import org.foedusprogramme.lattice.core.FocusStack
import org.foedusprogramme.lattice.core.GraphAccess
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryAbstractor
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryModelInterruptedException
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.MemoryTexts
import org.foedusprogramme.lattice.core.ModelMergeJudge
import org.foedusprogramme.lattice.core.NativeMemoryExtractor
import org.foedusprogramme.lattice.core.NativeMemoryIngestor
import org.foedusprogramme.lattice.core.PeriodLabels
import org.foedusprogramme.lattice.core.PeriodRecord
import org.foedusprogramme.lattice.core.PeriodService
import org.foedusprogramme.lattice.core.RecallDating
import org.foedusprogramme.lattice.core.RecallFormatter
import org.foedusprogramme.lattice.core.RecallMarkers
import org.foedusprogramme.lattice.core.Reinforce
import org.foedusprogramme.lattice.core.SceneChunkCapture
import org.foedusprogramme.lattice.core.ScenePipeline
import org.foedusprogramme.lattice.core.TemporalAnchor
import org.foedusprogramme.lattice.core.TemporalAnchorResolver
import org.foedusprogramme.lattice.core.TemporalIntent
import org.foedusprogramme.lattice.core.TemporalIntentClassifier
import org.foedusprogramme.lattice.core.TrustedPeriodLabels
import org.foedusprogramme.lattice.core.TranscriptRenderer
import org.foedusprogramme.lattice.core.UnparseableExtractionException
import org.foedusprogramme.lattice.core.isMemoryModelInterrupted
import org.foedusprogramme.lattice.core.isMemoryModelShutdown
import org.slf4j.LoggerFactory
import java.io.Flushable
import java.time.Instant
import java.time.YearMonth
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A sleep phase that lives outside the graph core: the scene catch-up ([SleepPhase.CATCH_UP]) and period formation
 * ([SleepPhase.PERIODS]). It runs inside [DefaultLattice.sleep] (the sleep slot is held) and reaches the graph and the
 * model through [DefaultLattice.onGraph], [DefaultLattice.onCapture] and [DefaultLattice.onNarration]. Returns the
 * phase's count for the report.
 */
internal fun interface SleepPass {
    suspend fun run(lattice: DefaultLattice): Int
}

/**
 * The [Lattice] implementation: the live calls over one graph and the sleep pipeline that maintains it.
 *
 * Threads. [LatticeGraph] is not thread-safe, so every graph access runs on one graph thread. Model calls never run
 * there: extraction runs on the capture thread, and the sleep's model calls (abstraction, narration, entities) on the
 * narration thread; merge-judge votes run on a temporary pool of [LatticeSettings.judgeParallelism] threads
 * ([LatticeGraph.vote] is the one graph method safe off the graph thread). Each call is a suspending hop onto those
 * threads. Cancelling a caller never cuts a graph task short: a task that has started runs to completion and only its
 * result is dropped, a task that hasn't started never runs. Live calls (recall, remember, saveEpisode, stats,
 * withGraph) wait for the graph thread at most [LatticeSettings.graphReadTimeout], then throw [GraphBusyException];
 * capture and sleep wait as long as it takes.
 *
 * The model-backed helpers swallow their model's exceptions (a failed call reads as an empty answer), so the instance
 * watches its model itself: once a call reports that the backend is shutting down or was interrupted, or once [close]
 * started, the sleep stops at its next step and reports the rest as skipped.
 *
 * Scenes. With a conversation source ([LatticeDependencies.conversations]), [SceneSettings.enabled] and a model, the
 * instance runs one [ScenePipeline] on its capture and narration threads ([scenes]): a capture with a conversation id
 * advances it, [conversationSealed] finishes a conversation, the sleep's CATCH_UP runs its catch-up and PERIODS waits
 * for it to go idle first, and [close] waits for it to go idle before stopping the threads.
 *
 * Periods. Alongside the pipeline runs one [PeriodService] ([periods]) on the same narration thread: a chat whose
 * narration drains is formed there, the sleep's PERIODS forms every chat, a turn-end recall labels periods under scene
 * trust ([TrustedPeriodLabels]), and the startup check runs once after opening. Without the pipeline there are no
 * periods: recall labels them neutrally and [catalogue] and [timeline] answer null.
 *
 * @param graphConfig the graph's tuning; null = the bge-m3 preset with the settings' switches ([graphConfig]).
 * @param catchUp the scene catch-up; null = the scene pipeline's when there is one (else CATCH_UP is reported skipped).
 * @param formPeriods period formation; null = the period service's when there is one (else PERIODS is reported skipped).
 */
internal class DefaultLattice(
    override val name: String,
    private val settings: LatticeSettings,
    private val dependencies: LatticeDependencies,
    graphConfig: LatticeGraph.Config? = null,
    catchUp: SleepPass? = null,
    formPeriods: SleepPass? = null,
) : Lattice {
    private val logger = LoggerFactory.getLogger(DefaultLattice::class.java)
    private val store = dependencies.store
    private val embedder = dependencies.embedder
    private val texts: MemoryTexts = dependencies.texts
    private val zone = settings.zone
    private val now: () -> Instant = { dependencies.clock.instant() }
    private val sceneEpisodes = settings.scenes.enabled && settings.scenes.sceneEpisodes

    @Volatile
    private var graphThread: Thread? = null

    /** Set by [close]: the store is flushed and closed as the graph executor terminates, after its last task ran. */
    @Volatile
    private var closeStoreOnStop = false
    private val graphExecutor: ExecutorService = object : ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { r -> Thread(r, "lattice-$name-graph").apply { isDaemon = true }.also { graphThread = it } },
    ) {
        override fun terminated() {
            if (closeStoreOnStop) closeStore()
        }
    }
    private val captureExecutor = singleThread("capture")
    private val narrationExecutor = singleThread("narrate")

    /** Set when a model call reports the backend shutting down or the call interrupted; reset at each sleep. */
    private val modelStopped = AtomicBoolean(false)
    private val model: MemoryModel? = dependencies.model?.let { WatchedModel(it) }
    private val extractor = model?.let { NativeMemoryExtractor(it, settings.userName, settings.temporalLayer, zone, settings.assistantNames) }
    private val abstractor = model?.let { MemoryAbstractor(it) }
    private val episodeNarrator = model?.let { EpisodeNarrator(it) }
    private val entityResolver = model?.let { EntityResolver(it, settings.assistantNames) }
    private val aliasResolver = model?.let { EntityAliasResolver(it) }

    /** The live graph; touched only on the graph thread (and [LatticeGraph.vote] from the judge pool). */
    private val graph: LatticeGraph

    /** The event months the dialogue has been dwelling on, for bare back-references; graph thread only. */
    private val focusStack = FocusStack()

    /**
     * Clusters whose synthesized parent the graph refused (a single-child paraphrase), keyed by the sorted child ids:
     * an unchanged refused set is not sent to the model again. A membership change is a new key, so it is retried.
     * Lives as long as the instance; touched only by the one running sleep.
     */
    private val refusedClusters = HashSet<List<Long>>()

    /** Same-day member sets whose non-blank narration the graph refused; same contract as [refusedClusters]. */
    private val refusedEpisodes = HashSet<List<Long>>()

    private val markerCache = ConcurrentHashMap<Language, RecallMarkers>()
    private val sleeping = AtomicBoolean(false)

    private val lifecycle = Any()
    private var active = 0
    private var closed = false
    private val drained = CompletableDeferred<Unit>()
    private val closedDone = CompletableDeferred<Unit>()

    init {
        val config = graphConfig ?: graphConfig(settings, texts)
        graph = try {
            graphExecutor.submit(
                Callable {
                    checkEmbedding()
                    LatticeGraph(embedder, config, now, store, model?.let { ModelMergeJudge(it, dependencies.judgeModelId) })
                },
            ).get()
        } catch (e: ExecutionException) {
            shutdownExecutors()
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            shutdownExecutors()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Opening graph '$name' was interrupted.", e)
        }
    }

    /**
     * The graph for code that blocks instead of suspending (the scene pipeline on the capture and narration threads):
     * each block runs on the graph thread and the caller waits for it. Never call it on the graph thread itself.
     *
     * An interrupt withdraws a block that has not started: it never runs, and the call throws
     * [MemoryModelInterruptedException]. A block that has started is waited for and its outcome returned, with the
     * interrupt flag set again: a caller must never report as failed a write that landed.
     */
    internal val graphAccess: GraphAccess = object : GraphAccess {
        override fun <T> withGraph(block: (LatticeGraph) -> T): T {
            check(Thread.currentThread() !== graphThread) {
                "GraphAccess.withGraph was called on the graph thread of '$name', which would wait for itself."
            }
            val state = AtomicInteger(QUEUED)
            val task = FutureTask(Callable { if (state.compareAndSet(QUEUED, STARTED)) block(graph) else null })
            try {
                graphExecutor.execute(task)
            } catch (e: RejectedExecutionException) {
                throw IllegalStateException("Lattice '$name' is closed; open the graph again to use it.", e)
            }
            var interrupted = false
            try {
                while (true) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        return task.get() as T
                    } catch (e: InterruptedException) {
                        interrupted = true
                        if (state.compareAndSet(QUEUED, WITHDRAWN)) {
                            task.cancel(false)
                            throw MemoryModelInterruptedException("Waiting for the graph of '$name' was interrupted.", e)
                        }
                    }
                }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    /** The scene pipeline; null without a conversation source, with scenes off or without a model. */
    internal val scenes: ScenePipeline? = run {
        val source = dependencies.conversations
        if (source == null || !settings.scenes.enabled || model == null || extractor == null) return@run null
        ScenePipeline(
            graph = graphAccess,
            source = source,
            model = model,
            embedder = embedder,
            capture = SceneCapture(extractor),
            captureExecutor = captureExecutor,
            narrationExecutor = narrationExecutor,
            settings = settings.scenes,
            zone = zone,
            userName = settings.userName,
            selfAliases = settings.assistantNames,
            trustPolicy = dependencies.sceneTrust,
            deadLetter = dependencies.deadLetter,
            // Inline on the narration thread, so the pipeline's awaitIdle covers the formation it starts.
            onChatDrained = { chatKey -> periods?.chatDrained(chatKey) },
        )
    }

    /**
     * The period layer over the pipeline; null without it. Built here, off the graph thread and after the graph loaded:
     * it reads the published catalogues once to seed them.
     */
    internal val periods: PeriodService? = scenes?.let { pipeline ->
        try {
            PeriodService(
                graph = graphAccess,
                source = checkNotNull(dependencies.conversations),
                model = checkNotNull(model),
                trust = pipeline.trust,
                narrationExecutor = narrationExecutor,
                texts = texts,
                language = { settings.language },
                fallbackLanguage = settings.language,
                catalogueLanguages = listOf(settings.language, Language.EN, Language.ZH).distinct(),
                userName = settings.userName,
                assistantNames = settings.assistantNames,
                deadLetter = dependencies.deadLetter,
            )
        } catch (e: Exception) {
            shutdownExecutors()
            throw e
        }
    }

    /** CATCH_UP: [catchUp] when given, else the pipeline's catch-up, run off the scene threads. */
    private val sceneCatchUp: SleepPass? = catchUp ?: scenes?.let { pipeline ->
        SleepPass { runInterruptible(Dispatchers.IO) { pipeline.catchUp() } }
    }

    /** PERIODS: [formPeriods] when given, else the period service's; [runPhase] settles the scene work before either. */
    private val periodPass: SleepPass? = formPeriods ?: periods?.let { periodFormationPass(it) }

    /** The period tags of a turn-end recall: names under scene trust with the period layer, else neutral dates. */
    private val periodLabels: PeriodLabels = if (periods != null) TrustedPeriodLabels else PeriodLabels.NEUTRAL

    init {
        // The startup check (it fills the trust cache), then one formation per chat, queued on the narration thread.
        periods?.scheduleStartup()
    }

    /** A new graph takes the embedder's spec; a graph embedded with another model refuses this embedder. */
    private fun checkEmbedding() {
        val spec = EmbeddingSpec(embedder.model, embedder.dimension)
        when (val stored = store.embedding) {
            null -> store.declareEmbedding(spec)
            spec -> {}
            else -> throw IllegalStateException(
                "Graph '$name' was embedded with ${stored.model} (dimension ${stored.dimension}), but the embedder is " +
                    "${spec.model} (dimension ${spec.dimension}). Open it with an embedder for ${stored.model}, or " +
                    "re-embed the graph with ${spec.model} first.",
            )
        }
    }

    // --- Recall ------------------------------------------------------------------------------------------------------

    override suspend fun recall(cue: String, options: RecallOptions): Recall = call {
        if (cue.isBlank()) return@call Recall("", emptyList(), false)
        val explicit = options.explicit
        // An explicit search is a deliberate retrieval: a passive SOFT reinforcement becomes a genuine replay.
        val reinforce = if (explicit && options.reinforce == Reinforce.SOFT) Reinforce.FULL else options.reinforce
        val language = options.language ?: settings.language
        val markers = markerCache.getOrPut(language) { RecallMarkers.of(texts, language) }
        // Scene episodes on: a search result may be stored, so it shows absolute days only; the per-turn block rides
        // that turn alone, so it also shows how long ago and the period.
        val dating = when {
            !sceneEpisodes -> RecallDating.LEGACY
            explicit -> RecallDating.absolute(zone)
            else -> RecallDating.turnEnd(options.chatKey, now(), zone)
        }
        onGraphBounded { g ->
            val hits = dispatchTemporal(g, cue, options.limit ?: settings.retrievalLimit, reinforce, applyEmitGate = !explicit, allowAggregative = explicit)
            val text = RecallFormatter.format(hits, markers, g.temporalLayerEnabled, lookup(g, language), settings.userName, dating)
            // Beyond the hits and their quotes the block shows thread names, written by a scene's narration; a scene
            // title rides only its own hub and a period name only a trusted scene.
            val tainted = hits.any { h ->
                val id = h.engram.id
                h.engram.tainted || g.evidenceQuotes(id).any { it.tainted } ||
                    g.threadStatusOf(id).let { s -> (s.closed + s.open).any(g::threadTextTainted) }
            }
            Recall(text, hits.map(::recallHit), tainted)
        }
    }

    /**
     * Recall through the temporal channel. With [LatticeSettings.temporalRetrieval] off it is plain [LatticeGraph.recall]
     * (an explicit search that finds nothing falls back to the soft lexical match). On: an explicit WHEN-ordering cue
     * goes to [LatticeGraph.temporalOrderFetch]; otherwise the cue's [TemporalIntent] and its [TemporalAnchor] route it
     * (see [temporalDispatch]). Graph thread only ([focusStack]).
     */
    private fun dispatchTemporal(
        g: LatticeGraph,
        cue: String,
        limit: Int,
        reinforce: Reinforce,
        applyEmitGate: Boolean,
        allowAggregative: Boolean,
    ): List<Activation> {
        // The broad-cue channels (temporal order, soft fallback) are explicit-search only: a passive recall the emit
        // gate silenced must never be overridden into surfacing something.
        val explicit = !applyEmitGate
        if (!g.temporalRetrievalEnabled) {
            val r = g.recall(cue, limit, reinforce, applyEmitGate, allowAggregative)
            return if (explicit && r.isEmpty()) g.appendSceneRecall(cue, g.softFallback(cue, limit), limit) else r
        }
        if (explicit) {
            g.temporalOrderFetch(cue, limit).let { if (it.isNotEmpty()) return g.appendSceneRecall(cue, it, limit) }
        }
        val first = temporalDispatch(g, cue, limit, reinforce, applyEmitGate, allowAggregative)
        val result = if (explicit && first.isEmpty()) g.appendSceneRecall(cue, g.softFallback(cue, limit), limit) else first
        pushFocus(result.mapNotNull { it.engram.eventYearMonth })
        return result
    }

    /**
     * NONE intent or no anchor: semantic recall. A day window ("four weeks ago") puts what was said or dated then
     * first, in at most half the slots. A PERIOD back-reference pre-seeds the period's memories (read-only; an
     * over-dense window abstains and falls through). A RELEVANCE cue with a certain point in time reorders the
     * semantic hits toward it, never changing which ones come back.
     */
    private fun temporalDispatch(
        g: LatticeGraph,
        cue: String,
        limit: Int,
        reinforce: Reinforce,
        applyEmitGate: Boolean,
        allowAggregative: Boolean,
    ): List<Activation> {
        val intent = TemporalIntentClassifier.classify(cue)
        if (intent == TemporalIntent.NONE) return g.recall(cue, limit, reinforce, applyEmitGate, allowAggregative)
        val anchor = TemporalAnchorResolver.resolve(cue, focusStack.snapshot(), now(), zone, g.periodMarginMonths)
            ?: return g.recall(cue, limit, reinforce, applyEmitGate, allowAggregative)

        val fromDay = anchor.fromDay
        val toDay = anchor.toDay
        if (fromDay != null && toDay != null && g.dayWindowRecallEnabled) {
            val window = g.dayWindowFetch(cue, fromDay, toDay, zone, cap = maxOf(2, limit / 2))
            val rerank = if (g.temporalRerankEnabled && intent == TemporalIntent.RELEVANCE) anchor else null
            val hits = g.recall(cue, limit, reinforce, applyEmitGate, allowAggregative, temporalAnchor = rerank)
            if (window.isEmpty()) return hits
            val windowIds = window.mapTo(HashSet()) { it.engram.id }
            val base = (window + hits.take(limit).filter { it.engram.id !in windowIds }).take(limit)
            val baseIds = base.mapTo(HashSet()) { it.engram.id }
            return base + hits.drop(limit).filter { it.engram.id !in baseIds }
        }
        if (g.periodRecallEnabled && intent == TemporalIntent.PERIOD) {
            val hits = g.periodFetch(anchor, limit)
            if (hits.isNotEmpty()) return g.appendSceneRecall(cue, hits, limit)
        }
        val rerank = if (g.temporalRerankEnabled && intent == TemporalIntent.RELEVANCE &&
            anchor.kind == TemporalAnchor.Kind.POINT && anchor.confidence == TemporalAnchor.Confidence.HIGH
        ) {
            anchor
        } else {
            null
        }
        return g.recall(cue, limit, reinforce, applyEmitGate, allowAggregative, temporalAnchor = rerank)
    }

    /** Pushes the distinct event months of just-captured or just-recalled memories, the first ending on top. */
    private fun pushFocus(isoMonths: List<String>) {
        val months = isoMonths.mapNotNull { runCatching { YearMonth.parse(it) }.getOrNull() }.distinct()
        for (m in months.asReversed()) focusStack.push(m)
    }

    /** The formatter's view of the live graph; called on the graph thread only. */
    private fun lookup(g: LatticeGraph, language: Language) = object : RecallFormatter.Lookup {
        override fun evidenceQuotes(id: Long): List<Engram> = g.evidenceQuotes(id)
        override fun sceneOfHub(hubId: Long) = g.sceneOfHub(hubId)
        override fun threadsOf(id: Long) = g.threadStatusOf(id).let { RecallFormatter.Threads(it.closed, it.open) }
        override fun closedResolvesOf(setupId: Long) = g.closedResolvesOf(setupId)
        override fun scenesOf(id: Long) = g.scenesOf(id).mapNotNull(g::scene)
        override fun periodOfScene(sceneId: Long) = g.periodOfScene(sceneId)
        override fun periodLabel(period: PeriodRecord) = periodLabels.label(g, period, language)
    }

    private fun recallHit(a: Activation) = RecallHit(
        id = a.engram.id,
        content = a.engram.content,
        activation = a.activation,
        origin = a.engram.origin,
        eventDate = a.engram.eventDate,
        dueDate = a.engram.dueDate,
        saidFrom = a.engram.saidFrom,
        saidTo = a.engram.saidTo,
    )

    // --- Encoding ----------------------------------------------------------------------------------------------------

    override suspend fun remember(content: String, options: RememberOptions): Long = call {
        require(content.isNotBlank()) { "A memory needs content; remember was given a blank string." }
        onGraphBounded {
            it.remember(content, options.salience, options.layer, origin = options.origin, tainted = options.tainted)
        }
    }

    /**
     * Chunks of [TranscriptRenderer.CHUNK_LINES] lines, each extracted on the capture thread and ingested on the graph
     * thread, in order. A chunk whose answer doesn't parse is dead-lettered and the next chunk still runs; the first
     * such failure is thrown at the end (the others suppressed under it), after every other chunk was ingested. Any
     * other failure (the model down, the backend shutting down) stops the capture at once.
     */
    override suspend fun capture(transcript: Transcript): Int = call {
        val conversationId = transcript.source.conversationId
        val pipeline = scenes
        if (pipeline != null && conversationId != null) {
            pipeline.advance(conversationId)
            return@call 0
        }
        val ex = extractor ?: return@call 0
        var count = 0
        var poison: UnparseableExtractionException? = null
        for (chunk in TranscriptRenderer.chunks(transcript, zone, TranscriptRenderer.CHUNK_LINES)) {
            if (chunk.text.isBlank()) continue
            try {
                count += captureChunk(ex, chunk)
            } catch (e: UnparseableExtractionException) {
                val first = poison
                if (first == null) poison = e else first.addSuppressed(e)
            }
        }
        poison?.let { throw it }
        count
    }

    private suspend fun captureChunk(ex: NativeMemoryExtractor, chunk: TranscriptRenderer.Rendered): Int {
        val extraction = onCapture { extract(ex, chunk) }
        if (extraction.memories.isEmpty()) return 0
        onGraph { g -> ingest(g, chunk, extraction, sceneId = null) }
        return extraction.memories.size
    }

    /** Extracts [chunk], blocking; an answer that doesn't parse is dead-lettered and rethrown. */
    private fun extract(ex: NativeMemoryExtractor, chunk: TranscriptRenderer.Rendered): Extraction {
        val referenceTime = chunk.referenceTime ?: now()
        try {
            return ex.extract(chunk.text, referenceTime)
        } catch (e: UnparseableExtractionException) {
            val entry = DeadLetterEntry(
                reason = e.message ?: "unparseable extraction",
                phase = "capture",
                sourceMessageId = chunk.sourceMessageId,
                referenceTime = referenceTime.toString(),
                transcript = chunk.text,
                rawOutput = e.rawOutput,
            )
            runCatching { dependencies.deadLetter.record(entry) }
                .onFailure { logger.warn("Lattice {} dead letter not recorded: {}", name, it.message) }
            logger.warn("Lattice {} capture: the model's answer did not parse (dead-lettered)", name)
            throw e
        }
    }

    /** Ingests [extraction] of [chunk], as members of scene [sceneId] when set; graph thread only. */
    private fun ingest(g: LatticeGraph, chunk: TranscriptRenderer.Rendered, extraction: Extraction, sceneId: Long?) {
        val ids = NativeMemoryIngestor.ingest(
            g, extraction,
            sourceMessageId = chunk.sourceMessageId,
            sceneId = sceneId,
            saidFrom = chunk.saidFrom ?: chunk.referenceTime,
            saidTo = chunk.saidTo ?: chunk.referenceTime,
            zone = zone,
            tainted = chunk.tainted,
        )
        if (g.temporalRetrievalEnabled) pushFocus(ids.values.mapNotNull { g.engram(it)?.eventYearMonth })
    }

    /**
     * The scene pipeline's capture: one chunk of a scene, extracted on the calling thread (the capture thread) and
     * ingested on the graph thread as members of its scene.
     */
    private inner class SceneCapture(private val ex: NativeMemoryExtractor) : SceneChunkCapture {
        override fun capture(transcript: Transcript, sceneId: Long?): Int {
            val chunk = TranscriptRenderer.render(transcript, zone)
            if (chunk.text.isBlank()) return 0
            val extraction = extract(ex, chunk)
            if (extraction.memories.isEmpty()) return 0
            graphAccess.withGraph { g -> ingest(g, chunk, extraction, sceneId) }
            return extraction.memories.size
        }
    }

    override suspend fun conversationSealed(conversationId: String) = call {
        scenes?.captureSealed(conversationId)
        Unit
    }

    /**
     * A durable, theme-recallable summary of one whole conversation, kept off the structural graph and ranked below
     * specific facts. [source] names the conversation for the scene pipeline; the episode itself carries no anchor.
     */
    override suspend fun saveEpisode(summary: String, source: Transcript.Source?): Long? = call {
        if (!settings.episodicSessionNodes || summary.isBlank()) return@call null
        onGraphBounded { g ->
            val before = g.size
            val id = g.remember(summary, EPISODE_SALIENCE, Engram.Layer.SEMANTIC, isEpisode = true, origin = Engram.Origin.EXTRACTED)
            if (g.size > before) id else null
        }
    }

    // --- Sleep -------------------------------------------------------------------------------------------------------

    override suspend fun sleep(phases: Set<SleepPhase>): SleepReport = call {
        val requested = SleepPhase.entries.filter { it in phases }
        if (!sleeping.compareAndSet(false, true)) {
            logger.warn("Lattice {} sleep skipped: another sleep is running", name)
            val size = onGraph { it.size }
            return@call SleepReport(requested.map { skipped(it, "Another sleep of this graph is running.") }, size, size)
        }
        try {
            runSleep(requested)
        } finally {
            sleeping.set(false)
        }
    }

    private suspend fun runSleep(requested: List<SleepPhase>): SleepReport {
        modelStopped.set(false)
        val before = onGraph { it.size }
        val embedderOk = try {
            onGraph { it.probeEmbedder() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!embedderOk) logger.warn("Lattice {} sleep: the embedder is unreachable; phases that embed are skipped", name)
        val reports = ArrayList<SleepReport.PhaseReport>(requested.size)
        for (phase in requested) {
            val reason = stopReason() ?: skipReason(phase, embedderOk)
            reports += if (reason != null) skipped(phase, reason) else runPhase(phase)
        }
        try {
            onGraph { (store as? Flushable)?.flush() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Lattice {} sleep: flushing the store failed: {}", name, e.message, e)
        }
        val after = onGraph { it.size }
        logger.info(
            "Lattice {} sleep done: {} | engrams {} -> {}",
            name,
            reports.joinToString(" ") { r -> "${r.phase}=" + (r.error?.let { "failed" } ?: if (r.skipped) "skipped" else r.count.toString()) },
            before, after,
        )
        return SleepReport(reports, before, after)
    }

    private fun skipped(phase: SleepPhase, reason: String) =
        SleepReport.PhaseReport(phase, skipped = true, count = 0, millis = 0, skipReason = reason)

    /** Why [phase] doesn't run this sleep, or null when it does: settings first, then the model, then the embedder. */
    private fun skipReason(phase: SleepPhase, embedderOk: Boolean): String? {
        val needs = when (phase) {
            SleepPhase.CATCH_UP -> when {
                !settings.scenes.enabled -> return "Scenes are off."
                sceneCatchUp == null && dependencies.conversations == null ->
                    return "The host provides no conversations to read scenes from."
                else -> Needs.MODEL_AND_EMBEDDER
            }
            SleepPhase.PERIODS -> when {
                !settings.scenes.enabled -> return "Scenes are off."
                periodPass == null && dependencies.conversations == null ->
                    return "The host provides no conversations to read scenes from."
                periodPass == null && model != null -> return "Period formation is not wired for this graph."
                else -> Needs.MODEL
            }
            SleepPhase.CONSOLIDATE, SleepPhase.DECAY -> Needs.NOTHING
            SleepPhase.JUDGED_DEDUP -> if (settings.judgeBudget <= 0) return "The judge budget is 0." else Needs.MODEL
            SleepPhase.SYNTHESIZE -> Needs.MODEL_AND_EMBEDDER
            SleepPhase.ENTITIES, SleepPhase.CANONICALIZE_ENTITIES ->
                if (!settings.entityExtraction) return "Entity extraction is off." else Needs.MODEL
            SleepPhase.EPISODES ->
                if (!settings.episodicBinding || !settings.sameDayEpisodes) return "Same-day episodes are off." else Needs.MODEL_AND_EMBEDDER
        }
        if (needs != Needs.NOTHING && model == null) return "There is no model."
        if (needs == Needs.MODEL_AND_EMBEDDER && !embedderOk) return "The embedder is unreachable."
        return null
    }

    private enum class Needs { NOTHING, MODEL, MODEL_AND_EMBEDDER }

    /** Why the sleep stops early, or null: the graph is closing, or the model backend went away. */
    private fun stopReason(): String? = when {
        isClosing() -> "The graph is closing."
        modelStopped.get() -> "The model is shutting down."
        else -> null
    }

    private suspend fun stopRequested(): Boolean = stopReason() != null || !currentCoroutineContext().isActive

    /** Runs one phase fail-soft: a failure is logged and reported, and the sleep goes on with the next phase. */
    private suspend fun runPhase(phase: SleepPhase): SleepReport.PhaseReport {
        val started = TimeSource.Monotonic.markNow()
        return try {
            val count = when (phase) {
                SleepPhase.CATCH_UP -> checkNotNull(sceneCatchUp).run(this)
                SleepPhase.PERIODS -> {
                    // Periods are formed from settled scenes: let the catch-up's narration finish first.
                    scenes?.let { pipeline -> runInterruptible(Dispatchers.IO) { pipeline.awaitIdle() } }
                    checkNotNull(periodPass).run(this)
                }
                // Merge before synthesizing (abstract a clean graph); synthesize after the merge so a fresh parent is
                // never folded away by the same sleep. The judged half of the merge runs in its own phase.
                SleepPhase.CONSOLIDATE -> onGraph { g -> g.consolidate().promoted + g.mergeDuplicates(runJudgedPass = false) }
                SleepPhase.JUDGED_DEDUP -> judgedDedupPass()
                SleepPhase.SYNTHESIZE -> synthesizeParents()
                SleepPhase.ENTITIES -> resolveEntitiesPass()
                SleepPhase.CANONICALIZE_ENTITIES -> canonicalizeEntitiesPass()
                SleepPhase.EPISODES -> formEpisodesPass()
                SleepPhase.DECAY -> onGraph { it.decay() }
            }
            SleepReport.PhaseReport(phase, skipped = false, count = count, millis = started.elapsedNow().inWholeMilliseconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Lattice {} sleep phase {} failed (the sleep goes on): {}", name, phase, e.message, e)
            SleepReport.PhaseReport(
                phase, skipped = false, count = 0, millis = started.elapsedNow().inWholeMilliseconds,
                error = e.message ?: e.toString(),
            )
        }
    }

    /**
     * Model-judged merges with the votes off the graph thread: candidates and cached verdicts on the graph thread, up to
     * [LatticeSettings.judgeBudget] pairs voted on the judge pool, the verdicts re-validated and applied on the graph
     * thread. Returns the pairs newly superseded.
     */
    private suspend fun judgedDedupPass(): Int {
        if (!onGraph { it.judgeWired }) return 0
        val phaseA = onGraph { it.judgeCandidates() }
        if (phaseA.pending.isEmpty()) return phaseA.supersededFromCache
        val job = currentCoroutineContext().job
        val results = onNarration { votes(phaseA.pending) { !job.isActive || stopReason() != null } }
        val applied = if (results.isEmpty()) 0 else onGraph { it.applyJudgeVerdicts(results) }
        return phaseA.supersededFromCache + applied
    }

    /** [LatticeGraph.vote] for each candidate, [LatticeSettings.judgeParallelism] at a time, in the candidates' order. */
    private fun votes(pending: List<LatticeGraph.JudgeCandidate>, stop: () -> Boolean): List<LatticeGraph.JudgeResult> {
        // A vote skipped for a stop is a RETRY: not cached, so the pair is judged on a later sleep.
        fun vote(c: LatticeGraph.JudgeCandidate) =
            LatticeGraph.JudgeResult(c, if (stop()) LatticeGraph.JudgeOutcome.RETRY else graph.vote(c))
        val threads = settings.judgeParallelism.coerceIn(1, pending.size.coerceAtLeast(1))
        if (threads == 1) return pending.map(::vote)
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "lattice-$name-judge").apply { isDaemon = true } }
        try {
            val futures = pending.map { c -> pool.submit(Callable { vote(c) }) }
            return futures.mapIndexed { i, future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    logger.warn("Lattice {} judge vote failed {}~{}; retried next sleep", name, pending[i].aId, pending[i].bId, e.cause)
                    LatticeGraph.JudgeResult(pending[i], LatticeGraph.JudgeOutcome.RETRY)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Synthesis: for each cluster of related memories the model writes one parent gist, ABSTRACTS-linked to its
     * children (which stay). Returns the parents created.
     */
    private suspend fun synthesizeParents(threshold: Double = SYNTH_THRESHOLD, minSize: Int = SYNTH_MIN_SIZE): Int {
        val ab = abstractor ?: return 0
        val clusters = onGraph { it.relatedClusters(threshold, minSize) }
        var parents = 0
        for (cluster in clusters) {
            if (stopRequested()) break
            val key = cluster.sorted()
            if (key in refusedClusters) continue
            val contents = onGraph { g -> cluster.mapNotNull { g.engram(it)?.content } }
            if (contents.size < minSize) continue
            val parentContent = onNarration { ab.synthesize(contents) }
            if (parentContent.isBlank()) continue
            // The guard may refuse a single-child paraphrase: an id <= 0 means skipped.
            val id = onGraph { it.synthesizeParent(cluster, parentContent) }
            if (id <= 0L) refusedClusters += key else parents++
        }
        return parents
    }

    /**
     * Same-day episodes: each candidate set of co-retrieved same-day memories is narrated by the model and bound under
     * an episode hub. Returns the hubs created.
     */
    private suspend fun formEpisodesPass(): Int {
        val narrator = episodeNarrator ?: return 0
        if (!onGraph { it.episodicBindingEnabled }) return 0
        val candidates = onGraph { it.formEpisodes() }
        var made = 0
        for (c in candidates) {
            if (stopRequested()) break
            val key = c.memberIds
            if (key in refusedEpisodes) continue
            val contents = onGraph { g -> c.memberIds.mapNotNull { g.engram(it)?.content } }
            if (contents.size < 2) continue
            val narration = onNarration { narrator.narrate(contents, c.eventDate) }
            // A blank narration is transient (the narrator abstained, or clipped an over-long narration): not cached,
            // so the unchanged set is narrated again on a later sleep.
            if (narration.isBlank()) continue
            // A non-blank narration the graph still refuses is a genuine refusal (a verbatim copy of a member, too few
            // members left, incoherent members): cached, so the unchanged set costs no model call next time.
            val id = onGraph { it.materializeEpisode(c.memberIds, narration, c.eventDate, skipCohesion = c.coEncoded) }
            if (id <= 0L) refusedEpisodes += key else made++
        }
        return made
    }

    /**
     * Entity resolution: one model call per unresolved window names each memory's entities. An unparseable answer
     * leaves the window unresolved (retried next sleep); a parsed empty one is marked resolved. Returns the memories
     * that gained an entity.
     */
    private suspend fun resolveEntitiesPass(): Int {
        val resolver = entityResolver ?: return 0
        val windows = onGraph { it.entityResolveCandidates() }
        var tagged = 0
        for (window in windows) {
            if (stopRequested()) break
            val items = onGraph { g ->
                window.mapNotNull { id -> g.engram(id)?.let { EntityResolver.Item("m$id", it.content, it.eventDate) } }
            }
            if (items.isEmpty()) continue
            val res = onNarration { runCatching { resolver.resolve(items) }.getOrNull() } ?: continue
            // A shutdown reads as an unparseable answer; never mark a window resolved on it.
            if (stopReason() != null) break
            val perMemory = res.perMemory.mapNotNull { (ref, ents) -> ref.removePrefix("m").toLongOrNull()?.let { it to ents } }.toMap()
            onGraph { it.applyEntityResolution(window, perMemory, res.occasion, res.keyEntities) }
            tagged += perMemory.size
        }
        return tagged
    }

    /** Alias grouping: one model call groups the entity registry's variants, which are then merged. */
    private suspend fun canonicalizeEntitiesPass(): Int {
        val resolver = aliasResolver ?: return 0
        val entities = onGraph { it.entityCanonicalizationInput() }
        if (entities.size < 2) return 0
        val groups = onNarration { runCatching { resolver.group(entities) }.getOrNull() } ?: return 0
        if (groups.isEmpty() || stopReason() != null) return 0
        return onGraph { it.applyEntityMerges(groups) }
    }

    // --- Inspection --------------------------------------------------------------------------------------------------

    override suspend fun stats(): LatticeStats = call {
        onGraphBounded { g ->
            LatticeStats(
                engrams = g.size,
                edges = g.edges().size,
                entities = g.entities().size,
                scenes = g.scenes().size,
                periods = g.periodChatKeys().sumOf { g.periods(it).size },
                embeddingModel = store.embedding?.model,
                embeddingDimension = store.embedding?.dimension,
            )
        }
    }

    override suspend fun catalogue(chatKey: String, language: Language?): String? = call {
        periods?.catalogue(chatKey, language ?: settings.language)
    }

    override suspend fun timeline(chatKey: String): ChatTimeline? = call {
        if (periods == null) null else onGraphBounded { g -> ChatTimeline.read(g, chatKey) }
    }

    override suspend fun <T> withGraph(block: (LatticeGraph) -> T): T = call { onGraphBounded(block) }

    // --- Lifecycle ---------------------------------------------------------------------------------------------------

    /**
     * Refuses new calls, waits up to [LatticeSettings.shutdownTimeout] for the calls in flight (a running sleep stops at
     * its next step) and then for the scene work queued so far, then stops the threads and flushes and closes the store
     * as the graph thread's last task. Work still running at the deadline is interrupted on the capture and narration
     * threads (a scene model call cut off this way counts no attempt) and work still queued there is dropped, failing
     * whoever waits on it with [IllegalStateException]. The graph thread is never interrupted: it gets another
     * [LatticeSettings.shutdownTimeout] of its own to run what is queued, and if it is still busy after that the store
     * closes when its last task ends, never before. A second close waits for the first.
     */
    override suspend fun close(): Unit = withContext(NonCancellable) {
        val first = synchronized(lifecycle) {
            if (closed) {
                false
            } else {
                closed = true
                if (active == 0) drained.complete(Unit)
                true
            }
        }
        if (!first) {
            closedDone.await()
            return@withContext
        }
        val deadline = TimeSource.Monotonic.markNow() + settings.shutdownTimeout
        fun remaining(): Duration = (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO)
        if (withTimeoutOrNull(remaining()) { drained.await() } == null) {
            logger.warn("Lattice {} close: calls still running after {}; stopping anyway", name, settings.shutdownTimeout)
        }
        scenes?.let { pipeline ->
            val idle = try {
                withTimeoutOrNull(remaining()) { runInterruptible(Dispatchers.IO) { pipeline.awaitIdle() } }
            } catch (e: Exception) {
                logger.warn("Lattice {} close: waiting for the scene work failed: {}", name, e.message, e)
                Unit
            }
            if (idle == null) logger.warn("Lattice {} close: scene work still queued after {}; stopping anyway", name, settings.shutdownTimeout)
        }
        withContext(Dispatchers.IO) {
            stop(captureExecutor, remaining(), interrupt = true)
            stop(narrationExecutor, remaining(), interrupt = true)
            // The graph gets a wait of its own (its tasks are short); the store closes once its last task has run.
            closeStoreOnStop = true
            if (!stop(graphExecutor, settings.shutdownTimeout, interrupt = false)) {
                logger.error(
                    "Lattice {} close: the graph thread is still busy after {}; the store closes when it finishes",
                    name, settings.shutdownTimeout,
                )
            }
        }
        closedDone.complete(Unit)
        Unit
    }

    /** Flushes and closes the store; on the graph thread as it terminates, so no graph task can write after it. */
    private fun closeStore() {
        try {
            (store as? Flushable)?.flush()
        } catch (e: Exception) {
            logger.warn("Lattice {} close: flushing the store failed: {}", name, e.message, e)
        }
        try {
            store.close()
        } catch (e: Exception) {
            logger.warn("Lattice {} close: closing the store failed: {}", name, e.message, e)
        }
    }

    /** Shuts [executor] down and waits up to [timeout]; true when it terminated. */
    private fun stop(executor: ExecutorService, timeout: Duration, interrupt: Boolean): Boolean {
        executor.shutdown()
        val done = executor.awaitTermination(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        // A queued task the executor drops never completes on its own: cancel it, so whoever waits on it hears.
        if (!done && interrupt) executor.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
        return done
    }

    private fun isClosing(): Boolean = synchronized(lifecycle) { closed }

    /** Runs [block] as one call: refused once closing started, counted until it ends so [close] can drain it. */
    private suspend fun <T> call(block: suspend () -> T): T {
        synchronized(lifecycle) {
            check(!closed) { "Lattice '$name' is closed; open the graph again to use it." }
            active++
        }
        try {
            return block()
        } finally {
            synchronized(lifecycle) {
                active--
                if (closed && active == 0) drained.complete(Unit)
            }
        }
    }

    private fun shutdownExecutors() {
        captureExecutor.shutdownNow()
        narrationExecutor.shutdownNow()
        graphExecutor.shutdownNow()
    }

    // --- Thread hops (also the seams the scene pipeline and period former use) ---------------------------------------

    /** Runs [block] on the graph thread, as long as that takes (offline work: capture, sleep). */
    internal suspend fun <T> onGraph(block: (LatticeGraph) -> T): T = graphExecutor.await { block(graph) }

    /** [onGraph] for the live path: waits at most [LatticeSettings.graphReadTimeout], then throws [GraphBusyException]. */
    private suspend fun <T> onGraphBounded(block: (LatticeGraph) -> T): T = try {
        withTimeout(settings.graphReadTimeout) { onGraph(block) }
    } catch (e: TimeoutCancellationException) {
        throw GraphBusyException(
            "Graph '$name' stayed busy for more than ${settings.graphReadTimeout} (a sleep pass or a stalled embedder " +
                "holds it); go on without memory this time.",
        )
    }

    /** Runs [block] on the capture thread (extraction). */
    internal suspend fun <T> onCapture(block: () -> T): T = captureExecutor.await(block)

    /** Runs [block] on the narration thread (the sleep's model calls, scene narration). */
    internal suspend fun <T> onNarration(block: () -> T): T = narrationExecutor.await(block)

    /**
     * Runs [block] on this executor and suspends until it ends. On cancellation the task is cancelled without an
     * interrupt: one that hasn't started never runs, one that has runs to completion and its result is dropped. A task
     * [close] drops before it ran fails the caller with [IllegalStateException].
     */
    private suspend fun <T> ExecutorService.await(block: () -> T): T = suspendCancellableCoroutine { cont ->
        val task = object : FutureTask<T>(Callable(block)) {
            override fun done() {
                if (isCancelled) {
                    // Cancelled by the close; a caller that cancelled it itself is no longer active.
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Lattice '$name' was closed before the call ran."))
                    return
                }
                try {
                    cont.resumeWith(Result.success(get()))
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                }
            }
        }
        cont.invokeOnCancellation { task.cancel(false) }
        try {
            execute(task)
        } catch (e: RejectedExecutionException) {
            cont.resumeWithException(IllegalStateException("Lattice '$name' is closed; open the graph again to use it.", e))
        }
    }

    private fun singleThread(role: String): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lattice-$name-$role").apply { isDaemon = true } }

    /** Notes when the backend reports shutting down or an interrupted call; the helpers would swallow both. */
    private inner class WatchedModel(private val delegate: MemoryModel) : MemoryModel {
        override fun complete(task: MemoryTask, system: String, user: String): String = try {
            delegate.complete(task, system, user)
        } catch (e: Exception) {
            if (e.isMemoryModelShutdown() || e.isMemoryModelInterrupted()) modelStopped.set(true)
            throw e
        }
    }

    companion object {
        /** A [graphAccess] block's state: waiting on the graph thread, running there, or withdrawn by an interrupt. */
        private const val QUEUED = 0
        private const val STARTED = 1
        private const val WITHDRAWN = 2

        /** Salience of an episode node (a whole-conversation summary). */
        private const val EPISODE_SALIENCE = 0.6

        /** Cohesion floor and minimum size of a cluster the synthesis pass abstracts. */
        private const val SYNTH_THRESHOLD = 0.63
        private const val SYNTH_MIN_SIZE = 3

        /**
         * The graph's configuration for [settings]: the bge-m3 preset with the host's switches, and the prompt
         * scaffolding a scene narration must not echo (the transcript wrapper and the recall block's legends).
         */
        fun graphConfig(settings: LatticeSettings, texts: MemoryTexts): LatticeGraph.Config {
            val sceneEpisodes = settings.scenes.enabled && settings.scenes.sceneEpisodes
            return LatticeGraph.Config.bgeM3().copy(
                temporalLayer = settings.temporalLayer,
                temporalRetrieval = settings.temporalRetrieval,
                periodRecall = settings.periodRecall,
                temporalRerank = settings.temporalRerank,
                episodicBinding = settings.episodicBinding,
                entityExtraction = settings.entityExtraction,
                entityRecall = settings.entityRecall,
                entityCoEncodeGate = settings.entityCoEncodeGate,
                judgeBudget = settings.judgeBudget,
                // Keeping an old and a new value apart only helps when recall shows which was said later: the said
                // tags render with scene episodes and the temporal layer on.
                valueAwareFold = settings.valueAwareFold && sceneEpisodes && settings.temporalLayer,
                judgedMergesOnly = settings.judgedMergesOnly,
                scaffoldMarkers = scaffoldMarkers(texts, settings.language),
            )
        }

        /** The transcript wrapper's headers and the recall legends in [language], English and Chinese. */
        fun scaffoldMarkers(texts: MemoryTexts, language: Language): List<String> {
            val transcript = listOf(
                TranscriptRenderer.MESSAGE_CONTEXT_HEADER,
                TranscriptRenderer.USER_MESSAGE_HEADER,
                TranscriptRenderer.NEW_HEADER,
                TranscriptRenderer.CONTEXT_HEADER,
                TranscriptRenderer.CONTINUED_CONTEXT_HEADER,
            )
            val legends = linkedSetOf(language, Language.EN, Language.ZH).flatMap { lang ->
                val m = RecallMarkers.of(texts, lang)
                listOf(m.legendScene, m.legendThread, m.legendSceneAndThread, m.legendPeriod, m.legendDates)
            }
            return (transcript + legends).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
    }
}
