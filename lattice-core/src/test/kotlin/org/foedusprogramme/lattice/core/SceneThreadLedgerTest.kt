package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.core.SceneNarrator.AdvancedThread
import org.foedusprogramme.lattice.core.SceneNarrator.ClosedThread
import org.foedusprogramme.lattice.core.SceneNarrator.LedgerChanges
import org.foedusprogramme.lattice.core.SceneNarrator.OpenedThread
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Candidate selection, STALE threads, the ledger and the backfill: [SceneThreadLedger] against a real in-memory graph.
 * Geometry ([BgeBandFakeEmbedder]): memories sharing `topic|sub` sit at 0.83, sharing only `topic` at 0.64,
 * anything else at 0.60.
 */
class SceneThreadLedgerTest {

    private val clock = { Instant.parse("2026-09-25T00:00:00Z") }

    companion object {
        const val CHAT = "telegram:42"
        const val CONV_A = "telegram:42:s1"
        const val CONV_B = "telegram:42:s2"
    }

    private val embedder = BgeBandFakeEmbedder()

    private fun graph() = LatticeGraph(
        embedder,
        LatticeGraph.Config(autoLinkThreshold = 0.99, dedupThreshold = 0.99, episodicBinding = true),
        now = clock,
    )

    private fun ledger(g: LatticeGraph) = SceneThreadLedger(GraphAccess.locked(g), embedder)

    /** Scene [id] of a chat: it starts on day [id] of September, so scene order is id order. */
    private fun scene(id: Long, conv: String = CONV_A, chat: String = CHAT) = SceneRecord(
        sceneId = id, title = "scene $id", conversationId = conv, chatKey = chat,
        firstMessageId = id * 100, lastMessageId = id * 100 + 9,
        dateFrom = "2026-09-%02d".format(id), startedAt = Instant.parse("2026-09-%02dT10:00:00Z".format(id)), seq = id,
    )

    private fun LatticeGraph.mem(content: String) = remember(content, layer = Engram.Layer.SEMANTIC)

    private fun LatticeGraph.roles(threadId: Long, role: ThreadRole) =
        threadLinks(threadId).filter { it.role == role }.map { it.engramId }.toSet()

    private fun LatticeGraph.edgeType(a: Long, b: Long): Edge.Type? = neighbors(a).firstOrNull { it.to == b }?.type

    // --- apply ------------------------------------------------------------------------------------------

    @Test
    fun `opened in A, advanced in B, closed in C - CLOSED with SETUP from A and B, RESOLVE from C, RESOLVES edges`() {
        val g = graph()
        val l = ledger(g)
        val setupA = g.mem("dusk|surprise|promise")
        val remB = g.mem("dusk|surprise|again")
        val resolveC = g.mem("gift|cake|candle")

        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("周末的惊喜", listOf(setupA)))), false)
        val t = g.threads(CHAT).single()
        assertEquals(ThreadState.OPEN, t.state)
        assertEquals(1L, t.openedSceneId)

        l.apply(scene(2), 0, LedgerChanges(advanced = listOf(AdvancedThread(t.id, listOf(remB)))), false)
        l.apply(scene(3), 0, LedgerChanges(closed = listOf(ClosedThread(t.id, listOf(resolveC)))), false)

        val closed = g.thread(t.id)!!
        assertEquals(ThreadState.CLOSED, closed.state)
        assertEquals(3L, closed.closedSceneId)
        assertEquals(setOf(setupA, remB), g.roles(t.id, ThreadRole.SETUP))
        assertEquals(setOf(resolveC), g.roles(t.id, ThreadRole.RESOLVE))
        assertEquals(Edge.Type.RESOLVES, g.edgeType(resolveC, setupA))
        assertEquals(Edge.Type.RESOLVES, g.edgeType(resolveC, remB))
    }

    @Test
    fun `a RESOLVES edge is not written over an existing protected or ordinary edge`() {
        val g = graph()
        val l = ledger(g)
        val causal = g.mem("dusk|surprise|promise")
        val semantic = g.mem("dusk|surprise|again")
        val bare = g.mem("dusk|walk|x")
        val resolve = g.mem("gift|cake|candle")
        g.link(resolve, causal, Edge.Type.CAUSAL, 0.7)
        g.link(resolve, semantic, Edge.Type.SEMANTIC, 0.4)
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("惊喜", listOf(causal, semantic, bare)))), false)
        val t = g.threads(CHAT).single().id

        l.apply(scene(2), 0, LedgerChanges(closed = listOf(ClosedThread(t, listOf(resolve)))), false)

        assertEquals(Edge.Type.CAUSAL, g.edgeType(resolve, causal), "the protected edge stands; the ledger carries the closure")
        assertEquals(Edge.Type.SEMANTIC, g.edgeType(resolve, semantic), "an ordinary spreading edge is left as it was")
        assertEquals(0.4, g.neighbors(resolve).single { it.to == semantic }.weight, 1e-9)
        assertEquals(Edge.Type.RESOLVES, g.edgeType(resolve, bare))
        assertEquals(setOf(causal, semantic, bare), g.roles(t, ThreadRole.SETUP), "direction lives in the side table")
    }

    @Test
    fun `repeating the same changes is idempotent`() {
        val g = graph()
        val l = ledger(g)
        val s1 = g.mem("dusk|surprise|promise")
        val s2 = g.mem("dusk|surprise|again")
        val r = g.mem("gift|cake|candle")
        val open = LedgerChanges(opened = listOf(OpenedThread("周末的惊喜", listOf(s1))))
        l.apply(scene(1), 0, open, true)
        l.apply(scene(1), 0, open, true) // an in-progress hub narrated again
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("周末的惊喜", listOf(s1, s2)))), false)
        val t = g.threads(CHAT).single()
        assertEquals(setOf(s1, s2), g.roles(t.id, ThreadRole.SETUP))

        val close = LedgerChanges(closed = listOf(ClosedThread(t.id, listOf(r))))
        l.apply(scene(2), 0, close, false)
        val edgesBefore = g.neighbors(r).map { Triple(it.to, it.type, it.weight) }.toSet()
        val linksBefore = g.threadLinks(t.id)
        l.apply(scene(2), 0, close, false)

        assertEquals(1, g.threads(CHAT).size)
        assertEquals(linksBefore, g.threadLinks(t.id))
        assertEquals(edgesBefore, g.neighbors(r).map { Triple(it.to, it.type, it.weight) }.toSet())
        assertEquals(ThreadState.CLOSED, g.thread(t.id)!!.state)
    }

    @Test
    fun `unknown thread ids, unknown memories and other chats' threads are ignored item by item`() {
        val g = graph()
        val l = ledger(g)
        val s1 = g.mem("dusk|surprise|promise")
        val other = g.mem("other|chat|setup")
        val r = g.mem("gift|cake|candle")
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("惊喜", listOf(s1)))), false)
        l.apply(scene(9, conv = "telegram:7:s1", chat = "telegram:7"), 0, LedgerChanges(opened = listOf(OpenedThread("别人的", listOf(other)))), false)
        val mine = g.threads(CHAT).single().id
        val theirs = g.threads("telegram:7").single().id

        l.apply(
            scene(2), 0,
            LedgerChanges(
                opened = listOf(OpenedThread("全是未知记忆", listOf(9_999L)), OpenedThread("新线索", listOf(9_998L, r))),
                advanced = listOf(AdvancedThread(12_345L, listOf(r)), AdvancedThread(mine, listOf(9_997L))),
                closed = listOf(ClosedThread(theirs, listOf(r)), ClosedThread(mine, listOf(r, 9_996L))),
                dropped = listOf(54_321L),
            ),
            false,
        )

        assertEquals(ThreadState.OPEN, g.thread(theirs)!!.state, "another chat's thread is untouched")
        assertEquals(ThreadState.CLOSED, g.thread(mine)!!.state)
        assertEquals(setOf(r), g.roles(mine, ThreadRole.RESOLVE))
        assertEquals(setOf(s1), g.roles(mine, ThreadRole.SETUP))
        val descriptions = g.threads(CHAT).map { it.description }
        assertEquals(listOf("惊喜", "新线索"), descriptions, "a thread with no known set-up memory is not opened")
        assertEquals(setOf(r), g.roles(g.threads(CHAT).last().id, ThreadRole.SETUP))
    }

    @Test
    fun `dropped marks the thread DROPPED and it is no longer a candidate`() {
        val g = graph()
        val l = ledger(g)
        val s1 = g.mem("dusk|surprise|promise")
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("惊喜", listOf(s1)))), false)
        val t = g.threads(CHAT).single().id

        l.apply(scene(2), 0, LedgerChanges(dropped = listOf(t)), false)

        assertEquals(ThreadState.DROPPED, g.thread(t)!!.state)
        assertTrue(l.candidates(scene(3), setOf(s1)).isEmpty())
    }

    // --- candidate selection ------------------------------------------------------------------------------

    /** Open one thread per setup content, oldest first; returns the thread ids in order. */
    private fun openThreads(g: LatticeGraph, l: SceneThreadLedger, sceneId: Long, setups: List<String>): List<Long> =
        setups.also { if (g.scene(sceneId) == null) g.putScene(scene(sceneId)) }.map { content ->
            val m = g.mem(content)
            l.apply(scene(sceneId), 0, LedgerChanges(opened = listOf(OpenedThread("thread $content", listOf(m)))), false)
            g.threads(CHAT).last().id
        }

    @Test
    fun `an old thread is chosen by relevance past 25 newer unrelated threads, and closes`() {
        val g = graph()
        val l = ledger(g)
        val old = openThreads(g, l, 1, listOf("dusk|surprise|promise")).single()
        openThreads(g, l, 2, (1..25).map { "misc$it|thing|x" })
        val ring = g.mem("dusk|surprise|reveal") // the reveal scene's member, on the thread's topic
        val other = g.mem("food|lunch|x")

        val candidates = l.candidates(scene(3), setOf(ring, other))

        assertEquals(20, candidates.size, "at most 20 candidates")
        assertTrue(candidates.any { it.id == old }, "the relevant old thread is a candidate")
        assertEquals(old, candidates.first().id, "the most relevant thread leads")

        l.apply(scene(3), 0, LedgerChanges(closed = listOf(ClosedThread(old, listOf(ring)))), false)
        assertEquals(ThreadState.CLOSED, g.thread(old)!!.state)
    }

    @Test
    fun `selection is the top 14 by relevance plus the 6 most recent of the rest`() {
        val g = graph()
        val l = ledger(g)
        // 16 relevant threads first (ids 1..16), then 14 unrelated ones (ids 17..30).
        val relevant = openThreads(g, l, 1, (1..16).map { "dusk|surprise|s$it" })
        val unrelated = openThreads(g, l, 2, (1..14).map { "misc$it|thing|x" })
        val member = g.mem("dusk|surprise|reveal")

        val chosen = l.candidates(scene(3), setOf(member)).map { it.id }

        assertEquals(20, chosen.size)
        val top = chosen.take(14)
        assertTrue(top.all { it in relevant }, "the first 14 are the relevant ones: $top")
        assertEquals(unrelated.takeLast(6).toSet(), chosen.drop(14).toSet(), "then the 6 most recent not chosen yet")
        assertEquals(chosen.size, chosen.toSet().size, "no duplicates")
    }

    @Test
    fun `ten consecutive scenes unselected - STALE, selected again by relevance - can be closed`() {
        val g = graph()
        val l = ledger(g)
        val target = openThreads(g, l, 1, listOf("old|promise|x")).single()
        openThreads(g, l, 2, (1..25).map { "misc$it|thing|x" }) // 25 newer threads push it out of every pick
        val filler = g.mem("food|lunch|x")

        for (sceneId in 10L..18L) l.candidates(scene(sceneId), setOf(filler))
        l.candidates(scene(18), setOf(filler)) // the same scene offered again counts once
        assertEquals(ThreadState.OPEN, g.thread(target)!!.state, "nine scenes: still OPEN")
        assertEquals(9, g.thread(target)!!.staleCount)

        l.candidates(scene(19), setOf(filler))
        assertEquals(ThreadState.STALE, g.thread(target)!!.state, "the tenth scene makes it STALE")

        val reveal = g.mem("old|promise|kept")
        val picked = l.candidates(scene(20), setOf(reveal))
        assertTrue(picked.any { it.id == target }, "relevance brings a STALE thread back")
        assertEquals(0, g.thread(target)!!.staleCount, "selection resets the counter")

        l.apply(scene(20), 0, LedgerChanges(closed = listOf(ClosedThread(target, listOf(reveal)))), false)
        assertEquals(ThreadState.CLOSED, g.thread(target)!!.state)
    }

    @Test
    fun `a STALE thread advanced again returns to OPEN`() {
        val g = graph()
        val l = ledger(g)
        val target = openThreads(g, l, 1, listOf("old|promise|x")).single()
        openThreads(g, l, 2, (1..25).map { "misc$it|thing|x" })
        val filler = g.mem("food|lunch|x")
        for (sceneId in 10L..19L) l.candidates(scene(sceneId), setOf(filler))
        assertEquals(ThreadState.STALE, g.thread(target)!!.state)

        val again = g.mem("old|promise|again")
        assertTrue(l.candidates(scene(20), setOf(again)).any { it.id == target })
        l.apply(scene(20), 0, LedgerChanges(advanced = listOf(AdvancedThread(target, listOf(again)))), false)

        val t = g.thread(target)!!
        assertEquals(ThreadState.OPEN, t.state)
        assertTrue(again in g.roles(target, ThreadRole.SETUP))
    }

    @Test
    fun `threads are per chat and survive a new conversation - closed from a scene of the next session`() {
        val g = graph()
        val l = ledger(g)
        val setup = g.mem("dusk|surprise|promise")
        l.apply(scene(1, conv = CONV_A), 0, LedgerChanges(opened = listOf(OpenedThread("周末的惊喜", listOf(setup)))), false)
        val t = g.threads(CHAT).single().id
        val foreign = g.mem("dusk|surprise|elsewhere")
        l.apply(scene(5, conv = "telegram:7:s1", chat = "telegram:7"), 0, LedgerChanges(opened = listOf(OpenedThread("别处", listOf(foreign)))), false)

        val reveal = g.mem("dusk|surprise|reveal")
        val candidates = l.candidates(scene(2, conv = CONV_B), setOf(reveal))
        assertEquals(listOf(t), candidates.map { it.id }, "only this chat's threads, across sessions")

        l.apply(scene(2, conv = CONV_B), 0, LedgerChanges(closed = listOf(ClosedThread(t, listOf(reveal)))), false)
        val closed = g.thread(t)!!
        assertEquals(ThreadState.CLOSED, closed.state)
        assertEquals(2L, closed.closedSceneId)
        assertEquals(1L, closed.openedSceneId)
    }

    @Test
    fun `closed threads are not candidates and a closed thread is not advanced`() {
        val g = graph()
        val l = ledger(g)
        val setup = g.mem("dusk|surprise|promise")
        val reveal = g.mem("dusk|surprise|reveal")
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("惊喜", listOf(setup)))), false)
        val t = g.threads(CHAT).single().id
        l.apply(scene(2), 0, LedgerChanges(closed = listOf(ClosedThread(t, listOf(reveal)))), false)

        assertTrue(l.candidates(scene(3), setOf(reveal)).isEmpty())
        val later = g.mem("dusk|surprise|later")
        l.apply(scene(3), 0, LedgerChanges(advanced = listOf(AdvancedThread(t, listOf(later)))), false)
        assertFalse(later in g.roles(t, ThreadRole.SETUP))
        assertNotNull(g.thread(t)).also { assertEquals(ThreadState.CLOSED, it.state) }
    }

    // --- backfilling earlier set-ups ----------------------------------------------------------------------------

    /** Confirms every candidate whose content contains one of [accept]; records each call. */
    private class ConfirmLlm(val accept: List<String>, val fail: Boolean = false) : MemoryModel {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        override fun complete(task: MemoryTask, system: String, user: String): String {
            assertEquals(MemoryTask.JUDGE, task)
            calls += system to user
            if (fail) throw IllegalStateException("side model down")
            val refs = candidates(user).filter { (_, content) -> accept.any { it in content } }.map { it.first }
            return """{"belongs": [${refs.joinToString(",")}]}"""
        }

        fun candidateLines(call: Int = 0): List<String> = candidates(calls[call].second).map { it.second }

        private fun candidates(user: String): List<Pair<String, String>> =
            Regex("^\\[(\\d+)] (.+)$", RegexOption.MULTILINE).findAll(user.substringAfter("## Candidates"))
                .map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
    }

    private fun backfillLedger(g: LatticeGraph, llm: MemoryModel, floor: Double = 0.62) =
        SceneThreadLedger(GraphAccess.locked(g), embedder, SceneThreadBackfill(llm, floor = floor))

    /** Put scene [id] into the graph with [members]. */
    private fun LatticeGraph.sceneWith(id: Long, members: List<Long>, chat: String = CHAT) {
        putScene(scene(id, chat = chat))
        addSceneMembers(id, members)
    }

    @Test
    fun `a thread opened one scene after its first set-up links the earlier set-ups on open and close`() {
        val g = graph()
        val llm = ConfirmLlm(accept = listOf("dusk|surprise"))
        val l = backfillLedger(g, llm)
        // Scene 1: the first set-up, before anyone treated it as a thread.
        val early1 = g.mem("dusk|surprise|early1")
        val early2 = g.mem("dusk|surprise|early2")
        val sameTopic = g.mem("dusk|walk|x") // above the floor, rejected by the side model
        val unrelated = g.mem("food|lunch|x") // below the floor: never offered
        g.sceneWith(1, listOf(early1, early2, sameTopic, unrelated))
        // Scene 2: the narrator opens the thread only here.
        val opener = g.mem("dusk|surprise|s2")
        g.sceneWith(2, listOf(opener))

        l.apply(scene(2), 0, LedgerChanges(opened = listOf(OpenedThread("dusk|surprise|thread", listOf(opener)))), false)

        val t = g.threads(CHAT).single().id
        assertEquals(setOf(opener, early1, early2), g.roles(t, ThreadRole.SETUP), "earlier set-ups are linked on open")
        assertEquals(1, llm.calls.size, "one confirmation call")
        val offered = llm.candidateLines()
        assertTrue(offered.containsAll(listOf("dusk|surprise|early1", "dusk|surprise|early2", "dusk|walk|x")), "$offered")
        assertFalse("food|lunch|x" in offered, "below the floor is not offered")
        assertFalse("dusk|surprise|s2" in offered, "the thread's own set-up is not a candidate")
        assertTrue("dusk|surprise|thread" in llm.calls.single().second, "the thread is described to the model")

        // Scene 3 closes it: RESOLVES markers reach the back-filled set-ups too.
        val reveal = g.mem("gift|cake|candle")
        g.sceneWith(3, listOf(reveal))
        l.apply(scene(3), 0, LedgerChanges(closed = listOf(ClosedThread(t, listOf(reveal)))), false)

        assertEquals(ThreadState.CLOSED, g.thread(t)!!.state)
        assertEquals(Edge.Type.RESOLVES, g.edgeType(reveal, early1))
        assertEquals(Edge.Type.RESOLVES, g.edgeType(reveal, early2))
        assertFalse(sameTopic in g.roles(t, ThreadRole.SETUP), "the side model's rejection stands")
        assertTrue(g.isThreadSettled(early1), "an earlier set-up now reads as settled")
    }

    @Test
    fun `closing backfills set-ups the open-time pass could not reach`() {
        val g = graph()
        val down = ConfirmLlm(accept = emptyList(), fail = true)
        val opener = g.mem("dusk|surprise|s2")
        val early = g.mem("dusk|surprise|early")
        g.sceneWith(1, listOf(early))
        g.sceneWith(2, listOf(opener))
        backfillLedger(g, down).apply(scene(2), 0, LedgerChanges(opened = listOf(OpenedThread("dusk|surprise|thread", listOf(opener)))), false)
        val t = g.threads(CHAT).single().id
        assertEquals(setOf(opener), g.roles(t, ThreadRole.SETUP), "a failed confirmation links nothing")
        assertEquals(1, down.calls.size, "and is not retried")

        val up = ConfirmLlm(accept = listOf("dusk|surprise"))
        val reveal = g.mem("gift|cake|candle")
        g.sceneWith(3, listOf(reveal))
        backfillLedger(g, up).apply(scene(3), 0, LedgerChanges(closed = listOf(ClosedThread(t, listOf(reveal)))), false)

        assertEquals(setOf(opener, early), g.roles(t, ThreadRole.SETUP))
        assertEquals(Edge.Type.RESOLVES, g.edgeType(reveal, early))
        assertEquals(1, up.calls.size)
        assertTrue("resolved" in up.calls.single().second.lowercase(), "the model is told the thread was resolved")
    }

    @Test
    fun `backfill offers at most 12 candidates, only from earlier scenes of the same chat`() {
        val g = graph()
        val llm = ConfirmLlm(accept = listOf("dusk|surprise"))
        val l = backfillLedger(g, llm)
        val earlier = (1..20).map { g.mem("dusk|surprise|e$it") }
        g.sceneWith(1, earlier)
        val otherChat = g.mem("dusk|surprise|foreign")
        g.sceneWith(7, listOf(otherChat), chat = "telegram:7")
        val opener = g.mem("dusk|surprise|s2")
        val sameScene = g.mem("dusk|surprise|same-scene")
        g.sceneWith(2, listOf(opener, sameScene))
        val later = g.mem("dusk|surprise|later")
        g.sceneWith(5, listOf(later))

        l.apply(scene(2), 0, LedgerChanges(opened = listOf(OpenedThread("dusk|surprise|thread", listOf(opener)))), false)

        val offered = llm.candidateLines()
        assertEquals(SceneThreadBackfill.MAX_CANDIDATES, offered.size, "$offered")
        assertEquals(12, SceneThreadBackfill.MAX_CANDIDATES)
        assertTrue(offered.all { it.startsWith("dusk|surprise|e") }, "earlier scenes of this chat only: $offered")
        val t = g.threads(CHAT).single().id
        assertEquals(13, g.roles(t, ThreadRole.SETUP).size, "the opener plus the 12 confirmed")
        assertFalse(otherChat in g.roles(t, ThreadRole.SETUP))
        assertFalse(later in g.roles(t, ThreadRole.SETUP))
        assertFalse(sameScene in g.roles(t, ThreadRole.SETUP))
    }

    @Test
    fun `backfill runs once per transition - repeating the same changes makes no new call`() {
        val g = graph()
        val llm = ConfirmLlm(accept = listOf("dusk|surprise|early"))
        val l = backfillLedger(g, llm)
        g.sceneWith(1, listOf(g.mem("dusk|surprise|early"), g.mem("dusk|surprise|noise")))
        val opener = g.mem("dusk|surprise|s2")
        g.sceneWith(2, listOf(opener))
        val open = LedgerChanges(opened = listOf(OpenedThread("dusk|surprise|thread", listOf(opener))))
        l.apply(scene(2), 0, open, true)
        l.apply(scene(2), 0, open, true)
        l.apply(scene(2), 0, open, false)
        assertEquals(1, llm.calls.size, "an in-progress scene narrated again does not re-run the backfill")

        val t = g.threads(CHAT).single().id
        val reveal = g.mem("gift|cake|candle")
        val close = LedgerChanges(closed = listOf(ClosedThread(t, listOf(reveal))))
        l.apply(scene(3), 0, close, false)
        l.apply(scene(3), 0, close, false)
        assertEquals(2, llm.calls.size, "one call on close, none on its repeat")
    }

    @Test
    fun `an unreadable confirmation links nothing`() {
        val g = graph()
        val llm = object : MemoryModel {
            val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
            override fun complete(task: MemoryTask, system: String, user: String): String {
                calls += user
                return "I think the first two, probably."
            }
        }
        val l = backfillLedger(g, llm)
        g.sceneWith(1, listOf(g.mem("dusk|surprise|early")))
        val opener = g.mem("dusk|surprise|s2")
        g.sceneWith(2, listOf(opener))
        l.apply(scene(2), 0, LedgerChanges(opened = listOf(OpenedThread("dusk|surprise|thread", listOf(opener)))), false)
        assertEquals(setOf(opener), g.roles(g.threads(CHAT).single().id, ThreadRole.SETUP))
        assertEquals(1, llm.calls.size)
    }

    // --- ledger-side guards -------------------------------------------------------------------------------------

    @Test
    fun `a close without any known resolve memory is rejected`() {
        val g = graph()
        val l = ledger(g)
        val s1 = g.mem("dusk|surprise|promise")
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("惊喜", listOf(s1)))), false)
        val t = g.threads(CHAT).single().id

        l.apply(scene(2), 0, LedgerChanges(closed = listOf(ClosedThread(t, emptyList()))), false)
        assertEquals(ThreadState.OPEN, g.thread(t)!!.state, "no resolve memory: the thread stays open")
        l.apply(scene(3), 0, LedgerChanges(closed = listOf(ClosedThread(t, listOf(9_999L)))), false)
        assertEquals(ThreadState.OPEN, g.thread(t)!!.state, "only unknown resolve memories: still open")
    }

    @Test
    fun `set-up refs are capped per thread per scene`() {
        val g = graph()
        val l = ledger(g)
        val many = (1..9).map { g.mem("dusk|surprise|m$it") }
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("惊喜", many))), false)
        val t = g.threads(CHAT).single().id
        assertEquals(SceneNarrator.MAX_THREAD_REFS, g.roles(t, ThreadRole.SETUP).size)
        assertEquals(6, SceneNarrator.MAX_THREAD_REFS)

        val more = (1..9).map { g.mem("dusk|surprise|n$it") }
        l.apply(scene(2), 0, LedgerChanges(advanced = listOf(AdvancedThread(t, more))), false)
        assertEquals(2 * SceneNarrator.MAX_THREAD_REFS, g.roles(t, ThreadRole.SETUP).size)
    }

    @Test
    fun `an opened thread keeps its short name, idempotently, and candidates carry it`() {
        val g = graph()
        val l = ledger(g)
        val s1 = g.mem("dusk|surprise|promise")
        val s2 = g.mem("dusk|surprise|again")
        val long = "Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物"

        // First narration of an in-progress scene gave no name; the next one does: the name is filled in.
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread(long, listOf(s1)))), true)
        assertNull(g.threads(CHAT).single().name)
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread(long, listOf(s1), name = "周末的惊喜礼物"))), true)
        // A repeat that rephrases the description but keeps the name is the same thread.
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread("周末揭晓的惊喜礼物", listOf(s2), name = "周末的惊喜礼物"))), false)
        // A repeat without a name (or with another one) does not erase or rename it.
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread(long, listOf(s1)))), false)
        l.apply(scene(1), 0, LedgerChanges(opened = listOf(OpenedThread(long, listOf(s1), name = "别的名字"))), false)

        val t = g.threads(CHAT).single()
        assertEquals("周末的惊喜礼物", t.name)
        assertEquals(long, t.description, "the fuller description is kept")
        assertEquals(setOf(s1, s2), g.roles(t.id, ThreadRole.SETUP))

        val candidates = l.candidates(scene(2), setOf(g.mem("dusk|surprise|later")))
        assertEquals(listOf(SceneNarrator.CandidateThread(t.id, long, name = "周末的惊喜礼物")), candidates)
    }
}
