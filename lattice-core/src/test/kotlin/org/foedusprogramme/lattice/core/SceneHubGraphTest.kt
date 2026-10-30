package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scene hubs and the scene / thread indexes in the graph alone: a hub never seeds nor joins the base but is reinstated
 * by a member seed; it is never auto-linked, folded into, merged or judged; it survives a reload; it is embedded off the
 * graph when the caller passes the vector, anchors to the scene's end, never rolls a fresher range back and refuses
 * prompt scaffolding; and the thread and per-chat scene indexes stay in step with every mutation and a reload.
 */
class SceneHubGraphTest {

    private val clock = { Instant.parse("2026-09-25T00:00:00Z") }

    private fun graphConfig() = LatticeGraph.Config(
        episodicBinding = true,
        episodeReinstatement = true,
        temporalLayer = true,
        autoLinkThreshold = 0.99,
    )

    private fun record(id: Long, chat: String = CHAT_A, first: Long = id * 100, last: Long = id * 100 + 9, dateFrom: String? = null) =
        SceneRecord(id, "scene $id", "$chat:s1", chat, first, last, dateFrom = dateFrom, dateTo = dateFrom)

    // --- hubs in recall --------------------------------------------------------------------------------------------

    @Test
    fun `a scene hub is never a seed nor in the base, but a member seed still reinstates it`() {
        val g = LatticeGraph(BgeBandFakeEmbedder(), graphConfig(), now = clock)
        val m1 = g.remember("trip|tongli|a|1", layer = Engram.Layer.SEMANTIC)
        val m2 = g.remember("trip|tongli|b|2", layer = Engram.Layer.SEMANTIC)
        g.putScene(record(1, first = 1, last = 2, dateFrom = "2026-09-20"))
        g.addSceneMembers(1, listOf(m1, m2))
        // The hub's narration is (by construction) the closest thing to the cue.
        val hub = g.materializeScene(g.scene(1)!!, "trip|tongli|hub|narration", listOf(m1, m2))
        assertTrue(hub > 0)

        val hits = g.recall("trip|tongli|hub|narration", limit = 1, reinforce = Reinforce.NONE, applyEmitGate = false)

        assertNotEquals(hub, hits.first().engram.id, "the hub is not the base result")
        assertTrue(hits.take(1).none { it.engram.isEpisodeHub })
        assertTrue(hits.drop(1).any { it.engram.id == hub }, "reinstated after the base via the member SEED")
    }

    @Test
    fun `an old-style episode hub is excluded from the base the same way`() {
        val g = LatticeGraph(BgeBandFakeEmbedder(), graphConfig().copy(episodeMinSize = 2), now = clock)
        val m1 = g.remember("walk|park|a|1", layer = Engram.Layer.SEMANTIC)
        val m2 = g.remember("walk|park|b|2", layer = Engram.Layer.SEMANTIC)
        val hub = g.materializeEpisode(listOf(m1, m2), "walk|park|hub|story", "2026-09-20", skipCohesion = true)
        assertTrue(hub > 0)

        val hits = g.recall("walk|park|hub|story", limit = 2, reinforce = Reinforce.NONE, applyEmitGate = false)

        assertTrue(hits.take(2).none { it.engram.isEpisodeHub }, "hub never in the base: $hits")
    }

    @Test
    fun `hubs are not auto-linked, folded into on write, merged or judged`() {
        val neverCalled = object : MergeJudge {
            override val modelId = "test"
            override fun judge(noteA: String, noteB: String, crossLingualHint: Boolean) = MergeJudge.Verdict.SAME
        }
        val g = LatticeGraph(
            BgeBandFakeEmbedder(),
            graphConfig().copy(autoLinkThreshold = 0.5, dedupThreshold = 0.95, judgeFloor = 0.68),
            now = clock,
            judge = neverCalled,
        )
        val m1 = g.remember("fold|x|a|1", layer = Engram.Layer.SEMANTIC)
        g.putScene(record(1, first = 1, last = 1))
        g.addSceneMembers(1, listOf(m1))
        val hub = g.materializeScene(g.scene(1)!!, "fold|x|hubtext|9", listOf(m1))
        assertEquals(setOf(m1), g.neighbors(hub).map { it.to }.toSet(), "only its EPISODE_OF edge")

        val again = g.remember("fold|x|hubtext|9", layer = Engram.Layer.SEMANTIC)
        assertNotEquals(hub, again, "a fact never folds into a hub")
        assertTrue(g.neighbors(again).none { it.to == hub }, "a new fact is not auto-linked to a hub")
        g.mergeDuplicates()
        assertNotNull(g.engram(hub), "a hub is never merged away")
        val other = g.remember("fold|x|other|2", layer = Engram.Layer.SEMANTIC)
        val pending = g.judgeCandidates().pending
        assertTrue(pending.any { setOf(it.aId, it.bId) == setOf(m1, other) }, "precondition: in-band facts are judged")
        assertTrue(pending.none { hub == it.aId || hub == it.bId }, "a hub is never judged")
    }

    @Test
    fun `hubs survive a reload`() {
        val store = TestLatticeStore()
        val g = LatticeGraph(BgeBandFakeEmbedder(), graphConfig(), now = clock, store = store)
        val hubs = (13L..15L).map { sceneId ->
            val ms = (1..3).map { g.remember("scene$sceneId|m|$it|x", layer = Engram.Layer.SEMANTIC) }
            g.putScene(record(sceneId, dateFrom = "2026-08-0${sceneId - 10}"))
            g.addSceneMembers(sceneId, ms)
            g.materializeScene(g.scene(sceneId)!!, "场景$sceneId 的叙述", ms)
        }

        val reloaded = LatticeGraph(BgeBandFakeEmbedder(), graphConfig(), now = clock, store = TestLatticeStore(initial = store.load()))

        for ((i, sceneId) in (13L..15L).withIndex()) {
            assertEquals(hubs[i], reloaded.scene(sceneId)!!.hubEngramId)
            val hub = reloaded.engram(hubs[i])!!
            assertTrue(hub.isEpisodeHub)
            assertEquals(Engram.Origin.HUB, hub.origin)
            assertEquals(3, reloaded.episodeMembers(hub.id).size)
            assertEquals(hub.id, reloaded.sceneOfHub(hub.id)?.hubEngramId)
        }
    }

    // --- writing a hub -----------------------------------------------------------------------------------------------

    /** Counts embed calls. */
    private class CountingEmbedder(private val inner: Embedder = BgeBandFakeEmbedder()) : Embedder by inner {
        var count = 0
        override fun embed(text: String): FloatArray {
            count++
            return inner.embed(text)
        }
    }

    private fun sceneGraph(embedder: Embedder, config: LatticeGraph.Config = LatticeGraph.Config(autoLinkThreshold = 0.99, dedupThreshold = 0.99)): Pair<LatticeGraph, List<Long>> {
        val g = LatticeGraph(embedder, config, now = clock)
        val members = listOf(g.remember("trip|tunnel|t1", layer = Engram.Layer.SEMANTIC), g.remember("trip|food|f1", layer = Engram.Layer.SEMANTIC))
        g.putScene(SceneRecord(1, "隧道", CONV_A, CHAT_A, 100, 150, "2026-09-20", "2026-09-20"))
        g.addSceneMembers(1, members)
        return g to members
    }

    @Test
    fun `materializeScene with a precomputed narration vector makes no embedding call inside the graph`() {
        val counting = CountingEmbedder()
        val (g, members) = sceneGraph(counting)
        val narration = "  narr|tunnel|story  "
        val vector = counting.embed(narration.trim()) // "off the graph thread"
        val before = counting.count

        val hub = g.materializeScene(g.scene(1)!!, narration, vector, members)

        assertTrue(hub > 0)
        assertEquals(before, counting.count, "no embedding inside the graph call")
        assertTrue(vector.contentEquals(g.engram(hub)!!.embedding))

        // The other overload embeds itself, to the same hub.
        val (g2, members2) = sceneGraph(CountingEmbedder())
        val hub2 = g2.materializeScene(g2.scene(1)!!, narration, members2)
        assertEquals(g.engram(hub)!!.content, g2.engram(hub2)!!.content)
        assertTrue(g.engram(hub)!!.embedding.contentEquals(g2.engram(hub2)!!.embedding))
    }

    @Test
    fun `a scene hub anchors to the scene's last message and follows a continued scene`() {
        val (g, members) = sceneGraph(BgeBandFakeEmbedder())
        val hub = g.materializeScene(g.scene(1)!!.copy(inProgress = true), "narr|tunnel|story", members)
        assertEquals(150L, g.engram(hub)!!.sourceMessageId)

        g.putScene(g.scene(1)!!.copy(lastMessageId = 180, inProgress = false))
        assertEquals(hub, g.materializeScene(g.scene(1)!!, "narr|tunnel|story2", members))
        assertEquals(180L, g.engram(hub)!!.sourceMessageId, "re-narration moves the anchor to the scene's new end")
    }

    @Test
    fun `a hub written from a stale scene record keeps the fresher range and dates written meanwhile`() {
        val (g, members) = sceneGraph(BgeBandFakeEmbedder())
        val stale = g.scene(1)!!.copy(inProgress = true) // read when the narration started: 100..150, 09-20
        // Meanwhile the capture thread extended the OPEN scene.
        g.putScene(g.scene(1)!!.copy(lastMessageId = 180, dateTo = "2026-09-21", inProgress = true))

        val hub = g.materializeScene(stale, "narr|tunnel|story", members)

        val now = g.scene(1)!!
        assertEquals(hub, now.hubEngramId)
        assertEquals(100L, now.firstMessageId)
        assertEquals(180L, now.lastMessageId, "the narration did not roll the range back")
        assertEquals("2026-09-20", now.dateFrom)
        assertEquals("2026-09-21", now.dateTo)
        assertEquals(180L, g.engram(hub)!!.sourceMessageId, "the hub anchors to the scene's current end")
    }

    @Test
    fun `a narration carrying the host's prompt scaffolding is refused and writes nothing`() {
        val markers = LatticeGraph.Config(
            autoLinkThreshold = 0.99, dedupThreshold = 0.99,
            scaffoldMarkers = listOf("## Message Context", "## User Message", "## Relevant Memory"),
        )
        for (bad in listOf(
            "## Message Context\n- Sender: Ethan\n\n## User Message\n我们去看海吧",
            "他说：## User Message 我们去看海吧",
            "## Relevant Memory\n- 旧记忆",
        )) {
            val (g, members) = sceneGraph(BgeBandFakeEmbedder(), markers)
            val size = g.size
            assertEquals(LatticeGraph.REFUSED, g.materializeScene(g.scene(1)!!, bad, members), bad)
            assertEquals(size, g.size, "no hub written")
            assertNull(g.scene(1)!!.hubEngramId)
        }
        val (g, members) = sceneGraph(BgeBandFakeEmbedder(), markers)
        assertTrue(g.materializeScene(g.scene(1)!!, "我们在隧道里聊了很久，## 不是标题", members) > 0, "an ordinary # is fine")
        val (unchecked, plain) = sceneGraph(BgeBandFakeEmbedder())
        assertTrue(unchecked.materializeScene(unchecked.scene(1)!!, "## Relevant Memory\n- 旧记忆", plain) > 0, "no markers, no check")
    }

    // --- indexes ----------------------------------------------------------------------------------------------------

    /** The answers the indexes must give, computed by scanning every thread of every chat through the public API. */
    private fun assertIndexesMatchScan(g: LatticeGraph) {
        val threads = g.threads()
        val links = threads.flatMap { g.threadLinks(it.id) }
        fun closed(id: Long) = g.thread(id)?.state == ThreadState.CLOSED
        for (e in g.engrams()) {
            val id = e.id
            assertEquals(links.filter { it.engramId == id }.sortedBy { it.threadId }, g.threadLinksOf(id), "threadLinksOf($id)")
            val closedSetup = links.any { it.engramId == id && it.role == ThreadRole.SETUP && closed(it.threadId) }
            assertEquals(closedSetup, g.isClosedThreadSetup(id), "isClosedThreadSetup($id)")
            val resolves = links.filter { it.engramId == id && it.role == ThreadRole.SETUP && closed(it.threadId) }
                .map { it.threadId }.distinct().sorted()
                .flatMap { t -> g.threadLinks(t).filter { it.role == ThreadRole.RESOLVE }.map { it.engramId } }
                .filter { it != id && g.engram(it)?.isEpisodeHub == false }
                .distinct()
            assertEquals(resolves, g.closedResolvesOf(id), "closedResolvesOf($id)")
            val status = g.threadStatusOf(id)
            if (threads.none { it.state == ThreadState.CLOSED }) {
                assertEquals(LatticeGraph.ThreadStatus.NONE, status)
            } else if (!e.isEpisodeHub) {
                val setupOf = links.filter { it.engramId == id && it.role == ThreadRole.SETUP }.map { it.threadId }.toSet()
                assertEquals(threads.filter { it.id in setupOf && it.state == ThreadState.CLOSED }.map { it.id }, status.closed.map { it.id }, "closed($id)")
                assertEquals(
                    threads.filter { it.id in setupOf && (it.state == ThreadState.OPEN || it.state == ThreadState.STALE) }.map { it.id },
                    status.open.map { it.id },
                    "open($id)",
                )
            }
        }
        for (chat in threads.map { it.chatKey }.toSet()) {
            assertEquals(threads.filter { it.chatKey == chat }, g.threads(chat), "threads($chat)")
        }
    }

    @Test
    fun `thread indexes stay in step through links, state changes, absorb, forget and reload`() {
        val store = TestLatticeStore()
        val config = LatticeGraph.Config(dedupThreshold = 1.01, nodePruneFloor = 0.5)
        val g = LatticeGraph(LocalHashingEmbedder(), config, now = clock, store = store)
        val setup = g.remember("用户说要给妹妹一个周末的惊喜", salience = 0.6)
        val survivor = g.remember("用户说要给妹妹一个周末的惊喜！", salience = 0.8)
        val forgotten = g.remember("明天去杭州出差的车票还没买", salience = 0.05)
        val reveal = g.remember("惊喜是河边的生日野餐", salience = 0.9, layer = Engram.Layer.SEMANTIC)
        val other = g.remember("妹妹最喜欢粉色的气球", salience = 0.9, layer = Engram.Layer.SEMANTIC)

        val tA = g.putThread(ThreadRecord(0, CHAT_A, "周末的惊喜是什么"))
        val tB = g.putThread(ThreadRecord(0, CHAT_B, "另一个聊天的约定"))
        val tA2 = g.putThread(ThreadRecord(0, CHAT_A, "出差的车票"))
        g.linkThread(tA, setup, ThreadRole.SETUP)
        g.linkThread(tA, forgotten, ThreadRole.SETUP)
        g.linkThread(tA, reveal, ThreadRole.RESOLVE)
        g.linkThread(tB, other, ThreadRole.SETUP)
        g.linkThread(tA2, forgotten, ThreadRole.SETUP)
        g.linkThread(tA2, other, ThreadRole.SETUP)
        assertIndexesMatchScan(g) // nothing closed yet: the fast path

        g.putThread(g.thread(tA)!!.copy(state = ThreadState.CLOSED, closedSceneId = 4))
        assertIndexesMatchScan(g)
        assertEquals(listOf(reveal), g.closedResolvesOf(setup))
        assertEquals(listOf(tA, tA2), g.threads(CHAT_A).map { it.id })
        assertEquals(listOf(tB), g.threads(CHAT_B).map { it.id })

        // Absorb: the set-up's links move to the survivor.
        assertEquals(1, g.mergeDuplicates(threshold = 0.8, runJudgedPass = false))
        assertNull(g.engram(setup))
        assertEquals(emptyList(), g.threadLinksOf(setup))
        assertTrue(g.isClosedThreadSetup(survivor))
        assertIndexesMatchScan(g)

        // Forget: the link rows go, the threads stay.
        g.decay()
        assertNull(g.engram(forgotten))
        assertEquals(emptyList(), g.threadLinksOf(forgotten))
        assertEquals(listOf(other), g.threadLinks(tA2).map { it.engramId })
        assertIndexesMatchScan(g)

        // Re-opened and closed again: the closed count follows the state.
        g.putThread(g.thread(tA)!!.copy(state = ThreadState.OPEN))
        assertFalse(g.isClosedThreadSetup(survivor))
        assertEquals(LatticeGraph.ThreadStatus.NONE, g.threadStatusOf(survivor))
        assertIndexesMatchScan(g)
        g.putThread(g.thread(tA)!!.copy(state = ThreadState.CLOSED))
        assertIndexesMatchScan(g)

        val reloaded = LatticeGraph(LocalHashingEmbedder(), config, now = clock, store = TestLatticeStore(initial = store.load()))
        assertTrue(reloaded.isClosedThreadSetup(survivor), "rebuilt on load")
        assertIndexesMatchScan(reloaded)
    }

    @Test
    fun `scenes of a chat come from the per-chat index, in scene order, and follow a moved scene`() {
        val g = LatticeGraph(LocalHashingEmbedder(), now = clock)
        fun rec(id: Long, chat: String, day: Int) = SceneRecord(
            id, "s$id", "$chat:s1", chat, id * 10, id * 10 + 5,
            dateFrom = "2026-09-%02d".format(day), startedAt = Instant.parse("2026-09-%02dT10:00:00Z".format(day)),
        )
        g.putScene(rec(3, CHAT_A, day = 1))
        g.putScene(rec(1, CHAT_A, day = 2))
        g.putScene(rec(2, CHAT_B, day = 3))
        assertEquals(listOf(3L, 1L), g.scenes(CHAT_A).map { it.sceneId }, "time order, not id order")
        assertEquals(listOf(2L), g.scenes(CHAT_B).map { it.sceneId })
        g.putScene(rec(3, CHAT_B, day = 1))
        assertEquals(listOf(1L), g.scenes(CHAT_A).map { it.sceneId })
        assertEquals(listOf(3L, 2L), g.scenes(CHAT_B).map { it.sceneId })
        assertEquals(emptyList(), g.scenes("telegram:9"))
    }

    private companion object {
        const val CHAT_A = "telegram:1"
        const val CHAT_B = "telegram:2"
        const val CONV_A = "telegram:1:s1"
    }
}
