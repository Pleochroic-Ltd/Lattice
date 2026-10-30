package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The anti-duplicate-parent guard in [LatticeGraph.synthesizeParent].
 *
 * The abstractor sometimes paraphrases ONE child verbatim instead of abstracting the set; that
 * "parent" is a near-duplicate of a single child and — because [LatticeGraph.mergeDuplicates] never
 * folds an ABSTRACTS pair — would be permanently protected as a duplicate.
 * The guard refuses such a parent (returns [LatticeGraph.REFUSED], writes nothing, consumes no id) iff
 * ALL of: max child cosine >= dedupThreshold, (max − mean) >= parentDominanceMargin, and no second
 * child is itself a near-duplicate (a genuinely dense/corroborated parent is spared).
 *
 * These offline tests use a [FixedVectorEmbedder] so the parent↔child cosine geometry is exact and
 * independent of remember()'s own dedup (children are kept mutually below dedupThreshold on purpose).
 */
class LatticeSynthesisGuardTest {

    private val clock = { Instant.parse("2026-07-31T00:00:00Z") }

    /** Deterministic embedder: each text maps to a fixed, L2-normalized vector. A child's cosine to
     *  the gist is its `alpha` (component on axis 0); distinct children use orthogonal unique axes so
     *  their pairwise cosine is alpha_i*alpha_j (kept below dedupThreshold to avoid remember() dedup). */
    private class FixedVectorEmbedder(private val vectors: Map<String, FloatArray>) : Embedder {
        override val model = "fixed"
        override val dimension = vectors.values.first().size
        override fun embed(text: String): FloatArray =
            vectors[text] ?: error("no fixed vector for '$text'")
    }

    private fun unit(alpha: Double, uniqueAxis: Int, dim: Int = 64): FloatArray {
        val v = FloatArray(dim)
        v[0] = alpha.toFloat()
        if (uniqueAxis > 0) v[uniqueAxis] = sqrt(1.0 - alpha * alpha).toFloat()
        return v
    }

    /** Build a graph over the given (text -> cosine-to-gist) children plus a "GIST" vector on axis 0. */
    private fun graphWith(gistText: String, children: List<Pair<String, Double>>): Pair<LatticeGraph, List<Long>> {
        val vectors = HashMap<String, FloatArray>()
        vectors[gistText] = unit(1.0, 0)
        children.forEachIndexed { i, (text, cos) -> vectors[text] = unit(cos, i + 1) }
        val emb = FixedVectorEmbedder(vectors)
        val g = LatticeGraph(emb, LatticeGraph.Config.bgeM3(), now = clock)
        val ids = children.map { (text, _) -> g.remember(text, salience = 0.6) }
        return g to ids
    }

    @Test
    fun paraphraseParentIsSkipped() {
        // Gist is a verbatim copy of child #1 (cosine 1.0); the rest sit at 0.65 -> one child towers.
        val (g, ids) = graphWith(
            gistText = "GIST",
            children = listOf("c1" to 1.0, "c2" to 0.65, "c3" to 0.65, "c4" to 0.65),
        )
        // The gist string embeds to axis 0 (== child #1's vector) -> cosine 1.0 to child #1.
        val before = g.size

        val id = g.synthesizeParent(ids, "GIST")

        assertEquals(LatticeGraph.REFUSED, id, "a lone near-duplicate parent must be refused (-1L)")
        assertEquals(before, g.size, "refusal materializes nothing (no id consumed, no node)")
        ids.forEach { child ->
            assertTrue(
                g.neighbors(child).none { it.type == Edge.Type.ABSTRACTS },
                "no ABSTRACTS edge is left on any child after a refusal",
            )
        }
    }

    @Test
    fun genuineTightAbstractionMaterializes() {
        // A dense community: the gist is close to ALL children (~0.88) so max-mean ~0 < margin ->
        // the guard does NOT fire even though max >= dedupThreshold. Regression the bare threshold lacked.
        val (g, ids) = graphWith(
            gistText = "GIST",
            children = listOf("c1" to 0.88, "c2" to 0.88, "c3" to 0.88),
        )
        val before = g.size

        val id = g.synthesizeParent(ids, "GIST")

        assertTrue(id > 0L, "a genuinely dense parent (small max-mean) is materialized")
        assertEquals(before + 1, g.size)
        val absChildren = g.neighbors(id).filter { it.type == Edge.Type.ABSTRACTS }.map { it.to }.toSet()
        assertEquals(ids.toSet(), absChildren, "parent ABSTRACTS all children")
    }

    @Test
    fun twoNearDupChildrenSpared() {
        // Clause 3: the gist is close to TWO children (>=0.86) and far from a diffuse tail, so
        // max-mean >= 0.15 (clauses 1+2 pass) but secondMax >= dedupThreshold spares it: one
        // incidental near-dup does not trip when a second child corroborates.
        val (g, ids) = graphWith(
            gistText = "GIST",
            children = listOf("c1" to 0.90, "c2" to 0.88, "c3" to 0.60, "c4" to 0.60),
        )
        val before = g.size

        val id = g.synthesizeParent(ids, "GIST")

        assertTrue(id > 0L, "two corroborating near-duplicate children spare the parent (clause 3)")
        assertEquals(before + 1, g.size)
    }

    @Test
    fun genuineDiffuseAbstractionMaterializes() {
        // Gist moderately close to all (0.83 < dedupThreshold 0.86) -> clause 1 fails -> materializes.
        val (g, ids) = graphWith(
            gistText = "GIST",
            children = listOf("c1" to 0.83, "c2" to 0.83, "c3" to 0.83),
        )
        val before = g.size

        val id = g.synthesizeParent(ids, "GIST")

        assertTrue(id > 0L, "a diffuse abstraction (max below dedup) is materialized")
        assertEquals(before + 1, g.size)
    }

    @Test
    fun singleChildIsAlwaysANoOpForTheGuard() {
        // With a single present child, max == mean -> (max-mean) == 0 < margin -> guard no-op,
        // even for a verbatim (cosine 1.0) parent.
        val (g, ids) = graphWith(gistText = "GIST", children = listOf("c1" to 1.0))
        val before = g.size

        val id = g.synthesizeParent(ids, "GIST")

        assertTrue(id > 0L, "single-child verbatim parent materializes (guard is a no-op)")
        assertEquals(before + 1, g.size)
    }

    @Test
    fun emptyPresentChildrenIsRefused() {
        val g = LatticeGraph(FixedVectorEmbedder(mapOf("GIST" to unit(1.0, 0))), LatticeGraph.Config.bgeM3(), now = clock)
        val before = g.size

        val id = g.synthesizeParent(listOf(9990L, 9991L), "GIST")

        assertEquals(LatticeGraph.REFUSED, id, "no present child -> refuse, create nothing")
        assertEquals(before, g.size)
    }
}
