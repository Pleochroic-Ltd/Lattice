package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The anti-cosine proof for contract (a): [LatticeGraph.periodFetch] selects purely by the structured
 * event-date window and salience — it never embeds the anchor, so a semantically-UNRELATED in-window
 * node IS surfaced and a semantically-RELATED out-of-window node is NOT. Read-only by construction.
 */
class LatticeGraphPeriodFetchTest {

    private val fixedClock = { Instant.parse("2026-08-01T00:00:00Z") }

    /** Each content is projected onto a single topic axis by keyword — same axis = cosine 1.0, else 0.0. */
    private class KwEmbedder : Embedder {
        override val model = "test"
        override val dimension = 8
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val axis = when {
                "beta" in text -> 5
                "alpha" in text -> 0
                "gamma" in text -> 2
                else -> 7
            }
            v[axis] = 1f
            return v
        }
    }

    private fun graph(
        temporalRetrieval: Boolean = true,
        periodRecall: Boolean = true,
        periodMaxNodes: Int = 40,
        periodMinNodes: Int = 1,
    ) = LatticeGraph(
        KwEmbedder(),
        LatticeGraph.Config.bgeM3().copy(
            temporalLayer = true,
            temporalRetrieval = temporalRetrieval,
            periodRecall = periodRecall,
            periodMaxNodes = periodMaxNodes,
            periodMinNodes = periodMinNodes,
        ),
        now = fixedClock,
    )

    /** center 2025-03, margin 1 => window {2025-02, 2025-03, 2025-04}. */
    private fun range(center: String, margin: Int = 1) =
        TemporalAnchor(YearMonth.parse(center), TemporalAnchor.Kind.RANGE, TemporalAnchor.Confidence.HIGH, margin)

    /** A: unrelated topic, in window. B/D: SAME topic (anti-fold keeps them distinct by date). C: in window. */
    private data class Ids(val a: Long, val b: Long, val c: Long, val d: Long)

    private fun populate(g: LatticeGraph): Ids = Ids(
        a = g.remember("alpha 用户领养了一只猫", salience = 0.6, eventDate = "2025-02"),
        b = g.remember("beta 项目截止", salience = 0.7, eventDate = "2025-03"), // center
        c = g.remember("gamma 用户开始学 Rust", salience = 0.5, eventDate = "2025-04"),
        d = g.remember("beta 项目截止", salience = 0.7, eventDate = "2025-06"), // related to B, OUT of window
    )

    @Test
    fun windowSelectsInWindowNodesIncludingUnrelatedAndExcludesRelatedOutOfWindow() {
        val g = graph()
        val ids = populate(g)
        val got = g.periodFetch(range("2025-03"), limit = 10).map { it.engram.id }.toSet()

        assertTrue(ids.a in got, "unrelated in-window node IS returned (no cosine gate)")
        assertTrue(ids.b in got, "center node returned")
        assertTrue(ids.c in got, "in-window node returned")
        assertFalse(ids.d in got, "related-but-out-of-window node is NOT returned (far-month exclusion)")
    }

    @Test
    fun overDenseWindowAbstains() {
        val g = graph(periodMaxNodes = 2) // window holds 3 (A,B,C) > 2
        populate(g)
        assertTrue(g.periodFetch(range("2025-03"), limit = 10).isEmpty(), "over-dense => abstain (falls through)")
    }

    @Test
    fun lowCoverageWindowAbstains() {
        val g = graph(periodMinNodes = 5) // window holds 3 < 5
        populate(g)
        assertTrue(g.periodFetch(range("2025-03"), limit = 10).isEmpty(), "below the coverage floor => abstain")
    }

    @Test
    fun uncoveredCenterAbstains() {
        val g = graph()
        populate(g)
        // center 2025-05 has no memory even though 2025-04/2025-06 fall in the +-1 window.
        assertTrue(g.periodFetch(range("2025-05"), limit = 10).isEmpty(), "uncovered center = mis-anchor => abstain")
    }

    @Test
    fun masterOffReturnsEmpty() {
        val g = graph(temporalRetrieval = false)
        populate(g)
        assertTrue(g.periodFetch(range("2025-03"), limit = 10).isEmpty(), "master OFF => no period hits")
        val g2 = graph(periodRecall = false)
        populate(g2)
        assertTrue(g2.periodFetch(range("2025-03"), limit = 10).isEmpty(), "periodRecall OFF => no period hits")
    }

    @Test
    fun supersededDatedFactCollapsesToCurrentVersion() {
        val g = graph()
        // Two DISTINCT in-window dated facts; the newer SUPERSEDES the older (a corrected fact). Both carry
        // an in-window event_date, so before the collapse both would surface (the old one merely down-weighted).
        val old = g.remember("alpha 项目预算是 10 万", salience = 0.7, eventDate = "2025-03")
        val cur = g.remember("gamma 项目预算改成 15 万", salience = 0.6, eventDate = "2025-03")
        g.markSuperseded(newId = cur, oldId = old)

        val got = g.periodFetch(range("2025-03"), limit = 10).map { it.engram.id }
        assertTrue(cur in got, "the CURRENT (superseding) version surfaces")
        assertFalse(old in got, "the superseded version is collapsed away, not surfaced alongside its successor")
    }

    @Test
    fun periodFetchIsReinforceNoneByConstruction() {
        val g = graph()
        val ids = populate(g)
        val beforeCount = g.engram(ids.b)!!.activationCount
        val beforeSoft = g.engram(ids.b)!!.softReplays

        g.periodFetch(range("2025-03"), limit = 10)

        assertEquals(beforeCount, g.engram(ids.b)!!.activationCount, "no activation bump (read-only)")
        assertEquals(beforeSoft, g.engram(ids.b)!!.softReplays, "no soft replay (read-only)")
        // No Hebbian CO_RETRIEVAL edge was wired among the surfaced nodes.
        val coRetrieval = g.neighbors(ids.a).any { it.type == Edge.Type.CO_RETRIEVAL } ||
            g.neighbors(ids.b).any { it.type == Edge.Type.CO_RETRIEVAL } ||
            g.neighbors(ids.c).any { it.type == Edge.Type.CO_RETRIEVAL }
        assertFalse(coRetrieval, "period-fetch never wires co-retrieval edges")
    }
}
