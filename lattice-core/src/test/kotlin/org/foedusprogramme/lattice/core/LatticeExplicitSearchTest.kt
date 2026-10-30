package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An explicit search (a search tool call) gathers broadly: it seeds by rank below the passive seed floor, with at least as
 * many seeds as it asked for, and prefers memories of what happened over plans. The passive turn recall is unchanged.
 */
class LatticeExplicitSearchTest {

    private val clock = { Instant.parse("2023-06-01T00:00:00Z") }

    /** The cue is axis 0; memory i sits at cosine a_i to it on its own axis, so memories barely resemble each other. */
    private class ConeEmbedder(private val cosines: Map<String, Double>) : Embedder {
        override val model = "test"
        override val dimension = 32
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val a = cosines[text]
            if (a == null) {
                v[0] = 1f
                return v
            }
            v[0] = a.toFloat()
            v[1 + (text.hashCode() and 0x7fffffff) % (dimension - 1)] = sqrt(1 - a * a).toFloat()
            return v
        }
    }

    private fun graph(cosines: Map<String, Double>, seedCount: Int = 8) = LatticeGraph(
        ConeEmbedder(cosines),
        LatticeGraph.Config(
            temporalLayer = true, seedFloor = 0.50, emitFloor = 0.60, seedCount = seedCount,
            explicitSeedFloor = 0.40, explicitEventRank = 1.1, explicitPlanRank = 0.9,
        ),
        now = clock,
    )

    @Test
    fun anExplicitSearchReachesMemoriesUnderThePassiveSeedFloor() {
        val led = "The user led the data analysis team in a Marketing Research class project"
        val solo = "The user is working on a solo project for a Data Mining class"
        val g = graph(mapOf(led to 0.47, solo to 0.45))
        g.remember(led)
        g.remember(solo)
        val cue = "How many projects have I led?"
        assertTrue(g.recall(cue, limit = 8, reinforce = Reinforce.NONE).isEmpty(), "the passive recall stays silent")
        val hits = g.recall(cue, limit = 8, reinforce = Reinforce.NONE, applyEmitGate = false).map { it.engram.content }
        assertTrue(led in hits && solo in hits, hits.toString())
    }

    @Test
    fun anExplicitSearchSeedsAtLeastAsManyMemoriesAsItAskedFor() {
        val memories = (1..6).associate { "The user attended charity event number $it" to 0.45 + it * 0.01 }
        val g = graph(memories, seedCount = 2)
        memories.keys.forEach { g.remember(it) }
        val hits = g.recall("charity events I joined", limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false)
        assertEquals(6, hits.count { it.engram.content in memories })
    }

    @Test
    fun anExplicitSearchPrefersWhatHappenedOverAPlan() {
        val plan = "The user is thinking about going to a music festival"
        val went = "The user went to a Billie Eilish concert"
        val g = graph(mapOf(plan to 0.50, went to 0.48), seedCount = 1)
        g.remember(plan, dueDate = "2023-08")
        g.remember(went, eventDate = "2023-03-18")
        val hits = g.recall("concerts I attended", limit = 1, reinforce = Reinforce.NONE, applyEmitGate = false)
        assertEquals(went, hits.first().engram.content)
    }
}
