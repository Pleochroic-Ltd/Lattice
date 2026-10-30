package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ENTITY-ANCHOR GRAPH tests: the [EntityResolver] JSON contract (occasion abstention,
 * hallucinated-ref drop, fan-out caps) and the sleep-time resolve pass ([EntityTestPasses.resolve]) —
 * which must (a) write per-memory entities + mention links, (b) make mention_count == distinct
 * mentioning engrams, and (c) watermark each engram so a re-sleep NEVER re-bumps mention_count.
 *
 * Uses the offline [LocalHashingEmbedder] + a canned resolver model, so it runs with no servers. The graph mints
 * entity ids and keeps the counts itself, so the store is a [TestLatticeStore] read back after each pass.
 */
class LatticeEntityResolutionTest {

    private val clock = { Instant.parse("2026-08-05T00:00:00Z") }

    // ---- EntityResolver.parse contract ---------------------------------------

    @Test
    fun parseAbstainsWhenOccasionNull() {
        val r = EntityResolver.parse(
            """{"occasion": null, "key_entities": [], "per_memory": [{"ref":"m1","entities":["老陈"]}]}""",
            setOf("m1"),
        )!!
        assertNull(r.occasion, "occasion=null is abstention")
        assertEquals(listOf("老陈"), r.perMemory["m1"], "per-memory entities still extracted even when abstaining")
    }

    @Test
    fun parseTreatsBlankAndNoneOccasionAsAbstention() {
        assertNull(EntityResolver.parse("""{"occasion":"none","per_memory":[]}""", emptySet())!!.occasion)
        assertNull(EntityResolver.parse("""{"occasion":"  ","per_memory":[]}""", emptySet())!!.occasion)
    }

    @Test
    fun parseDropsHallucinatedRefsAndCapsFanout() {
        val ents = (1..20).joinToString(",") { "\"e$it\"" }
        val r = EntityResolver.parse(
            """{"occasion":"x","key_entities":[$ents],
                "per_memory":[{"ref":"m1","entities":[$ents]},{"ref":"GHOST","entities":["z"]}]}""",
            setOf("m1"),
        )!!
        assertTrue("GHOST" !in r.perMemory.keys, "a ref not in the known set is dropped")
        assertEquals(EntityResolver.MAX_KEY_ENTITIES, r.keyEntities.size, "key entities capped")
        assertEquals(EntityResolver.MAX_ENTITIES_PER_MEMORY, r.perMemory["m1"]!!.size, "per-memory entities capped")
    }

    @Test
    fun parseReturnsNullOnUnparseableResponse() {
        assertNull(EntityResolver.parse("I could not do that.", setOf("m1")), "no JSON object -> null (retry)")
    }

    @Test
    fun parseHandlesExplicitNullArraysWithoutThrowing() {
        // The LLM routinely emits explicit null for these when abstaining; `.jsonArray` on JsonNull THREW and
        // aborted the whole sleep pass in production. Must degrade to empty, never throw.
        val r = EntityResolver.parse(
            """{"occasion":null,"key_entities":null,"per_memory":null}""", setOf("m1"),
        ) ?: error("explicit-null fields must parse, not throw/return null")
        assertNull(r.occasion); assertTrue(r.keyEntities.isEmpty() && r.perMemory.isEmpty())
        // per_memory present but an element's entities is null / a bad element type -> skipped, no throw.
        val r2 = EntityResolver.parse(
            """{"occasion":"x","key_entities":["A"],"per_memory":[{"ref":"m1","entities":null},{"ref":"m1"},"junk"]}""",
            setOf("m1"),
        )!!
        assertEquals(listOf("A"), r2.keyEntities)
        assertTrue(r2.perMemory.isEmpty(), "null/absent entities and a non-object element are dropped, not thrown")
    }

    @Test
    fun parseDropsSelfReferenceSoItIsNotAUniversalAnchor() {
        val r = EntityResolver.parse(
            """{"occasion":"老陈的生日","key_entities":["用户","老陈"],
                "per_memory":[{"ref":"m1","entities":["用户","我","老陈"]}]}""",
            setOf("m1"),
        )!!
        assertTrue("用户" !in r.keyEntities && "老陈" in r.keyEntities, "self dropped from occasion anchors, real one kept")
        assertEquals(listOf("老陈"), r.perMemory["m1"], "self ('用户'/'我') dropped from per-memory entities")

        // A CONFIGURED persona alias (deployment-specific, case-insensitive) is also dropped — but only when
        // configured, so a wrong hardcoded guess never silently filters the real persona.
        val persona = EntityResolver.parse(
            """{"occasion":"x","key_entities":["Mika Jr.","Kiwi"],
                "per_memory":[{"ref":"m1","entities":["Mika Jr.","Kiwi"]}]}""",
            setOf("m1"), setOf("mika jr."),
        )!!
        assertEquals(listOf("Kiwi"), persona.keyEntities, "configured persona alias dropped; the user (Kiwi) kept")
        assertEquals(listOf("Kiwi"), persona.perMemory["m1"])
        // Without that config, the persona name is NOT filtered (no hardcoded guess).
        val noCfg = EntityResolver.parse(
            """{"occasion":"x","key_entities":["Mika Jr."],"per_memory":[]}""", setOf("m1"),
        )!!
        assertEquals(listOf("Mika Jr."), noCfg.keyEntities, "un-configured persona name is not silently filtered")
    }

    // ---- resolve pass write + watermark ----------------------------------------

    /** Canned resolver: reads the (m<id>) refs from the prompt; first ref gets 老陈+望京, the rest 老陈. */
    private class CannedResolverLlm : MemoryModel {
        var calls = 0
        override fun complete(task: MemoryTask, system: String, user: String): String {
            calls++
            val refs = Regex("\\((m\\d+)\\)").findAll(user).map { it.groupValues[1] }.toList()
            val perMem = refs.mapIndexed { i, r ->
                val ents = if (i == 0) """["老陈","望京"]""" else """["老陈"]"""
                """{"ref":"$r","entities":$ents}"""
            }.joinToString(",")
            return """{"occasion":"老陈的生日","key_entities":["老陈"],"per_memory":[$perMem]}"""
        }
    }

    @Test
    fun resolvePassWritesEntitiesAndIsWatermarkIdempotent() {
        val store = TestLatticeStore()
        val llm = CannedResolverLlm()
        val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config().copy(entityExtraction = true), now = clock, store = store)
        val resolver = EntityResolver(llm)

        // Three plain facts co-encoded in one window (same clock -> no createdAt gap -> one occasion window).
        val a = g.remember("老陈在望京请我吃云南菜", 0.6, Engram.Layer.EPISODIC)
        val b = g.remember("老陈下周要出差去上海", 0.6, Engram.Layer.EPISODIC)
        val c = g.remember("老陈推荐了一家咖啡馆", 0.6, Engram.Layer.EPISODIC)

        val tagged = EntityTestPasses.resolve(g, resolver)
        assertEquals(3, tagged, "all three memories gained an entity")
        assertEquals(1, llm.calls, "one window -> exactly one resolver call")

        run {
            val loaded = store.load()
            val laochen = loaded.entities.first { it.nameLc == "老陈" }
            assertEquals(3, laochen.mentionCount, "mention_count == the 3 distinct engrams mentioning 老陈")
            val wangjing = loaded.entities.first { it.nameLc == "望京" }
            assertEquals(1, wangjing.mentionCount, "望京 mentioned by exactly one engram")
            assertEquals(4, loaded.entityLinks.size, "3 links to 老陈 + 1 to 望京")
            assertTrue(loaded.entityLinks.contains(a to wangjing.id))
            assertEquals(setOf(a, b, c), loaded.resolvedEntityIds, "every window member is watermarked resolved")
        }

        // Re-sleep: nothing is unresolved, so NO new resolver call and NO mention_count re-bump.
        val again = EntityTestPasses.resolve(g, resolver)
        assertEquals(0, again, "re-sleep resolves nothing")
        assertEquals(1, llm.calls, "watermark prevents a second resolver call")
        run {
            val laochen = store.load().entities.first { it.nameLc == "老陈" }
            assertEquals(3, laochen.mentionCount, "mention_count did NOT double-bump on re-sleep")
            assertEquals(laochen, g.entities().first { it.nameLc == "老陈" }, "the graph's row matches the stored one")
        }
    }

    // ---- LLM-free entity recall -----------------------------------------------

    /** Three 老陈 memories on deliberately DISJOINT topics, so only the entity self-join (not cosine) can
     *  gather them under one cue. Returns (graph, ids) with entities already resolved. */
    private fun resolvedLaochenGraph(config: LatticeGraph.Config): Pair<LatticeGraph, Triple<Long, Long, Long>> {
        val g = LatticeGraph(LocalHashingEmbedder(), config, now = clock, store = TestLatticeStore())
        val a = g.remember("老陈在望京请我吃云南菜", 0.6, Engram.Layer.EPISODIC)
        val b = g.remember("量子计算课程的期末作业非常难", 0.6, Engram.Layer.EPISODIC)
        val c = g.remember("周末骑车去了郊外的水库", 0.6, Engram.Layer.EPISODIC)
        EntityTestPasses.resolve(g, EntityResolver(CannedResolverLlm()))
        return g to Triple(a, b, c)
    }

    @Test
    fun entityRecallEnumeratesAllMentionsOnExplicitSearch() {
        val (g, ids) = resolvedLaochenGraph(
            LatticeGraph.Config().copy(entityExtraction = true, entityRecall = true, seedFloor = 0.5, emitFloor = 0.5),
        )
        val (a, b, c) = ids
        // Explicit search naming 老陈 -> all three mentions surface via the LLM-free self-join, including the
        // two whose content shares nothing with the cue (a bi-encoder would never rank them here).
        val out = g.recall("我关于老陈都记得什么", 10, applyEmitGate = false).map { it.engram.id }.toSet()
        assertTrue(out.containsAll(setOf(a, b, c)), "all 老陈 mentions enumerated by entity self-join: $out")
    }

    @Test
    fun entityRecallIsDarkAndAdditiveWhenOff() {
        val (g, ids) = resolvedLaochenGraph(
            // entityRecall OFF; extraction still wrote the rows, but recall must not read them.
            LatticeGraph.Config().copy(entityExtraction = true, entityRecall = false, seedFloor = 0.5, emitFloor = 0.5),
        )
        val (_, b, c) = ids
        val out = g.recall("我关于老陈都记得什么", 10, applyEmitGate = false).map { it.engram.id }.toSet()
        // The two topic-disjoint mentions are only reachable via the entity index — OFF must not surface them.
        assertTrue(b !in out && c !in out, "entityRecall OFF does not inject entity-only mentions: $out")
    }

    // ---- occasion co-reference back-fill (entityCoEncodeGate) ------------------

    /** Gives each memory a DISJOINT entity, so ONLY an occasion back-fill (key_entities on a non-null
     *  occasion) can make the window share a common anchor. occasion=null is the abstention. */
    private class OccasionResolverLlm(private val occasion: String?) : MemoryModel {
        override fun complete(task: MemoryTask, system: String, user: String): String {
            val refs = Regex("\\((m\\d+)\\)").findAll(user).map { it.groupValues[1] }.toList()
            val perMem = refs.mapIndexed { i, r -> """{"ref":"$r","entities":["e$i"]}""" }.joinToString(",")
            val occ = if (occasion == null) "null" else "\"$occasion\""
            val key = if (occasion == null) "[]" else """["老陈"]"""
            return """{"occasion":$occ,"key_entities":$key,"per_memory":[$perMem]}"""
        }
    }

    @Test
    fun occasionBackfillSharesKeyEntityAcrossMembersAndAbstainsOnNull() {
        // The occasion co-reference back-fill (entityCoEncodeGate) links a resolver-confirmed occasion's
        // key_entities onto EVERY window member, so a member that named only its own disjoint entity still
        // inherits the shared anchor. occasion=null is the abstention (no back-fill). Asserted on the persisted
        // entity registry.
        fun laochenMentions(occasion: String?): Int {
            val store = TestLatticeStore()
            val g = LatticeGraph(
                LocalHashingEmbedder(),
                LatticeGraph.Config().copy(entityExtraction = true, entityCoEncodeGate = true),
                now = clock,
                store = store,
            )
            // Three facts, each naming a DIFFERENT (disjoint) entity — only a back-fill can give them a shared anchor.
            g.remember("在望京吃了云南菜", 0.6, Engram.Layer.EPISODIC)
            g.remember("聊到要去上海出差", 0.6, Engram.Layer.EPISODIC)
            g.remember("推荐了一家咖啡馆", 0.6, Engram.Layer.EPISODIC)
            EntityTestPasses.resolve(g, EntityResolver(OccasionResolverLlm(occasion)))
            return store.load().entities.firstOrNull { it.nameLc == "老陈" }?.mentionCount ?: 0
        }

        // A resolver-confirmed occasion back-fills 老陈 onto all three -> the anchor is linked to every member.
        assertEquals(3, laochenMentions("老陈的生日派对"), "resolved occasion back-fills the key entity onto all members")
        // occasion=null -> no back-fill -> 老陈 was never named by any member -> it does not exist in the registry.
        assertEquals(0, laochenMentions(null), "abstention -> no back-fill -> no shared anchor")
    }

    @Test
    fun entityStateSurvivesMergeAndStaysConsistentWithDiskAfterReopen() {
        val store = TestLatticeStore()
        // dedupThreshold low enough that the two near-identical facts merge on mergeDuplicates().
        val cfg = LatticeGraph.Config().copy(entityExtraction = true, entityRecall = true, dedupThreshold = 0.55)
        val g = LatticeGraph(LocalHashingEmbedder(), cfg, now = clock, store = store)
        g.remember("老陈在望京请我吃云南菜", 0.6, Engram.Layer.EPISODIC)
        g.remember("老陈在望京请我吃了云南菜", 0.6, Engram.Layer.EPISODIC) // near-dup -> will be absorbed
        g.remember("老陈下周要出差", 0.6, Engram.Layer.EPISODIC)
        EntityTestPasses.resolve(g, EntityResolver(CannedResolverLlm()))

        // Merge duplicates: the absorbed engram's entity refs must be forgotten (not leaked / not crash).
        g.mergeDuplicates()

        val live = g.recall("我关于老陈都记得什么", 10, applyEmitGate = false).map { it.engram.id }.toSet()
        // No dangling id: every recalled id is a live node (a merged-away id would null-drop, never surface).
        val liveNodes = g.engrams().map { it.id }.toSet()
        assertTrue(live.all { it in liveNodes }, "entity recall never surfaces a merged-away engram: $live vs $liveNodes")
        assertTrue(live.isNotEmpty(), "the surviving 老陈 mentions still recall after the merge")

        // The live RAM side-store must agree with a FRESH reload off the cascade-cleaned store.
        val reopened = LatticeGraph(LocalHashingEmbedder(), cfg, now = clock, store = TestLatticeStore(initial = store.load()))
        val reloaded = reopened.recall("我关于老陈都记得什么", 10, applyEmitGate = false).map { it.engram.id }.toSet()
        assertEquals(live, reloaded, "live entity recall matches a fresh reload (RAM side-store consistent with disk)")
    }

    @Test
    fun resolvePassIsDarkWhenExtractionOff() {
        val store = TestLatticeStore()
        val llm = CannedResolverLlm()
        // entityExtraction defaults OFF.
        val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(), now = clock, store = store)
        g.remember("老陈在望京请我吃云南菜", 0.6, Engram.Layer.EPISODIC)

        assertEquals(0, EntityTestPasses.resolve(g, EntityResolver(llm)), "OFF -> no candidates -> no work")
        assertEquals(0, llm.calls, "OFF -> the resolver model is never called")
        assertTrue(store.load().entities.isEmpty(), "OFF writes zero entity rows")
    }
}
