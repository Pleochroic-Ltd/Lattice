package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for the per-node synaptic budget ([LatticeGraph.Config.maxDegree]): bound fan-out
 * under load WITHOUT hurting recall or shipped structure. Every test isolates the cap by disabling
 * auto-link/dedup (impossible thresholds) so edges are created only by explicit [LatticeGraph.link],
 * at weights the test controls.
 */
class LatticeGraphDegreeCapTest {

    private val clock = { Instant.parse("2026-08-03T00:00:00Z") }

    /** maxDegree on; auto-link and dedup disabled so remember() only ever mints a fresh, unlinked node. */
    private fun capConfig(maxDegree: Int) =
        LatticeGraph.Config(maxDegree = maxDegree, autoLinkThreshold = 2.0, dedupThreshold = 2.0)

    private fun graph(config: LatticeGraph.Config) =
        LatticeGraph(LocalHashingEmbedder(), config, now = clock)

    private fun hubDegree(g: LatticeGraph, hub: Long) = g.neighbors(hub).size

    /** A store that rehydrates the graph from fixed engrams/edges (bypassing link). */
    private fun preloadStore(engrams: List<Engram>, edges: List<EdgeRecord>) =
        TestLatticeStore(initial = LatticeStore.Loaded(engrams = engrams, edges = edges))

    /** Size of the largest spread-reachable component (BFS over neighbors, skipping SUPERSEDES/EVIDENCE,
     *  the two types spreading activation ignores). */
    private fun giantComponent(g: LatticeGraph): Int {
        val seen = HashSet<Long>()
        var best = 0
        for (start in g.engrams().map { it.id }) {
            if (start in seen) continue
            var size = 0
            val stack = ArrayDeque<Long>().apply { add(start) }
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (!seen.add(id)) continue
                size++
                for (edge in g.neighbors(id)) {
                    if (edge.type == Edge.Type.SUPERSEDES || edge.type == Edge.Type.EVIDENCE) continue
                    if (edge.to !in seen) stack.add(edge.to)
                }
            }
            if (size > best) best = size
        }
        return best
    }

    /** Walk every edge and assert its reverse exists with equal type+weight (the module invariant). */
    private fun assertSymmetric(g: LatticeGraph) {
        for (e in g.engrams()) {
            for (edge in g.neighbors(e.id)) {
                val back = g.neighbors(edge.to).firstOrNull { it.to == e.id }
                assertTrue(back != null, "missing reverse edge ${edge.to}->${e.id}")
                assertEquals(edge.type, back.type, "type asymmetry ${e.id}<->${edge.to}")
                assertEquals(edge.weight, back.weight, "weight asymmetry ${e.id}<->${edge.to}")
            }
        }
    }

    @Test
    fun writeTimeBoundsDegreeKeepingTheStrongest() {
        val g = graph(capConfig(8))
        val hub = g.remember("hub")
        // 20 leaves, distinct weights 0.05..1.00, added in an order that is NOT weight-sorted.
        val leaves = (0 until 20).map { g.remember("leaf$it") }
        val weightOf = HashMap<Long, Double>()
        for ((i, leaf) in leaves.withIndex()) {
            val w = ((i * 7) % 20 + 1) / 20.0 // pseudo-shuffled distinct weights in (0,1]
            weightOf[leaf] = w
            g.link(hub, leaf, Edge.Type.SEMANTIC, w)
        }
        assertEquals(8, hubDegree(g, hub), "fan-out must be bounded to the budget")
        val survivors = g.neighbors(hub).map { it.to }.toSet()
        val minKept = survivors.minOf { weightOf.getValue(it) }
        val maxEvicted = weightOf.filterKeys { it !in survivors }.values.max()
        assertTrue(minKept > maxEvicted, "the STRONGEST 8 edges must be the survivors ($minKept > $maxEvicted)")
        assertSymmetric(g)
    }

    @Test
    fun protectedEdgesAreNeitherEvictedNorCounted() {
        val g = graph(capConfig(8))
        val hub = g.remember("hub")
        // Fill the evictable budget with 8 SEMANTIC edges.
        val sem = (0 until 8).map { g.remember("s$it").also { id -> g.link(hub, id, Edge.Type.SEMANTIC, 0.5) } }
        // Add one of each protected type at LOW weight — none may be refused or evict a SEMANTIC.
        val abs = g.remember("abs"); g.link(hub, abs, Edge.Type.ABSTRACTS, 0.05)
        val evi = g.remember("evi"); g.link(hub, evi, Edge.Type.EVIDENCE, 0.05)
        val cau = g.remember("cau"); g.link(hub, cau, Edge.Type.CAUSAL, 0.05)
        val old = g.remember("old"); g.markSuperseded(hub, old) // SUPERSEDES edge, protected

        val types = g.neighbors(hub).groupingBy { it.type }.eachCount()
        assertEquals(8, types[Edge.Type.SEMANTIC], "no SEMANTIC edge may be evicted by a protected add")
        assertEquals(1, types[Edge.Type.ABSTRACTS])
        assertEquals(1, types[Edge.Type.EVIDENCE])
        assertEquals(1, types[Edge.Type.CAUSAL])
        assertEquals(1, types[Edge.Type.SUPERSEDES])
        assertEquals(12, hubDegree(g, hub), "total degree = 8 evictable + 4 protected (protected uncounted)")
        for (id in sem) assertTrue(g.neighbors(hub).any { it.to == id }, "SEMANTIC $id survived")
        assertSymmetric(g)
    }

    @Test
    fun weakerNewcomerIsRefused() {
        val g = graph(capConfig(8))
        val hub = g.remember("hub")
        repeat(8) { g.remember("s$it").also { id -> g.link(hub, id, Edge.Type.SEMANTIC, 0.9) } }
        val weakLeaf = g.remember("weak")
        g.link(hub, weakLeaf, Edge.Type.SEMANTIC, 0.1) // weaker than every incumbent
        assertEquals(8, hubDegree(g, hub))
        assertFalse(g.neighbors(hub).any { it.to == weakLeaf }, "weak newcomer must be refused")
        assertTrue(g.neighbors(weakLeaf).isEmpty(), "refusal must leave no dangling reverse edge")
        assertSymmetric(g)
    }

    @Test
    fun strongerNewcomerEvictsTheWeakestIncumbent() {
        val g = graph(capConfig(8))
        val hub = g.remember("hub")
        val weakIncumbent = g.remember("weak")
        g.link(hub, weakIncumbent, Edge.Type.SEMANTIC, 0.1)
        repeat(7) { g.remember("s$it").also { id -> g.link(hub, id, Edge.Type.SEMANTIC, 0.9) } }
        val strongLeaf = g.remember("strong")
        g.link(hub, strongLeaf, Edge.Type.SEMANTIC, 0.95)
        assertEquals(8, hubDegree(g, hub))
        assertTrue(g.neighbors(hub).any { it.to == strongLeaf }, "stronger newcomer must be admitted")
        assertFalse(g.neighbors(hub).any { it.to == weakIncumbent }, "weakest incumbent must be evicted")
        assertTrue(g.neighbors(weakIncumbent).isEmpty(), "eviction must remove both directions")
        assertSymmetric(g)
    }

    @Test
    fun homeostaticPassTrimsAPreCapGraphOnDecay() {
        // Rehydrate a graph from a store that predates the cap: init loads edges DIRECTLY into the
        // adjacency map (bypassing link/competition), so the hub starts over budget. decay() must
        // trim it to the budget, keeping the strongest — the migration path the homeostatic pass exists for.
        val emb = LocalHashingEmbedder()
        val t = clock()
        val hub = 1L
        val engrams = ArrayList<Engram>()
        engrams.add(Engram(hub, "hub", emb.embed("hub"), 0.9, Engram.Layer.SEMANTIC, t, t))
        val edges = ArrayList<EdgeRecord>()
        val weightOf = HashMap<Long, Double>()
        for (i in 0 until 20) {
            val id = (i + 2).toLong()
            engrams.add(Engram(id, "leaf$i", emb.embed("leaf$i"), 0.8, Engram.Layer.SEMANTIC, t, t))
            val w = ((i * 7) % 20 + 1) / 20.0
            weightOf[id] = w
            edges.add(EdgeRecord(hub, id, Edge.Type.SEMANTIC, w, t))
        }
        val preloaded = preloadStore(engrams, edges)
        // High salience + semantic + wall-clock null so no NODE is pruned; we isolate the degree pass.
        val cfg = capConfig(8).copy(semanticSalienceDecay = 1.0, decayHalfLifeHours = null, decayFactor = 1.0)
        val g = LatticeGraph(emb, cfg, now = clock, store = preloaded)
        assertEquals(20, hubDegree(g, hub), "pre-cap load leaves the hub over budget")

        g.decay()
        assertEquals(8, hubDegree(g, hub), "homeostatic pass trims to the budget")
        val survivors = g.neighbors(hub).map { it.to }.toSet()
        val minKept = survivors.minOf { weightOf.getValue(it) }
        val maxEvicted = weightOf.filterKeys { it !in survivors }.values.max()
        assertTrue(minKept > maxEvicted, "homeostasis keeps the STRONGEST edges ($minKept > $maxEvicted)")
        assertSymmetric(g)
    }

    @Test
    fun disabledByDefaultIsExactPreCapBehavior() {
        // maxDegree defaults to 0 (off): a 20-way hub keeps all 20 edges, byte-for-byte prior behavior.
        val g = LatticeGraph(
            LocalHashingEmbedder(),
            LatticeGraph.Config(autoLinkThreshold = 2.0, dedupThreshold = 2.0),
            now = clock,
        )
        val hub = g.remember("hub")
        repeat(20) { g.remember("leaf$it").also { id -> g.link(hub, id, Edge.Type.SEMANTIC, 0.1 + it * 0.01) } }
        assertEquals(20, hubDegree(g, hub), "cap OFF must not evict anything")
        assertSymmetric(g)
    }

    @Test
    fun protectedTypeUpTypedOverSemantic() {
        // A PROTECTED relation (CAUSAL / SUPERSEDES) arriving over a pre-existing
        // evictable SEMANTIC auto-link must UP-TYPE the edge in place (both directions + store), so the
        // synaptic budget stops counting/evicting a real provenance/structural relation geometry masked.
        val g = graph(capConfig(4))
        val hub = g.remember("hub")
        val cause = g.remember("cause")
        val old = g.remember("old")
        // Two pre-existing EVICTABLE SEMANTIC edges (as a cosine-close auto-link would create).
        g.link(hub, cause, Edge.Type.SEMANTIC, 0.7)
        g.link(hub, old, Edge.Type.SEMANTIC, 0.7)
        assertEquals(Edge.Type.SEMANTIC, g.neighbors(hub).first { it.to == cause }.type)

        // A CAUSAL relation and a supersession over those SAME pairs must up-type in place.
        g.link(hub, cause, Edge.Type.CAUSAL, 0.5)
        g.markSuperseded(hub, old)
        assertEquals(Edge.Type.CAUSAL, g.neighbors(hub).first { it.to == cause }.type, "CAUSAL up-typed fwd")
        assertEquals(Edge.Type.CAUSAL, g.neighbors(cause).first { it.to == hub }.type, "CAUSAL up-typed rev")
        assertEquals(Edge.Type.SUPERSEDES, g.neighbors(hub).first { it.to == old }.type, "SUPERSEDES up-typed fwd")
        assertEquals(Edge.Type.SUPERSEDES, g.neighbors(old).first { it.to == hub }.type, "SUPERSEDES up-typed rev")
        assertFalse(
            g.neighbors(hub).any { (it.to == cause || it.to == old) && it.type == Edge.Type.SEMANTIC },
            "0 protected relations may remain masked as SEMANTIC",
        )

        // The up-typed edges are UNCOUNTED: saturate the evictable budget (4 SEMANTIC) — both protected
        // edges survive while only SEMANTIC edges compete against the budget.
        repeat(4) { g.remember("s$it").also { id -> g.link(hub, id, Edge.Type.SEMANTIC, 0.6) } }
        assertTrue(g.neighbors(hub).any { it.to == cause && it.type == Edge.Type.CAUSAL }, "CAUSAL survived saturation")
        assertTrue(g.neighbors(hub).any { it.to == old && it.type == Edge.Type.SUPERSEDES }, "SUPERSEDES survived saturation")
        assertEquals(4, g.neighbors(hub).count { it.type == Edge.Type.SEMANTIC }, "budget bounds SEMANTIC (evictable) only")
        assertEquals(6, hubDegree(g, hub), "4 evictable + 2 protected (protected uncounted)")
        assertSymmetric(g)
    }

    @Test
    fun capEvictionsCountedSeparatelyFromDecayPrunes() {
        // A decay pass that both prunes a sub-floor edge AND trims an over-budget
        // hub must report the two in DISTINCT counters (bound-enforcement vs weight decay).
        val emb = LocalHashingEmbedder()
        val t = clock()
        val engrams = ArrayList<Engram>()
        val edges = ArrayList<EdgeRecord>()
        // Hub over budget: 12 SEMANTIC edges, budget 8 -> homeostatic evicts exactly 4 (cap evictions).
        val hub = 1L
        engrams.add(Engram(hub, "hub", emb.embed("hub"), 0.9, Engram.Layer.SEMANTIC, t, t))
        for (i in 0 until 12) {
            val id = (i + 2).toLong()
            engrams.add(Engram(id, "leaf$i", emb.embed("leaf$i"), 0.8, Engram.Layer.SEMANTIC, t, t))
            edges.add(EdgeRecord(hub, id, Edge.Type.SEMANTIC, 0.4 + i * 0.02, t))
        }
        // A separate weak pair (degree 1, never capped) whose edge is below pruneFloor -> decay prune.
        val p = 100L; val q = 101L
        engrams.add(Engram(p, "p", emb.embed("p"), 0.9, Engram.Layer.SEMANTIC, t, t))
        engrams.add(Engram(q, "q", emb.embed("q"), 0.9, Engram.Layer.SEMANTIC, t, t))
        edges.add(EdgeRecord(p, q, Edge.Type.SEMANTIC, 0.01, t)) // < pruneFloor 0.03
        val g = LatticeGraph(emb, capConfig(8).copy(semanticSalienceDecay = 1.0, decayHalfLifeHours = null, decayFactor = 1.0), now = clock, store = preloadStore(engrams, edges))

        val res = g.decayDetailed()
        assertEquals(4, res.capEvictions, "exactly (12 - 8) over-budget edges are cap evictions")
        assertTrue(res.prunedEdges in 1..2, "the sub-floor edge is a decay prune, NOT conflated with the 4 cap evictions")
        assertEquals(8, hubDegree(g, hub), "hub trimmed to budget")
        assertNull(g.neighbors(p).firstOrNull { it.to == q }, "sub-floor edge pruned")
        assertSymmetric(g)
    }

    @Test
    fun connectivityPreservedOnRealWeights() {
        // A redundantly-connected dense topic with CONTINUOUS distinct weights: the cap bounds fan-out
        // WITHOUT fragmenting the spread-reachable giant component or isolating any node.
        fun buildClique(cfg: LatticeGraph.Config): LatticeGraph {
            val g = graph(cfg)
            val n = 60
            val ids = (0 until n).map { g.remember("n$it") }
            for (i in 0 until n) for (j in i + 1 until n) {
                val w = ((i * 997 + j * 31) % 1000 + 1) / 1001.0 // distinct continuous weights in (0,1)
                g.link(ids[i], ids[j], Edge.Type.SEMANTIC, w)
            }
            return g
        }
        val off = buildClique(capConfig(0)) // maxDegree 0 == OFF
        val on = buildClique(capConfig(40))
        assertTrue(on.engrams().all { on.neighbors(it.id).size <= 40 }, "cap ON bounds fan-out to 40")
        assertTrue(off.engrams().any { off.neighbors(it.id).size > 40 }, "cap OFF leaves the clique uncapped")
        assertEquals(giantComponent(off), giantComponent(on), "spread-reachable giant component preserved")
        assertEquals(60, giantComponent(on), "cap must not fragment a redundantly-connected topic")
        assertEquals(0, on.engrams().count { on.neighbors(it.id).isEmpty() }, "0 newly-isolated nodes")
        assertSymmetric(on)
    }

    @Test
    fun strengtheningAnExistingEdgeDoesNotCompete() {
        // Re-link an already-present pair (a strengthen, not a new synapse): fan-out is unchanged,
        // so it must never trigger competition even at a full hub.
        val g = graph(capConfig(4))
        val hub = g.remember("hub")
        val leaves = (0 until 4).map { g.remember("s$it").also { id -> g.link(hub, id, Edge.Type.SEMANTIC, 0.5) } }
        assertEquals(4, hubDegree(g, hub))
        g.link(hub, leaves[0], Edge.Type.SEMANTIC, 0.9) // strengthen existing
        assertEquals(4, hubDegree(g, hub), "strengthening keeps degree constant")
        assertEquals(0.9, g.neighbors(hub).first { it.to == leaves[0] }.weight)
        assertNull(g.neighbors(hub).firstOrNull { it.to !in leaves }, "no spurious node added/dropped")
        assertSymmetric(g)
    }
}
