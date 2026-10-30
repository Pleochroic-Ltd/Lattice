package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Graph-integration tests for neuromodulatory encode salience. The novelty assertions use
 * [BgeBandFakeEmbedder] (bge's compressed cosine regime); the legacy/switch/dedup/guard cases use
 * [LocalHashingEmbedder], which is sufficient there.
 */
class LatticeSalienceTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }

    private fun bgeGraph() = LatticeGraph(BgeBandFakeEmbedder(), LatticeGraph.Config.bgeM3(), now = clock)

    @Test
    fun distinctWriteGetsHigherSalienceThanNearRestatement() {
        val g = bgeGraph()
        // Anchor the fact pool with one memory.
        g.remember("t1|s1|m1|anchor", salience = 0.6)
        // A distinct write: nearest existing is a DIFFERENT topic (cosine 0.60) => high novelty.
        val distinct = g.remember(
            "z9|s9|m9|distinct", salience = 0.6,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.6, arousal = 0.5),
        )
        // A near-restatement: same topic+sub, different micro (cosine 0.83) => low novelty.
        val restatement = g.remember(
            "t1|s1|m2|restatement", salience = 0.6,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.6, arousal = 0.5),
        )
        val sDistinct = g.engram(distinct)!!.salience
        val sRestatement = g.engram(restatement)!!.salience
        assertTrue(sDistinct > sRestatement + 0.1,
            "novelty must be discriminative in-band: distinct=$sDistinct vs restatement=$sRestatement")
    }

    @Test
    fun dedupHitKeepsCappedRecoveryNotNeuromod() {
        val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(), now = clock)
        val first = g.remember(
            "完全相同的一句记忆内容", salience = 0.2,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.2),
        )
        // A near-identical re-mention (cosine 1.0 >= dedupThreshold) returns the SAME id on the
        // capped dedup-recovery path — it never reaches the neuromod formula.
        val second = g.remember(
            "完全相同的一句记忆内容", salience = 0.9,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.9, arousal = 1.0),
        )
        assertEquals(first, second, "a dedup hit must return the existing engram id")
        // Salience moved only by the capped dedup recovery (0.2 -> 0.3), NOT re-scored by neuromod.
        assertEquals(0.3, g.engram(first)!!.salience, 1e-9)
    }

    @Test
    fun legacyPathUnchangedWithoutSignal() {
        val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config.bgeM3(), now = clock)
        // No signal => salience is the raw param, coerced only to [0,1] — UNCAPPED by [salienceMax].
        val important = g.remember("显式重要事实 importance ten", salience = 1.0)
        assertEquals(1.0, g.engram(important)!!.salience, 1e-9)
        val mid = g.remember("另一条普通事实 something else entirely", salience = 0.7)
        assertEquals(0.7, g.engram(mid)!!.salience, 1e-9)
    }

    @Test
    fun masterSwitchOffUsesRawSalience() {
        val cfg = LatticeGraph.Config.bgeM3().copy(neuromodulatorySalience = false)
        val g = LatticeGraph(BgeBandFakeEmbedder(), cfg, now = clock)
        g.remember("t1|s1|m1|anchor", salience = 0.6)
        // With the master switch off, the signal is ignored and the raw salience param wins (rollback).
        val id = g.remember(
            "z9|s9|m9|distinct", salience = 0.9,
            salienceSignal = LatticeGraph.SalienceSignal(base = 0.9, arousal = 1.0),
        )
        assertEquals(0.9, g.engram(id)!!.salience, 1e-9)
    }

    @Test
    fun signalWithEpisodeThrows() {
        val g = bgeGraph()
        assertFailsWith<IllegalArgumentException> {
            g.remember(
                "t1|s1|m1|ep", salience = 0.6, isEpisode = true,
                salienceSignal = LatticeGraph.SalienceSignal(base = 0.6),
            )
        }
    }
}
