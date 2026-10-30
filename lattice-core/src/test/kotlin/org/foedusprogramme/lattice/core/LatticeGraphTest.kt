package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Acceptance tests for the memory graph.
 *
 * The headline test ([spreadingActivationReachesWhatTopKMisses]) is the whole point
 * of the graph: a memory that shares almost no words with the query is still recalled
 * because a causal edge carries activation to it — something a flat vector top-k
 * (or a full-text store) fundamentally cannot do.
 */
class LatticeGraphTest {

    private val fixedClock = { Instant.parse("2026-07-31T00:00:00Z") }

    private fun graph(config: LatticeGraph.Config = LatticeGraph.Config()) =
        LatticeGraph(LocalHashingEmbedder(), config, now = fixedClock)

    @Test
    fun spreadingActivationReachesWhatTopKMisses() {
        val g = graph()

        // A: the incident — shares "部署/失败" with the query.
        val a = g.remember("部署 llama-server 时报错 CUDA out of memory 部署失败", salience = 0.7)
        // B: the fix — shares essentially NO words with the query below.
        val b = g.remember("把 n_ctx 从 8192 降到 4096 就不再爆显存了", salience = 0.9)
        // Distractors that share a word with the query but are irrelevant.
        g.remember("部署文档和运维手册都写在 wiki 上")
        g.remember("周末去爬山天气很好")

        // The fix causally resolved the incident — this is the edge topology exists for.
        g.link(a, b, Edge.Type.CAUSAL, weight = 0.9)

        val cue = "上次那个部署失败是怎么修的"

        // Baseline: a flat vector top-k cannot reach B — it has ~zero lexical overlap.
        val flat = g.recallFlatTopK(cue, limit = 3).map { it.engram.id }
        assertTrue(a in flat, "flat top-k should still find the incident A")
        assertFalse(b in flat, "flat top-k must MISS the fix B (no shared words) — this is the gap")

        // Spreading activation seeds on A, then flows across the causal edge to B.
        val recalled = g.recall(cue, limit = 8)
        val ids = recalled.map { it.engram.id }
        assertTrue(a in ids, "recall should surface the incident A as a seed")
        assertTrue(b in ids, "recall should reach the fix B via the causal edge")

        // B was reached by spreading, not by direct cue match.
        val bHit = recalled.first { it.engram.id == b }
        assertEquals(Activation.Reach.SPREAD, bHit.via, "B is reached by spread, not seed")
    }

    @Test
    fun coRetrievalWiresEngramsTogether() {
        val g = graph()
        val a = g.remember("信用卡账单每月 15 号出账", salience = 0.6)
        val b = g.remember("水电费走自动扣款", salience = 0.6)
        // No edge between them initially, and they are lexically unrelated.
        assertTrue(g.neighbors(a).none { it.to == b }, "precondition: A and B start unconnected")

        // Force both into a co-activated set by querying a cue near A, with B linked so
        // it rides along — then a second, direct recall of each keeps them co-present.
        g.link(a, b, Edge.Type.SEMANTIC, weight = 0.5)
        val before = g.neighbors(a).first { it.to == b }.weight
        repeat(3) { g.recall("账单 扣款") }
        val after = g.neighbors(a).first { it.to == b }.weight

        assertTrue(after > before, "co-retrieval should Hebbian-strengthen the A-B edge ($before -> $after)")
    }

    @Test
    fun patternSeparationMergesNearDuplicates() {
        val g = graph()
        val first = g.remember("用户偏好用简体中文回复")
        val sizeAfterFirst = g.size
        val second = g.remember("用户偏好用简体中文回复")

        assertEquals(first, second, "a near-identical cue should merge into the existing engram")
        assertEquals(sizeAfterFirst, g.size, "no duplicate node should be created")
    }

    @Test
    fun decayPrunesWeakEdgesButKeepsStrongOnes() {
        val g = graph(LatticeGraph.Config(decayFactor = 0.5, pruneFloor = 0.1))
        val a = g.remember("锚点记忆 A 内容各不相同")
        val b = g.remember("完全不同的记忆 B 主题无关")
        val c = g.remember("另一段独立记忆 C 话题各异")
        g.link(a, b, Edge.Type.SEMANTIC, weight = 0.12) // weak: dies after one 0.5 decay (-> 0.06)
        g.link(a, c, Edge.Type.CAUSAL, weight = 0.9)    // strong: survives

        val pruned = g.decay()

        // An edge is held once (not once per direction), so one pruned association counts once.
        assertEquals(1, pruned, "the weak A-B edge should be the one pruned edge")
        assertTrue(g.neighbors(b).none { it.to == a }, "the pruned edge is gone from both ends")
        assertTrue(g.neighbors(a).any { it.to == c }, "the strong A-C edge should survive decay")
        assertTrue(g.neighbors(a).none { it.to == b }, "the weak A-B edge should be gone")
    }
}
