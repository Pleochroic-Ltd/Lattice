package org.foedusprogramme.lattice.core

import org.slf4j.LoggerFactory

/**
 * The thread ledger: which story threads — a set-up awaiting its pay-off — a chat has open, which memories set them up
 * and which resolved them. Threads belong to a chat, not to a conversation, so a thread opened before a new
 * conversation can still be closed after it.
 *
 * Two roles, both called by scene narration on the narration thread; every graph read and write goes through
 * [graph] (the graph's owner thread):
 *  - [candidates]: the chat's OPEN and STALE threads shown to the narrator for a scene — the [RELEVANT_CANDIDATES] with
 *    the highest max-cosine between a thread's SETUP memories and the scene's member memories, then the
 *    [RECENT_CANDIDATES] most recent of the rest (at most 20). Staleness is counted here, once per scene: an OPEN
 *    thread not chosen gains one on its counter and turns STALE (logged) after [STALE_AFTER_SCENES] scenes in a row;
 *    being chosen resets the counter. A STALE thread stays a candidate (relevance can still pick it) and returns to
 *    OPEN when advanced, or CLOSED when closed.
 *  - [apply]: applies a scene's [SceneNarrator.LedgerChanges]. opened → a new OPEN thread with SETUP links; advanced →
 *    more SETUP links (and back to OPEN); closed → CLOSED with RESOLVE links plus a RESOLVES marker edge between each
 *    RESOLVE and each SETUP where the pair has no edge yet ([LatticeGraph.markResolves]); dropped → DROPPED. Each item
 *    stands alone: an unknown thread id, another chat's thread, or a thread whose memories are all unknown is skipped
 *    (logged) and the rest applies. A close with no known RESOLVE memory is rejected (the thread stays as it was), and
 *    at most [SceneNarrator.MAX_THREAD_REFS] memories per opened or advanced thread are linked per scene. Applying the
 *    same changes again is a no-op — an in-progress scene is narrated (and its changes offered) again when it
 *    continues or ends; a thread "opened" again by the same scene with the same description (or the same short name)
 *    is the same thread; it keeps its first name, and one opened without a name takes the first name a repeat gives it.
 *
 * BACKFILL (with a [backfill] helper): when a thread is newly opened or newly closed, the chat's EARLIER scenes are
 * searched for set-ups the narrator could not see (it only reads one scene), and the ones the model confirms in one
 * call are linked as SETUP (plus RESOLVES markers when the thread is closed). It runs once per transition — a repeat of
 * the same open or close does not ask again — and a failure skips it. The model call and the embedding of the thread
 * description run off the graph thread, between [GraphAccess.withGraph] passes.
 *
 * Recency is the order of the scenes that opened the threads ([LatticeGraph.threads]), never thread ids, which say
 * nothing across id slots. The scenes counted for staleness live in memory only: after a restart a scene narrated
 * again (a retry, an in-progress scene) may count once more — at worst one scene early on the way to STALE.
 */
class SceneThreadLedger(
    private val graph: GraphAccess,
    private val embedder: Embedder,
    /** Looks for earlier set-ups when a thread opens or closes; null = no backfill. */
    private val backfill: SceneThreadBackfill? = null,
) {
    private val logger = LoggerFactory.getLogger(SceneThreadLedger::class.java)

    /** Scene ids already counted toward staleness (bounded, oldest evicted). Touched only on the graph thread. */
    private val countedScenes = LinkedHashSet<Long>()

    /** A thread that was just opened or closed by a scene: its earlier set-ups are looked for once. */
    private data class Transition(val threadId: Long, val closed: Boolean)

    /** The chat's candidate threads for [scene], whose member engrams are [memberIds]; counts staleness. */
    fun candidates(scene: SceneRecord, memberIds: Set<Long>): List<SceneNarrator.CandidateThread> =
        graph.withGraph { g -> select(g, scene, memberIds) }

    /**
     * Applies [changes] of [scene] (whose hub is [hubEngramId], null when the scene was abandoned without one), then
     * backfills the threads it opened or closed. [inProgress]: the scene is still OPEN and may offer them again.
     */
    fun apply(scene: SceneRecord, hubEngramId: Long?, changes: SceneNarrator.LedgerChanges, inProgress: Boolean) {
        if (changes.isEmpty) return
        val transitions = graph.withGraph { g -> applyChanges(g, scene, changes) }
        val helper = backfill ?: return
        for (t in transitions) {
            runCatching { backfillThread(helper, scene, t) }
                .onFailure { logger.warn("Thread backfill failed thread={} scene={}: {}", t.threadId, scene.sceneId, it.message) }
        }
    }

    /**
     * One transition: read the thread (graph thread), embed its description (off it — the embedder call never holds
     * the graph), gather candidates (graph thread), confirm (model), link (graph thread).
     */
    private fun backfillThread(helper: SceneThreadBackfill, scene: SceneRecord, t: Transition) {
        val view = graph.withGraph { g ->
            val thread = g.thread(t.threadId) ?: return@withGraph null
            // No earlier scene in this chat: nothing to look back at, so no embedding either.
            if (g.scenes(scene.chatKey).none { SCENE_ORDER.compare(it, scene) < 0 }) return@withGraph null
            val setups = g.threadLinks(t.threadId)
                .filter { it.role == ThreadRole.SETUP }
                .mapNotNull { g.engram(it.engramId)?.content }
            SceneThreadBackfill.ThreadView(thread.description, setups, t.closed)
        } ?: return
        val descriptionVec = embedder.embed(view.description)
        val candidates = graph.withGraph { g -> helper.candidates(g, t.threadId, scene, descriptionVec) }
        if (candidates.isEmpty()) return
        val confirmed = helper.confirm(view, candidates) ?: return
        if (confirmed.isEmpty()) return
        graph.withGraph { g ->
            val thread = g.thread(t.threadId) ?: return@withGraph
            val resolves = g.threadLinks(thread.id).filter { it.role == ThreadRole.RESOLVE }.mapTo(HashSet()) { it.engramId }
            val added = confirmed.filter { it !in resolves && g.engram(it)?.isEpisodeHub == false }
            added.forEach { g.linkThread(thread.id, it, ThreadRole.SETUP) }
            if (thread.state == ThreadState.CLOSED) {
                for (r in resolves) for (s in added) g.markResolves(r, s)
            }
            logger.info(
                "Thread backfill linked {} earlier set-up(s) of {} candidate(s): thread={} scene={} description={}",
                added.size, candidates.size, thread.id, scene.sceneId, thread.description,
            )
        }
    }

    private fun select(g: LatticeGraph, scene: SceneRecord, memberIds: Set<Long>): List<SceneNarrator.CandidateThread> {
        // Oldest first; the most recent are the last.
        val eligible = g.threads(scene.chatKey).filter { it.state == ThreadState.OPEN || it.state == ThreadState.STALE }
        if (eligible.isEmpty()) return emptyList()
        val recency = eligible.withIndex().associate { (i, t) -> t.id to i }
        val memberVecs = memberIds.mapNotNull { g.engram(it)?.embedding }
        val relevance = eligible.associate { t -> t.id to relevance(g, t.id, memberVecs) }
        val byRelevance = eligible
            .sortedWith(compareByDescending<ThreadRecord> { relevance.getValue(it.id) }.thenByDescending { recency.getValue(it.id) })
            .take(RELEVANT_CANDIDATES)
        val chosenIds = byRelevance.mapTo(HashSet()) { it.id }
        val recent = eligible.filter { it.id !in chosenIds }.asReversed().take(RECENT_CANDIDATES)
        val selected = byRelevance + recent
        val selectedIds = selected.mapTo(HashSet()) { it.id }

        val countScene = countedScenes.add(scene.sceneId)
        if (countedScenes.size > COUNTED_SCENES_MAX) countedScenes.remove(countedScenes.first())
        for (t in eligible) {
            when {
                t.id in selectedIds -> if (t.staleCount != 0) g.putThread(t.copy(staleCount = 0))
                countScene && t.state == ThreadState.OPEN -> {
                    val count = t.staleCount + 1
                    if (count >= STALE_AFTER_SCENES) {
                        logger.info(
                            "Thread went STALE after {} scenes without being a candidate: thread={} chat={} description={}",
                            count, t.id, t.chatKey, t.description,
                        )
                        g.putThread(t.copy(state = ThreadState.STALE, staleCount = count))
                    } else {
                        g.putThread(t.copy(staleCount = count))
                    }
                }
            }
        }
        return selected.map { SceneNarrator.CandidateThread(it.id, it.description, it.name) }
    }

    /** Max cosine between the thread's live SETUP memories and the scene's members; -1 when either side is empty. */
    private fun relevance(g: LatticeGraph, threadId: Long, memberVecs: List<FloatArray>): Double {
        if (memberVecs.isEmpty()) return -1.0
        var best = -1.0
        for (link in g.threadLinks(threadId)) {
            if (link.role != ThreadRole.SETUP) continue
            val v = g.engram(link.engramId)?.embedding ?: continue
            for (m in memberVecs) if (m.size == v.size) best = maxOf(best, cosine(v, m))
        }
        return best
    }

    /** Apply [changes]; returns the threads newly opened or newly closed by them (for the backfill). */
    private fun applyChanges(g: LatticeGraph, scene: SceneRecord, changes: SceneNarrator.LedgerChanges): List<Transition> {
        val chatKey = scene.chatKey
        val transitions = ArrayList<Transition>()

        fun live(ids: List<Long>): List<Long> = ids.filter { g.engram(it)?.isEpisodeHub == false }.distinct()

        fun capped(ids: List<Long>): List<Long> = live(ids).take(SceneNarrator.MAX_THREAD_REFS)

        fun own(threadId: Long): ThreadRecord? =
            g.thread(threadId)?.takeIf { it.chatKey == chatKey }
                ?: null.also { logger.info("Thread ledger: unknown thread {} for chat {} ignored (scene={})", threadId, chatKey, scene.sceneId) }

        fun roleIds(threadId: Long, role: ThreadRole): Set<Long> =
            g.threadLinks(threadId).filter { it.role == role }.mapTo(HashSet()) { it.engramId }

        for (o in changes.opened) {
            val setup = capped(o.setupEngramIds)
            val description = o.description.trim()
            if (setup.isEmpty() || description.isEmpty()) {
                logger.info("Thread ledger: opened thread without a known set-up memory ignored (scene={}): {}", scene.sceneId, description)
                continue
            }
            val name = o.name?.trim()?.takeIf { it.isNotEmpty() }
            val existing = g.threads(chatKey).firstOrNull {
                it.openedSceneId == scene.sceneId &&
                    (normalize(it.description) == normalize(description) || (name != null && it.name != null && normalize(it.name) == normalize(name)))
            }
            // A repeat keeps the thread's first name; one that was opened without a name gets it now.
            if (existing != null && existing.name == null && name != null) g.putThread(existing.copy(name = name))
            val id = existing?.id ?: g.putThread(
                ThreadRecord(id = 0, chatKey = chatKey, description = description, openedSceneId = scene.sceneId, name = name),
            ).also {
                logger.info("Thread opened: thread={} scene={} name={} description={}", it, scene.sceneId, name, description)
                transitions += Transition(it, closed = false)
            }
            setup.forEach { g.linkThread(id, it, ThreadRole.SETUP) }
        }

        for (a in changes.advanced) {
            val t = own(a.threadId) ?: continue
            if (t.state != ThreadState.OPEN && t.state != ThreadState.STALE) continue
            val resolves = roleIds(t.id, ThreadRole.RESOLVE)
            val ids = capped(a.engramIds).filter { it !in resolves }
            if (ids.isEmpty()) continue
            ids.forEach { g.linkThread(t.id, it, ThreadRole.SETUP) }
            if (t.state != ThreadState.OPEN || t.staleCount != 0) {
                g.putThread(t.copy(state = ThreadState.OPEN, staleCount = 0))
            }
        }

        for (c in changes.closed) {
            val t = own(c.threadId) ?: continue
            val repeat = t.state == ThreadState.CLOSED && t.closedSceneId == scene.sceneId
            if (!repeat && t.state != ThreadState.OPEN && t.state != ThreadState.STALE) continue
            val setups = roleIds(t.id, ThreadRole.SETUP)
            val resolveIds = live(c.resolveEngramIds).filter { it !in setups }
            if (resolveIds.isEmpty() && roleIds(t.id, ThreadRole.RESOLVE).isEmpty()) {
                logger.info("Thread ledger: close without a known resolve memory rejected: thread={} scene={}", t.id, scene.sceneId)
                continue
            }
            if (!repeat) {
                g.putThread(t.copy(state = ThreadState.CLOSED, closedSceneId = scene.sceneId, staleCount = 0))
                logger.info("Thread closed: thread={} scene={} description={}", t.id, scene.sceneId, t.description)
                transitions += Transition(t.id, closed = true)
            }
            resolveIds.forEach { g.linkThread(t.id, it, ThreadRole.RESOLVE) }
            for (r in roleIds(t.id, ThreadRole.RESOLVE)) for (s in setups) g.markResolves(r, s)
        }

        for (threadId in changes.dropped) {
            val t = own(threadId) ?: continue
            if (t.state != ThreadState.OPEN && t.state != ThreadState.STALE) continue
            g.putThread(t.copy(state = ThreadState.DROPPED))
            logger.info("Thread dropped: thread={} scene={} description={}", t.id, scene.sceneId, t.description)
        }
        // A thread opened and closed by the same scene needs one look back, not two.
        return transitions.sortedByDescending { it.closed }.distinctBy { it.threadId }
    }

    companion object {
        /** Candidates chosen by relevance to the scene. */
        const val RELEVANT_CANDIDATES = 14

        /** Then the most recent of the rest; at most RELEVANT + RECENT = 20 candidates. */
        const val RECENT_CANDIDATES = 6

        /** Consecutive scenes an OPEN thread may go unchosen before it turns STALE. */
        const val STALE_AFTER_SCENES = 10

        private const val COUNTED_SCENES_MAX = 4096

        private fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
