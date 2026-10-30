package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Community detection ([LatticeGraph.relatedClusters] now runs Louvain modularity
 * maximization over the cosine-weighted learned-edge graph instead of single-linkage connected
 * components). Single-linkage chained bge's low-threshold auto-links into one mega-component that
 * failed the size AND cohesion gates, so diffuse-but-real motifs never got abstracted. Louvain
 * splits the blob into dense sub-communities, each independently gated by the UNCHANGED
 * size(minSize..maxSize) + cohesion(>=threshold) checks.
 *
 * Uses [BgeBandFakeEmbedder] so cosines land on bge's real compressed band (same-topic+sub = 0.83,
 * unrelated = 0.60), reproducing exactly the regime the fix targets.
 */
class LatticeCommunityClusterTest {

    private val clock = { Instant.parse("2026-07-31T00:00:00Z") }

    /** Six same-(topic|sub) nodes: pairwise cosine 0.83 (< dedup 0.86 so they coexist; >= autoLink
     *  0.62 so they auto-link SEMANTIC) — a tight, gate-passing community. */
    private fun tightCluster(g: LatticeGraph, topic: String, sub: String): List<Long> =
        (1..6).map { g.remember("$topic|$sub|m$it") }

    @Test
    fun blobSplitsIntoDenseCommunities() {
        // Three 6-node tight clusters (different topics), chained by two weak SEMANTIC bridges into
        // ONE single-linkage component of 18 (cross-topic cosine 0.60). Single-linkage would return 0
        // (18 > maxSize). Louvain splits it back into the three dense communities.
        val g = LatticeGraph(BgeBandFakeEmbedder(), LatticeGraph.Config.bgeM3(), now = clock)
        val a = tightCluster(g, "TA", "sA")
        val b = tightCluster(g, "TB", "sB")
        val c = tightCluster(g, "TC", "sC")
        // Weak cross-cluster bridges (cosine 0.60, weight 0.62) — the edges modularity cuts first.
        g.link(a.last(), b.first(), Edge.Type.SEMANTIC, 0.62)
        g.link(b.last(), c.first(), Edge.Type.SEMANTIC, 0.62)

        val clusters = g.relatedClusters(threshold = 0.63, minSize = 3, maxSize = 12)

        assertEquals(3, clusters.size, "the 18-node blob splits into 3 dense communities")
        val sets = clusters.map { it.toSet() }
        assertTrue(sets.contains(a.toSet()), "cluster A recovered whole and unmixed")
        assertTrue(sets.contains(b.toSet()), "cluster B recovered whole and unmixed")
        assertTrue(sets.contains(c.toSet()), "cluster C recovered whole and unmixed")
        clusters.forEach { assertEquals(6, it.size, "each community is size 6") }
    }

    @Test
    fun unsplittableOversizeCommunityIsDroppedWhole() {
        // A 14-node uniform dense clique (all pairwise 0.83) has no internal community structure, so
        // Louvain cannot split it below maxSize. It must be DROPPED whole, never fragmented below
        // minSize into spurious sub-clusters.
        val g = LatticeGraph(BgeBandFakeEmbedder(), LatticeGraph.Config.bgeM3(), now = clock)
        val nodes = (1..14).map { g.remember("BIG|sub|m$it") }
        // Fully connect (they already auto-link at 0.83; make it explicit and uniform).
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            g.link(nodes[i], nodes[j], Edge.Type.SEMANTIC, 0.83)
        }

        val clusters = g.relatedClusters(threshold = 0.63, minSize = 3, maxSize = 12)

        assertEquals(0, clusters.size, "an unsplittable oversize clique is dropped, not fragmented")
    }

    @Test
    fun gatesStillApplyAfterPartitioning() {
        // A size-2 community (below minSize) and a loose community (cross-cosine 0.60 < cohesion floor)
        // are both dropped — only FORMATION changed, the gates are unchanged.
        val g = LatticeGraph(BgeBandFakeEmbedder(), LatticeGraph.Config.bgeM3(), now = clock)
        // A tight size-2 pair (dropped by minSize 3).
        val p1 = g.remember("PAIR|s|m1"); val p2 = g.remember("PAIR|s|m2")
        g.link(p1, p2, Edge.Type.SEMANTIC, 0.83)
        // A loose triangle: three DIFFERENT topics wired together (pairwise cosine 0.60 < 0.63).
        val l1 = g.remember("L1|s|m"); val l2 = g.remember("L2|s|m"); val l3 = g.remember("L3|s|m")
        g.link(l1, l2, Edge.Type.SEMANTIC, 0.62)
        g.link(l2, l3, Edge.Type.SEMANTIC, 0.62)
        g.link(l1, l3, Edge.Type.SEMANTIC, 0.62)

        val clusters = g.relatedClusters(threshold = 0.63, minSize = 3, maxSize = 12)

        assertEquals(0, clusters.size, "size gate drops the pair; cohesion gate drops the loose triangle")
    }

    @Test
    fun emptyStructuralGraphYieldsNoClusters() {
        // No structural edges >= pruneFloor -> every node a singleton -> all below minSize.
        val g = LatticeGraph(BgeBandFakeEmbedder(), LatticeGraph.Config.bgeM3(), now = clock)
        // Distinct topics -> cross-cosine 0.60 < autoLink 0.62 -> no auto-links form.
        (1..5).forEach { g.remember("SOLO$it|s|m") }

        assertTrue(g.relatedClusters(threshold = 0.63, minSize = 3, maxSize = 12).isEmpty())
    }

    @Test
    fun partitionIsDeterministicAcrossFreshInstances() {
        // Determinism must survive a JVM restart, not merely two calls on one object: build the same
        // graph into a shared store, then load TWO fresh LatticeGraph instances and assert identical
        // community sets. The graph carries an engineered exact-gain tie (a symmetric barbell).
        val store = TestLatticeStore()
        run {
            val g = LatticeGraph(BgeBandFakeEmbedder(), LatticeGraph.Config.bgeM3(), now = clock, store = store)
            val a = (1..6).map { g.remember("DA|sA|m$it") }
            val b = (1..6).map { g.remember("DB|sB|m$it") }
            g.link(a.last(), b.first(), Edge.Type.SEMANTIC, 0.62)
        }
        fun partition(): List<Set<Long>> {
            val g = LatticeGraph(BgeBandFakeEmbedder(), LatticeGraph.Config.bgeM3(), now = clock, store = store)
            return g.relatedClusters(threshold = 0.63, minSize = 3, maxSize = 12)
                .map { it.toSet() }
                .sortedBy { it.minOrNull() ?: Long.MAX_VALUE }
        }

        assertEquals(partition(), partition(), "the Louvain partition is byte-stable across fresh instances")
    }
}
