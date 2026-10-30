package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the coherence repairs:
 *  - a PROTECTED hub edge survives an [LatticeGraph.mergeDuplicates] absorb (not demoted to SEMANTIC),
 *        and the cap-OFF path is byte-identical (survivor's incumbent type stands).
 *  - a near-duplicate fact does NOT accrete a SEMANTIC auto-link onto a PROTECTED episode hub, while
 *        an ordinary fact neighbor still auto-links.
 */
class LatticeGraphCoherenceRepairTest {

    private val clock = { Instant.parse("2026-08-03T00:00:00Z") }

    /** Unit-axis embedder: keep/drop share axis 0 (cosine 1.0, so they merge); hub is orthogonal. */
    private class AxisEmbedder : Embedder {
        override val model = "axis"
        override val dimension = 4
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val axis = when {
                "keep" in text || "drop" in text -> 0
                "hub" in text -> 1
                else -> 3
            }
            v[axis] = 1f
            return v
        }
    }

    /** Returns a fixed vector per cue content (the preloaded engrams carry their own embeddings). */
    private class MapEmbedder(private val m: Map<String, FloatArray>) : Embedder {
        override val model = "map"
        override val dimension = 2
        override fun embed(text: String): FloatArray = m[text] ?: FloatArray(dimension)
    }

    /** A store that rehydrates the graph from fixed engrams/edges. */
    private fun preloadStore(engrams: List<Engram>, edges: List<EdgeRecord>) =
        TestLatticeStore(initial = LatticeStore.Loaded(engrams = engrams, edges = edges))

    private fun buildHubMerge(maxDegree: Int): Triple<LatticeGraph, Long, Long> {
        // auto-link/dedup disabled (impossible thresholds) so the only edges are the two explicit links,
        // and the two identical-axis facts stay DISTINCT until the sleep merge folds them.
        val cfg = LatticeGraph.Config(maxDegree = maxDegree, autoLinkThreshold = 2.0, dedupThreshold = 2.0)
        val g = LatticeGraph(AxisEmbedder(), cfg, now = clock)
        val keep = g.remember("keep fact A", salience = 0.9)
        val drop = g.remember("drop fact B", salience = 0.5)
        val hub = g.remember("hub node", salience = 0.7)
        // survivor -> hub is an EVICTABLE SEMANTIC auto-link; dropped -> SAME hub is a PROTECTED ABSTRACTS edge.
        g.link(keep, hub, Edge.Type.SEMANTIC, 0.7)
        g.link(drop, hub, Edge.Type.ABSTRACTS, 0.5)
        return Triple(g, keep, hub)
    }

    @Test
    fun absorbPreservesProtectedHubEdgeOverSurvivorSemantic() {
        val (g, keep, hub) = buildHubMerge(maxDegree = 40)
        val merged = g.mergeDuplicates(threshold = 0.99)

        assertEquals(1, merged, "the two near-duplicate facts merged")
        val edge = g.neighbors(keep).firstOrNull { it.to == hub }
        assertTrue(edge != null, "survivor keeps an edge to the shared hub")
        assertEquals(
            Edge.Type.ABSTRACTS,
            edge.type,
            "the PROTECTED type wins the absorb merge (the star edge is not demoted to a cap-evictable SEMANTIC)",
        )
    }

    @Test
    fun absorbKeepsSurvivorTypeUnchangedWhenCapOff() {
        val (g, keep, hub) = buildHubMerge(maxDegree = 0) // cap OFF => strict legacy rollback equivalence
        g.mergeDuplicates(threshold = 0.99)
        val edge = g.neighbors(keep).firstOrNull { it.to == hub }
        assertTrue(edge != null)
        assertEquals(
            Edge.Type.SEMANTIC,
            edge.type,
            "cap OFF => byte-identical legacy: the survivor's incumbent SEMANTIC type stands",
        )
    }

    @Test
    fun nearDuplicateFactDoesNotAutoLinkOntoEpisodeHub() {
        val t = Instant.parse("2026-08-03T00:00:00Z")
        // hub and plain both sit at cosine 0.7 from the new fact (in [autoLink 0.62, dedup 0.86)).
        val hub = Engram(
            id = 1, content = "hub gist", embedding = floatArrayOf(1f, 0f), salience = 0.7,
            layer = Engram.Layer.SEMANTIC, createdAt = t, lastActivatedAt = t, isEpisodeHub = true,
        )
        val plain = Engram(
            id = 2, content = "plain gist", embedding = floatArrayOf(1f, 0f), salience = 0.7,
            layer = Engram.Layer.SEMANTIC, createdAt = t, lastActivatedAt = t,
        )
        val embedder = MapEmbedder(mapOf("newfact" to floatArrayOf(0.7f, 0.7141428f)))
        val g = LatticeGraph(
            embedder,
            LatticeGraph.Config(autoLinkThreshold = 0.62, dedupThreshold = 0.86),
            now = clock,
            store = preloadStore(listOf(hub, plain), emptyList()),
        )
        val newId = g.remember("newfact")

        assertTrue(
            g.neighbors(newId).any { it.to == 2L && it.type == Edge.Type.SEMANTIC },
            "a near-duplicate fact still auto-links to an ORDINARY fact neighbor at the same similarity",
        )
        assertFalse(
            g.neighbors(newId).any { it.to == 1L },
            "a near-duplicate fact does NOT accrete an auto-link onto the protected episode hub",
        )
        assertNull(
            g.neighbors(1L).firstOrNull { it.to == newId },
            "no reverse edge onto the hub either",
        )
    }
}
