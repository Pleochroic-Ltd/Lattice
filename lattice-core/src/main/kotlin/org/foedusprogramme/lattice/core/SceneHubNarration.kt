package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationSource
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/**
 * Scene narration: narrates scenes in chat order and materializes their hubs. The capture side hands every ready scene
 * over ([offer]); the work runs on [executor], one task at a time for every chat, so all narration — its self-check,
 * the thread ledger and its backfill — serializes there without holding the capture thread. It only waits on the graph
 * thread, and embeds the narration before its graph call. [sceneReady] is also `@Synchronized`, so a caller driving it
 * directly cannot interleave with the thread.
 *
 * Because extraction runs on another thread, a scene can change under a narration (an OPEN scene continued or ended
 * while its in-progress hub is being written): every state move is guarded (it acts only from the state it read) and
 * the scene is re-read before narrating, so the worst case is a hub re-narrated once more when the scene is offered
 * again.
 *
 * Per offered scene:
 *  - NARRATING: narrated only when every EARLIER scene of the chat ([SCENE_ORDER]) is MATERIALIZED or ABANDONED, and no
 *    conversation of the chat holds messages before it that wait on a failed segmentation
 *    ([SceneCursorRecord.blockedSince]: they may still become an earlier scene). Success → hub written, ledger changes
 *    applied, MATERIALIZED. Failure → `narrateAttempts + 1`, stays NARRATING (the next catch-up offers it again); at
 *    [MAX_NARRATE_ATTEMPTS] → ABANDONED. No member memories → ABANDONED at once.
 *    A failed attempt whose reply was still parseable (a gate miss, a self-check veto, the graph gate refusing the
 *    hub) keeps its ledger changes: the latest such changes are applied — once, with no hub — when the scene is
 *    ABANDONED, so a thread the scene closed is not lost with its narration. They are held back until then (not
 *    applied on the first valid parse) so the retries neither repeat the ledger's backfill model calls nor mix
 *    changes from two differently-worded attempts; a later success applies its own changes instead. The held changes
 *    live in memory: after a restart only the final attempt's reply counts.
 *  - OPEN (a scene still growing): same ordering rule; an in-progress hub is written (or updated) and the scene stays
 *    OPEN. A failure is not counted — the scene is narrated again when it continues or ends. An OPEN scene offered
 *    again with the same range and members is skipped.
 *  - anything else: ignored (an idempotent re-offer).
 * Whenever a scene finishes (MATERIALIZED or ABANDONED) the chat is drained: the next scenes in order that are
 * NARRATING (or OPEN) are narrated right away, until one is not ready or fails, so a scene held back by the ordering
 * rule does not wait for the next sleep. When the drain stops, [onChatDrained] is told — inline, in the same task.
 * A scene the pipeline did not write (no [SceneWorkRecord]) counts as done. A scene whose conversation this host does not
 * have (another device's, in a shared graph) is that device's to narrate: it is never narrated here, and holds the
 * chat's later scenes back like any scene not yet done.
 */
internal class SceneHubNarration(
    private val graph: GraphAccess,
    private val source: ConversationSource,
    private val narrator: SceneNarrator,
    private val ledger: SceneThreadLedger,
    private val embedder: Embedder,
    /** The zone the transcript excerpt's message times are shown in. */
    private val zone: ZoneId,
    private val executor: Executor,
    private val onChatDrained: (String) -> Unit = {},
    /** Per-message body cap in the narrator's transcript excerpt. */
    private val transcriptBodyChars: Int = TRANSCRIPT_BODY_CHARS,
) {
    private val logger = LoggerFactory.getLogger(SceneHubNarration::class.java)

    /** OPEN scenes already narrated in progress: scene id → (last message id, member count) at that time. */
    private val openNarrated = HashMap<Long, Pair<Long, Int>>()

    /** NARRATING scenes: the ledger changes of the latest failed-but-parseable attempt, applied if abandoned. */
    private val failedLedgers = HashMap<Long, SceneNarrator.LedgerChanges>()

    private val onThread = ThreadLocal.withInitial { false }

    val onNarrationThread: Boolean get() = onThread.get()

    private enum class Outcome { FINISHED, WAITING }

    /** Queues [sceneId] for narration; returns at once. */
    fun offer(sceneId: Long) {
        try {
            executor.execute {
                onThread.set(true)
                try {
                    sceneReady(sceneId)
                } catch (e: Exception) {
                    logger.warn("Scene narration failed scene={}: {}", sceneId, e.message, e)
                } finally {
                    onThread.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            logger.info("Scene narration not queued scene={}: the narration thread is shut down", sceneId)
        }
    }

    /** Waits until the narration queued so far has run. */
    fun awaitIdle() {
        val barrier = FutureTask(Callable { })
        executor.execute(barrier)
        try {
            barrier.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: CancellationException) {
            // The shutdown dropped it before it ran; a CancellationException would read as the caller's own cancellation.
            throw IllegalStateException("The narration thread was shut down before the queued narration ran.", e)
        }
    }

    private class Current(val scene: SceneRecord, val work: SceneWorkRecord)

    private fun current(sceneId: Long): Current? = graph.withGraph { g ->
        val scene = g.scene(sceneId) ?: return@withGraph null
        val work = g.sceneWork(sceneId) ?: return@withGraph null
        Current(scene, work)
    }

    private fun Current.narratable(): Boolean = work.state == SceneWorkState.NARRATING || work.state == SceneWorkState.OPEN

    @Synchronized
    fun sceneReady(sceneId: Long) {
        val current = current(sceneId)?.takeIf { it.narratable() } ?: return
        if (!hosted(current.scene)) return
        if (!earlierScenesDone(current.scene)) {
            logger.debug("Scene narration held back by an earlier scene: scene={} chat={}", sceneId, current.scene.chatKey)
            return
        }
        if (narrate(current) == Outcome.FINISHED) drain(current.scene.chatKey)
    }

    /** Narrate the chat's next ready scenes in order until one is not ready, stays OPEN, or fails; then tell the sink. */
    private fun drain(chatKey: String) {
        try {
            narrateReady(chatKey)
        } finally {
            runCatching { onChatDrained(chatKey) }
                .onFailure { logger.warn("Scene drain hook failed chat={}: {}", chatKey, it.message, it) }
        }
    }

    private fun narrateReady(chatKey: String) {
        val visited = HashSet<Long>()
        while (true) {
            val next = graph.withGraph { g ->
                g.scenes(chatKey).firstNotNullOfOrNull { s ->
                    g.sceneWork(s.sceneId)?.takeIf { !it.state.done }?.let { Current(s, it) }
                }
            } ?: return
            if (!next.narratable() || !hosted(next.scene)) return
            if (blockedBefore(next.scene)) return
            if (!visited.add(next.scene.sceneId)) return // its state did not move (a concurrent change): stop, never spin
            if (narrate(next) != Outcome.FINISHED) return
        }
    }

    /** Whether this host has the scene's conversation; a read that fails says no (the scene is offered again). */
    private fun hosted(scene: SceneRecord): Boolean =
        runCatching { source.conversation(scene.conversationId) != null }.getOrDefault(false).also {
            if (!it) logger.debug("Scene narration left to the device that has its conversation: scene={}", scene.sceneId)
        }

    private val SceneWorkState.done: Boolean get() = this == SceneWorkState.MATERIALIZED || this == SceneWorkState.ABANDONED

    private fun earlierScenesDone(scene: SceneRecord): Boolean {
        val earlierDone = graph.withGraph { g ->
            g.scenes(scene.chatKey)
                .takeWhile { it.sceneId != scene.sceneId }
                .all { g.sceneWork(it.sceneId)?.state?.done ?: true }
        }
        return earlierDone && !blockedBefore(scene)
    }

    /** Messages of the scene's chat said before it still wait on a failed segmentation: they may become earlier scenes. */
    private fun blockedBefore(scene: SceneRecord): Boolean {
        val started = scene.startedAt ?: return false
        return graph.withGraph { g ->
            g.sceneCursors().any { it.chatKey == scene.chatKey && it.blockedSince?.isBefore(started) == true }
        }
    }

    private class Member(val id: Long, val content: String)

    private fun narrate(current: Current): Outcome {
        val scene = current.scene
        val inProgress = current.work.state == SceneWorkState.OPEN
        val members = graph.withGraph { g ->
            g.sceneMembers(scene.sceneId)
                .mapNotNull { id -> g.engram(id)?.takeIf { !it.isEpisodeHub } }
                .sortedWith(compareBy<Engram> { it.createdAt }.thenBy { it.id })
                .map { Member(it.id, it.content) }
        }
        if (members.isEmpty()) {
            if (inProgress) return Outcome.WAITING
            logger.info("Scene has no member memories; abandoned: scene={} title={}", scene.sceneId, scene.title)
            return abandon(scene)
        }
        if (inProgress && openNarrated[scene.sceneId] == (scene.lastMessageId to members.size)) return Outcome.WAITING

        val messages = source.messages(scene.conversationId, scene.firstMessageId, scene.lastMessageId)
        val neighbours = graph.withGraph { g -> g.scenes(scene.chatKey) }
        val index = neighbours.indexOfFirst { it.sceneId == scene.sceneId }
        val memberIds = members.mapTo(LinkedHashSet()) { it.id }
        val input = SceneNarrator.Input(
            title = scene.title,
            synopsis = current.work.synopsis,
            members = members.mapIndexed { i, m -> SceneNarrator.Member(i + 1, m.id, m.content) },
            transcript = messages.filter { it.said }.map { TranscriptRenderer.line(it.transcriptLine(), zone, transcriptBodyChars) },
            dateFrom = scene.dateFrom,
            dateTo = scene.dateTo,
            inProgress = inProgress,
            candidateThreads = runCatching { ledger.candidates(scene, memberIds) }
                .onFailure { logger.warn("Thread candidate selection failed scene={}: {}", scene.sceneId, it.message) }
                .getOrDefault(emptyList()),
            previousTitle = neighbours.getOrNull(index - 1)?.title?.takeIf { index > 0 },
            nextTitle = neighbours.getOrNull(index + 1)?.title?.takeIf { index >= 0 },
            observed = SceneNarrator.observedSpeakers(messages),
        )
        val result = narrator.narrate(input)
        val hubId = when (result) {
            is SceneNarrator.Result.Failure -> null.also {
                if (result.reason == SceneNarrator.Reason.SHUTDOWN) {
                    logger.info("Scene narration cut off scene={}; nothing counted", scene.sceneId)
                } else {
                    logger.warn("Scene narration failed scene={} reason={}: {}", scene.sceneId, result.reason, result.detail)
                }
            }
            is SceneNarrator.Result.Success -> {
                // Embed on this thread, not inside the graph call: the graph thread serves every live recall, and an
                // embedder round-trip there would hold them all up. A narration the graph gate refuses on length alone
                // is not worth an embedding.
                val text = result.narration.trim()
                val vector = if (EpisodeNarrator.withinNarrationBand(text)) embedder.embed(text) else null
                // Messages that do not read back vouch for nothing: the hub is tainted.
                val tainted = messages.isEmpty() || messages.any { it.tainted }
                graph.withGraph { g ->
                    val record = (g.scene(scene.sceneId) ?: scene).copy(inProgress = inProgress)
                    if (vector != null) {
                        g.materializeScene(record, result.narration, vector, memberIds, tainted = tainted)
                    } else {
                        g.materializeScene(record, result.narration, memberIds, tainted = tainted)
                    }
                }.takeIf { it != LatticeGraph.REFUSED }
                    ?: null.also { logger.warn("Scene hub refused by the graph gate scene={}", scene.sceneId) }
            }
        }
        if (hubId == null) {
            if (inProgress) return Outcome.WAITING
            // Cut off by the shutdown: no attempt — the scene stays NARRATING and the catch-up after the restart offers it
            // again (a kill must never push it toward ABANDONED).
            if ((result as? SceneNarrator.Result.Failure)?.reason == SceneNarrator.Reason.SHUTDOWN) return Outcome.WAITING
            val parsed = when (result) {
                is SceneNarrator.Result.Failure -> result.ledger
                is SceneNarrator.Result.Success -> result.ledger
            }
            if (parsed != null) failedLedgers[scene.sceneId] = parsed
            return countFailure(scene)
        }

        failedLedgers.remove(scene.sceneId)
        val changes = (result as SceneNarrator.Result.Success).ledger
        runCatching { ledger.apply(scene, hubId, changes, inProgress) }
            .onFailure { logger.warn("Thread ledger update failed scene={}: {}", scene.sceneId, it.message, it) }
        if (inProgress) {
            openNarrated[scene.sceneId] = scene.lastMessageId to members.size
            logger.info("Scene hub written in progress: scene={} hub={} members={}", scene.sceneId, hubId, members.size)
            return Outcome.WAITING
        }
        openNarrated.remove(scene.sceneId)
        moveState(scene.sceneId, from = SceneWorkState.NARRATING) { it.copy(state = SceneWorkState.MATERIALIZED) }
        logger.info("Scene hub materialized: scene={} hub={} members={}", scene.sceneId, hubId, members.size)
        return Outcome.FINISHED
    }

    private fun countFailure(scene: SceneRecord): Outcome {
        val attempts = moveState(scene.sceneId, from = SceneWorkState.NARRATING) { it.copy(narrateAttempts = it.narrateAttempts + 1) }
            ?.narrateAttempts ?: return Outcome.WAITING
        if (attempts < MAX_NARRATE_ATTEMPTS) return Outcome.WAITING
        logger.warn("Scene narration failed {} times; abandoned: scene={} title={}", attempts, scene.sceneId, scene.title)
        val changes = failedLedgers.remove(scene.sceneId)
        if (changes != null && !changes.isEmpty) {
            logger.info(
                "Abandoned scene's thread changes applied without a hub: scene={} opened={} advanced={} closed={} dropped={}",
                scene.sceneId, changes.opened.size, changes.advanced.size, changes.closed.size, changes.dropped.size,
            )
            runCatching { ledger.apply(scene, null, changes, false) }
                .onFailure { logger.warn("Thread ledger update failed scene={}: {}", scene.sceneId, it.message, it) }
        }
        return abandon(scene)
    }

    /**
     * An abandoned scene keeps whatever hub it has: an in-progress hub written while it was OPEN stays marked in progress
     * (see [ScenePipeline]'s meta rule), so recall never shows that partial narration as the whole scene.
     */
    private fun abandon(scene: SceneRecord): Outcome {
        moveState(scene.sceneId, from = SceneWorkState.NARRATING) { it.copy(state = SceneWorkState.ABANDONED) }
        openNarrated.remove(scene.sceneId)
        failedLedgers.remove(scene.sceneId)
        return Outcome.FINISHED
    }

    /** Writes [change] of the scene's work state when it is still [from]; returns the new state, or null. */
    private fun moveState(sceneId: Long, from: SceneWorkState, change: (SceneWorkRecord) -> SceneWorkRecord): SceneWorkRecord? =
        graph.withGraph { g ->
            val work = g.sceneWork(sceneId)?.takeIf { it.state == from } ?: return@withGraph null
            change(work).also { g.putSceneState(work = it) }
        }

    companion object {
        /** Narration attempts before a scene is ABANDONED and later scenes go on. */
        const val MAX_NARRATE_ATTEMPTS = 3

        const val TRANSCRIPT_BODY_CHARS = 300
    }
}
