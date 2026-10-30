package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenes (hub metadata + members), the thread ledger, the engram `origin` and the RESOLVES edge through a reload
 * of the graph from its store ([TestLatticeStore]): what the scene index writes through is what it reads back.
 * Offline ([LocalHashingEmbedder]).
 */
class SceneIndexReloadTest {

    private val clock = { Instant.parse("2026-09-25T00:00:00Z") }

    /** Encode never folds (threshold > 1), so near-duplicates stay two nodes until [LatticeGraph.mergeDuplicates]. */
    private val noEncodeFold = LatticeGraph.Config(dedupThreshold = 1.01)

    private fun graphOn(store: LatticeStore, config: LatticeGraph.Config = noEncodeFold) =
        LatticeGraph(LocalHashingEmbedder(), config, now = clock, store = store)

    /** A second graph over everything the first one wrote. */
    private fun reopen(store: TestLatticeStore, config: LatticeGraph.Config = noEncodeFold) =
        graphOn(TestLatticeStore(initial = store.load()), config)

    private fun scene(id: Long, hub: Long? = null) = SceneRecord(
        sceneId = id, title = "周末的惊喜", conversationId = "telegram:1:s1", chatKey = "telegram:1",
        firstMessageId = 10, lastMessageId = 20, dateFrom = "2026-09-20", dateTo = "2026-09-20",
        inProgress = false, hubEngramId = hub,
    )

    @Test
    fun `a thread's short name survives reopen and a thread without one stays unnamed`() {
        val store = TestLatticeStore()
        val named: Long
        val plain: Long
        run {
            val g = graphOn(store)
            plain = g.putThread(ThreadRecord(id = 0, chatKey = "telegram:1", description = "旧线索"))
            named = g.putThread(
                ThreadRecord(id = 0, chatKey = "telegram:1", description = "Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物", name = "周末的惊喜礼物"),
            )
            assertTrue(named != plain, "each thread gets its own id")
        }
        val g2 = reopen(store)
        assertEquals("周末的惊喜礼物", g2.thread(named)!!.name)
        assertEquals("旧线索", g2.thread(plain)!!.description)
        assertNull(g2.thread(plain)!!.name)
    }

    @Test
    fun `new writes carry an explicit origin that survives reopen`() {
        val store = TestLatticeStore()
        val manual: Long
        val extracted: Long
        val verbatim: Long
        val parent: Long
        run {
            val g = graphOn(store)
            manual = g.remember("用户的猫叫年糕", origin = Engram.Origin.MANUAL)
            extracted = g.remember("用户周末去爬了香山", origin = Engram.Origin.EXTRACTED)
            verbatim = g.addVerbatim(extracted, "香山的红叶好看极了", Speaker.USER)
            val sibling = g.remember("用户周六去爬了香山", origin = Engram.Origin.EXTRACTED)
            parent = g.synthesizeParent(listOf(extracted, sibling), "用户喜欢周末去香山爬山")
        }
        val byId = reopen(store).engrams().associateBy { it.id }
        assertEquals(Engram.Origin.MANUAL, byId.getValue(manual).origin)
        assertEquals(Engram.Origin.EXTRACTED, byId.getValue(extracted).origin)
        assertEquals(Engram.Origin.VERBATIM, byId.getValue(verbatim).origin)
        assertTrue(parent > 0, "the parent materialized")
        assertEquals(Engram.Origin.SYNTHESIZED, byId.getValue(parent).origin)
    }

    @Test
    fun `a manual save that later absorbs an extracted duplicate stays manual`() {
        val g = LatticeGraph(LocalHashingEmbedder(), now = clock) // default dedup: the second write folds
        val id = g.remember("用户的猫叫年糕", origin = Engram.Origin.EXTRACTED, sourceMessageId = 5)
        assertEquals(id, g.remember("用户的猫叫年糕", origin = Engram.Origin.MANUAL))
        assertEquals(Engram.Origin.MANUAL, g.engram(id)!!.origin, "an explicit-save re-mention marks the node manual")
        g.remember("用户的猫叫年糕", origin = Engram.Origin.EXTRACTED)
        assertEquals(Engram.Origin.MANUAL, g.engram(id)!!.origin, "an extracted re-mention never demotes manual")
    }

    @Test
    fun `deleting a hub nulls the scene pointer and deleting a member drops its membership`() {
        val store = TestLatticeStore()
        // Low-salience EPISODIC nodes are pruned by the next decay pass (nodePruneFloor 0.5).
        val config = noEncodeFold.copy(nodePruneFloor = 0.5)
        val hub: Long
        val member: Long
        val keeper: Long
        run {
            val g = graphOn(store, config)
            hub = g.remember("周末的惊喜：用户给妹妹准备了一场生日野餐", salience = 0.05)
            member = g.remember("用户订了河边的野餐位", salience = 0.05)
            keeper = g.remember("妹妹最喜欢粉色的气球", salience = 0.9, layer = Engram.Layer.SEMANTIC)
            g.putScene(scene(7, hub = hub))
            g.addSceneMembers(7, listOf(member, keeper))
            assertEquals(setOf(member, keeper), g.sceneMembers(7))
            g.decay()
            assertNull(g.engram(hub))
            assertNull(g.scene(7)!!.hubEngramId, "RAM cache nulls the hub pointer")
            assertEquals(setOf(keeper), g.sceneMembers(7), "RAM cache drops the forgotten member")
        }
        val g2 = reopen(store, config)
        val reloaded = assertNotNull(g2.scene(7), "the scene row survives its hub")
        assertNull(reloaded.hubEngramId, "the store nulls a deleted hub's pointer")
        assertEquals("周末的惊喜", reloaded.title)
        assertEquals(setOf(keeper), g2.sceneMembers(7), "the member row cascaded away")
        assertNull(store.scenes.getValue(7).hubEngramId, "the store row itself, not only the reload")
        assertEquals(setOf(7L), g2.scenesOf(keeper))
    }

    @Test
    fun `absorbing a set-up memory repoints its thread links to the survivor`() {
        val store = TestLatticeStore()
        val setup: Long
        val survivor: Long
        val thread: Long
        run {
            val g = graphOn(store)
            setup = g.remember("用户说要给妹妹一个周末的惊喜", salience = 0.3)
            survivor = g.remember("用户说要给妹妹一个周末的惊喜！", salience = 0.8)
            assertTrue(cosine(g.engram(setup)!!.embedding, g.engram(survivor)!!.embedding) > 0.8)
            g.putScene(scene(3))
            g.addSceneMembers(3, listOf(setup))
            thread = g.putThread(ThreadRecord(id = 0, chatKey = "telegram:1", description = "周末的惊喜是什么", openedSceneId = 3))
            g.linkThread(thread, setup, ThreadRole.SETUP)
            assertEquals(1, g.mergeDuplicates(threshold = 0.8, runJudgedPass = false))
            assertNull(g.engram(setup), "the lower-salience set-up was absorbed")
            assertEquals(listOf(ThreadLinkRecord(thread, survivor, ThreadRole.SETUP)), g.threadLinks(thread))
            assertEquals(setOf(survivor), g.sceneMembers(3), "scene membership follows the survivor")
        }
        val g2 = reopen(store)
        assertEquals(listOf(ThreadLinkRecord(thread, survivor, ThreadRole.SETUP)), g2.threadLinks(thread))
        assertEquals(ThreadState.OPEN, g2.thread(thread)!!.state, "thread state untouched by the merge")
        assertEquals(setOf(survivor), g2.sceneMembers(3))
    }

    @Test
    fun `a closed thread's resolves come back in the same order whatever order the store keeps its links in`() {
        val store = TestLatticeStore()
        val setup: Long
        val before: List<Long>
        run {
            val g = graphOn(store)
            setup = g.remember("用户说要给妹妹一个周末的惊喜")
            val resolves = listOf("惊喜是河边的生日野餐", "野餐时他们看到了萤火虫").map { g.remember(it) }
            val thread = g.putThread(ThreadRecord(id = 0, chatKey = "telegram:1", description = "周末的惊喜是什么"))
            g.linkThread(thread, setup, ThreadRole.SETUP)
            resolves.asReversed().forEach { g.linkThread(thread, it, ThreadRole.RESOLVE) }
            g.putThread(g.thread(thread)!!.copy(state = ThreadState.CLOSED, closedSceneId = 4))
            before = g.closedResolvesOf(setup)
            assertEquals(resolves, before, "oldest first, not in link order")
        }
        // A store that hands the links back in another order (as SQLite does after a merge moved one).
        val loaded = store.load()
        val g2 = graphOn(TestLatticeStore(initial = loaded.copy(threadLinks = loaded.threadLinks.asReversed())))
        assertEquals(before, g2.closedResolvesOf(setup))
    }

    @Test
    fun `forgetting a set-up memory deletes its links but keeps the thread`() {
        val store = TestLatticeStore()
        val config = noEncodeFold.copy(nodePruneFloor = 0.5)
        val thread: Long
        val resolve: Long
        run {
            val g = graphOn(store, config)
            val setup = g.remember("用户说要给妹妹一个周末的惊喜", salience = 0.05)
            resolve = g.remember("惊喜是河边的生日野餐", salience = 0.9, layer = Engram.Layer.SEMANTIC)
            thread = g.putThread(ThreadRecord(id = 0, chatKey = "telegram:1", description = "周末的惊喜是什么"))
            g.linkThread(thread, setup, ThreadRole.SETUP)
            g.linkThread(thread, resolve, ThreadRole.RESOLVE)
            g.putThread(g.thread(thread)!!.copy(state = ThreadState.CLOSED, closedSceneId = 4))
            g.decay()
            assertNull(g.engram(setup))
            assertEquals(listOf(ThreadLinkRecord(thread, resolve, ThreadRole.RESOLVE)), g.threadLinks(thread))
        }
        val g2 = reopen(store, config)
        val t = assertNotNull(g2.thread(thread), "the thread row survives")
        assertEquals(ThreadState.CLOSED, t.state)
        assertEquals(4L, t.closedSceneId)
        assertEquals(listOf(ThreadLinkRecord(thread, resolve, ThreadRole.RESOLVE)), g2.threadLinks(thread))
        // Ids continue after reload: the next thread id is new to the whole graph (one sequence for every kind).
        val next = g2.putThread(ThreadRecord(0, "telegram:1", "另一条线索"))
        assertTrue(next > thread && next > resolve, "ids continue past every loaded id: $next")
        assertTrue(g2.engram(next) == null, "a thread id never names an engram")
    }

    @Test
    fun `a RESOLVES edge persists, is never budget-evicted and never spreads`() {
        val store = TestLatticeStore()
        val config = noEncodeFold.copy(maxDegree = 1, seedCount = 1)
        val a: Long
        val b: Long
        run {
            val g = graphOn(store, config)
            a = g.remember("用户说要给妹妹一个周末的惊喜")
            b = g.remember("河边生日野餐的座位订好了")
            val c = g.remember("用户家楼下新开了一家咖啡店")
            val d = g.remember("周三要交季度报告")
            g.link(a, b, Edge.Type.RESOLVES, 0.1)
            g.link(a, c, Edge.Type.SEMANTIC, 0.9)
            g.link(a, d, Edge.Type.SEMANTIC, 0.8)
            g.decay()
            assertEquals(Edge.Type.RESOLVES, g.neighbors(a).first { it.to == b }.type, "protected from the budget")
        }
        val g2 = reopen(store, config)
        val edge = assertNotNull(g2.neighbors(a).firstOrNull { it.to == b }, "the RESOLVES edge reloads")
        assertEquals(Edge.Type.RESOLVES, edge.type)
        assertEquals(a to b, g2.edgeBetween(a, b)!!.let { it.from to it.to }, "it keeps its resolve -> set-up direction")

        val reached = g2.recall("用户说要给妹妹一个周末的惊喜", reinforce = Reinforce.NONE, applyEmitGate = false)
            .map { it.engram.id }
        assertTrue(a in reached, "the set-up seeds: $reached")
        assertTrue(b !in reached, "activation never crosses a RESOLVES edge: $reached")

        // Control: the same topology over a spreading edge does reach b, so the assertion above has teeth.
        val ctl = LatticeGraph(LocalHashingEmbedder(), config, now = clock)
        val ca = ctl.remember("用户说要给妹妹一个周末的惊喜")
        val cb = ctl.remember("河边生日野餐的座位订好了")
        ctl.link(ca, cb, Edge.Type.CAUSAL, 0.9)
        assertTrue(cb in ctl.recall("用户说要给妹妹一个周末的惊喜", reinforce = Reinforce.NONE, applyEmitGate = false).map { it.engram.id })
    }
}
