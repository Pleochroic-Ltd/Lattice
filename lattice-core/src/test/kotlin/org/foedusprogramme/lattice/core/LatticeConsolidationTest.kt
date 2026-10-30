package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the sleep-consolidation lifecycle: episodic -> semantic promotion, merge, and
 * synthesis. Offline — toy embedder; reloads go through [TestLatticeStore].
 */
class LatticeConsolidationTest {

    private val clock = { Instant.parse("2026-07-31T00:00:00Z") }
    private fun graph() = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(), now = clock)

    @Test
    fun consolidatePromotesWellReplayedEpisodicToSemantic() {
        val g = graph()
        val id = g.remember("某次线上部署临时救火的经历", salience = 0.5, layer = Engram.Layer.EPISODIC)
        // Simulate the memory having been recalled several times (hippocampal replay).
        g.engrams().first { it.id == id }.activationCount = 6

        val result = g.consolidate()

        assertEquals(1, result.promoted, "a heavily-replayed episodic memory should consolidate")
        assertEquals(Engram.Layer.SEMANTIC, g.engrams().first { it.id == id }.layer, "layer flips to semantic")
    }

    @Test
    fun coRetrievalDegreeAloneDoesNotPromoteWithoutGenuineReplay() {
        // The passive (SOFT) path grows CO_RETRIEVAL degree without bumping activationCount. A hub
        // with high degree + high salience but zero replay must NOT promote — else passive
        // relevance would re-open the reinforce loop through the wiring term.
        val g = graph()
        val hub = g.remember("被反复共现但从没被显式搜过的中心记忆", salience = 0.7, layer = Engram.Layer.EPISODIC)
        // Distinct neighbors (so they don't dedup into one) — as SOFT wiring would connect a hub
        // to many different current hits across a long conversation.
        listOf("喝咖啡", "去爬山", "看电影", "炒股票", "逛京都", "做红烧肉", "戴机械表", "听黑胶", "逛菜市场", "刷短视频").forEach { t ->
            val other = g.remember(t, salience = 0.5)
            g.link(hub, other, Edge.Type.CO_RETRIEVAL, 0.5)
        }
        assertTrue(g.neighbors(hub).size >= 9, "precondition: hub has high degree")
        assertEquals(0, g.engram(hub)!!.activationCount, "precondition: never explicitly searched")

        val result = g.consolidate()

        assertEquals(0, result.promoted, "degree/co-retrieval alone must not promote without genuine replay")
        assertEquals(Engram.Layer.EPISODIC, g.engram(hub)!!.layer)
    }

    private val distinctTopics =
        listOf("喝咖啡", "去爬山", "看电影", "炒股票", "逛京都", "做红烧肉", "戴机械表", "听黑胶", "逛菜市场", "刷短视频")

    @Test
    fun coRetrievalDegreeDoesNotCountTowardConsolidationEvenPastTheGate() {
        // The gate is cleared (softReplays 4 -> replayEvidence 1.0), so this isolates the STRUCTURAL
        // exclusion: 10 CO_RETRIEVAL edges must NOT push readiness over — if they counted, degree 10
        // would (0.15 + 10*0.08 = 0.95). Passive wiring (CO_RETRIEVAL) can never drive promotion.
        val g = graph()
        val hub = g.remember("被动相关过、又被大量共现连边的记忆", salience = 0.6, layer = Engram.Layer.EPISODIC)
        g.engram(hub)!!.softReplays = 4
        distinctTopics.forEach { g.link(hub, g.remember(it, salience = 0.5), Edge.Type.CO_RETRIEVAL, 0.6) }

        assertEquals(0, g.consolidate().promoted, "CO_RETRIEVAL degree is excluded from wiring — passive wiring can't promote")
    }

    @Test
    fun structuralDegreePlusSustainedRelevancePromotesAHub() {
        // Positive control: genuine STRUCTURAL neighbors do count. softReplays 4 (replayEvidence 1.0)
        // + 9 SEMANTIC neighbors -> readiness 0.15 + 9*0.08 = 0.87 >= 0.7.
        val g = graph()
        val hub = g.remember("结构上很中心的重要事实", salience = 0.6, layer = Engram.Layer.EPISODIC)
        g.engram(hub)!!.softReplays = 4
        distinctTopics.take(9).forEach { g.link(hub, g.remember(it, salience = 0.5), Edge.Type.SEMANTIC, 0.7) }

        assertEquals(1, g.consolidate().promoted, "structural degree + sustained relevance consolidates")
    }

    @Test
    fun sustainedPassiveRelevancePromotesButAFewSurfacesDoNot() {
        // Passive (SOFT) surfaces accumulate softReplays; enough of them consolidate a fact even
        // without any explicit search — but a few are not enough (weighted at 0.25 each).
        val g = graph()
        val sustained = g.remember("反复相关但从没被显式搜过的重要事实", salience = 0.6, layer = Engram.Layer.EPISODIC)
        g.engram(sustained)!!.softReplays = 40 // 40*0.25 = 10 replay-evidence, capped at replayCap 8
        val occasional = g.remember("只偶尔被动相关的小事", salience = 0.6, layer = Engram.Layer.EPISODIC)
        g.engram(occasional)!!.softReplays = 4 // 4*0.25 = 1 replay-evidence — not enough

        g.consolidate()

        assertEquals(Engram.Layer.SEMANTIC, g.engram(sustained)!!.layer, "persistent passive relevance consolidates, no explicit search needed")
        assertEquals(Engram.Layer.EPISODIC, g.engram(occasional)!!.layer, "a few passive surfaces are not enough")
    }

    @Test
    fun consolidateLeavesAnUnreadyEpisodicUnpromoted() {
        val g = graph()
        val id = g.remember("刚刚发生、还没被回忆过的小事", salience = 0.4, layer = Engram.Layer.EPISODIC)

        val result = g.consolidate()

        assertEquals(0, result.promoted, "an un-replayed episodic memory must not promote yet")
        assertEquals(Engram.Layer.EPISODIC, g.engrams().first { it.id == id }.layer)
    }

    @Test
    fun consolidationSurvivesPersistenceReopen() {
        val store = TestLatticeStore()
        val id: Long
        run {
            val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(), now = clock, store = store)
            id = g.remember("被反复回忆的重要经历", salience = 0.6, layer = Engram.Layer.EPISODIC)
            g.engrams().first { it.id == id }.activationCount = 8
            g.consolidate()
        }
        // Reopen from the store: the promotion (layer) must have persisted.
        val g2 = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(), now = clock, store = TestLatticeStore(initial = store.load()))
        val e = g2.engrams().first { it.id == id }
        assertEquals(Engram.Layer.SEMANTIC, e.layer, "consolidated layer survives restart")
    }

    @Test
    fun mergeDuplicatesFoldsNearIdenticalEngrams() {
        // A high dedup threshold lets near-duplicates coexist after ingest; the sleep
        // merge pass then folds them (the "these should be one node" fix).
        val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(dedupThreshold = 0.999), now = clock)
        g.remember("用户喜欢喝美式咖啡不加糖", salience = 0.6)
        g.remember("用户喜欢喝美式咖啡不加糖也不加奶", salience = 0.7)
        g.remember("周末去爬山天气很好", salience = 0.5)
        assertEquals(3, g.size)

        val merged = g.mergeDuplicates(threshold = 0.7)

        assertTrue(merged >= 1, "the two near-identical coffee memories should merge")
        assertEquals(2, g.size, "the duplicate is folded; the unrelated memory survives")
        assertTrue(g.engrams().any { it.content.contains("爬山") }, "unrelated memory untouched")
    }

    @Test
    fun mergedDuplicateStaysGoneAfterReopen() {
        val store = TestLatticeStore()
        run {
            val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(dedupThreshold = 0.999), now = clock, store = store)
            g.remember("用户喜欢喝美式咖啡不加糖", salience = 0.6)
            g.remember("用户喜欢喝美式咖啡不加糖也不加奶", salience = 0.7)
            assertEquals(2, g.size)
            g.mergeDuplicates(threshold = 0.7)
            assertEquals(1, g.size)
        }
        val g2 = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(), now = clock, store = TestLatticeStore(initial = store.load()))
        assertEquals(1, g2.size, "the merged-away duplicate is gone from the store after reopen")
    }

    @Test
    fun synthesizeParentAddsHierarchyAndKeepsChildren() {
        val g = graph()
        val a = g.remember("锚点甲各不相同的内容", salience = 0.6)
        val b = g.remember("锚点乙完全不同的话题", salience = 0.6)
        val c = g.remember("锚点丙另一段独立记忆", salience = 0.6)
        val before = g.size

        val parent = g.synthesizeParent(listOf(a, b, c), "这三条记忆的共同要点", salience = 0.8)

        assertEquals(before + 1, g.size, "parent added, children kept (no collapse)")
        val absChildren = g.neighbors(parent).filter { it.type == Edge.Type.ABSTRACTS }.map { it.to }.toSet()
        assertEquals(setOf(a, b, c), absChildren, "parent ABSTRACTS all three children")
        assertTrue(g.engram(a) != null && g.engram(b) != null && g.engram(c) != null, "children survive")
    }

    @Test
    fun synthesizedParentSurvivesTheMergePassInSameSleep() {
        // A synthesized parent gist can be near-identical to a child (cosine ~1.0). The merge
        // pass must NOT fold it away, or synthesis is silently undone within one sleep.
        val g = graph()
        val child = g.remember("用户喜欢美式咖啡不加糖", salience = 0.5)
        val parent = g.synthesizeParent(listOf(child), "用户喜欢美式咖啡不加糖", salience = 0.7)

        val merged = g.mergeDuplicates(threshold = 0.7) // low threshold; parent≈child would fold without the guard

        assertEquals(0, merged, "the ABSTRACTS parent/child pair is not merged despite high similarity")
        assertTrue(g.engram(parent) != null && g.engram(child) != null, "both the parent and its child survive")
        assertTrue(
            g.neighbors(parent).any { it.type == Edge.Type.ABSTRACTS && it.to == child },
            "the ABSTRACTS hierarchy edge is intact after the merge pass",
        )
    }
}
