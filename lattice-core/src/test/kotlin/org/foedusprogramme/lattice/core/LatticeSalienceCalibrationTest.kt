package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Consolidation / forgetting calibration for neuromodulatory salience, run in bge's ACTUAL
 * cosine regime via [BgeBandFakeEmbedder] (NOT [LocalHashingEmbedder], whose distinct-token cosine ≈ 0
 * is the opposite regime). Before any salience assertion the embedder fixture is self-checked against
 * bge's measured band, and every case runs under [LatticeGraph.Config.bgeM3].
 *
 * Decay-based cases use a per-pass copy (decayHalfLifeHours = null) so one decay() == one salience
 * half-step, matching the design's half-life reasoning exactly; the novelty band (noveltyFloorCosine
 * = 0.60, dedupThreshold = 0.86) and all gains are unchanged from bgeM3().
 */
class LatticeSalienceCalibrationTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }
    private val bge = LatticeGraph.Config.bgeM3()
    private val bgePerPass = bge.copy(decayHalfLifeHours = null)

    @BeforeTest
    fun selfCheckFixtureLandsOnBgeBand() {
        // The single most important guard: if the fixture's distribution is wrong, every downstream
        // salience assertion is meaningless. Fail loudly here.
        val e = BgeBandFakeEmbedder()
        val ref = e.embed("t1|s1|m1|x")
        assertEquals(1.0, cosine(ref, e.embed("t1|s1|m1|x")), 1e-6, "identical text must be 1.0")
        assertEquals(BgeBandFakeEmbedder.COS_UNRELATED, cosine(ref, e.embed("t2|s2|m2|y")), 1e-6, "unrelated ≈ 0.60")
        assertEquals(BgeBandFakeEmbedder.COS_RELATED, cosine(ref, e.embed("t1|s2|m2|y")), 1e-6, "related ≈ 0.64")
        assertEquals(BgeBandFakeEmbedder.COS_NEAR_RESTATEMENT, cosine(ref, e.embed("t1|s1|m2|y")), 1e-6, "near-restatement ≈ 0.83")
        assertEquals(BgeBandFakeEmbedder.COS_PARAPHRASE, cosine(ref, e.embed("t1|s1|m1|y")), 1e-6, "paraphrase ≈ 0.90")
        // Gram PSD is guaranteed by construction (these are genuine inner products of real vectors);
        // sanity-check symmetry and range as a lightweight proxy.
        val vs = listOf("a|b|c|d", "a|b|e|f", "g|h|i|j").map { e.embed(it) }
        for (i in vs.indices) for (j in vs.indices) {
            val cij = cosine(vs[i], vs[j])
            assertEquals(cij, cosine(vs[j], vs[i]), 1e-9, "Gram matrix must be symmetric")
            assertTrue(cij in -1e-6..(1.0 + 1e-6), "cosine in range (was $cij)")
        }
    }

    // --- Corpus: 30 durable facts (base 0.8) across 6 topics + 10 low-value junk one-offs (base 0.15). ---

    private data class Mem(val content: String, val base: Double)

    private fun corpus(): List<Mem> = buildList {
        for (topic in 0 until 6) for (j in 0 until 5) {
            add(Mem("topic$topic|sub$topic|micro${topic}_$j|uid${topic}_$j", 0.8))
        }
        for (k in 0 until 10) add(Mem("junk$k|junk$k|junk$k|junk$k", 0.15))
    }

    private fun ingest(cfg: LatticeGraph.Config): Pair<LatticeGraph, List<Long>> {
        val g = LatticeGraph(BgeBandFakeEmbedder(), cfg, now = clock)
        val ids = corpus().map { m ->
            g.remember(m.content, m.base, Engram.Layer.EPISODIC,
                salienceSignal = LatticeGraph.SalienceSignal(base = m.base))
        }
        return g to ids
    }

    private fun tolerance(total: Int) = maxOf(1, ceil(0.10 * total).toInt())

    @Test
    fun promotionCountWithinToleranceVsLegacy() {
        val (gOn, _) = ingest(bge)
        val (gOff, _) = ingest(bge.copy(neuromodulatorySalience = false))
        // Identical scripted replay on each: explicitly search each topic's representative fact 5×.
        fun script(g: LatticeGraph) {
            for (topic in 0 until 6) {
                repeat(5) { g.recall("topic$topic|sub$topic|micro${topic}_0|uid${topic}_0", reinforce = Reinforce.FULL) }
            }
        }
        script(gOn); script(gOff)
        val promotedOn = gOn.consolidate().promoted
        val promotedOff = gOff.consolidate().promoted
        assertTrue(abs(promotedOn - promotedOff) <= tolerance(corpus().size),
            "promotion must be preserved within tolerance: on=$promotedOn off=$promotedOff")
    }

    @Test
    fun survivalCountWithinToleranceVsLegacy() {
        val (gOn, _) = ingest(bgePerPass)
        val (gOff, _) = ingest(bgePerPass.copy(neuromodulatorySalience = false))
        repeat(2) { gOn.decay(); gOff.decay() }
        val surviveOn = gOn.size
        val surviveOff = gOff.size
        assertTrue(abs(surviveOn - surviveOff) <= tolerance(corpus().size),
            "forgetting must be preserved within tolerance: on=$surviveOn off=$surviveOff")
    }

    /** Run decay() until [id] is pruned; return the pass number (or -1 if it never prunes). */
    private fun prunePass(g: LatticeGraph, id: Long, maxPasses: Int = 40): Int {
        for (k in 1..maxPasses) { g.decay(); if (g.engram(id) == null) return k }
        return -1
    }

    @Test
    fun lowBaseNovelTangentDoesNotOutliveLegacy() {
        // Prune parity: a base-0.2 fact whose nearest neighbor is maximally UNRELATED (novelty 1.0)
        // must prune within ≤ 1 pass of a legacy raw-0.2 node (the direct rebuttal to "protected 3–4×").
        val g = LatticeGraph(BgeBandFakeEmbedder(), bgePerPass, now = clock)
        val legacy = g.remember("legacyTopic|legacySub|legacyMicro|legacyUid", 0.2) // no signal => raw 0.2
        val tangent = g.remember("tanTopic|tanSub|tanMicro|tanUid", 0.2,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.2)) // nearest = legacy @ 0.60 => novelty 1.0
        val encode = g.engram(tangent)!!.salience
        assertTrue(encode <= 0.232 + 1e-9, "tangent must encode <= 0.232, was $encode")
        assertEquals(0, g.consolidate().promoted, "an unused tangent must never promote")

        var legacyPass = -1
        var tangentPass = -1
        for (k in 1..40) {
            g.decay()
            if (legacyPass < 0 && g.engram(legacy) == null) legacyPass = k
            if (tangentPass < 0 && g.engram(tangent) == null) tangentPass = k
            if (legacyPass >= 0 && tangentPass >= 0) break
        }
        assertTrue(tangentPass in 1..(legacyPass + 1),
            "tangent must prune within legacy+1 pass: tangent=$tangentPass legacy=$legacyPass")

        // Bounded scripted replay must not promote it either (readiness stays below promoteThreshold).
        val g2 = LatticeGraph(BgeBandFakeEmbedder(), bge, now = clock)
        g2.remember("anchorTopic|anchorSub|anchorMicro|anchorUid", 0.8)
        val t2 = g2.remember("tanTopic|tanSub|tanMicro|tanUid", 0.2,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.2))
        repeat(4) { g2.recall("tanTopic|tanSub|tanMicro|tanUid", reinforce = Reinforce.FULL) }
        g2.consolidate()
        assertEquals(Engram.Layer.EPISODIC, g2.engram(t2)!!.layer, "a lightly-replayed tangent must not promote")
    }

    @Test
    fun lowEncodeSalienceButReplayedStillPromotes() {
        // A default-base, low-novelty (near-restatement, c≈0.83) fact encodes ~0.42 — below the floor —
        // but is recovered above it by genuine use, so it still promotes (use, not encode, gates).
        val g = LatticeGraph(BgeBandFakeEmbedder(), bge, now = clock)
        g.remember("t1|s1|m1|anchor", 0.6)
        val target = g.remember("t1|s1|m2|target", 0.5,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.5)) // nearest anchor @ 0.83 => low novelty
        assertTrue(g.engram(target)!!.salience < 0.5, "target must encode below the promotion floor")
        repeat(5) { g.recall("t1|s1|m2|target", reinforce = Reinforce.FULL) }
        g.consolidate()
        assertEquals(Engram.Layer.SEMANTIC, g.engram(target)!!.layer,
            "a low-encode but genuinely-replayed fact must still promote")
    }

    @Test
    fun denselyWiredLowEncodePromotesOneRecallLate() {
        // The residual boundary case: encode ~0.42, pre-wired so readiness crosses 0.7 on recall #1,
        // but salience clears the floor only on recall #2 — a one-recall delay, not a lost promotion.
        val g = LatticeGraph(BgeBandFakeEmbedder(), bge, now = clock)
        for (i in 0 until 7) g.remember("t1|s1|m$i|anchor$i", 0.6) // 7 same-topic+sub anchors (pairwise 0.83)
        val target = g.remember("t1|s1|m7|target", 0.5,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.5)) // auto-links to all 7 => structuralDegree 7
        assertTrue(g.engram(target)!!.salience < 0.5, "target must encode below the floor")

        g.recall("t1|s1|m7|target", reinforce = Reinforce.FULL) // recall #1
        g.consolidate()
        assertEquals(Engram.Layer.EPISODIC, g.engram(target)!!.layer,
            "readiness crosses 0.7 on recall #1 but salience is still below the floor")

        g.recall("t1|s1|m7|target", reinforce = Reinforce.FULL) // recall #2
        g.consolidate()
        assertEquals(Engram.Layer.SEMANTIC, g.engram(target)!!.layer,
            "salience clears the floor on recall #2 => promotes one recall late")
    }

    @Test
    fun lowRatedOneOffPrunesOnLegacySchedule() {
        // An LLM-base-0.2, LOW-novelty (near-restatement) one-off encodes slightly BELOW base yet still
        // above salienceMin, and prunes within a bounded number of passes of a legacy raw-0.2 node.
        val g = LatticeGraph(BgeBandFakeEmbedder(), bgePerPass, now = clock)
        g.remember("t1|s1|m1|anchor", 0.8)
        val legacy = g.remember("legacyTopic|legacySub|legacyMicro|legacyUid", 0.2) // no signal
        val oneOff = g.remember("t1|s1|m2|oneOff", 0.2,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.2)) // nearest anchor @ 0.83 => low novelty
        val encode = g.engram(oneOff)!!.salience
        assertTrue(encode > bgePerPass.salienceMin && encode <= 0.2 + 1e-9,
            "low-novelty one-off encodes below base but above salienceMin, was $encode")

        var legacyPass = -1
        var oneOffPass = -1
        for (k in 1..40) {
            g.decay()
            if (legacyPass < 0 && g.engram(legacy) == null) legacyPass = k
            if (oneOffPass < 0 && g.engram(oneOff) == null) oneOffPass = k
            if (legacyPass >= 0 && oneOffPass >= 0) break
        }
        assertTrue(oneOffPass in 1..(legacyPass + 1),
            "one-off must prune on ~the legacy schedule: oneOff=$oneOffPass legacy=$legacyPass")
    }
}
