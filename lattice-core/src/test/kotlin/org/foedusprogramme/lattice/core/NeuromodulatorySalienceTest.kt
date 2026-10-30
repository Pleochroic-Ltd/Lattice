package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline unit tests for the pure salience combination function. No embedder, no graph:
 * [NeuromodulatorySalience.combine] takes the nearest cosine directly, so every hazard the formula
 * defuses is pinned here in isolation.
 */
class NeuromodulatorySalienceTest {

    private val cfg = LatticeGraph.Config.bgeM3() // dedupThreshold=0.86, noveltyFloorCosine=0.60, gains default

    private fun combine(base: Double, c: Double?, arousal: Double? = null) =
        NeuromodulatorySalience.combine(base, c, arousal, cfg)

    /** Band midpoint cosine at which novelty == noveltyNeutral (0.5): (0.86+0.60)/2 = 0.73. */
    private val neutralCosine = (cfg.dedupThreshold + cfg.noveltyFloorCosine) / 2.0 // 0.73

    @Test
    fun emptyPoolUsesNeutralPriorScoresAtBase() {
        // First write into an empty pool: neutral prior => no first-write novelty spike.
        assertEquals(0.5, combine(0.5, null, null), 1e-9)
    }

    @Test
    fun zeroArousalScoresIdenticallyToAbsent() {
        // The 0-vs-absent ambiguity is closed by construction.
        assertEquals(combine(0.5, neutralCosine, null), combine(0.5, neutralCosine, 0.0), 0.0)
        assertEquals(combine(0.7, 0.68, null), combine(0.7, 0.68, 0.0), 0.0)
    }

    @Test
    fun outOfRangeArousalTreatedAsAbsent() {
        val ref = combine(0.6, 0.70, null)
        assertEquals(ref, combine(0.6, 0.70, 1.5), 0.0)
        assertEquals(ref, combine(0.6, 0.70, -0.2), 0.0)
        assertEquals(ref, combine(0.6, 0.70, Double.NaN), 0.0)
    }

    @Test
    fun arousalIsOneSidedAndBaseBounded() {
        val base = 0.5
        val c = neutralCosine // novelty neutral => modulation is arousal-only
        val floor = combine(base, c, 0.0)
        var prev = floor
        for (i in 0..20) {
            val a = i / 20.0 // integer steps avoid float drift past 1.0 into the out-of-range fold
            val s = combine(base, c, a)
            assertTrue(s >= prev - 1e-12, "arousal must never lower salience (a=$a)")
            prev = s
        }
        val rise = combine(base, c, 1.0) - floor
        assertTrue(rise > 0.0, "arousal must raise salience")
        assertTrue(rise <= cfg.salienceArousalGain * base + 1e-9, "rise bounded by arousalGain·base")
    }

    @Test
    fun noveltyBandRecalibrated() {
        val base = 0.5
        // c at floor => novelty 1.0 => M=+0.4 => 0.5*(1+0.5*0.4)=0.6
        assertEquals(0.6, combine(base, cfg.noveltyFloorCosine, null), 1e-9)
        // c at band top (dedupThreshold) => novelty 0.0 => M=-0.4 => 0.5*(1-0.5*0.4)=0.4
        assertEquals(0.4, combine(base, cfg.dedupThreshold, null), 1e-9)
        // c at midpoint => novelty ~0.5 => M=0 => base
        assertEquals(0.5, combine(base, neutralCosine, null), 1e-9)
        // monotonic non-increasing in c across the band, no discontinuity
        var c = cfg.noveltyFloorCosine
        var prev = combine(base, c, null)
        while (c <= cfg.dedupThreshold + 1e-9) {
            val s = combine(base, c, null)
            assertTrue(s <= prev + 1e-12, "salience must be non-increasing in cosine (c=$c)")
            assertTrue(kotlin.math.abs(s - prev) < 0.02, "no discontinuity across the band")
            prev = s
            c += 0.005
        }
    }

    @Test
    fun noveltyClampsBelowFloorAndAboveTop() {
        val base = 0.5
        // below the floor clamps to novelty 1.0 (same as at the floor)
        assertEquals(combine(base, cfg.noveltyFloorCosine, null), combine(base, 0.50, null), 1e-9)
        // above the band top clamps to novelty 0.0 (same as at the top)
        assertEquals(combine(base, cfg.dedupThreshold, null), combine(base, 0.90, null), 1e-9)
    }

    @Test
    fun neutralNoveltyNoArousalReturnsBase() {
        for (base in listOf(0.2, 0.5, 0.8)) {
            assertEquals(base, combine(base, neutralCosine, null), 1e-9, "pivot must hold for base=$base")
        }
    }

    @Test
    fun defaultBaseAtNeutralLandsOnPromotionFloor() {
        // The extractor's fallback importance (0.5) at neutral novelty encodes at exactly the
        // promotion floor (0.5) — the two 0.5s coincide by design.
        assertEquals(cfg.promoteSalienceFloor, combine(0.5, neutralCosine, null), 1e-9)
        assertTrue(combine(0.5, cfg.noveltyFloorCosine, null) > 0.5, "distinct default fact sits above the floor")
        assertTrue(combine(0.5, cfg.dedupThreshold, null) < 0.5, "redundant default fact sits below the floor")
    }

    @Test
    fun lowBaseIsTrappedNearBase() {
        // The bounded-envelope lemma: a low-base fact cannot be inflated into the durable band
        // no matter how novel or emotional it looks.
        for (b in listOf(0.1, 0.2, 0.3)) {
            val lower = b * (1 - cfg.salienceNoveltyGain * cfg.noveltyNeutral * b) // b·(1−0.4b)
            val upper = b * (1 + (cfg.salienceNoveltyGain * (1 - cfg.noveltyNeutral) + cfg.salienceArousalGain) * b) // b·(1+0.8b)
            var c = 0.50
            while (c <= 0.90 + 1e-9) {
                var a = 0.0
                while (a <= 1.0 + 1e-9) {
                    val s = combine(b, c, a)
                    assertTrue(s >= lower - 1e-9, "base=$b c=$c a=$a: $s below envelope floor $lower")
                    assertTrue(s <= upper + 1e-9, "base=$b c=$c a=$a: $s above envelope ceiling $upper")
                    a += 0.1
                }
                c += 0.02
            }
        }
        // Concrete worked examples.
        assertTrue(combine(0.2, 0.50, 1.0) <= 0.232 + 1e-9, "b=0.2 never exceeds 0.232")
        assertTrue(combine(0.1, 0.50, 1.0) <= 0.108 + 1e-9, "b=0.1 never exceeds 0.108")
    }

    @Test
    fun clampsToFloorAndCeiling() {
        // Ceiling: maximally novel + emotional + important overshoots and clamps to salienceMax.
        assertEquals(cfg.salienceMax, combine(1.0, cfg.noveltyFloorCosine, 1.0), 1e-9)
        // Floor: a tiny base at maximal redundancy dips below salienceMin and clamps up.
        assertEquals(cfg.salienceMin, combine(0.05, cfg.dedupThreshold, null), 1e-9)
    }

    @Test
    fun gainPerturbationKeepsOrderingStable() {
        // A fixed corpus of (base, cosine, arousal) tuples with cosines drawn from bge's real band.
        val corpus = listOf(
            Triple(0.8, 0.62, 0.9),
            Triple(0.7, 0.66, null),
            Triple(0.6, 0.70, 0.3),
            Triple(0.5, 0.73, null),
            Triple(0.5, 0.83, 0.2),
            Triple(0.4, 0.80, null),
            Triple(0.3, 0.64, 0.5),
            Triple(0.2, 0.62, null),
        )
        fun scores(c: LatticeGraph.Config) =
            corpus.map { NeuromodulatorySalience.combine(it.first, it.second, it.third, c) }

        val baseline = scores(cfg)
        val baselineOrder = baseline.indices.sortedBy { baseline[it] }
        val baselineAboveFloor = baseline.count { it >= cfg.promoteSalienceFloor }

        for (dn in listOf(-0.1, 0.1)) for (da in listOf(-0.1, 0.1)) {
            val perturbed = cfg.copy(
                salienceNoveltyGain = cfg.salienceNoveltyGain + dn,
                salienceArousalGain = cfg.salienceArousalGain + da,
            )
            val s = scores(perturbed)
            val order = s.indices.sortedBy { s[it] }
            assertEquals(baselineOrder, order, "ordering must be invariant under gain perturbation ($dn,$da)")
            assertEquals(baselineAboveFloor, s.count { it >= cfg.promoteSalienceFloor },
                "which tuples clear the promotion floor must be stable ($dn,$da)")
        }
    }
}
