package org.foedusprogramme.lattice.core

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cadence-independence of salience/weight decay (the fix for the quadratic-in-pass-count avalanche).
 *
 * Invariant under test: running the decay pass N times over T real hours applies EXACTLY the same total
 * decay as ONE pass over T hours — for both the node-salience line and the edge-weight line — because
 * decay measures elapsed time from a per-node ([Engram.salienceDecayedAt]) / per-edge
 * ([Edge.weightDecayedAt]) watermark that each pass advances to now. The legacy flag reproduces the bug.
 *
 * Offline [LocalHashingEmbedder]; a mutable clock advances between passes.
 */
class LatticeDecayCadenceTest {

    private val START = Instant.parse("2026-08-01T00:00:00Z")
    private val HL = 168.0 // 1-week half-life

    private fun clockOf(ref: AtomicReference<Instant>): () -> Instant = { ref.get() }

    private fun fixedConfig() = LatticeGraph.Config(
        decayHalfLifeHours = HL,
        cadenceIndependentDecay = true,
        recencyGain = 0.0,
    )

    private fun legacyConfig() = LatticeGraph.Config(
        decayHalfLifeHours = HL,
        cadenceIndependentDecay = false,
        recencyGain = 0.0,
    )

    // ---- (i) cadence independence: N daily passes over T == 1 pass over T (NODE salience) ----
    @Test
    fun nDailyPassesEqualOnePass_nodeSalience() {
        val days = 30

        // N daily passes.
        val refN = AtomicReference(START)
        val gN = LatticeGraph(LocalHashingEmbedder(), fixedConfig(), now = clockOf(refN))
        val idN = gN.remember("episodic memory about a weekend hike", salience = 0.6)
        for (d in 1..days) {
            refN.set(START.plus(Duration.ofDays(d.toLong())))
            gN.decay()
        }
        val salienceN = gN.engram(idN)!!.salience

        // 1 pass over the whole T.
        val ref1 = AtomicReference(START)
        val g1 = LatticeGraph(LocalHashingEmbedder(), fixedConfig(), now = clockOf(ref1))
        val id1 = g1.remember("episodic memory about a weekend hike", salience = 0.6)
        ref1.set(START.plus(Duration.ofDays(days.toLong())))
        g1.decay()
        val salience1 = g1.engram(id1)!!.salience

        // Analytic wall-clock target: 0.6 * 0.9^(T/HL).
        val expected = 0.6 * Math.pow(0.9, (days * 24.0) / HL)
        assertEquals(expected, salience1, 1e-9, "single pass == analytic wall-clock")
        assertEquals(salience1, salienceN, 1e-9, "N daily passes == 1 pass over the same elapsed time")
    }

    // ---- (i) cadence independence for the EDGE-weight line ----
    @Test
    fun nDailyPassesEqualOnePass_edgeWeight() {
        val days = 30
        fun run(passes: Int): Double {
            val ref = AtomicReference(START)
            // Semantic + salient so the endpoints are never pruned; we isolate edge-weight decay.
            val g = LatticeGraph(LocalHashingEmbedder(), fixedConfig(), now = clockOf(ref))
            val a = g.remember("the deployment failed with a cuda out of memory error", salience = 0.95, layer = Engram.Layer.SEMANTIC)
            val b = g.remember("the weekend hike had beautiful sunny mountain weather", salience = 0.95, layer = Engram.Layer.SEMANTIC)
            g.link(a, b, Edge.Type.CAUSAL, weight = 0.9)
            val step = days.toDouble() / passes
            for (k in 1..passes) {
                ref.set(START.plus(Duration.ofMinutes((step * k * 24 * 60).toLong())))
                g.decay()
            }
            return g.neighbors(a).first { it.to == b }.weight
        }
        val daily = run(days)
        val once = run(1)
        val expected = 0.9 * Math.pow(0.95, (days * 24.0) / HL)
        assertEquals(expected, once, 1e-9, "single edge pass == analytic wall-clock")
        assertEquals(once, daily, 1e-9, "N daily edge passes == 1 pass over the same elapsed time")
    }

    // ---- (ii) the legacy path avalanches; the fixed path does not (same schedule, same data) ----
    @Test
    fun legacyPathAvalanches_fixedPathSurvives() {
        fun runDaily(cfg: LatticeGraph.Config, days: Int): Double? {
            val ref = AtomicReference(START)
            val g = LatticeGraph(LocalHashingEmbedder(), cfg, now = clockOf(ref))
            val id = g.remember("a fading episodic detail", salience = 0.55)
            for (d in 1..days) {
                ref.set(START.plus(Duration.ofDays(d.toLong())))
                g.decay()
            }
            return g.engram(id)?.salience
        }
        // By day 20 the legacy (buggy) path has already reaped the node (salience < 0.15 floor -> pruned).
        assertNull(runDaily(legacyConfig(), 20), "legacy daily decay reaps the node inside 3 weeks (avalanche)")
        // The fixed path keeps it alive well past then; an s0=0.55 node's wall-clock horizon is ~day 86.
        val fixed20 = runDaily(fixedConfig(), 20)
        assertNotNull(fixed20, "fixed daily decay does NOT reap the node at day 20")
        assertTrue(fixed20 > 0.15, "fixed node still above the prune floor at day 20: $fixed20")
    }

    // ---- (iii) a fresh node decays from its OWN age, not a global watermark ----
    @Test
    fun freshNodeDecaysFromItsOwnAge() {
        val ref = AtomicReference(START)
        val g = LatticeGraph(LocalHashingEmbedder(), fixedConfig(), now = clockOf(ref))
        val old = g.remember("an old memory", salience = 0.6)
        // 10 days later a brand-new memory is encoded; its watermark defaults to its OWN createdAt (now).
        ref.set(START.plus(Duration.ofDays(10)))
        val fresh = g.remember("a brand-new memory", salience = 0.6)
        // One day after that, a single decay pass runs.
        ref.set(START.plus(Duration.ofDays(11)))
        g.decay()

        val sOld = g.engram(old)!!.salience     // decayed 11 days
        val sFresh = g.engram(fresh)!!.salience // decayed only 1 day (its own age), NOT 11
        assertEquals(0.6 * Math.pow(0.9, (11 * 24.0) / HL), sOld, 1e-9)
        assertEquals(0.6 * Math.pow(0.9, (1 * 24.0) / HL), sFresh, 1e-9, "fresh node decays from its own age")
        assertTrue(sFresh > sOld, "the younger memory retains more salience")
    }

    // ---- (iv) recovery-on-use resets the watermark and protects a recalled node ----
    @Test
    fun recoveryOnUseResetsWatermarkAndProtects() {
        val ref = AtomicReference(START)
        // seedFloor gates seeding so the recall of one memory does NOT also surface (and recover) the
        // orthogonal control memory — isolating recovery-on-use to the single node we recall.
        val cfg = fixedConfig().copy(seedFloor = 0.5, emitFloor = 0.0)
        val g = LatticeGraph(LocalHashingEmbedder(), cfg, now = clockOf(ref))
        val recalled = g.remember("quantum entanglement photon polarization laboratory experiment", salience = 0.3)
        val control = g.remember("grandmother baking cinnamon apple pie yesterday afternoon", salience = 0.3)

        // Day 5: an explicit recall of the first memory (FULL testing effect) recovers its salience and
        // resets its decay watermark to now.
        ref.set(START.plus(Duration.ofDays(5)))
        val hits = g.recall("quantum entanglement photon polarization laboratory experiment", reinforce = Reinforce.FULL)
        assertTrue(hits.any { it.engram.id == recalled }, "the recall surfaces the node")
        val e = g.engram(recalled)!!
        assertEquals(ref.get(), e.salienceDecayedAt, "recovery advances the decay watermark to now")
        assertTrue(e.salience > 0.3, "recovery raised salience: ${e.salience}")

        // Run daily decay out to day 55. The recalled node (recovered + watermark reset) survives; the
        // untouched control reaps under the floor (control horizon ~day 46, recalled ~day 61).
        for (d in 6..55) {
            ref.set(START.plus(Duration.ofDays(d.toLong())))
            g.decay()
        }
        assertNotNull(g.engram(recalled), "the recalled node is protected and survives")
        assertNull(g.engram(control), "the never-recalled node fades and is reaped")
    }

    // ---- idempotent-per-elapsed-time: decaying twice with 0 elapsed == decaying once ----
    @Test
    fun idempotentWithZeroElapsedBetweenPasses() {
        val ref = AtomicReference(START)
        val g = LatticeGraph(LocalHashingEmbedder(), fixedConfig(), now = clockOf(ref))
        val a = g.remember("a fact about kotlin coroutines and structured concurrency", salience = 0.8, layer = Engram.Layer.SEMANTIC)
        val b = g.remember("a fact about sqlite write-ahead logging and checkpoints", salience = 0.8, layer = Engram.Layer.SEMANTIC)
        g.link(a, b, Edge.Type.CAUSAL, weight = 0.9)

        ref.set(START.plus(Duration.ofDays(10)))
        g.decay()
        val salAfter1 = g.engram(a)!!.salience
        val wAfter1 = g.neighbors(a).first { it.to == b }.weight
        // Second pass with the clock NOT advanced: 0 elapsed -> factor^0 = 1 -> no further change.
        g.decay()
        assertEquals(salAfter1, g.engram(a)!!.salience, 1e-12, "0 elapsed applies no extra node decay")
        assertEquals(wAfter1, g.neighbors(a).first { it.to == b }.weight, 1e-12, "0 elapsed applies no extra edge decay")
    }

    // ---- persistence: the watermark round-trips through the store ----
    @Test
    fun watermarkRoundTripsThroughStore() {
        val store = TestLatticeStore()
        val ref = AtomicReference(START)
        val id: Long
        run {
            val g = LatticeGraph(LocalHashingEmbedder(), fixedConfig(), now = clockOf(ref), store = store)
            id = g.remember("a fact to persist", salience = 0.7, layer = Engram.Layer.SEMANTIC)
            // A decay pass advances the watermark to a known instant.
            ref.set(START.plus(Duration.ofDays(3)))
            g.decay()
        }
        val g2 = LatticeGraph(LocalHashingEmbedder(), fixedConfig(), now = clockOf(ref), store = store)
        assertEquals(
            START.plus(Duration.ofDays(3)),
            g2.engram(id)!!.salienceDecayedAt,
            "the advanced salience watermark survived the reopen",
        )
    }
}
