package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.YearMonth
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract (b): [LatticeGraph.recall]'s time-fold reorders the base toward the anchor month as a
 * SET-PRESERVING permutation — it cannot add, drop, or admit an off-topic hit, and an undated hit is
 * neither boosted nor demoted (time-proximity factor 1.0). OFF (or no anchor) => identical to plain recall.
 */
class LatticeGraphTimeFoldTest {

    private val fixedClock = { Instant.parse("2026-08-01T00:00:00Z") }

    /** Cue lands equally on three axes; each node owns one axis => equal cosine, no folding, no autolink. */
    private class MultiAxisEmbedder : Embedder {
        override val model = "test"
        override val dimension = 8
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            if ("CUE" in text) {
                v[1] = 1f; v[2] = 1f; v[3] = 1f
            } else {
                val axis = when {
                    "undated" in text -> 1
                    "anchor" in text -> 2
                    "far" in text -> 3
                    "offtopic" in text -> 4
                    else -> 7
                }
                v[axis] = 1f
            }
            var n = 0.0
            for (x in v) n += x * x
            n = sqrt(n)
            if (n > 0) for (i in v.indices) v[i] = (v[i] / n).toFloat()
            return v
        }
    }

    private fun graph(rerank: Boolean = true) = LatticeGraph(
        MultiAxisEmbedder(),
        // The fold alone: the explicit search's damped salience (explicitSalienceExponent) would narrow the base gaps
        // these cases are built on.
        LatticeGraph.Config.bgeM3().copy(temporalLayer = true, temporalRetrieval = true, temporalRerank = rerank, explicitSalienceExponent = 1.0),
        now = fixedClock,
    )

    private fun point(ym: String) =
        TemporalAnchor(YearMonth.parse(ym), TemporalAnchor.Kind.POINT, TemporalAnchor.Confidence.HIGH)

    @Test
    fun anchorMonthNodeRisesToFrontAmongEqualRanks() {
        val g = graph()
        g.remember("far 项目", salience = 0.5, eventDate = "2025-01")
        val anchorId = g.remember("anchor 项目", salience = 0.5, eventDate = "2025-06")
        g.remember("undated 项目", salience = 0.5, eventDate = "2025-12") // (dated, third axis)

        val folded = g.recall("CUE", limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false, temporalAnchor = point("2025-06"))
        assertEquals(anchorId, folded.first().engram.id, "the anchor-month node is nudged to the front")
    }

    @Test
    fun setIsPreservedUndatedKeepsRankAndOffTopicNeverEnters() {
        val g = graph()
        val undated = g.remember("undated 项目", salience = 0.9) // no eventDate, highest base rank
        val anchor = g.remember("anchor 项目", salience = 0.5, eventDate = "2025-06")
        val far = g.remember("far 项目", salience = 0.5, eventDate = "2025-01")
        val offTopic = g.remember("offtopic 项目", salience = 0.9, eventDate = "2025-06") // not a cue axis

        val plain = g.recall("CUE", limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false)
        val folded = g.recall("CUE", limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false, temporalAnchor = point("2025-06"))

        val plainIds = plain.map { it.engram.id }.toSet()
        assertEquals(setOf(undated, anchor, far), plainIds, "off-topic node is never seeded")
        assertFalse(offTopic in plainIds, "off-topic node absent from the base")
        assertEquals(plainIds, folded.map { it.engram.id }.toSet(), "fold preserves the base SET exactly")
        assertFalse(offTopic in folded.map { it.engram.id }, "fold cannot pull in an off-topic node by date match")
        // Undated node has the highest base rank; its time factor is exactly 1.0, so it is NOT demoted
        // by the anchor month's boost — it stays on top while the anchor rises above the far month.
        assertEquals(undated, folded.first().engram.id, "undated top-ranked hit keeps its rank (tProx 0)")
        assertTrue(
            folded.indexOfFirst { it.engram.id == anchor } < folded.indexOfFirst { it.engram.id == far },
            "among dated hits the anchor month outranks the far month",
        )
    }

    @Test
    fun rerankOffIsIdenticalToPlainRecall() {
        val g = graph(rerank = false)
        g.remember("far 项目", salience = 0.5, eventDate = "2025-01")
        g.remember("anchor 项目", salience = 0.5, eventDate = "2025-06")
        g.remember("undated 项目", salience = 0.5, eventDate = "2025-12")

        val withAnchor = g.recall("CUE", limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false, temporalAnchor = point("2025-06"))
        val withoutAnchor = g.recall("CUE", limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false)
        assertEquals(
            withoutAnchor.map { it.engram.id },
            withAnchor.map { it.engram.id },
            "temporalRerank OFF => the anchor is inert (byte-identical order)",
        )
    }
}
