package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationInfo
import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.ConversationSource
import org.foedusprogramme.lattice.SceneSettings
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.foedusprogramme.lattice.Transcript
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/**
 * Extracts memories from one chunk of a scene: the host-facing capture, with scene membership. The scene pipeline
 * calls it on its capture thread.
 */
fun interface SceneChunkCapture {
    /**
     * Extracts memories from [transcript] and encodes them — as members of scene [sceneId] when it is set — and returns
     * how many were written or reinforced. Blocks; throws when the model can't extract: an
     * [UnparseableExtractionException] for a reply that did not parse (its dead letter recorded), a
     * [MemoryModelShutdownException] or [MemoryModelInterruptedException] when the call was cut off, anything else for
     * an outage.
     */
    fun capture(transcript: Transcript, sceneId: Long?): Int
}

/**
 * The scene pipeline: it reads the host's conversations back through [source], cuts them into scenes
 * ([SceneSegmenter]), extracts each scene in chunks that never cross a scene boundary ([capture], so every memory joins
 * its scene), and hands every scene whose messages are all extracted to narration, which writes its hub and its thread
 * changes in chat order ([SceneHubNarration]). Its state — each conversation's cursor and each scene's work state —
 * lives in the graph's store ([SceneCursorRecord], [SceneWorkRecord]).
 *
 * A conversation is segmented from its cursor on: everything up to [SceneCursorRecord.segmentedThrough] is in a scene
 * (or was extracted without one); what follows waits. A live conversation keeps the last
 * [SceneSettings.segmentLookaheadMessages] said messages back as the segmenter's lookahead, and segments once
 * [SceneSettings.segmentBatchMessages] more have gathered; its last scene stays OPEN while the segmenter says it runs
 * on, and the next pass continues it. A sealed conversation segments everything left and ends its last scene.
 *
 * Failures: a segmentation that fails counts an attempt on the cursor and leaves the messages for the catch-up (a live
 * step does not retry it); at [MAX_SEGMENT_ATTEMPTS] they fall back to plain chunks without a scene. A chunk that
 * fails counts an attempt on its scene and stops the scene's extraction there; at [MAX_CHUNK_ATTEMPTS] the chunk is
 * given up (the scene is incomplete) and the next one runs. A call the shutdown or an interrupt cut off counts nothing.
 *
 * Threads: segmentation, extraction and all bookkeeping run on [captureExecutor], narration on [narrationExecutor];
 * both must run their tasks one at a time, in order. Every graph access goes through [graph]. The public calls return
 * at once except [catchUp] and [awaitIdle].
 *
 * Scenes of different conversations of a chat are ordered by [SCENE_ORDER], never by position (positions are per
 * conversation) and never by id (ids are per device slot).
 */
class ScenePipeline(
    private val graph: GraphAccess,
    private val source: ConversationSource,
    model: MemoryModel,
    embedder: Embedder,
    private val capture: SceneChunkCapture,
    private val captureExecutor: Executor,
    narrationExecutor: Executor,
    private val settings: SceneSettings = SceneSettings(enabled = true),
    /** The zone of scene dates and of the times shown to the models. */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The human counterpart's name, for titles, synopses and narrations. */
    userName: String? = null,
    /** The assistant's names, which narrations turn into the first person. */
    selfAliases: Set<String> = emptySet(),
    trustPolicy: SceneTrustPolicy = SceneTrustPolicy.Conservative,
    deadLetter: MemoryDeadLetter = MemoryDeadLetter.NoOp,
    /**
     * Told, on the narration thread, when a chat's narration drain stops: every scene it could narrate in order is
     * done, or the next one is not ready. Period formation hooks in here. Must not throw (it is logged and ignored).
     */
    onChatDrained: (chatKey: String) -> Unit = {},
    private val chunkSize: Int = CHUNK_SIZE,
) {
    private val logger = LoggerFactory.getLogger(ScenePipeline::class.java)

    private val segmenter = SceneSegmenter(
        model, zone,
        windowMessages = settings.segmentWindowMessages,
        lookaheadMessages = settings.segmentLookaheadMessages,
        openTailMessages = settings.openTailMessages,
        maxBodyChars = settings.segmentBodyChars,
        deadLetter = deadLetter,
        selfAliases = selfAliases,
        userName = userName,
    )

    /** Whose content a scene's verdict trusts: for period formation, the catalogue and recall's period labels. */
    val trust: SceneTrust = SceneTrust(graph, source, trustPolicy)

    private val narration = SceneHubNarration(
        graph = graph,
        source = source,
        narrator = SceneNarrator(
            model, SceneNarrator.Mode.fromSetting(settings.narrationCheck), selfAliases = selfAliases, userName = userName,
        ),
        ledger = SceneThreadLedger(graph, embedder, SceneThreadBackfill(model)),
        embedder = embedder,
        zone = zone,
        executor = narrationExecutor,
        onChatDrained = onChatDrained,
    )

    private val onCaptureThread = ThreadLocal.withInitial { false }

    private enum class Mode {
        /** The host committed more messages: segment once enough have gathered. */
        LIVE,

        /** The conversation is sealed: segment everything left and end its last scene. */
        SEALED,

        /** The sleep's catch-up: segment whatever is pending, retry what failed. */
        CATCH_UP,
    }

    // --- the seam ------------------------------------------------------------------------------------------------

    /**
     * The host committed messages to [conversationId]: segment and extract what is settled, on the capture thread.
     * Returns at once. A conversation the source says is sealed is handled as by [captureSealed].
     */
    fun advance(conversationId: String) = submit("advance $conversationId") { process(conversationId, Mode.LIVE) }

    /**
     * [conversationId] was sealed (it takes no more messages): segment and extract everything left, end its last scene
     * and narrate it. Returns at once.
     */
    fun captureSealed(conversationId: String) = submit("seal $conversationId") { process(conversationId, Mode.SEALED) }

    /**
     * The sleep's CATCH_UP: on the capture thread (behind the live work already queued), segment every conversation
     * with pending messages — the ones the graph has a cursor for and the ones [ConversationSource.conversations] lists
     * for [ConversationSource.chats] — retry failed segmentations and chunks, heal scenes whose extraction settled, and
     * offer once more every scene that was waiting for narration. Blocks until the capture side is done (narration goes
     * on behind it; see [awaitIdle]). Returns how many messages were extracted.
     */
    fun catchUp(): Int = onCapture { catchUpNow() }

    /**
     * Waits until the work queued so far has run: everything on the capture thread (which may hand scenes to
     * narration), then everything on the narration thread. For callers that need settled scene state (tests, a sleep
     * before period formation); never call it from either thread.
     */
    fun awaitIdle() {
        check(!onCaptureThread.get() && !narration.onNarrationThread) { "awaitIdle must not be called from a scene thread" }
        onCapture { }
        narration.awaitIdle()
    }

    // --- capture-thread plumbing ---------------------------------------------------------------------------------

    private fun submit(what: String, task: () -> Unit) {
        try {
            captureExecutor.execute {
                onCaptureThread.set(true)
                try {
                    task()
                } catch (e: Exception) {
                    logger.warn("Scene work failed ({}): {}", what, e.message, e)
                } finally {
                    onCaptureThread.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            logger.info("Scene work not queued ({}): the capture thread is shut down", what)
        }
    }

    /** Runs [block] on the capture thread and waits for it; inline when already there. Rethrows its failure. */
    private fun <T> onCapture(block: () -> T): T {
        if (onCaptureThread.get()) return block()
        val task = FutureTask(
            Callable {
                onCaptureThread.set(true)
                try {
                    block()
                } finally {
                    onCaptureThread.set(false)
                }
            },
        )
        captureExecutor.execute(task)
        return try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: CancellationException) {
            // The shutdown dropped it before it ran; a CancellationException would read as the caller's own cancellation.
            throw IllegalStateException("The capture thread was shut down before the scene work ran.", e)
        }
    }

    // --- one conversation ----------------------------------------------------------------------------------------

    private class Pass(var messages: Int = 0)

    private fun process(conversationId: String, requested: Mode): Int {
        val info = source.conversation(conversationId) ?: run {
            logger.info("Scene pipeline: the host has no conversation {}; nothing to segment", conversationId)
            return 0
        }
        val mode = if (info.sealed && requested == Mode.LIVE) Mode.SEALED else requested
        var cursor = graph.withGraph { it.sceneCursor(conversationId) } ?: SceneCursorRecord(conversationId, info.chatKey)
        if (cursor.finished) return 0
        // A failed segmentation waits for the catch-up or the seal: retrying it at every turn would burn its attempts.
        if (mode == Mode.LIVE && (cursor.segmentAttempts > 0 || cursor.fallbackThrough != null)) return 0
        val pass = Pass()
        if (mode == Mode.SEALED) retryPendingScenes(conversationId, pass)
        val sealed = info.sealed || mode == Mode.SEALED
        var drained = false
        while (true) {
            if (cursor.fallbackThrough != null) {
                cursor = extractWithoutScene(info, cursor, pass)
                if (cursor.fallbackThrough != null) break
                continue
            }
            val after = cursor.segmentedThrough
            val batch = source.messages(conversationId, after?.plus(1) ?: Long.MIN_VALUE, limit = READ_BATCH)
            val more = batch.size >= READ_BATCH
            val said = batch.filter { it.said }
            if (said.isEmpty()) {
                if (!more) {
                    drained = true
                    break
                }
                cursor = cursor.copy(segmentedThrough = batch.last().position)
                graph.withGraph { it.putSceneState(cursor = cursor) }
                continue
            }
            val keepBack = if (sealed && !more) 0 else settings.segmentLookaheadMessages
            val run = said.dropLast(keepBack)
            val lookahead = said.takeLast(keepBack)
            val minimum = if (mode == Mode.LIVE) settings.segmentBatchMessages.coerceAtLeast(1) else 1
            if (run.size < minimum) break
            val outcome = segmentAndCommit(info, cursor, batch.first().position, run, lookahead, endLast = keepBack == 0, pass)
            cursor = outcome ?: break
            if (cursor.segmentAttempts > 0) break
            if (cursor.fallbackThrough != null) continue
            if (keepBack == 0) {
                drained = true
                break
            }
        }
        if (sealed && drained && cursor.segmentAttempts == 0 && cursor.fallbackThrough == null) finish(cursor)
        return pass.messages
    }

    /**
     * Segments [run] (said messages after the cursor, [lookahead] after it) and writes its scenes, then extracts them.
     * Returns the cursor after it — with a counted attempt when segmentation failed — or null when the shutdown cut the
     * call off (nothing counted). [rangeStart] is the first position read after the cursor: a new conversation's first
     * scene starts there, so host messages before the first said one belong to it.
     */
    private fun segmentAndCommit(
        info: ConversationInfo,
        cursor: SceneCursorRecord,
        rangeStart: Long,
        run: List<ConversationMessage>,
        lookahead: List<ConversationMessage>,
        endLast: Boolean,
        pass: Pass,
    ): SceneCursorRecord? {
        val open = openScene(info.id, cursor)
        val openInput = open?.let { (scene, work) ->
            SceneSegmenter.OpenScene(
                scene.title, work.synopsis,
                source.messages(info.id, scene.firstMessageId, scene.lastMessageId).filter { it.said }.takeLast(settings.openTailMessages),
            )
        }
        val scenes = try {
            segmentRange(run, lookahead, openInput)
        } catch (e: Exception) {
            if (e.isMemoryModelShutdown() || e.isMemoryModelInterrupted()) {
                logger.info("Scene segmentation cut off conversation={}; nothing counted", info.id)
                return null
            }
            val attempts = cursor.segmentAttempts + 1
            logger.warn("Scene segmentation failed conversation={} attempts={}: {}", info.id, attempts, e.message)
            // Past the cap the messages never become a scene, so they hold nothing back any more.
            val failed = if (attempts >= MAX_SEGMENT_ATTEMPTS) {
                cursor.copy(segmentAttempts = 0, fallbackThrough = run.last().position, blockedSince = null)
            } else {
                cursor.copy(segmentAttempts = attempts, blockedSince = run.first().at)
            }
            graph.withGraph { it.putSceneState(cursor = failed) }
            // Past the cap the messages after the OPEN scene get no scene, so nothing can continue it: it has ended.
            if (failed.fallbackThrough != null && open != null) endScene(open.first.sceneId)
            return failed
        }
        val planned = scenes.mapIndexed { i, s ->
            Planned(run.subList(s.start, s.end + 1), s.title, s.synopsis, ended = i < scenes.lastIndex || s.ended || endLast, s.continues)
        }
        val next = cursor.copy(segmentedThrough = run.last().position, segmentAttempts = 0, blockedSince = null)
        val touched = commit(info, cursor, rangeStart, planned, open?.first, next)
        if (open != null && planned.first().continues.not()) reevaluate(open.first.sceneId)
        for (sceneId in touched) {
            extractScene(sceneId, pass)
            reevaluate(sceneId)
        }
        return next
    }

    private class Planned(
        val messages: List<ConversationMessage>,
        val title: String?,
        val synopsis: String?,
        val ended: Boolean,
        val continues: Boolean,
    )

    /**
     * Writes the planned scenes and the conversation's [next] cursor; returns the ids of the scenes written, in order.
     * The OPEN scene [open] is continued by a first scene that says so; otherwise it has ended.
     */
    private fun commit(
        info: ConversationInfo,
        cursor: SceneCursorRecord,
        rangeStart: Long,
        planned: List<Planned>,
        open: SceneRecord?,
        next: SceneCursorRecord,
    ): List<Long> = graph.withGraph { g ->
        val touched = ArrayList<Long>()
        if (open != null && !planned.first().continues) endSceneOn(g, open.sceneId)
        var start = cursor.segmentedThrough?.plus(1) ?: rangeStart
        planned.forEachIndexed { i, p ->
            val cursorWrite = if (i == planned.lastIndex) next else null
            val first = p.messages.first()
            val last = p.messages.last()
            val state = if (p.ended) SceneWorkState.EXTRACTING else SceneWorkState.OPEN
            val continued = open?.takeIf { p.continues && i == 0 }?.let { g.scene(it.sceneId) }
            if (continued != null) {
                val work = g.sceneWork(continued.sceneId) ?: SceneWorkRecord(continued.sceneId, SceneWorkState.OPEN)
                g.putSceneState(
                    meta(g, continued.copy(lastMessageId = last.position, dateTo = localDate(last.at), inProgress = !p.ended)),
                    work.copy(state = state),
                    cursorWrite,
                )
                touched += continued.sceneId
                logger.info("Scene continued: scene={} through={} ended={} conversation={}", continued.sceneId, last.position, p.ended, info.id)
            } else {
                val id = g.allocateSceneId()
                val scene = SceneRecord(
                    sceneId = id,
                    title = p.title.orEmpty(),
                    conversationId = info.id,
                    chatKey = info.chatKey,
                    firstMessageId = minOf(start, first.position),
                    lastMessageId = last.position,
                    dateFrom = localDate(first.at),
                    dateTo = localDate(last.at),
                    inProgress = !p.ended,
                    startedAt = first.at,
                    seq = g.nextSceneSeq(info.chatKey),
                )
                g.putSceneState(scene, SceneWorkRecord(id, state, p.synopsis), cursorWrite)
                touched += id
                logger.info(
                    "Scene created: scene={} messages={}..{} ended={} title={} conversation={}",
                    id, scene.firstMessageId, scene.lastMessageId, p.ended, scene.title, info.id,
                )
            }
            start = last.position + 1
        }
        touched
    }

    /**
     * [scene] as written over the stored row: the hub stays (only narration sets it), and so does the in-progress flag
     * while that hub is an in-progress one — it covers only part of the scene until a full narration replaces it, so a
     * scene abandoned after it never shows the partial narration as the whole.
     */
    private fun meta(g: LatticeGraph, scene: SceneRecord): SceneRecord {
        val existing = g.scene(scene.sceneId) ?: return scene
        val hub = scene.hubEngramId ?: existing.hubEngramId
        val partialHub = scene.hubEngramId == null && existing.hubEngramId != null && existing.inProgress
        return scene.copy(hubEngramId = hub, inProgress = scene.inProgress || partialHub)
    }

    /** The conversation's OPEN scene that ends at the cursor (the one a segmentation may continue), with its work. */
    private fun openScene(conversationId: String, cursor: SceneCursorRecord): Pair<SceneRecord, SceneWorkRecord>? {
        val (open, orphans) = graph.withGraph { g ->
            val open = g.scenes(cursor.chatKey).filter {
                it.conversationId == conversationId && g.sceneWork(it.sceneId)?.state == SceneWorkState.OPEN
            }
            val current = open.firstOrNull { it.lastMessageId == cursor.segmentedThrough }
            current?.let { it to g.sceneWork(it.sceneId)!! } to open.filter { it != current }
        }
        // Any other OPEN scene of the conversation lies before the cursor: nothing can continue it any more, and while
        // it stays OPEN it holds back the narration of every later scene of the chat.
        for (orphan in orphans) {
            logger.info("Orphaned OPEN scene ended: scene={} conversation={}", orphan.sceneId, conversationId)
            endScene(orphan.sceneId)
        }
        return open
    }

    /** OPEN → EXTRACTING (the scene has ended), then re-evaluated so it is narrated once extracted. */
    private fun endScene(sceneId: Long) {
        graph.withGraph { g -> endSceneOn(g, sceneId) }
        reevaluate(sceneId)
    }

    private fun endSceneOn(g: LatticeGraph, sceneId: Long) {
        val work = g.sceneWork(sceneId)?.takeIf { it.state == SceneWorkState.OPEN } ?: return
        val scene = g.scene(sceneId) ?: return
        g.putSceneState(meta(g, scene.copy(inProgress = false)), work.copy(state = SceneWorkState.EXTRACTING))
    }

    /** Sealed and wholly segmented: end what is still OPEN and never read the conversation again. */
    private fun finish(cursor: SceneCursorRecord) {
        val open = graph.withGraph { g ->
            g.scenes(cursor.chatKey)
                .filter { it.conversationId == cursor.conversationId && g.sceneWork(it.sceneId)?.state == SceneWorkState.OPEN }
                .map { it.sceneId }
        }
        for (sceneId in open) {
            logger.info("Scene ended by the seal: scene={} conversation={}", sceneId, cursor.conversationId)
            endScene(sceneId)
        }
        graph.withGraph { it.putSceneState(cursor = cursor.copy(finished = true)) }
    }

    // --- extraction --------------------------------------------------------------------------------------------------

    /** Scenes of [conversationId] (null = of every conversation) whose extraction stopped at a failed chunk: try again. */
    private fun retryPendingScenes(conversationId: String?, pass: Pass) {
        val pending = graph.withGraph { g ->
            g.scenes().filter { s ->
                val work = g.sceneWork(s.sceneId)
                (conversationId == null || s.conversationId == conversationId) && work != null && pendingExtraction(s, work)
            }.sortedWith(SCENE_ORDER).map { it.sceneId }
        }
        for (sceneId in pending) {
            extractScene(sceneId, pass)
            reevaluate(sceneId)
        }
    }

    private fun pendingExtraction(scene: SceneRecord, work: SceneWorkRecord): Boolean =
        (work.state == SceneWorkState.OPEN || work.state == SceneWorkState.EXTRACTING) &&
            (work.extractedThrough ?: Long.MIN_VALUE) < scene.lastMessageId

    /**
     * Extracts the scene's messages after [SceneWorkRecord.extractedThrough] in chunks of at most [chunkSize] said
     * messages; stops at a chunk that fails short of its cap (the next pass retries it).
     */
    private fun extractScene(sceneId: Long, pass: Pass) {
        val (scene, start) = graph.withGraph { g ->
            val scene = g.scene(sceneId)
            val work = g.sceneWork(sceneId)
            if (scene == null || work == null || !pendingExtraction(scene, work)) null to null
            else scene to (work.extractedThrough ?: (scene.firstMessageId - 1)) + 1
        }
        if (scene == null || start == null) return
        val messages = source.messages(scene.conversationId, start, scene.lastMessageId)
        val chunks = messages.filter { it.said }.chunked(chunkSize)
        var previousEnd = start - 1
        for ((i, chunk) in chunks.withIndex()) {
            // The last chunk settles the whole range, trailing host and tool messages included.
            val end = if (i == chunks.lastIndex) scene.lastMessageId else chunk.last().position
            val from = chunk.first().position
            // A chunk is tainted by anything untrusted since the previous one: a tool result between two said messages.
            val transcript = Transcript(
                chunk.map { it.transcriptLine() },
                Transcript.Source(scene.chatKey, scene.conversationId),
                tainted = messages.any { it.tainted && it.position > previousEnd && it.position <= end },
            )
            previousEnd = end
            val outcome = runCatching { capture.capture(transcript, sceneId) }
            val failure = outcome.exceptionOrNull()
            if (failure != null && (failure.isMemoryModelShutdown() || failure.isMemoryModelInterrupted())) {
                logger.info("Scene chunk extraction cut off scene={} messages={}..{}; nothing counted", sceneId, from, end)
                return
            }
            val settled = settle { g ->
                val work = g.sceneWork(sceneId) ?: return@settle false
                val next = when {
                    failure == null -> work.copy(extractedThrough = end, extractAttempts = 0, chunksDone = work.chunksDone + 1)
                    work.extractAttempts + 1 >= MAX_CHUNK_ATTEMPTS ->
                        work.copy(extractedThrough = end, extractAttempts = 0, chunksFailed = work.chunksFailed + 1)
                    else -> work.copy(extractAttempts = work.extractAttempts + 1)
                }
                g.putSceneState(work = next)
                next.extractedThrough == end
            }
            if (failure == null) {
                pass.messages += chunk.size
            } else if (settled) {
                logger.warn("Scene chunk given up after {} attempts scene={} messages={}..{}: {}", MAX_CHUNK_ATTEMPTS, sceneId, from, end, failure.message)
            } else {
                logger.warn("Scene chunk failed scene={} messages={}..{}: {}", sceneId, from, end, failure.message)
                return
            }
        }
        if (chunks.isEmpty()) {
            graph.withGraph { g -> g.sceneWork(sceneId)?.let { g.putSceneState(work = it.copy(extractedThrough = scene.lastMessageId)) } }
        }
    }

    /**
     * Past the segmentation cap: the messages up to [SceneCursorRecord.fallbackThrough] are extracted in plain chunks
     * without a scene. Only a reply that did not parse counts an attempt here (an outage must not burn them); at the cap
     * the chunk is given up. Returns the cursor after it: the fallback cleared once done.
     */
    private fun extractWithoutScene(info: ConversationInfo, start: SceneCursorRecord, pass: Pass): SceneCursorRecord {
        var cursor = start
        val through = cursor.fallbackThrough ?: return cursor
        val messages = source.messages(info.id, cursor.segmentedThrough?.plus(1) ?: Long.MIN_VALUE, through)
        val chunks = messages.filter { it.said }.chunked(chunkSize)
        for ((i, chunk) in chunks.withIndex()) {
            val end = if (i == chunks.lastIndex) through else chunk.last().position
            val transcript = Transcript(
                chunk.map { it.transcriptLine() },
                Transcript.Source(info.chatKey, info.id),
                tainted = messages.any { it.tainted && it.position <= end && it.position > (cursor.segmentedThrough ?: Long.MIN_VALUE) },
            )
            val failure = runCatching { capture.capture(transcript, null) }.exceptionOrNull()
            cursor = when {
                failure == null -> cursor.copy(segmentedThrough = end, extractAttempts = 0).also { pass.messages += chunk.size }
                failure.isMemoryModelShutdown() || failure.isMemoryModelInterrupted() -> return cursor
                failure !is UnparseableExtractionException -> {
                    logger.warn("Plain chunk failed conversation={} (not counted): {}", info.id, failure.message)
                    return cursor
                }
                cursor.extractAttempts + 1 >= MAX_CHUNK_ATTEMPTS -> {
                    logger.warn("Plain chunk given up conversation={} messages={}..{}", info.id, chunk.first().position, end)
                    cursor.copy(segmentedThrough = end, extractAttempts = 0)
                }
                else -> cursor.copy(extractAttempts = cursor.extractAttempts + 1).also {
                    graph.withGraph { g -> g.putSceneState(cursor = it) }
                    return it
                }
            }
            settle { g -> g.putSceneState(cursor = cursor) }
        }
        cursor = cursor.copy(segmentedThrough = through, fallbackThrough = null, extractAttempts = 0, blockedSince = null)
        graph.withGraph { g -> g.putSceneState(cursor = cursor) }
        logger.info("Messages extracted without a scene after failed segmentations conversation={} through={}", info.id, through)
        return cursor
    }

    /**
     * [GraphAccess.withGraph] with a pending interrupt held off, then set again: the bookkeeping of a chunk whose
     * memories landed must land too, or the chunk is extracted once more after a restart.
     */
    private fun <T> settle(block: (LatticeGraph) -> T): T {
        val interrupted = Thread.interrupted()
        try {
            return graph.withGraph(block)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    // --- hand-over to narration ----------------------------------------------------------------------------------

    /**
     * Once none of a scene's messages is pending: an ended scene moves EXTRACTING → NARRATING and is handed to
     * narration; an OPEN one is handed over as it is (for an in-progress hub). The capture thread never waits for it.
     */
    private fun reevaluate(sceneId: Long) {
        val ready = graph.withGraph { g ->
            val scene = g.scene(sceneId) ?: return@withGraph false
            val work = g.sceneWork(sceneId) ?: return@withGraph false
            if (pendingExtraction(scene, work)) return@withGraph false
            if (work.state == SceneWorkState.EXTRACTING) g.putSceneState(work = work.copy(state = SceneWorkState.NARRATING))
            work.state == SceneWorkState.EXTRACTING || work.state == SceneWorkState.NARRATING || work.state == SceneWorkState.OPEN
        }
        if (ready) narration.offer(sceneId)
    }

    // --- catch-up ----------------------------------------------------------------------------------------------------

    private fun catchUpNow(): Int {
        // Scenes already waiting on narration are offered once more at the end; one that becomes ready during the pass
        // is offered by its own hand-over.
        val waiting = graph.withGraph { g -> g.scenes().filter { g.sceneWork(it.sceneId)?.state == SceneWorkState.NARRATING }.map { it.sceneId } }
        val cursors = graph.withGraph { g -> g.sceneCursors().associateBy { it.conversationId } }
        val conversations = LinkedHashSet<String>()
        cursors.values.filter { !it.finished }.mapTo(conversations) { it.conversationId }
        val chats = runCatching { source.chats() }
            .onFailure { logger.warn("Scene catch-up: the host's chats are unreadable: {}", it.message) }
            .getOrDefault(emptyList())
        for (chat in chats) {
            val infos = runCatching { source.conversations(chat) }
                .onFailure { logger.warn("Scene catch-up: the conversations of chat={} are unreadable: {}", chat, it.message) }
                .getOrDefault(emptyList())
            // A conversation with a cursor is in already (unless finished); an empty live one has nothing to read.
            for (info in infos) {
                if (info.id !in cursors && (info.lastPosition != null || info.sealed)) conversations += info.id
            }
        }
        // Chunks that failed short of their cap, in every conversation (a sealed one is finished but may have some).
        val retried = Pass()
        runCatching { retryPendingScenes(null, retried) }
            .onFailure { logger.warn("Scene catch-up: retrying failed chunks failed: {}", it.message, it) }
        var messages = retried.messages
        for (conversationId in conversations) {
            messages += runCatching { process(conversationId, Mode.CATCH_UP) }
                .onFailure { logger.warn("Scene catch-up of conversation={} failed: {}", conversationId, it.message, it) }
                .getOrDefault(0)
        }
        // Heal scenes whose extraction settled without a hand-over, then re-offer the ones that were waiting.
        val settled = graph.withGraph { g -> g.scenes().filter { g.sceneWork(it.sceneId)?.state == SceneWorkState.EXTRACTING }.map { it.sceneId } }
        settled.forEach(::reevaluate)
        for (sceneId in waiting) {
            if (graph.withGraph { it.sceneWork(sceneId)?.state } == SceneWorkState.NARRATING) narration.offer(sceneId)
        }
        logger.info("Scene catch-up: {} conversation(s), {} message(s) extracted", conversations.size, messages)
        return messages
    }

    // --- segmentation ------------------------------------------------------------------------------------------------

    /** A segmented scene as an inclusive index range into the segmented list. */
    private data class RangeScene(
        val start: Int,
        val end: Int,
        val title: String?,
        val synopsis: String?,
        val ended: Boolean,
        val continues: Boolean,
    )

    /**
     * Segment [messages] in windows of [SceneSettings.segmentWindowMessages]; each later window gets the previous
     * window's last scene as its open scene and merges into it when the model says it continues. Throws on any failure.
     */
    private fun segmentRange(
        messages: List<ConversationMessage>,
        lookahead: List<ConversationMessage>,
        open: SceneSegmenter.OpenScene?,
    ): List<RangeScene> {
        val position = messages.withIndex().associate { (i, m) -> m.position to i }
        val out = ArrayList<RangeScene>()
        var openInput = open
        val windows = messages.chunked(settings.segmentWindowMessages.coerceAtLeast(1))
        var offset = 0
        windows.forEachIndexed { w, window ->
            val la = if (w == windows.lastIndex) lookahead else messages.subList(offset + window.size, messages.size).take(settings.segmentLookaheadMessages)
            val result = segmenter.segment(window, la, openInput)
            val scenes = result.scenes.map { s ->
                RangeScene(position.getValue(s.startId), position.getValue(s.endId), s.title, s.synopsis, s.ended, false)
            }
            var rest = scenes
            if (w == 0) {
                if (result.continuesOpenScene) rest = listOf(scenes.first().copy(continues = true)) + scenes.drop(1)
            } else if (result.continuesOpenScene && out.isNotEmpty()) {
                val prev = out.removeAt(out.lastIndex)
                out += prev.copy(end = scenes.first().end, ended = scenes.first().ended)
                rest = scenes.drop(1)
            }
            out += rest
            val last = out.last()
            openInput = SceneSegmenter.OpenScene(
                last.title,
                last.synopsis,
                messages.subList(last.start, last.end + 1).takeLast(settings.openTailMessages),
            )
            offset += window.size
        }
        return out
    }

    private fun localDate(at: Instant): String = LOCAL_DATE.format(at.atZone(zone))

    companion object {
        /** Said messages per extraction call: the extractor's chunk size. */
        const val CHUNK_SIZE = TranscriptRenderer.CHUNK_LINES

        /** Attempts per chunk before it is given up and its scene is incomplete. */
        const val MAX_CHUNK_ATTEMPTS = 3

        /** Segmentation attempts before the messages fall back to plain chunks without a scene. */
        const val MAX_SEGMENT_ATTEMPTS = 3

        /** Messages read from the host per step; a longer backlog is segmented in several. */
        const val READ_BATCH = 500

        private val LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
