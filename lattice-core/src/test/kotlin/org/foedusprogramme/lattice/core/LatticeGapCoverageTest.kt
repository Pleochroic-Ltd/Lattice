package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Durable OFFLINE coverage for thin, load-bearing seams that the other tests do not pin:
 * value-type identity ([Engram.equals]/[hashCode], [Engram.eventYearMonth]), the [cosine] contract,
 * [LatticeGraph.link] guards, the supersession-chain resolver ([LatticeGraph.currentEngram] via recall
 * redirection incl. the 8-hop cap and a cycle), the generic maxNodes eviction path, the [LatticeGraph.Config.bgeM3]
 * production preset, and the [LocalHashingEmbedder] tokenizer. All deterministic — no env gate, no model.
 */
class LatticeGapCoverageTest {

    private val t0 = Instant.parse("2026-08-01T00:00:00Z")
    private val fixedClock = { t0 }

    private fun engram(
        id: Long,
        content: String = "c",
        salience: Double = 0.5,
        layer: Engram.Layer = Engram.Layer.EPISODIC,
        eventDate: String? = null,
    ) = Engram(
        id = id,
        content = content,
        embedding = FloatArray(4),
        salience = salience,
        layer = layer,
        createdAt = t0,
        lastActivatedAt = t0,
        eventDate = eventDate,
    )

    // --- Engram.equals / hashCode: identity by id (hand-written for the FloatArray field) ----------

    @Test
    fun engramEqualityIsIdentityById() {
        val a = engram(7, content = "alpha", salience = 0.2, layer = Engram.Layer.EPISODIC)
        // Same id, every OTHER field different — still equal (identity is the id alone).
        val bSameId = engram(7, content = "TOTALLY OTHER", salience = 0.95, layer = Engram.Layer.SEMANTIC, eventDate = "2020-01-02")
        assertEquals(a, bSameId, "equal-id engrams are equal regardless of other fields")
        assertEquals(a.hashCode(), bSameId.hashCode(), "equal engrams must share a hashCode")

        val differentId = engram(8, content = "alpha", salience = 0.2)
        assertNotEquals(a, differentId, "different-id engrams are not equal even with identical content")

        // Contract sanity: not equal to a non-Engram, and usable as a set/map key by id.
        assertFalse(a.equals("alpha"))
        val set = hashSetOf(a)
        assertTrue(bSameId in set, "id-keyed membership resolves via equals/hashCode")
    }

    // --- Engram.eventYearMonth getter -------------------------------------------------------------

    @Test
    fun eventYearMonthSlicesTheMonthOrIsNull() {
        assertNull(engram(1, eventDate = null).eventYearMonth, "undated => null")
        assertEquals("2026-08", engram(2, eventDate = "2026-08-05").eventYearMonth, "YYYY-MM-DD -> YYYY-MM slice")
        assertEquals("2026-08", engram(3, eventDate = "2026-08").eventYearMonth, "already month-granular is unchanged")
    }

    // --- cosine(a, b) contract --------------------------------------------------------------------

    @Test
    fun cosineThrowsOnDimensionMismatch() {
        assertFailsWith<IllegalArgumentException> { cosine(FloatArray(3), FloatArray(4)) }
    }

    @Test
    fun cosineOfIdenticalUnitVectorsIsOneAndOrthogonalIsZero() {
        val u = floatArrayOf(1f, 0f, 0f)
        assertEquals(1.0, cosine(u, u), 1e-9, "identical unit vectors -> 1.0")
        val x = floatArrayOf(1f, 0f)
        val y = floatArrayOf(0f, 1f)
        assertEquals(0.0, cosine(x, y), 1e-9, "orthogonal unit vectors -> 0.0")
    }

    // --- LatticeGraph.link guards -------------------------------------------------------------------

    private fun graph(config: LatticeGraph.Config = LatticeGraph.Config()) =
        LatticeGraph(LocalHashingEmbedder(), config, now = fixedClock)

    @Test
    fun linkToUnknownNodeThrows() {
        val g = graph()
        val a = g.remember("已知节点甲")
        assertFailsWith<IllegalArgumentException> { g.link(a, 999_999L, Edge.Type.SEMANTIC, 0.5) }
        assertFailsWith<IllegalArgumentException> { g.link(999_999L, a, Edge.Type.SEMANTIC, 0.5) }
    }

    @Test
    fun selfLinkIsANoOp() {
        val g = graph(LatticeGraph.Config(autoLinkThreshold = 2.0)) // suppress incidental auto-links
        val a = g.remember("孤立节点内容")
        g.link(a, a, Edge.Type.SEMANTIC, 0.9)
        assertTrue(g.neighbors(a).none { it.to == a }, "a node is never linked to itself")
        assertTrue(g.neighbors(a).isEmpty(), "self-link added no edge at all")
    }

    // --- currentEngram (via recall redirection): multi-hop, 8-hop cap, cycle ----------------------

    @Test
    fun recallRedirectsThroughASupersessionChainToTheNewest() {
        val g = graph(LatticeGraph.Config(autoLinkThreshold = 2.0))
        val old = g.remember("部署CUDA显存报错版本零")
        val mid = g.remember("河流山川湖泊溪水潺潺")
        val new = g.remember("科技创新未来无人驾驶")
        g.markSuperseded(mid, old) // old -> mid
        g.markSuperseded(new, mid) // mid -> new

        val hits = g.recall("部署CUDA显存", reinforce = Reinforce.NONE, applyEmitGate = false)
        val contents = hits.map { it.engram.content }
        assertTrue("科技创新未来无人驾驶" in contents, "an on-topic seed on the OLD fact resolves to the newest: $contents")
        assertFalse("部署CUDA显存报错版本零" in contents, "the superseded original is redirected away")
        assertFalse("河流山川湖泊溪水潺潺" in contents, "the intermediate version is redirected away too")
    }

    @Test
    fun redirectionStopsAtTheEightHopCap() {
        val g = graph(LatticeGraph.Config(autoLinkThreshold = 2.0))
        // n0 is the on-topic seed; the rest are pairwise-unrelated distinct topics (no dedup, no auto-link).
        val topics = listOf(
            "部署CUDA显存报错起点",
            "河流山川湖泊", "天气晴朗温暖", "音乐旋律节奏", "美食佳肴烹饪",
            "运动健身跑步", "书籍阅读文学", "旅行探索风景",
            "科技创新未来", // index 8 — the cap lands here
            "艺术绘画色彩", // index 9 — the true newest, UNREACHABLE past the 8-hop cap
        )
        val ids = topics.map { g.remember(it) }
        for (i in 1 until ids.size) g.markSuperseded(ids[i], ids[i - 1]) // n0 -> n1 -> ... -> n9

        val contents = g.recall("部署CUDA显存", reinforce = Reinforce.NONE, applyEmitGate = false).map { it.engram.content }
        assertTrue("科技创新未来" in contents, "8 hops from n0 lands on index 8: $contents")
        assertFalse("艺术绘画色彩" in contents, "the true newest (index 9) is beyond the 8-hop cap")
    }

    @Test
    fun supersedeCycleTerminatesWithoutInfiniteLoop() {
        val g = graph(LatticeGraph.Config(autoLinkThreshold = 2.0))
        val x = g.remember("苹果香蕉橙子")
        val y = g.remember("电脑键盘鼠标")
        g.markSuperseded(y, x) // x.supersededBy = y
        g.markSuperseded(x, y) // y.supersededBy = x  -> a 2-cycle

        // The only assertion that matters is that this RETURNS (the resolver's hop cap breaks the cycle).
        val hits = g.recall("苹果香蕉", reinforce = Reinforce.NONE, applyEmitGate = false)
        assertTrue(hits.isNotEmpty(), "recall completes over a supersede cycle instead of hanging")
    }

    // --- decayDetailed: generic maxNodes eviction of the lowest-salience isolated node -------------

    @Test
    fun maxNodesEvictsLowestSalienceIsolatedNodeButSparesEpisodeAnchors() {
        // Freeze salience over the pass so eviction order is decided by the ENCODED salience only.
        val g = graph(
            LatticeGraph.Config(
                autoLinkThreshold = 2.0, // keep every fact isolated (degree 0) so the size-budget path applies
                episodicSalienceDecay = 1.0,
                semanticSalienceDecay = 1.0,
                maxNodes = 2,
            ),
        )
        val high = g.remember("孤立高显著度事实山脉", salience = 0.9)
        val low = g.remember("孤立低显著度事实河流", salience = 0.2)
        // An episode anchor is an INTENTIONAL isolate — even at a LOWER salience than the evicted fact,
        // the size-budget eviction must spare it (the exemption distinct from the generic path).
        val episode = g.remember("某次会话的整体摘要天气", salience = 0.16, isEpisode = true)

        g.decayDetailed() // 3 nodes, budget 2 => evict 1 (the lowest-salience non-episode isolate)

        assertNull(g.engram(low), "the lowest-salience isolated fact is evicted by the size budget")
        assertNotNull(g.engram(high), "the higher-salience isolated fact survives")
        assertNotNull(g.engram(episode), "the episode anchor is spared even though its salience is lowest")
    }

    // --- Config.bgeM3() production preset (a recalibration must break this on purpose) -------------

    @Test
    fun bgeM3PresetPinsProductionThresholds() {
        val c = LatticeGraph.Config.bgeM3()
        assertEquals(8, c.seedCount, "seedCount")
        assertEquals(0.62, c.autoLinkThreshold, 0.0, "autoLinkThreshold")
        assertEquals(0.86, c.dedupThreshold, 0.0, "dedupThreshold")
        assertEquals(0.15, c.activationFloor, 0.0, "activationFloor")
        assertEquals(0.50, c.seedFloor, 0.0, "seedFloor")
        assertEquals(0.60, c.emitFloor, 0.0, "emitFloor")
    }

    // --- LocalHashingEmbedder tokenize/embed ------------------------------------------------------

    private val embedder = LocalHashingEmbedder()

    private fun allZero(v: FloatArray) = v.all { it == 0f }

    @Test
    fun embedBlankOrUntokenizableInputIsAZeroVector() {
        assertTrue(allZero(embedder.embed("")), "empty string -> zero vector")
        assertTrue(allZero(embedder.embed("   \t\n")), "whitespace -> zero vector")
        assertTrue(allZero(embedder.embed("中")), "a single CJK char produces no bigram -> zero vector")
        assertTrue(allZero(embedder.embed("a")), "a length-1 ASCII token is dropped -> zero vector")
    }

    @Test
    fun embedAsciiWordsAndCjkBigramsProduceNormalizedNonzeroFeatures() {
        val ascii = embedder.embed("hello world")
        assertFalse(allZero(ascii), "ASCII words produce features")
        assertEquals(1.0, cosine(ascii, ascii), 1e-6, "output is L2-normalized (self-cosine == 1)")

        val cjk = embedder.embed("部署失败")
        assertFalse(allZero(cjk), "CJK bigrams produce features")
        assertEquals(1.0, cosine(cjk, cjk), 1e-6, "CJK output is L2-normalized too")

        // Shared bigram (部署) -> positive overlap; fully disjoint bigrams -> zero.
        assertTrue(cosine(embedder.embed("部署失败"), embedder.embed("部署成功")) > 0.0, "shared 部署 bigram overlaps")
        assertEquals(0.0, cosine(embedder.embed("部署失败"), embedder.embed("天气晴朗")), 1e-9, "disjoint bigrams are orthogonal")
    }

    // --- Episodic binding: exact same-day requirement (clean, size-unconfounded) ------------------

    @Test
    fun differingDayCoRetrievedCliqueBindsNothing() {
        // A size-3, high-weight, fully-connected CO_RETRIEVAL clique whose every member is on a DIFFERENT
        // day: the exact-day gate strips every edge, so NO component forms — distinct from the existing
        // different-day test where the survivor is confounded by falling below minSize.
        val g = LatticeGraph(
            BgeBandFakeEmbedder(),
            LatticeGraph.Config(episodicBinding = true, temporalLayer = true, autoLinkThreshold = 0.99),
            now = fixedClock,
        )
        val a = g.remember("clique member alpha", eventDate = "2025-03-10")
        val b = g.remember("clique member beta", eventDate = "2025-03-11")
        val c = g.remember("clique member gamma", eventDate = "2025-03-12")
        g.link(a, b, Edge.Type.CO_RETRIEVAL, 0.9)
        g.link(b, c, Edge.Type.CO_RETRIEVAL, 0.9)
        g.link(a, c, Edge.Type.CO_RETRIEVAL, 0.9)
        assertTrue(g.formEpisodes().isEmpty(), "an all-cross-day clique binds nothing despite size/weight/density")
    }
}
