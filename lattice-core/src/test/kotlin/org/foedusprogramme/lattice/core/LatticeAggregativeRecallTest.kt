package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Query-type-aware AGGREGATIVE recall (see [LatticeGraph.Config.aggregativeRecall]).
 *
 * THE DEFECT: a breadth cue — "总结她的生活有哪些变化" / "summarize how her life changed" — wants the
 * WHOLE cluster of life-change nuggets, but seed→spread→top-[limit] surfaces only the 1–2 the meaningful-
 * spread slots can reach. The sleep pass DOES synthesize an ABSTRACTS community-parent over the cluster;
 * recall just never exploited it for breadth. This suite proves recall now routes a FIRED aggregative cue
 * through that parent's member set (recovering the cluster) WITHOUT touching narrow/adversarial cues.
 *
 * Determinism: [RegistryEmbedder] pins every content/cue vector so cosines are exact. The parent P is
 * built by the REAL [LatticeGraph.synthesizeParent] (ABSTRACTS edges, weight 0.85). Geometry (floor 0.60):
 *  - cue AGG seeds P at 0.85 (top seed); 12 distractors seed at 0.45; the 8 members seed at 0.42 (below
 *    the distractors) and are reached from P by spread at 0.85·0.85·0.6 = 0.4335 — still below the
 *    distractors, so the members fall OUTSIDE the top-6 base. So the OFF baseline recovers members only
 *    through the 2 meaningful-spread slots (2/8); the aggregative path appends the parent's members (6/8,
 *    the [aggregativeAppendCap]). The delta is the mechanism, not a bigger spread budget.
 *
 * The two adversarial controls share AGG's EXACT vector but name a member in their TEXT ("养猫"/"搬家"),
 * so only gate 3 (member-exclusion) — not geometry, not the trivial intent-scope regex — can separate
 * them from the genuine breadth cue. They must be byte-identical ON vs OFF.
 */
class LatticeAggregativeRecallTest {

    private val clock = { Instant.parse("2026-08-04T00:00:00Z") }

    /** Deterministic embedder: each registered text maps to a fixed unit vector; unknown text is
     *  floor-only (orthogonal to everything but the shared floor coordinate). */
    private class RegistryEmbedder(override val dimension: Int = 4096) : Embedder {
        override val model: String get() = "registry"
        private val reg = HashMap<String, FloatArray>()
        fun register(text: String, vararg coords: Pair<Int, Double>): String {
            val v = FloatArray(dimension)
            for ((i, x) in coords) v[i] = x.toFloat()
            var n = 0.0
            for (x in v) n += (x * x).toDouble()
            n = sqrt(n)
            require(n in 0.98..1.02) { "vector for '$text' not unit-norm: $n" }
            reg[text] = v
            return text
        }
        override fun embed(text: String): FloatArray =
            reg[text] ?: FloatArray(dimension).also { it[FLOOR] = 1f }
        companion object { const val FLOOR = 0 }
    }

    // Coordinate axes (all orthogonal): 0 = shared floor, 1 = life/change abstract axis, 2 = P's private
    // orthogonal remainder, 10.. = per-member private, 100.. = per-distractor private.
    private val members = listOf(
        "开始搬家",   // 0 move  (ADV2 names this)
        "换了工作",   // 1 job
        "开始养猫",   // 2 cat   (ADV1 names this)
        "小猫走丢",   // 3 cat gone
        "跑了马拉松", // 4 marathon
        "报名钢琴",   // 5 piano
        "学做蛋糕",   // 6 baking
        "戒掉熬夜",   // 7 sleep
    )
    private val parentText = "她的生活发生了很多变化"
    private val AGG = "总结她的生活有哪些变化"
    private val NARROW = "她的猫叫什么名字"
    private val ADV_CAT = "总结养猫的经历"      // scope word 总结 + names member 养猫
    private val ADV_MOVE = "概括搬家的情况"     // scope word 概括 + names member 搬家

    /** seedCount 8, no auto-links (0.95), strong graph with only the manual ABSTRACTS edges. */
    private fun cfg(aggregative: Boolean) = LatticeGraph.Config(
        seedCount = 8,
        autoLinkThreshold = 0.95,
        activationFloor = 0.15,
        seedFloor = 0.0,
        emitFloor = 0.60,
        hopDecay = 0.6,
        recencyGain = 0.0,
        neuromodulatorySalience = false,
        spreadAppendSlots = 2, // OFF baseline still gets the 2 meaningful-spread slots
        spreadAppendActivationFrac = 0.35,
        spreadAppendOriginRank = 2,
        spreadAppendRedundancy = 0.85,
        aggregativeRecall = aggregative,
        aggregativeAppendCap = 6,
        aggregativeMemberOverlap = 0.34,
    )

    /** Build the cluster: 8 members under one synthesized parent, among 12 unrelated distractors. */
    private fun buildGraph(config: LatticeGraph.Config): Pair<LatticeGraph, Set<Long>> {
        val emb = RegistryEmbedder()
        emb.register(AGG, 0 to 0.6, 1 to 0.8)
        emb.register(ADV_CAT, 0 to 0.6, 1 to 0.8)   // SAME vector as AGG — only the text differs
        emb.register(ADV_MOVE, 0 to 0.6, 1 to 0.8)  // SAME vector as AGG
        emb.register(parentText, 0 to 0.51, 1 to 0.68, 2 to 0.527) // cos(AGG,P)=0.85
        // Members seed at 0.42 to AGG (below the distractors, so outside the base). Member 2 (养猫) shares
        // a private axis (coord 12) with NARROW so a narrow cat query seeds it hard (cos ~0.99), while its
        // cos to AGG is only 0.36 — still below the distractors, so it too stays out of the base.
        members.forEachIndexed { i, m ->
            if (i == 2) emb.register(m, 0 to 0.6, 12 to 0.8) else emb.register(m, 0 to 0.7, 10 + i to 0.7141)
        }
        emb.register(NARROW, 0 to 0.5, 12 to 0.866) // cos(NARROW, member2)=0.99; no life/change axis
        val distractors = (0 until 12).map { "干扰项-$it" }
        distractors.forEachIndexed { j, d -> emb.register(d, 0 to 0.75, 100 + j to 0.6614) } // cos(AGG,d)=0.45

        val g = LatticeGraph(emb, config, now = clock)
        val memberIds = members.map { g.remember(it, salience = 0.5) }
        distractors.forEach { g.remember(it, salience = 0.5) }
        val parentId = g.synthesizeParent(memberIds, parentText, salience = 0.7)
        assertTrue(parentId > 0, "parent must materialize (guard must not fire)")
        return g to memberIds.toSet()
    }

    private fun recall(g: LatticeGraph, cue: String, allowAggregative: Boolean) =
        g.recall(cue, limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false, allowAggregative = allowAggregative)

    private fun recovered(hits: List<Activation>, memberIds: Set<Long>) =
        hits.count { it.engram.id in memberIds }

    @Test
    fun multiLevelHierarchyReturnsLeafFactsNotSubParents() {
        // 2-level tree: grandparent -> {sub-parent A, sub-parent B} -> 4 leaf facts. A breadth cue seeds
        // the GRANDparent hardest, so gate 2 lands on it; the fix must drill THROUGH the sub-parents to
        // the leaf facts (old behaviour returned the 2 sub-parent abstractions, 0 leaves).
        val emb = RegistryEmbedder()
        val cue = "总结整体上有哪些变化"
        emb.register(cue, 0 to 0.6, 1 to 0.8)
        emb.register("整体的变化", 0 to 0.51, 1 to 0.68, 2 to 0.527)   // GP, cos(cue,GP)=0.85 (top seed)
        val leaves = listOf("搬去西雅图", "换成资深工程师", "养了猫Byte", "跑了半马")
        leaves.forEachIndexed { i, l -> emb.register(l, 0 to 0.7, 20 + i to 0.7141) } // cos(cue,leaf)=0.42 (outside base)
        emb.register("搬迁与工作面", 0 to 0.72, 3 to 0.694)  // sub-parent A, cos(cue,pA)=0.432
        emb.register("宠物与运动面", 0 to 0.72, 4 to 0.694)  // sub-parent B
        val distractors = (0 until 8).map { "无关-$it" }
        distractors.forEachIndexed { j, d -> emb.register(d, 0 to 0.75, 100 + j to 0.6614) } // cos=0.45 (fills base, above leaves)

        val g = LatticeGraph(emb, cfg(aggregative = true), now = clock)
        val leafIds = leaves.map { g.remember(it, salience = 0.5) }
        distractors.forEach { g.remember(it, salience = 0.5) }
        val pA = g.synthesizeParent(listOf(leafIds[0], leafIds[1]), "搬迁与工作面", salience = 0.6)
        val pB = g.synthesizeParent(listOf(leafIds[2], leafIds[3]), "宠物与运动面", salience = 0.6)
        val gp = g.synthesizeParent(listOf(pA, pB), "整体的变化", salience = 0.7)
        assertTrue(pA > 0 && pB > 0 && gp > 0, "the 2-level tree must materialize")

        // The 4 leaves seed BELOW the distractors (cos 0.42 < 0.45), so they are outside the top-limit
        // base — they can ONLY appear via the aggregative append drilling GP -> sub-parents -> leaves.
        // Old behaviour appended the 2 sub-parent abstractions instead, so leaves recovered would be 0.
        val ids = recall(g, cue, allowAggregative = true).map { it.engram.id }.toSet()
        assertEquals(4, leafIds.count { it in ids }, "aggregative over a 2-level tree drills through the sub-parents and returns all 4 LEAF facts (old behaviour: 0)")
    }

    @Test
    fun aggregativeCueRecoversTheClusterWhereBaselineMissesIt() {
        // OFF: aggregative master switch off -> only the 2 meaningful-spread slots reach members.
        val (gOff, membersOff) = buildGraph(cfg(aggregative = false))
        val off = recall(gOff, AGG, allowAggregative = true) // allow=true is inert while the switch is off
        val offRecovered = recovered(off, membersOff)
        assertEquals(2, offRecovered, "baseline recovers only the 2 spread-slot members (the measured defect)")

        // ON: the aggregative path appends the parent's members (capped at aggregativeAppendCap=6).
        val (gOn, membersOn) = buildGraph(cfg(aggregative = true))
        val on = recall(gOn, AGG, allowAggregative = true)
        val onRecovered = recovered(on, membersOn)

        assertTrue(onRecovered >= 5, "aggregative recall recovers MOST of the 8-member cluster (got $onRecovered)")
        assertTrue(onRecovered > offRecovered, "aggregative recall ($onRecovered) beats the baseline ($offRecovered)")
        // The first 6 hits (the base) are byte-identical: append is union-only, never a base reshuffle.
        assertEquals(off.take(6).map { it.engram.id }, on.take(6).map { it.engram.id }, "base is preserved verbatim")
        assertTrue(on.size <= 6 + 6, "emitted size stays within limit + aggregativeAppendCap")
    }

    @Test
    fun masterSwitchOffIsByteIdenticalToBaseline() {
        // With the switch OFF, allowAggregative=true must change NOTHING (dark ship / instant rollback).
        val (g, _) = buildGraph(cfg(aggregative = false))
        val withFlag = recall(g, AGG, allowAggregative = true).map { it.engram.id to it.activation }
        val (g2, _) = buildGraph(cfg(aggregative = false))
        val withoutFlag = recall(g2, AGG, allowAggregative = false).map { it.engram.id to it.activation }
        assertEquals(withoutFlag, withFlag, "switch OFF: the allowAggregative flag is inert")
    }

    @Test
    fun narrowFactualCueIsUnchanged() {
        // A narrow cue misses the intent regex (gate 1), so ON == OFF byte-for-byte, and it still returns
        // its best semantic match (the cat member) — narrow precision is untouched.
        val (g, memberIds) = buildGraph(cfg(aggregative = true))
        val on = recall(g, NARROW, allowAggregative = true).map { it.engram.id to it.activation }
        val (g2, _) = buildGraph(cfg(aggregative = true))
        val off = recall(g2, NARROW, allowAggregative = false).map { it.engram.id to it.activation }
        assertEquals(off, on, "a narrow (non-aggregative) cue is byte-identical ON vs OFF")
        assertTrue(on.first().first in memberIds, "the narrow cat query still surfaces a member (precision kept)")
    }

    @Test
    fun adversarialScopePlusNamedMemberRejectedByMemberExclusion() {
        // BOTH adversarial cues carry AGG's EXACT vector AND a scope word (总结/概括), so gate 1 (intent)
        // AND gate 2 (parent-exists) both PASS — only gate 3 (member-exclusion) can separate them from the
        // genuine breadth cue. They name a specific member (养猫 / 搬家), so gate 3 rejects and the result
        // is byte-identical to OFF. This is the discriminator the withdrawn co-lead-cosine test could not
        // provide (real bge-m3 non-aggregative co-lead reaches 0.90–0.97).
        for (cue in listOf(ADV_CAT, ADV_MOVE)) {
            val (g, _) = buildGraph(cfg(aggregative = true))
            val on = recall(g, cue, allowAggregative = true).map { it.engram.id to it.activation }
            val (g2, _) = buildGraph(cfg(aggregative = true))
            val off = recall(g2, cue, allowAggregative = false).map { it.engram.id to it.activation }
            assertEquals(off, on, "adversarial cue '$cue' (scope word + named member) must be byte-identical ON vs OFF")
        }
    }
}
