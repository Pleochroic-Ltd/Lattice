package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Meaningful-spread APPEND tests (see [LatticeGraph.Config.spreadAppendSlots]).
 *
 * The defect this fixes: at the production recall limit (6) seedCount (8) already exceeds it, so the
 * cosine seeds fill every base slot and a spread hit — even one reached over a strong CAUSAL edge —
 * never out-ranks a raw seed. The retired DESTRUCTIVE reserve tried to displace a seed, which both
 * evicted a genuine base member (a causal mechanism node on its cue) and could only
 * reshuffle. The APPEND design instead grows the emit to limit..limit+M NON-destructively, so the
 * base is emitted verbatim and spreading CONTRIBUTES a member that flat cosine top-k structurally
 * cannot surface — without evicting or reordering anything.
 *
 * Cosines are deterministic via [BgeBandFakeEmbedder]: sharing a "topic|sub|micro|uid" prefix sums
 * fixed masses. Cue "q|q|q|q" vs a same-topic seed "q|sN|.." = 0.64 (RELATED); vs an unrelated
 * "z|z|z|z" = 0.60 (the compressed floor) — below the 0.64 seeds, so a manually-wired CAUSAL target
 * is BURIED (not seeded, not in flat top-k) yet still reachable by spreading.
 */
class LatticeSpreadAppendTest {

    private val clock = { Instant.parse("2026-08-03T00:00:00Z") }

    /** seedCount == limit == 4, no auto-links (wired manually), strong-tier emit at 0.62. */
    private fun cfg(
        appendSlots: Int,
        frac: Double = 0.35,
        originRank: Int = 2,
        redundancy: Double = 0.85,
    ) = LatticeGraph.Config(
        seedCount = 4,
        autoLinkThreshold = 0.95, // 0.64/0.83 < 0.95 => remember() creates NO edges; we wire by hand
        activationFloor = 0.15,
        seedFloor = 0.0,
        emitFloor = 0.62, // seeds at 0.64 clear it; the unrelated 0.60 floor does not
        hopDecay = 0.6,
        recencyGain = 0.0,
        neuromodulatorySalience = false, // raw salience 0.5, deterministic ranking
        spreadAppendSlots = appendSlots,
        spreadAppendActivationFrac = frac,
        spreadAppendOriginRank = originRank,
        spreadAppendRedundancy = redundancy,
    )

    private val CUE = "q|q|q|q"

    /**
     * Four same-topic seeds (cue-cosine 0.64) that fill the base, plus a target [targetContent] wired
     * from the seed at [causalFromSeedIndex] with [causalWeight]. Seeds are mutually unrelated (0.60)
     * so no auto-links form. Insertion order sets the tie-break, so seed rank == index.
     */
    private fun graphWithTarget(
        config: LatticeGraph.Config,
        targetContent: String,
        causalFromSeedIndex: Int = 0,
        causalWeight: Double = 0.9,
        targetIsEpisode: Boolean = false,
        edgeType: Edge.Type = Edge.Type.CAUSAL,
    ): Triple<LatticeGraph, List<Long>, Long> {
        val g = LatticeGraph(BgeBandFakeEmbedder(), config, now = clock)
        val seeds = listOf(
            g.remember("q|sa|ma|seedA", salience = 0.5),
            g.remember("q|sb|mb|seedB", salience = 0.5),
            g.remember("q|sc|mc|seedC", salience = 0.5),
            g.remember("q|sd|md|seedD", salience = 0.5),
        )
        val t = g.remember(targetContent, salience = 0.5, isEpisode = targetIsEpisode)
        g.link(seeds[causalFromSeedIndex], t, edgeType, causalWeight)
        return Triple(g, seeds, t)
    }

    /** The canonical buried-causal target: unrelated topic (cue-cosine 0.60), CAUSAL from the top seed. */
    private fun buildGraph(config: LatticeGraph.Config, causalFromSeedIndex: Int = 0, causalWeight: Double = 0.9) =
        graphWithTarget(config, "z|z|z|targetT", causalFromSeedIndex, causalWeight)

    @Test
    fun appendSurfacesBuriedCausalHitNonDestructively() {
        // Baseline (append OFF): the 0.64 seeds fill all 4 base slots; T (0.60, activation ~0.35) is dropped.
        val (base, baseSeeds, tBase) = buildGraph(cfg(appendSlots = 0))
        val flat = base.recallFlatTopK(CUE, limit = 4).map { it.engram.id }
        assertFalse(tBase in flat, "flat cosine top-4 must MISS the buried causal target (this is the gap)")
        val baseIds = base.recall(CUE, limit = 4).map { it.engram.id }
        assertFalse(tBase in baseIds, "baseline recall also buries T — seeds fill every slot")
        assertEquals(baseSeeds.toSet(), baseIds.toSet(), "baseline emits exactly the four seeds")

        // Append ON: T is APPENDED after the full base — non-destructively (all four seeds retained).
        val (g, seeds, t) = buildGraph(cfg(appendSlots = 1))
        val hits = g.recall(CUE, limit = 4)
        val ids = hits.map { it.engram.id }
        assertEquals(5, ids.size, "emit grows to limit + 1 (append is additive, not a reshuffle)")
        assertEquals(seeds.toSet(), ids.take(4).toSet(), "the base (first `limit`) is the four seeds, unchanged")
        assertEquals(t, ids[4], "the appended causal target sits AFTER the base")
        assertEquals(Activation.Reach.SPREAD, hits.first { it.engram.id == t }.via, "T is reached by spread")
        assertTrue(seeds.all { it in ids }, "NO seed is evicted (non-destructive)")
    }

    @Test
    fun masterSwitchOffEqualsPreAppendBaseline() {
        // spreadAppendSlots defaults to 0; recall must equal the pre-append seed-ranked base exactly.
        val (g, seeds, t) = buildGraph(cfg(appendSlots = 0))
        val ids = g.recall(CUE, limit = 4).map { it.engram.id }
        assertEquals(4, ids.size, "OFF emits exactly `limit` hits, no append")
        assertEquals(seeds.toSet(), ids.toSet(), "OFF emits the pre-append base (the four seeds)")
        assertFalse(t in ids, "OFF never surfaces the graph-only causal target")
    }

    @Test
    fun appendIsAdditiveNeverEvictsBase() {
        // The base (first `limit` hits) is byte-identical with the feature ON vs OFF — append only adds a tail.
        val baseOff = buildGraph(cfg(appendSlots = 0)).first.recall(CUE, limit = 4).map { it.engram.id }
        val onHits = buildGraph(cfg(appendSlots = 2)).first.recall(CUE, limit = 4).map { it.engram.id }
        assertEquals(baseOff, onHits.take(4), "base(M=0) == base(M=2); appended hits occupy positions > limit only")
        assertTrue(onHits.size >= 4, "append never shrinks the emit below limit")
    }

    @Test
    fun activationFloorRejectsFaintSpreadHit() {
        // Weak causal edge (0.5): T is still REACHED (0.64*0.5*0.6 = 0.192 >= activationFloor 0.15) and
        // meaningful, but 0.192 < frac*bestSeed (0.35*0.64 = 0.224), so the activation guard abstains.
        val (g, _, t) = buildGraph(cfg(appendSlots = 1), causalWeight = 0.5)
        val ids = g.recall(CUE, limit = 4).map { it.engram.id }
        assertFalse(t in ids, "a faint spread hit below frac*bestSeed must NOT be appended")
        assertEquals(4, ids.size, "no append -> emit stays at limit")
    }

    @Test
    fun originRankRejectsPeripheralSeedHit() {
        // T reached only from the rank-3 (peripheral) seed: originRank 3 is not < spreadAppendOriginRank (2),
        // so the provenance guard abstains — a strong edge off a low-ranked seed can't append.
        val (g, _, t) = buildGraph(cfg(appendSlots = 1), causalFromSeedIndex = 3)
        assertFalse(t in g.recall(CUE, limit = 4).map { it.engram.id }, "a hit reached only from a peripheral seed must NOT append")

        // Control: the SAME edge from the rank-0 seed DOES qualify (isolates provenance as the cause).
        val (g0, _, t0) = buildGraph(cfg(appendSlots = 1), causalFromSeedIndex = 0)
        assertTrue(t0 in g0.recall(CUE, limit = 4).map { it.engram.id }, "same edge from the top seed appends")
    }

    @Test
    fun redundancyGuardDropsNearDuplicateOfBase() {
        // A candidate that near-duplicates a base seed (paraphrase, cosine 0.90 >= redundancy 0.85) is
        // dropped even though it is meaningful, on-topic and graph-only — true near-dup protection.
        val (gDup, _, tDup) = graphWithTarget(cfg(appendSlots = 1), "q|sa|ma|dupA") // vs seedA "q|sa|ma|seedA" => 0.90
        assertFalse(tDup in gDup.recall(CUE, limit = 4).map { it.engram.id }, "a near-duplicate (cos>=0.85) of a base node must NOT append")

        // Control: a genuine on-topic NEIGHBOR at cosine 0.83 (< 0.85) to the same seed IS appended.
        val (gNear, _, tNear) = graphWithTarget(cfg(appendSlots = 1), "q|sa|mz|nearA") // vs seedA => 0.83
        assertTrue(tNear in gNear.recall(CUE, limit = 4).map { it.engram.id }, "a 0.83 neighbor (< redundancy) is kept")
    }

    @Test
    fun graphOnlyGuardRejectsCosineVisibleSpreadHit() {
        // An episode target whose text == the cue has cue-cosine 1.0, so it sits INSIDE flat-top-4 (a
        // flat store would surface it) — but the episode seed-rank penalty (1.0*0.5 < the 0.90 seeds)
        // keeps it un-seeded, and the episode rankScore penalty keeps it out of the base. The graph-only
        // guard (flat rank >= limit) must REJECT it: append only ever adds what cosine could not surface.
        fun episodeGraph(targetContent: String): Triple<LatticeGraph, List<Long>, Long> {
            val g = LatticeGraph(BgeBandFakeEmbedder(), cfg(appendSlots = 1), now = clock)
            val seeds = listOf(
                g.remember("q|q|q|s1", salience = 0.5), // cue-cosine 0.90 (topic+sub+micro shared)
                g.remember("q|q|q|s2", salience = 0.5),
                g.remember("q|q|q|s3", salience = 0.5),
                g.remember("q|q|q|s4", salience = 0.5),
            )
            val t = g.remember(targetContent, salience = 0.5, isEpisode = true)
            g.link(seeds[0], t, Edge.Type.CAUSAL, 0.9)
            return Triple(g, seeds, t)
        }
        // Cosine-visible episode (text == cue, cue-cosine 1.0, flat rank 0 < limit) -> rejected.
        val (gVis, _, tVis) = episodeGraph(CUE)
        assertFalse(tVis in gVis.recall(CUE, limit = 4).map { it.engram.id }, "a cosine-visible hit (flat rank < limit) must NOT append")

        // Control: a graph-only episode (cue-cosine 0.60, flat rank >= limit) with the SAME wiring appends.
        val (gGraph, _, tGraph) = episodeGraph("z|z|z|ep")
        assertTrue(tGraph in gGraph.recall(CUE, limit = 4).map { it.engram.id }, "the graph-only variant (flat rank >= limit) appends")
    }

    @Test
    fun strongTierGuardAbstainsOnWeakCue() {
        // An unrelated cue: every node sits at the 0.60 floor, below emitFloor 0.62, so the block only
        // emits via the weak meaningful-edge tier. Append requires the STRONG tier, so append ON must
        // equal append OFF here — the weak-cue path is untouched.
        val weakCue = "z|z|z|q" // ~0.60 to the q-topic seeds and to T
        val off = buildGraph(cfg(appendSlots = 0)).first.recall(weakCue, limit = 4).map { it.engram.id }
        val on = buildGraph(cfg(appendSlots = 1)).first.recall(weakCue, limit = 4).map { it.engram.id }
        assertEquals(off, on, "append must not engage on a weak (below-emitFloor) cue")
    }

    @Test
    fun offTopicCueStaysSilentUnderAppend() {
        // No meaningful edge to reach and an off-topic cue (0.60 < emitFloor): the two-tier emit gate
        // returns emptyList BEFORE append runs, with append ON exactly as with it OFF.
        fun seedsOnly(config: LatticeGraph.Config): LatticeGraph {
            val g = LatticeGraph(BgeBandFakeEmbedder(), config, now = clock)
            g.remember("q|sa|ma|seedA", salience = 0.5)
            g.remember("q|sb|mb|seedB", salience = 0.5)
            g.remember("q|sc|mc|seedC", salience = 0.5)
            g.remember("q|sd|md|seedD", salience = 0.5)
            return g
        }
        val offTopic = "z|z|z|z" // 0.60 to every q-topic seed, below emitFloor; no meaningful reach
        val off = seedsOnly(cfg(appendSlots = 0)).recall(offTopic, limit = 4)
        val on = seedsOnly(cfg(appendSlots = 2)).recall(offTopic, limit = 4)
        assertTrue(off.isEmpty(), "off-topic cue is gated silent with append OFF")
        assertTrue(on.isEmpty(), "off-topic cue stays gated silent with append ON (gate runs before append)")
    }

    @Test
    fun reinforcementExcludesAppendedHits() {
        // After a SOFT recall, only the BASE ids are reinforced/touched; the appended graph-only hit is a
        // read-only surface (no CO_RETRIEVAL wiring, no softReplays increment) so it can't entrench itself.
        val (g, seeds, t) = buildGraph(cfg(appendSlots = 1))
        val hits = g.recall(CUE, limit = 4, reinforce = Reinforce.SOFT)
        assertTrue(t in hits.map { it.engram.id }, "precondition: T is appended")

        val tEngram = g.engrams().first { it.id == t }
        assertEquals(0, tEngram.softReplays, "appended hit gets NO softReplays increment")
        // The only edge on T is its original inbound CAUSAL — no Hebbian CO_RETRIEVAL was wired to it.
        assertTrue(g.neighbors(t).none { it.type == Edge.Type.CO_RETRIEVAL }, "appended hit is never Hebbian-wired")

        // Base seeds ARE reinforced: they gained softReplays and CO_RETRIEVAL edges among themselves.
        val seedEngram = g.engrams().first { it.id == seeds[0] }
        assertEquals(1, seedEngram.softReplays, "base seed IS reinforced (softReplays incremented)")
        assertTrue(g.neighbors(seeds[0]).any { it.type == Edge.Type.CO_RETRIEVAL }, "base seeds are Hebbian co-wired")
    }
}
