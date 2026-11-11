package org.foedusprogramme.lattice.core

import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage vs retrieval strength ([LatticeGraph.Config.explicitSalienceExponent]): an explicit search reads decayed
 * salience through its square root, a passive per-turn recall still multiplies by salience as it is, and what the
 * sleep's forgetting removed stays removed.
 */
class LatticeExplicitSalienceTest {

    /** The cue is axis 0; memory i sits at cosine a_i to it on its own axis. */
    private class ConeEmbedder(private val cosines: Map<String, Double>) : Embedder {
        override val model = "test"
        override val dimension = 64
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val a = cosines[text] ?: return v.also { it[0] = 1f }
            v[0] = a.toFloat()
            v[1 + (text.hashCode() and 0x7fffffff) % (dimension - 1)] = sqrt(1 - a * a).toFloat()
            return v
        }
    }

    private var now = Instant.parse("2023-06-01T00:00:00Z")

    private val onTarget = "Melanie saw the Perseid meteor shower on the camping trip"
    private val generic = "Melanie loves camping with her family"
    private val cue = "What did Melanie and her family see during their camping trip?"

    private fun config(exponent: Double) = LatticeGraph.Config(
        seedFloor = 0.50, emitFloor = 0.60, explicitSeedFloor = 0.40, recencyGain = 0.0, autoLinkThreshold = 0.95,
        explicitSalienceExponent = exponent,
    )

    /** The on-target memory (cosine 0.80) decayed to 0.38; the generic one (0.66) kept at the passive cap 0.70. */
    private fun graph(exponent: Double): LatticeGraph {
        val g = LatticeGraph(ConeEmbedder(mapOf(onTarget to 0.80, generic to 0.66)), config(exponent), now = { now })
        g.engram(g.remember(onTarget, salience = 0.5, layer = Engram.Layer.SEMANTIC))!!.salience = 0.38
        g.engram(g.remember(generic, salience = 0.5, layer = Engram.Layer.SEMANTIC))!!.salience = 0.70
        return g
    }

    private fun LatticeGraph.order(explicit: Boolean) =
        recall(cue, limit = 2, reinforce = Reinforce.NONE, applyEmitGate = !explicit).map { it.engram.content }

    @Test
    fun anExplicitSearchLeansLessOnDecayedSalienceAndAPassiveRecallIsUnchanged() {
        val damped = graph(exponent = 0.5)
        // Passive: 0.80 x (0.5 + 0.5 x 0.38) = 0.552 < 0.66 x (0.5 + 0.5 x 0.70) = 0.561.
        assertEquals(listOf(generic, onTarget), damped.order(explicit = false), "a passive recall still multiplies by salience")
        // Explicit: 0.80 x (0.5 + 0.5 x sqrt 0.38) = 0.647 > 0.66 x (0.5 + 0.5 x sqrt 0.70) = 0.606.
        assertEquals(listOf(onTarget, generic), damped.order(explicit = true), "an explicit search: cosine decides again")

        val legacy = graph(exponent = 1.0)
        assertEquals(listOf(generic, onTarget), legacy.order(explicit = true), "exponent 1: the legacy ranking")
        assertEquals(damped.order(explicit = false), legacy.order(explicit = false))
    }

    @Test
    fun theDampingIsMonotoneAndKeepsUseAsASignal() {
        // Equal cosine: the more salient memory still ranks first under the square root.
        val a = "Caroline went to a pride parade"
        val b = "Caroline went to a support group"
        val g = LatticeGraph(ConeEmbedder(mapOf(a to 0.75, b to 0.75)), config(0.5), now = { now })
        g.engram(g.remember(a, salience = 0.5, layer = Engram.Layer.SEMANTIC))!!.salience = 0.3
        g.engram(g.remember(b, salience = 0.5, layer = Engram.Layer.SEMANTIC))!!.salience = 0.6
        assertEquals(listOf(b, a), g.recall("Caroline went", limit = 2, reinforce = Reinforce.NONE, applyEmitGate = false).map { it.engram.content })
    }

    @Test
    fun whatForgettingRemovedStaysRemoved() {
        val faded = "Melanie mentioned a pottery workshop once"
        val g = LatticeGraph(
            ConeEmbedder(mapOf(faded to 0.85, generic to 0.66)),
            config(0.5).copy(decayHalfLifeHours = 168.0, episodicSalienceDecay = 0.9, nodePruneFloor = 0.15),
            now = { now },
        )
        val id = g.remember(faded, salience = 0.2, layer = Engram.Layer.EPISODIC)
        g.remember(generic, salience = 0.7, layer = Engram.Layer.SEMANTIC)
        now = now.plus(Duration.ofDays(70)) // ten weeks: 0.2 x 0.9^10 = 0.07, under the prune floor
        g.decay()
        assertNull(g.engram(id), "the sleep pruned it")
        val hits = g.recall("pottery workshop", limit = 4, reinforce = Reinforce.NONE, applyEmitGate = false).map { it.engram.content }
        assertTrue(faded !in hits, "an explicit search never brings a pruned memory back: $hits")
    }

    @Test
    fun theBgeM3PresetDampsAndABareConfigDoesNot() {
        assertEquals(0.5, LatticeGraph.Config.bgeM3().explicitSalienceExponent)
        assertEquals(1.0, LatticeGraph.Config().explicitSalienceExponent)
    }
}
