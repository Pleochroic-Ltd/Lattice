package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EPISODIC BINDING graph-level tests. Everything is behind
 * [LatticeGraph.Config.episodicBinding] (default OFF), so the OFF cases prove byte-identical behavior and
 * the ON cases prove the demand-gated (CO_RETRIEVAL) + sub-DAY event_date-equality episode formation.
 */
class LatticeEpisodicBindingTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }

    // BgeBandFakeEmbedder maps any two DISTINCT pipe-free strings to the unrelated floor (cosine 0.60) —
    // below dedup — so plain distinct facts never fold, unlike a hashing embedder whose token overlap
    // inflates cosine. autoLinkThreshold 0.99 keeps the graph clean of encode-time SEMANTIC noise, so
    // only the explicit CO_RETRIEVAL / EPISODE_OF edges under test are present.
    private fun graph(
        episodicBinding: Boolean = true,
        temporalLayer: Boolean = true,
        embedder: Embedder = BgeBandFakeEmbedder(),
        judge: MergeJudge? = null,
        cfg: (LatticeGraph.Config) -> LatticeGraph.Config = { it },
    ) = LatticeGraph(
        embedder,
        cfg(LatticeGraph.Config(episodicBinding = episodicBinding, temporalLayer = temporalLayer, autoLinkThreshold = 0.99)),
        now = clock,
        judge = judge,
    )

    private fun co(g: LatticeGraph, a: Long, b: Long, w: Double) = g.link(a, b, Edge.Type.CO_RETRIEVAL, w)

    private fun clique(g: LatticeGraph, ids: List<Long>, w: Double) {
        for (i in ids.indices) for (j in i + 1 until ids.size) co(g, ids[i], ids[j], w)
    }

    // --- clustering matrix -------------------------------------------------

    @Test
    fun sameDayCoRetrievedComponentIsOneCandidate() {
        val g = graph()
        val d = "2025-03-10"
        val ids = (1..4).map { g.remember("fact distinct alpha $it", eventDate = d) }
        clique(g, ids, 0.5)
        val cands = g.formEpisodes()
        assertEquals(1, cands.size, "one same-day co-retrieved clique => one candidate")
        assertEquals(ids.sorted(), cands[0].memberIds)
        assertEquals(d, cands[0].eventDate)
    }

    @Test
    fun differentDayMembersSplitOut() {
        val g = graph()
        val a = (1..4).map { g.remember("groupA distinct $it", eventDate = "2025-03-10") }
        val b = (1..2).map { g.remember("groupB distinct $it", eventDate = "2025-04-10") }
        clique(g, a, 0.5)
        co(g, b[0], b[1], 0.5)
        co(g, a[0], b[0], 0.5) // cross-day edge — excluded by the exact-day gate
        val cands = g.formEpisodes()
        assertEquals(1, cands.size, "only the same-day component survives")
        assertEquals(a.sorted(), cands[0].memberIds, "different-day pair split out (size 2 < minSize)")
    }

    @Test
    fun monthOnlyDatesExcluded() {
        val g = graph()
        val ids = (1..3).map { g.remember("month only $it", eventDate = "2025-03") } // length 7
        clique(g, ids, 0.5)
        assertTrue(g.formEpisodes().isEmpty(), "sub-DAY equality needs full YYYY-MM-DD; month-only is ineligible")
    }

    @Test
    fun coWeightBelowFloorDropped() {
        val g = graph()
        val ids = (1..3).map { g.remember("weak edge $it", eventDate = "2025-03-10") }
        clique(g, ids, 0.20) // < episodeCoActivationFloor (0.30)
        assertTrue(g.formEpisodes().isEmpty(), "edges below the co-activation floor never bind")
    }

    @Test
    fun sizeGatesDropTooSmallAndTooLarge() {
        val gSmall = graph()
        val pair = (1..2).map { gSmall.remember("pair $it", eventDate = "2025-03-10") }
        co(gSmall, pair[0], pair[1], 0.5)
        assertTrue(gSmall.formEpisodes().isEmpty(), "size 2 < minSize (3) dropped")

        val gLarge = graph(cfg = { it.copy(episodeMaxSize = 3) })
        val ids = (1..4).map { gLarge.remember("big $it", eventDate = "2025-03-10") }
        clique(gLarge, ids, 0.5)
        assertTrue(gLarge.formEpisodes().isEmpty(), "size 4 > maxSize (3) dropped whole")
    }

    @Test
    fun lowDensityComponentDropped() {
        val g = graph()
        val ids = (1..4).map { g.remember("chain $it", eventDate = "2025-03-10") }
        // A 4-node chain: 3 present edges over 6 pairs at 0.5 => avg 0.25 < episodeMinAvgCoWeight (0.35).
        co(g, ids[0], ids[1], 0.5); co(g, ids[1], ids[2], 0.5); co(g, ids[2], ids[3], 0.5)
        assertTrue(g.formEpisodes().isEmpty(), "sparse component fails the density gate")
    }

    @Test
    fun formEpisodesIsDeterministic() {
        val g = graph()
        val ids = (1..4).map { g.remember("det $it", eventDate = "2025-03-10") }
        clique(g, ids, 0.5)
        assertEquals(g.formEpisodes(), g.formEpisodes(), "two runs over the same graph are identical")
    }

    @Test
    fun offMasterFormsNothing() {
        val g = graph(episodicBinding = false)
        val ids = (1..4).map { g.remember("off $it", eventDate = "2025-03-10") }
        clique(g, ids, 0.5)
        assertTrue(g.formEpisodes().isEmpty(), "episodicBinding OFF => formEpisodes is a no-op")
    }

    // --- edge-model membership / protection --------------------------------

    @Test
    fun episodeEdgeSurvivesDecayAndDegreeCap() {
        val g = graph(cfg = { it.copy(maxDegree = 2) })
        val hub = g.remember("episode hub anchor")
        val m = g.remember("member node")
        g.link(hub, m, Edge.Type.EPISODE_OF, 0.85)
        // Saturate m with evictable CO edges beyond the budget.
        val c = (1..3).map { g.remember("co neighbor $it") }
        co(g, m, c[0], 0.4); co(g, m, c[1], 0.5); co(g, m, c[2], 0.6)
        g.decay()
        assertTrue(
            g.neighbors(m).any { it.type == Edge.Type.EPISODE_OF && it.to == hub },
            "the protected EPISODE_OF edge is never counted/evicted by the synaptic budget",
        )
    }

    // --- materializeEpisode ------------------------------------------------

    @Test
    fun materializeCreatesSemanticHubWithEpisodeEdges() {
        val g = graph()
        val d = "2025-03-10"
        // Shared-topic members (cosine 0.64) clear the coherence gate — a genuine coherent episode.
        val members = (1..3).map { g.remember("ep|s$it|m$it|u$it", eventDate = d) }
        val hubId = g.materializeEpisode(members, "那天完成了三件相关的事", d)
        assertTrue(hubId > 0, "hub materialized")
        val hub = g.engram(hubId)!!
        assertEquals(Engram.Layer.SEMANTIC, hub.layer)
        assertTrue(hub.isEpisodeHub)
        assertEquals(d, hub.eventDate, "hub inherits the shared day")
        assertEquals(members.sorted(), g.episodeMembers(hubId).sorted(), "one EPISODE_OF edge per member")
        members.forEach { m ->
            assertTrue(g.neighbors(m).any { it.type == Edge.Type.EPISODE_OF && it.to == hubId }, "member links back to hub")
        }
    }

    @Test
    fun materializeDoesNotApplyAntiParaphraseDominanceGuard() {
        // Regression vs synthesizeParent: a hub geometrically >= dedupThreshold to a SINGLE member still
        // materializes (no dominance refusal), because a faithful episode narration is legitimately close.
        val emb = BgeBandFakeEmbedder()
        val g = graph(embedder = emb, cfg = { it.copy(dedupThreshold = 0.86, autoLinkThreshold = 0.99) })
        val d = "2025-03-10"
        val m1 = g.remember("topic|sub|micro|m1", eventDate = d) // narration will be paraphrase-close to this one
        val m2 = g.remember("topic|other|micro2|m2", eventDate = d)
        val m3 = g.remember("topic|other3|micro3|m3", eventDate = d)
        val hubId = g.materializeEpisode(listOf(m1, m2, m3), "topic|sub|micro|narr", d) // cosine 0.90 to m1
        assertTrue(hubId > 0, "no anti-paraphrase refusal — the episode still materializes")
    }

    @Test
    fun materializeRejectsIncoherentClusterButAcceptsCoherentOne() {
        // The demand-gate (CO edges) + day-gate can present a same-day trio of MUTUALLY
        // UNRELATED facts (stats homework / cat litter / exoplanet naming) that a weak narrator has
        // confabulated a connecting story over. The length/verbatim screen never catches it; the
        // average-pairwise-member-cosine coherence gate must.
        val d = "2025-03-10"

        // Incoherent: three distinct-topic facts — each pair sits at bge's unrelated floor (cosine 0.60),
        // averaging 0.60 < episodeMinMemberCohesion (0.62). No hub is materialized.
        val gBad = graph()
        val unrelated = listOf("统计作业", "清理猫砂", "命名系外行星").map { gBad.remember(it, eventDate = d) }
        assertEquals(
            LatticeGraph.REFUSED,
            gBad.materializeEpisode(unrelated, "那天她把这三件事串成了一段故事", d),
            "an incoherent same-day trio (avg member cosine 0.60) is rejected — no hub",
        )
        assertTrue(gBad.engrams().none { it.isEpisodeHub }, "no episode hub exists after a rejected incoherent cluster")

        // Coherent: three facts sharing a topic — each pair at cosine 0.64, averaging 0.64 >= 0.62.
        // A genuine coherent episode still materializes.
        val gGood = graph()
        val related = (1..3).map { gGood.remember("day|s$it|m$it|u$it", eventDate = d) }
        val hubId = gGood.materializeEpisode(related, "那天完成了一段连贯的经历", d)
        assertTrue(hubId > 0, "a coherent same-day trio (avg member cosine 0.64) still materializes")
        assertTrue(gGood.engram(hubId)!!.isEpisodeHub)
        assertEquals(related.sorted(), gGood.episodeMembers(hubId).sorted(), "all coherent members bound to the hub")
    }

    @Test
    fun materializeRefusesDegenerateNarration() {
        val g = graph()
        val d = "2025-03-10"
        val members = (1..3).map { g.remember("mem $it", eventDate = d) }
        assertEquals(LatticeGraph.REFUSED, g.materializeEpisode(members, "   ", d), "blank narration refused")
        assertEquals(LatticeGraph.REFUSED, g.materializeEpisode(members, "ab", d), "too-short narration refused")
        assertEquals(
            LatticeGraph.REFUSED,
            g.materializeEpisode(members, g.engram(members[0])!!.content, d),
            "verbatim copy of a member refused",
        )
    }

    @Test
    fun materializeRefusesWhenMembersAlreadyBoundAndReRunIsConvergent() {
        val g = graph()
        val d = "2025-03-10"
        // Shared-topic members (cosine 0.64) so the first materialization clears the coherence gate.
        val members = (1..3).map { g.remember("bd|s$it|m$it|u$it", eventDate = d) }
        val hub1 = g.materializeEpisode(members, "第一次绑定", d)
        assertTrue(hub1 > 0)
        // Carry-once: all members are now bound, so a re-run has < minSize unbound present => REFUSED.
        assertEquals(LatticeGraph.REFUSED, g.materializeEpisode(members, "第二次尝试", d), "no duplicate hub over bound members")
        // And formEpisodes excludes them going forward.
        clique(g, members, 0.5)
        assertTrue(g.formEpisodes().isEmpty(), "already-bound members are excluded from future clustering")
    }

    @Test
    fun offMasterMaterializeRefuses() {
        val g = graph(episodicBinding = false)
        val members = (1..3).map { g.remember("x $it", eventDate = "2025-03-10") }
        assertEquals(LatticeGraph.REFUSED, g.materializeEpisode(members, "narr", "2025-03-10"))
    }

    // --- Promotion carve-out (EPISODE_OF not structural) -----------------------

    @Test
    fun episodeEdgesDoNotDriveConsolidationPromotion() {
        val g = graph()
        val fact = g.remember("consolidation candidate fact")
        val others = (1..5).map { g.remember("episode neighbor $it") }
        // 5 EPISODE_OF edges, zero STRUCTURAL edges. If EPISODE_OF counted (5*wiringGain=0.40) plus
        // replay (2*replayGain=0.30) readiness would be 0.70 >= promoteThreshold and it WOULD promote.
        others.forEach { g.link(fact, it, Edge.Type.EPISODE_OF, 0.85) }
        val e = g.engram(fact)!!
        e.activationCount = 2
        e.salience = 0.9
        assertEquals(Engram.Layer.EPISODIC, e.layer, "precondition")
        g.consolidate()
        assertEquals(
            Engram.Layer.EPISODIC,
            g.engram(fact)!!.layer,
            "EPISODE_OF edges are excluded from structuralDegree — no promotion through the wiring door",
        )
    }

    // --- Merge survival --------------------------------------------------------

    @Test
    fun episodeHubsAreNeverFoldedByMergeDuplicates() {
        val emb = BgeBandFakeEmbedder()
        val g = graph(embedder = emb, cfg = { it.copy(dedupThreshold = 0.86, autoLinkThreshold = 0.99) })
        val d = "2025-03-10"
        val a = (1..3).map { g.remember("A|$it|m|u", eventDate = d) }
        val b = (1..3).map { g.remember("B|$it|m|u", eventDate = d) }
        // Two hubs whose narrations are paraphrase-cosine (0.90 >= dedupThreshold 0.86).
        val h1 = g.materializeEpisode(a, "hubtopic|s|micro|n1", d)
        val h2 = g.materializeEpisode(b, "hubtopic|s|micro|n2", d)
        assertTrue(h1 > 0 && h2 > 0)
        g.mergeDuplicates()
        assertNotNull(g.engram(h1)); assertNotNull(g.engram(h2))
        assertTrue(g.engram(h1)!!.isEpisodeHub && g.engram(h2)!!.isEpisodeHub, "both hubs survive the merge pass")
    }

    @Test
    fun absorbMovesEpisodeMembershipToKeeper() {
        // Keep the default (0.92) dedupThreshold so the near-restatement pair (cosine 0.83) does NOT fold
        // at encode; then run the SLEEP merge at an explicit lower threshold (0.80) so it folds only now —
        // exactly the "weren't mutual-nearest at insert" case mergeDuplicates exists for.
        val g = graph()
        val d = "2025-03-10"
        // All three share topic "T" (pairwise cosine 0.64) so the episode clears the coherence gate;
        // m2/m3 differ from m1 at the SUB level, so only m1 (share T|S, cosine 0.83) is a fold candidate
        // for the keeper below — m2/m3 (share T only, cosine 0.64 < 0.80) never fold.
        val m1 = g.remember("T|S|micro1|u1", salience = 0.3, eventDate = d)
        val m2 = g.remember("T|b|y|u2", salience = 0.5, eventDate = d)
        val m3 = g.remember("T|c|y|u3", salience = 0.5, eventDate = d)
        val hub = g.materializeEpisode(listOf(m1, m2, m3), "episode narration text", d)
        assertTrue(hub > 0)
        // A higher-salience near-duplicate of m1 (cosine 0.83) — at sleep it absorbs m1, and m1's
        // EPISODE_OF edge to the hub must follow to the keeper.
        val keeper = g.remember("T|S|micro1keeper|uk", salience = 0.9, eventDate = d)
        g.mergeDuplicates(threshold = 0.80)
        assertNull(g.engram(m1), "m1 was folded into the keeper")
        assertTrue(keeper in g.episodeMembers(hub), "membership followed the survivor")
        assertTrue(
            g.neighbors(keeper).any { it.type == Edge.Type.EPISODE_OF && it.to == hub },
            "keeper carries the moved EPISODE_OF edge",
        )
    }

    @Test
    fun judgedSupersedePassSkipsHubs() {
        val emb = BgeBandFakeEmbedder()
        // A judge that would merge anything; hubs must still be short-circuited by guardedCandidate.
        val alwaysSame = object : MergeJudge {
            override val modelId = "test"
            override fun judge(noteA: String, noteB: String, crossLingualHint: Boolean) = MergeJudge.Verdict.SAME
        }
        val g = graph(embedder = emb, judge = alwaysSame, cfg = { it.copy(dedupThreshold = 0.86, judgeFloor = 0.68, autoLinkThreshold = 0.99) })
        val d = "2025-03-10"
        val a = (1..3).map { g.remember("A|$it|m|u", eventDate = d) }
        val b = (1..3).map { g.remember("B|$it|m|u", eventDate = d) }
        // Two hubs at cosine 0.83, inside the judge band [0.68, 0.86).
        val h1 = g.materializeEpisode(a, "hub|band|micro1|n1", d)
        val h2 = g.materializeEpisode(b, "hub|band|micro2|n2", d)
        g.mergeDuplicates() // runs judgedSupersedePass because judge != null
        assertNull(g.engram(h1)?.supersededBy, "hub h1 never superseded")
        assertNull(g.engram(h2)?.supersededBy, "hub h2 never superseded")
    }

    // --- event_date-modulated Hebbian potentiation -------------------------

    @Test
    fun sameDayCoRetrievalPotentiatesFaster() {
        fun edgeWeightAfterRecall(bind: Boolean, temporal: Boolean, dateB: String, boost: Double = 1.5): Double {
            val emb = BgeBandFakeEmbedder()
            val g = graph(
                episodicBinding = bind,
                temporalLayer = temporal,
                embedder = emb,
                cfg = { it.copy(autoLinkThreshold = 0.99, dedupThreshold = 0.995, episodeHebbianBoost = boost) },
            )
            val a = g.remember("topic|sub|microA|uA", eventDate = "2025-05-05")
            val b = g.remember("topic|sub|microB|uB", eventDate = dateB)
            // A cue on the shared topic|sub seeds BOTH (cosine 0.83); FULL recall co-activates them and,
            // with no prior edge, creates one CO_RETRIEVAL edge whose weight == hebbianRateFor(a,b).
            g.recall("topic|sub|microCue|uCue", reinforce = Reinforce.FULL, applyEmitGate = false)
            return g.neighbors(a).firstOrNull { it.to == b }?.weight ?: -1.0
        }
        // Same day + binding + temporal => boosted rate 0.2*1.5 = 0.3.
        assertEquals(0.3, edgeWeightAfterRecall(bind = true, temporal = true, dateB = "2025-05-05"), 1e-9)
        // Different day => plain rate 0.2.
        assertEquals(0.2, edgeWeightAfterRecall(bind = true, temporal = true, dateB = "2025-05-06"), 1e-9)
        // Binding OFF => plain rate 0.2 (byte-identical reinforcement).
        assertEquals(0.2, edgeWeightAfterRecall(bind = false, temporal = true, dateB = "2025-05-05"), 1e-9)
        // temporalLayer OFF => plain rate 0.2 (dates not even persisted).
        assertEquals(0.2, edgeWeightAfterRecall(bind = true, temporal = false, dateB = "2025-05-05"), 1e-9)
        // Boost clamps the rate to <= 1.0.
        assertEquals(1.0, edgeWeightAfterRecall(bind = true, temporal = true, dateB = "2025-05-05", boost = 10.0), 1e-9)
    }

    // --- Episodic recall via EPISODE_OF spread ---------------------------------

    @Test
    fun seedingAMemberReinstatesItsHubButNeverRanksItInTheBase() {
        // A hub is never a seed or a base hit; it only surfaces through episode
        // reinstatement from a SEED member (it still relays activation over EPISODE_OF).
        fun g(reinstate: Boolean) = graph(
            embedder = BgeBandFakeEmbedder(),
            cfg = { it.copy(dedupThreshold = 0.995, autoLinkThreshold = 0.99, episodeReinstatement = reinstate) },
        )
        for (reinstate in listOf(false, true)) {
            val gr = g(reinstate)
            val d = "2025-03-10"
            val m1 = gr.remember("ep|s|micro1|u1", eventDate = d)
            val m2 = gr.remember("ep|s2|micro2|u2", eventDate = d)
            val m3 = gr.remember("ep|s3|micro3|u3", eventDate = d)
            val hub = gr.materializeEpisode(listOf(m1, m2, m3), "ep|hub|hmicro|hu", d)
            assertTrue(hub > 0)
            val reached = gr.recall("ep|s|micro1|cue", reinforce = Reinforce.NONE, applyEmitGate = false).map { it.engram.id }
            assertEquals(reinstate, hub in reached, "reinstatement=$reinstate: $reached")
        }
    }
}
