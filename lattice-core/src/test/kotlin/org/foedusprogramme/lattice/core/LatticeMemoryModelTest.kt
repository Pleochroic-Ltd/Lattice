package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memory model: the automatic recall learns softly, recall has an absolute relevance gate, promotion needs
 * salience, forgetting is salience-driven and reaps isolated semantic zombies, clusters need cohesion, and a
 * superseded fact gives way to its successor.
 */
class LatticeMemoryModelTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }

    private fun graph(config: LatticeGraph.Config = LatticeGraph.Config(), store: LatticeStore = NoOpLatticeStore) =
        LatticeGraph(LocalHashingEmbedder(), config, now = clock, store = store)

    @Test
    fun aSoftRecallKeepsAMemoryAliveWithoutPromotingItAndAFullOneCountsAsReplay() {
        val g = graph()
        val a = g.remember("部署 llama-server 报错 CUDA 显存不足", salience = 0.3)
        g.remember("把 n_ctx 从 8192 降到 4096 就不再爆显存", salience = 0.3)
        g.recall("部署 显存 报错", reinforce = Reinforce.SOFT)
        assertEquals(0, g.engram(a)!!.activationCount, "SOFT is no replay")
        assertTrue(g.engram(a)!!.salience > 0.3, "SOFT recovers salience (capped)")
        g.recall("部署 显存 报错", reinforce = Reinforce.FULL)
        assertTrue(g.engram(a)!!.activationCount > 0, "FULL is a replay")
    }

    @Test
    fun theSeedFloorSilencesAnIrrelevantCue() {
        val g = graph(LatticeGraph.Config(seedFloor = 0.5))
        g.remember("部署失败排查记录", salience = 0.6)
        assertTrue(g.recall("部署失败排查记录").isNotEmpty())
        assertTrue(g.recall("部署文档").isEmpty(), "below the floor: nothing")
    }

    @Test
    fun replayAloneDoesNotPromoteWithoutSalience() {
        val g = graph()
        val id = g.remember("一条不重要但被反复检索的琐事", salience = 0.1, layer = Engram.Layer.EPISODIC)
        g.engram(id)!!.activationCount = 20
        assertEquals(0, g.consolidate().promoted)
        assertEquals(Engram.Layer.EPISODIC, g.engram(id)!!.layer)
    }

    @Test
    fun aGenuineRecallRecoversSalience() {
        val g = graph()
        val id = g.remember("用户偏好简体中文", salience = 0.3, layer = Engram.Layer.SEMANTIC)
        g.recall("用户偏好简体中文", reinforce = Reinforce.FULL)
        assertTrue(g.engram(id)!!.salience > 0.3)
    }

    @Test
    fun aFadedEpisodicMemoryIsPrunedWhateverItsPastActivation() {
        val g = graph(LatticeGraph.Config(episodicSalienceDecay = 0.1, nodePruneFloor = 0.5))
        val id = g.remember("一次性的小事", salience = 0.4, layer = Engram.Layer.EPISODIC)
        g.engram(id)!!.activationCount = 5
        g.decay()
        assertNull(g.engram(id))
    }

    @Test
    fun anIsolatedSemanticZombieIsReapedAndAConnectedOneSurvives() {
        val g = graph(LatticeGraph.Config(semanticSalienceDecay = 0.1, semanticPruneFloor = 0.5))
        val lonely = g.remember("孤立无援的语义甲", salience = 0.4, layer = Engram.Layer.SEMANTIC)
        val y = g.remember("共同话题乙的内容", salience = 0.4, layer = Engram.Layer.SEMANTIC)
        val z = g.remember("共同话题乙的另一段", salience = 0.4, layer = Engram.Layer.SEMANTIC)
        assertTrue(g.neighbors(y).any { it.to == z }, "precondition: Y and Z are linked")
        g.decay()
        assertNull(g.engram(lonely))
        assertNotNull(g.engram(y))
        assertNotNull(g.engram(z))
    }

    @Test
    fun aLooseChainIsNoClusterUntilTheCohesionFloorDrops() {
        val g = graph()
        val a = g.remember("阿尔法独立话题")
        val b = g.remember("贝塔独立话题")
        val c = g.remember("伽马独立话题")
        g.link(a, b, Edge.Type.SEMANTIC, 0.6)
        g.link(b, c, Edge.Type.SEMANTIC, 0.6)
        assertTrue(g.relatedClusters(threshold = 0.65, minSize = 3).isEmpty(), "connected but incoherent")
        assertEquals(1, g.relatedClusters(threshold = 0.0, minSize = 3).size)
    }

    @Test
    fun aSupersededMemoryGivesWayToTheCurrentOne() {
        val g = graph()
        val old = g.remember("用户住在北京")
        val new = g.remember("用户搬到上海居住")
        assertEquals(old, g.recall("用户住哪", reinforce = Reinforce.NONE).first().engram.id, "precondition")
        g.markSuperseded(newId = new, oldId = old)
        assertEquals(new, g.recall("用户住哪", reinforce = Reinforce.NONE).first().engram.id)
        assertEquals(new, g.engram(old)!!.supersededBy)
        assertNotNull(g.engram(old), "kept, only down-weighted")
    }

    @Test
    fun recallShowsTheSuccessorEvenWhenTheCueMatchedTheStaleFact() {
        val g = graph()
        val old = g.remember("用户住在北京做金融工作")
        val new = g.remember("用户现在搬到上海转做设计")
        g.markSuperseded(newId = new, oldId = old)
        val ids = g.recall("用户在北京的工作和居住情况", reinforce = Reinforce.NONE).map { it.engram.id }
        assertTrue(new in ids)
        assertTrue(old !in ids, "redirected to its successor")
    }

    @Test
    fun theTwoTierGateSilencesAnIsolatedWeakSeedButNotAConnectedOne() {
        val cfg = LatticeGraph.Config(seedFloor = 0.1, emitFloor = 0.5)
        val isolated = graph(cfg)
        isolated.remember("部署失败排查记录")
        assertTrue(isolated.recall("部署文档", reinforce = Reinforce.NONE).isEmpty())
        val connected = graph(cfg)
        val a = connected.remember("部署失败排查记录")
        val b = connected.remember("把配置降到一半就修好了")
        connected.link(a, b, Edge.Type.CAUSAL, 0.9)
        assertTrue(connected.recall("部署文档", reinforce = Reinforce.NONE).isNotEmpty())
    }

    @Test
    fun pruningASuccessorClearsTheDanglingPointer() {
        val g = graph(LatticeGraph.Config(episodicSalienceDecay = 0.01, nodePruneFloor = 0.5))
        val old = g.remember("用户住在北京", salience = 0.7, layer = Engram.Layer.SEMANTIC)
        val new = g.remember("用户搬到上海", salience = 0.2, layer = Engram.Layer.EPISODIC)
        g.markSuperseded(newId = new, oldId = old)
        g.decay()
        assertNull(g.engram(new))
        assertNotNull(g.engram(old))
        assertNull(g.engram(old)!!.supersededBy)
    }

    @Test
    fun theMergeDoesNotFoldASupersedesPair() {
        val g = graph(LatticeGraph.Config(dedupThreshold = 0.999))
        val old = g.remember("用户喜欢喝美式咖啡不加糖", salience = 0.6)
        val new = g.remember("用户喜欢喝美式咖啡不加糖也不加奶", salience = 0.7)
        g.markSuperseded(newId = new, oldId = old)
        g.mergeDuplicates(threshold = 0.7)
        assertNotNull(g.engram(old))
        assertNotNull(g.engram(new))
        assertEquals(new, g.engram(old)!!.supersededBy)
        assertNull(g.engram(new)!!.supersededBy, "no self-supersede loop")
    }

    @Test
    fun anExplicitSearchBypassesTheEmitGate() {
        val g = graph(LatticeGraph.Config(seedFloor = 0.1, emitFloor = 0.9))
        g.remember("部署失败排查记录")
        assertTrue(g.recall("部署文档", reinforce = Reinforce.NONE, applyEmitGate = true).isEmpty())
        assertTrue(g.recall("部署文档", reinforce = Reinforce.NONE, applyEmitGate = false).isNotEmpty())
    }

    @Test
    fun theSupersededMarkerSurvivesAReload() {
        val store = TestLatticeStore()
        val g = graph(store = store)
        val oldId = g.remember("用户住在北京")
        val newId = g.remember("用户搬到上海居住")
        g.markSuperseded(newId = newId, oldId = oldId)
        assertEquals(newId, graph(store = store).engram(oldId)!!.supersededBy)
    }
}
