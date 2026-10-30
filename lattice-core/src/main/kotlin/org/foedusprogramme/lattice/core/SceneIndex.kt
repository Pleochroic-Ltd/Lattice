package org.foedusprogramme.lattice.core

import java.util.TreeSet

/**
 * The SCENE EPISODES side-store of [LatticeGraph]: the RAM cache of the stored scenes, scene members, threads and
 * thread links, and of the scene pipeline's work state and cursors, plus the indexes recall and the formatter read
 * once per recalled hit.
 * [LatticeGraph] owns one instance and delegates its public scene / thread methods to it; like the graph it is
 * not thread-safe and is touched only on the graph's owner thread.
 *
 * Indexes kept in step with every mutation (load, [putScene], [putThread], [linkThread], [redirect] on an
 * absorb, [removeEngram] on a node removal):
 *  - hub engram id -> scene ids, chat -> scenes in [SCENE_ORDER], scene -> members, member -> scenes;
 *  - chat -> thread ids, thread -> links, engram id -> its thread links (the reverse index: which
 *    threads a memory sets up or resolves, without scanning every thread of every chat), and the number of
 *    CLOSED threads (the "no closed thread at all" fast path).
 *
 * The graph-side facts it needs (a node, the supersession redirect, ABSTRACTS children) come through
 * [GraphView]; the store writes go to [store] (or the batch store a caller passes); a thread written without an id
 * gets one from [ids], the graph's allocator.
 */
internal class SceneIndex(
    private val store: LatticeStore,
    private val graph: GraphView,
    private val ids: IdAllocator,
) {
    /** What the index reads from the graph that owns it. */
    interface GraphView {
        /** The live engram [id], or null. */
        fun engram(id: Long): Engram?

        /** Follow [id]'s supersession chain to the memory recall would actually emit. */
        fun current(id: Long): Long

        /** [id]'s ABSTRACTS children: the targets of its outgoing ABSTRACTS edges. */
        fun abstractsChildren(id: Long): List<Long>
    }

    private val scenesById = HashMap<Long, SceneRecord>()
    /** hub engram id -> the scenes pointing at it, most recently written last ([sceneOfHub] reads the last). */
    private val sceneIdsByHub = HashMap<Long, LinkedHashSet<Long>>()
    private val scenesByChat = HashMap<String, TreeSet<SceneRecord>>()
    /** chat -> the highest [SceneRecord.seq] of its scenes. */
    private val maxSeqByChat = HashMap<String, Long>()
    private val workByScene = HashMap<Long, SceneWorkRecord>()
    private val cursorsByConversation = LinkedHashMap<String, SceneCursorRecord>()
    private val membersByScene = HashMap<Long, MutableSet<Long>>()
    private val scenesByMember = HashMap<Long, MutableSet<Long>>()

    private val threadsById = LinkedHashMap<Long, ThreadRecord>()
    private val threadIdsByChat = HashMap<String, TreeSet<Long>>()
    /** thread id -> its links, in insertion order. */
    private val linksByThread = HashMap<Long, MutableList<ThreadLinkRecord>>()
    /** engram id -> every thread link that references it (reverse of [linksByThread]). */
    private val linksByEngram = HashMap<Long, LinkedHashSet<ThreadLinkRecord>>()
    private var closedThreads = 0

    /** Rehydrate from a store load (no write-through). */
    fun load(loaded: LatticeStore.Loaded) {
        for (sc in loaded.scenes) cacheScene(sc)
        for ((sceneId, engramId) in loaded.sceneMembers) cacheSceneMember(sceneId, engramId)
        for (w in loaded.sceneWork) workByScene[w.sceneId] = w
        for (c in loaded.sceneCursors) cursorsByConversation[c.conversationId] = c
        for (t in loaded.threads) cacheThread(t)
        for (l in loaded.threadLinks) cacheLink(l)
    }

    // --- scenes --------------------------------------------------------------------------------------

    fun putScene(scene: SceneRecord) {
        ids.observe(scene.sceneId)
        cacheScene(scene)
        store.putScene(scene)
    }

    /** Add [engramIds] to scene [sceneId] (which must exist); ids [isLive] rejects are skipped. */
    fun addSceneMembers(sceneId: Long, engramIds: Collection<Long>, isLive: (Long) -> Boolean) {
        require(sceneId in scenesById) { "unknown scene $sceneId" }
        store.batch { s ->
            for (id in engramIds) {
                if (!isLive(id) || membersByScene[sceneId]?.contains(id) == true) continue
                cacheSceneMember(sceneId, id)
                s.putSceneMember(sceneId, id)
            }
        }
    }

    fun scene(sceneId: Long): SceneRecord? = scenesById[sceneId]

    fun scenes(): List<SceneRecord> = scenesById.values.toList()

    /** The scenes of one chat, oldest first ([SCENE_ORDER]). */
    fun scenes(chatKey: String): List<SceneRecord> = scenesByChat[chatKey]?.toList().orEmpty()

    /** The next capture sequence number of [chatKey]: one past the highest [SceneRecord.seq] of its scenes. */
    fun nextSceneSeq(chatKey: String): Long = (maxSeqByChat[chatKey] ?: 0) + 1

    fun sceneWork(sceneId: Long): SceneWorkRecord? = workByScene[sceneId]

    fun sceneCursor(conversationId: String): SceneCursorRecord? = cursorsByConversation[conversationId]

    /** Every conversation cursor, in the order they were first written. */
    fun sceneCursors(): List<SceneCursorRecord> = cursorsByConversation.values.toList()

    /**
     * Writes whichever of [scene], [work] and [cursor] is given as ONE store batch, then caches them. A [work] record
     * needs its scene: [scene], or one already written.
     */
    fun putSceneState(scene: SceneRecord?, work: SceneWorkRecord?, cursor: SceneCursorRecord?) {
        if (work != null) {
            require(work.sceneId == scene?.sceneId || work.sceneId in scenesById) {
                "Scene ${work.sceneId} doesn't exist; write the scene with its work state."
            }
        }
        scene?.let { ids.observe(it.sceneId) }
        store.batch { s ->
            scene?.let(s::putScene)
            work?.let(s::putSceneWork)
            cursor?.let(s::putSceneCursor)
        }
        scene?.let(::cacheScene)
        work?.let { workByScene[it.sceneId] = it }
        cursor?.let { cursorsByConversation[it.conversationId] = it }
    }

    fun sceneOfHub(hubEngramId: Long): SceneRecord? =
        sceneIdsByHub[hubEngramId]?.lastOrNull()?.let { scenesById[it] }

    fun isSceneHub(hubEngramId: Long): Boolean = sceneIdsByHub[hubEngramId]?.isNotEmpty() == true

    fun sceneMembers(sceneId: Long): Set<Long> = membersByScene[sceneId]?.toSet().orEmpty()

    fun scenesOf(engramId: Long): Set<Long> = scenesByMember[engramId]?.toSet().orEmpty()

    fun sceneHubsOf(engramId: Long): Set<Long> =
        scenesByMember[engramId].orEmpty().mapNotNullTo(LinkedHashSet()) { scenesById[it]?.hubEngramId }

    private fun cacheScene(scene: SceneRecord) {
        val id = scene.sceneId
        scenesById.put(id, scene)?.let { old ->
            old.hubEngramId?.let { hub -> removeFrom(sceneIdsByHub, hub, id) }
            scenesByChat[old.chatKey]?.let { chat ->
                chat.remove(old)
                if (chat.isEmpty()) scenesByChat.remove(old.chatKey)
            }
        }
        scene.hubEngramId?.let { hub -> sceneIdsByHub.getOrPut(hub) { LinkedHashSet() }.add(id) }
        scenesByChat.getOrPut(scene.chatKey) { TreeSet(SCENE_ORDER) }.add(scene)
        if (scene.seq > (maxSeqByChat[scene.chatKey] ?: 0)) maxSeqByChat[scene.chatKey] = scene.seq
    }

    private fun cacheSceneMember(sceneId: Long, engramId: Long) {
        membersByScene.getOrPut(sceneId) { HashSet() }.add(engramId)
        scenesByMember.getOrPut(engramId) { HashSet() }.add(sceneId)
    }

    /** Mirror in RAM what the DB does when engram [id] is deleted: membership rows CASCADE, a hub pointer is SET NULL. */
    private fun forgetSceneRefs(id: Long) {
        scenesByMember.remove(id)?.forEach { sceneId ->
            membersByScene[sceneId]?.let { it.remove(id); if (it.isEmpty()) membersByScene.remove(sceneId) }
        }
        sceneIdsByHub[id]?.toList()?.forEach { sceneId ->
            scenesById[sceneId]?.let { cacheScene(it.copy(hubEngramId = null)) }
        }
    }

    // --- threads -------------------------------------------------------------------------------------

    /** Upsert a thread-ledger row. An id <= 0 allocates the next id; returns the row's id. */
    fun putThread(thread: ThreadRecord): Long {
        val id = if (thread.id > 0) thread.id.also(ids::observe) else ids.next()
        val row = thread.copy(id = id)
        cacheThread(row)
        store.putThread(row)
        return id
    }

    /** Record [engramId] as a [role] of thread [threadId] (idempotent); the caller vouches the engram is live. */
    fun linkThread(threadId: Long, engramId: Long, role: ThreadRole) {
        require(threadId in threadsById) { "unknown thread $threadId" }
        if (!cacheLink(ThreadLinkRecord(threadId, engramId, role))) return
        store.putThreadLink(ThreadLinkRecord(threadId, engramId, role))
    }

    fun thread(threadId: Long): ThreadRecord? = threadsById[threadId]

    /**
     * Thread rows, of one chat or all, oldest first: in the [SCENE_ORDER] of the scene that opened them (a thread with
     * no known opening scene first), then by id.
     */
    fun threads(chatKey: String?): List<ThreadRecord> {
        val rows = if (chatKey == null) threadsById.values else threadIdsByChat[chatKey].orEmpty().mapNotNull { threadsById[it] }
        return rows.sortedWith(threadOrder)
    }

    private val threadOrder: Comparator<ThreadRecord> =
        compareBy<ThreadRecord, SceneRecord?>(nullsFirst(SCENE_ORDER)) { t -> t.openedSceneId?.let(scenesById::get) }
            .thenBy { it.id }

    fun threadLinks(threadId: Long): List<ThreadLinkRecord> = linksByThread[threadId]?.toList().orEmpty()

    fun threadLinksOf(engramId: Long): List<ThreadLinkRecord> =
        linksByEngram[engramId].orEmpty().sortedBy { it.threadId }

    /** Some thread is CLOSED (else no memory can read as settled and closure appends nothing). */
    val hasClosedThread: Boolean get() = closedThreads > 0

    /** Ids of the threads [engramId] is a [role] of, in id order. */
    private fun threadsWith(engramId: Long, role: ThreadRole): List<Long> =
        linksByEngram[engramId].orEmpty().filter { it.role == role }.map { it.threadId }.distinct().sorted()

    private fun isClosed(threadId: Long) = threadsById[threadId]?.state == ThreadState.CLOSED

    /**
     * The RESOLVE memories of the closed threads [setupId] sets up: thread by thread in id order, each thread's oldest
     * first (then by id). Not in link order: a merge moves a link to the end here but keeps its row's place in a store,
     * so link order changes on a reload.
     */
    fun closedResolvesOf(setupId: Long): List<Long> {
        if (closedThreads == 0) return emptyList()
        val out = LinkedHashSet<Long>()
        for (threadId in threadsWith(setupId, ThreadRole.SETUP)) {
            if (!isClosed(threadId)) continue
            linksByThread[threadId].orEmpty()
                .filter { it.role == ThreadRole.RESOLVE }
                .mapNotNull { l -> graph.current(l.engramId).takeIf { it != setupId }?.let(graph::engram) }
                .filter { !it.isEpisodeHub }
                .sortedWith(compareBy<Engram> { it.createdAt }.thenBy { it.id })
                .forEach { out.add(it.id) }
        }
        return out.toList()
    }

    fun isClosedThreadSetup(id: Long): Boolean =
        closedThreads > 0 && threadsWith(id, ThreadRole.SETUP).any(::isClosed)

    fun isThreadSettled(id: Long): Boolean {
        if (closedThreads == 0) return false
        if (isClosedThreadSetup(id)) return true
        return graph.abstractsChildren(id).any(::isClosedThreadSetup)
    }

    fun threadStatusOf(id: Long): LatticeGraph.ThreadStatus {
        if (closedThreads == 0) return LatticeGraph.ThreadStatus.NONE
        val node = graph.engram(id) ?: return LatticeGraph.ThreadStatus.NONE
        val scene = if (node.isEpisodeHub) sceneOfHub(id) else null
        val sources: Set<Long> = when {
            scene != null -> membersByScene[scene.sceneId].orEmpty()
            node.isEpisodeHub -> return LatticeGraph.ThreadStatus.NONE
            else -> buildSet {
                add(id)
                addAll(graph.abstractsChildren(id))
            }
        }
        val threadIds = TreeSet<Long>()
        for (src in sources) {
            linksByEngram[src]?.forEach { if (it.role == ThreadRole.SETUP) threadIds.add(it.threadId) }
        }
        val closed = ArrayList<ThreadRecord>()
        val open = ArrayList<ThreadRecord>()
        for (threadId in threadIds) {
            val t = threadsById[threadId] ?: continue
            when (t.state) {
                ThreadState.CLOSED -> if (scene == null || t.closedSceneId != scene.sceneId) closed += t
                ThreadState.OPEN, ThreadState.STALE -> open += t
                ThreadState.DROPPED -> {}
            }
        }
        return LatticeGraph.ThreadStatus(closed, open)
    }

    /**
     * Whether thread [t]'s name or description may carry untrusted text. Both come from the narration of the scene that
     * opened it, so they are as tainted as that scene's hub, which takes the narration's taint before the ledger
     * applies. With no hub to tell (no known opening scene, no hub, or a scene given up: its ledger may come from a
     * failed attempt whose reading nothing records) the thread counts as tainted.
     */
    fun threadTextTainted(t: ThreadRecord): Boolean {
        val scene = t.openedSceneId?.let(scenesById::get) ?: return true
        if (workByScene[scene.sceneId]?.state == SceneWorkState.ABANDONED) return true
        return scene.hubEngramId?.let(graph::engram)?.tainted ?: true
    }

    private fun cacheThread(row: ThreadRecord) {
        val old = threadsById.put(row.id, row)
        if (old?.state == ThreadState.CLOSED) closedThreads--
        if (row.state == ThreadState.CLOSED) closedThreads++
        if (old != null && old.chatKey != row.chatKey) removeFrom(threadIdsByChat, old.chatKey, row.id)
        threadIdsByChat.getOrPut(row.chatKey) { TreeSet() }.add(row.id)
    }

    /** Add [link] to both link indexes; false when it was already there. */
    private fun cacheLink(link: ThreadLinkRecord): Boolean {
        val links = linksByThread.getOrPut(link.threadId) { ArrayList() }
        if (link in links) return false
        links.add(link)
        linksByEngram.getOrPut(link.engramId) { LinkedHashSet() }.add(link)
        return true
    }

    // --- engram lifecycle ----------------------------------------------------------------------------

    /** Engram [id] was removed: drop its scene refs and its thread links (link rows are not cascaded). */
    fun removeEngram(id: Long, s: LatticeStore) {
        forgetSceneRefs(id)
        val links = linksByEngram.remove(id)
        if (links.isNullOrEmpty()) return
        for (l in links) linksByThread[l.threadId]?.remove(l)
        s.deleteThreadLinks(id)
    }

    /**
     * An absorb folds [dropId] into [keepId]: its scene memberships and thread links move to the survivor
     * (mirroring the supersededBy redirect) so the merge loses neither; thread rows are untouched. Must run
     * before the dropped engram is deleted, or its membership rows cascade away.
     */
    fun redirect(dropId: Long, keepId: Long, s: LatticeStore) {
        val scenes = scenesByMember[dropId]
        if (!scenes.isNullOrEmpty()) {
            for (sceneId in scenes) cacheSceneMember(sceneId, keepId)
            s.redirectSceneMembers(dropId, keepId)
        }
        forgetSceneRefs(dropId)
        val links = linksByEngram.remove(dropId)
        if (links.isNullOrEmpty()) return
        for (l in links) {
            linksByThread[l.threadId]?.remove(l)
            cacheLink(ThreadLinkRecord(l.threadId, keepId, l.role))
        }
        s.redirectThreadLinks(dropId, keepId)
    }

    private fun <K> removeFrom(index: HashMap<K, out MutableSet<Long>>, key: K, id: Long) {
        val set = index[key] ?: return
        set.remove(id)
        if (set.isEmpty()) index.remove(key)
    }
}
