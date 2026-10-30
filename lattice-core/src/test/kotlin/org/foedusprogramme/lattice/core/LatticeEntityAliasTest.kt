package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entity alias canonicalization: the [EntityAliasResolver] grouping contract, and the end-to-end
 * resolve → canonicalize → recall path — a cross-window variant set ("Rafi" / "Rafi Karim Ahmed" /
 * "rafikarim") must fold into ONE entity (the most-MENTIONED form survives, so a short "Rafi" cue still
 * matches), and a recall for that form must then reach EVERY merged mention.
 */
class LatticeEntityAliasTest {

    private val clock = { Instant.parse("2026-08-05T00:00:00Z") }

    // ---- EntityAliasResolver.parse contract ----------------------------------

    @Test
    fun parseGroupsKnownNamesDropsUnknownAndSingletons() {
        val groups = EntityAliasResolver.parse(
            """{"groups":[["Rafi","Rafi Karim Ahmed","rafikarim","GHOST"],["Solo"],["A","B"]]}""",
            setOf("Rafi", "Rafi Karim Ahmed", "rafikarim", "A", "B", "Solo"),
        )!!
        assertEquals(2, groups.size, "singleton group dropped")
        assertTrue(groups.any { it.toSet() == setOf("Rafi", "Rafi Karim Ahmed", "rafikarim") }, "GHOST (unknown) dropped")
        assertTrue(groups.any { it.toSet() == setOf("A", "B") })
    }

    @Test
    fun parseAcceptsObjectGroupFormAndIsNullSafe() {
        val groups = EntityAliasResolver.parse(
            """{"groups":[{"canonical":"Starlight Book Club","aliases":["Starlight"]}, null, "junk"]}""",
            setOf("Starlight Book Club", "Starlight"),
        )!!
        assertEquals(listOf(listOf("Starlight Book Club", "Starlight")), groups.map { it })
        assertNull(EntityAliasResolver.parse("no json here", setOf("x")), "unparseable -> null")
        // explicit-null groups must not throw
        assertTrue(EntityAliasResolver.parse("""{"groups":null}""", setOf("x"))!!.isEmpty())
    }

    // ---- resolve -> canonicalize -> recall ------------------------------------

    /** Handles BOTH prompts: the alias grouper (system mentions "groups") and the per-window entity resolver. */
    private class CannedLlm : MemoryModel {
        override fun complete(task: MemoryTask, system: String, user: String): String {
            if ("\"groups\"" in system || "same real-world entity" in system) {
                return """{"groups":[["Rafi","Rafi Karim Ahmed","rafikarim"]]}"""
            }
            // entity resolver: map each (m<id>) line to a variant by content (check the longest form first).
            val perMem = Regex("\\((m\\d+)\\)[^\\n]*").findAll(user).map { it.value }.map { line ->
                val ref = Regex("\\((m\\d+)\\)").find(line)!!.groupValues[1]
                val ent = when {
                    "Rafi Karim Ahmed" in line -> "Rafi Karim Ahmed"
                    "rafikarim" in line -> "rafikarim"
                    else -> "Rafi"
                }
                """{"ref":"$ref","entities":["$ent"]}"""
            }.joinToString(",")
            return """{"occasion":null,"key_entities":[],"per_memory":[$perMem]}"""
        }
    }

    @Test
    fun surfaceGateRejectsSemanticMismerge() {
        val store = TestLatticeStore()
        val llm = object : MemoryModel {
            override fun complete(task: MemoryTask, system: String, user: String): String {
                if ("\"groups\"" in system || "same real-world entity" in system) {
                    return """{"groups":[["Miku","Teto"]]}""" // WRONG: distinct vocaloids, no surface overlap
                }
                val perMem = Regex("\\((m\\d+)\\)[^\\n]*").findAll(user).map {
                    val ref = Regex("\\((m\\d+)\\)").find(it.value)!!.groupValues[1]
                    val ent = if ("Teto" in it.value) "Teto" else "Miku"
                    """{"ref":"$ref","entities":["$ent"]}"""
                }.joinToString(",")
                return """{"occasion":null,"key_entities":[],"per_memory":[$perMem]}"""
            }
        }
        val g = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config().copy(entityExtraction = true), now = clock, store = store)
        g.remember("Miku 唱了新歌", 0.6, Engram.Layer.EPISODIC)
        g.remember("Teto 也出场了", 0.6, Engram.Layer.EPISODIC)
        EntityTestPasses.resolve(g, EntityResolver(llm))
        assertEquals(0, EntityTestPasses.canonicalize(g, EntityAliasResolver(llm)), "surface gate rejects the semantic mismerge (Miku vs Teto)")
        assertEquals(2, store.load().entities.size, "both distinct entities survive the wrong grouping")
    }

    @Test
    fun variantsFoldIntoMostMentionedFormAndRecallReachesAll() {
        val store = TestLatticeStore()
        val g = LatticeGraph(
            LocalHashingEmbedder(),
            LatticeGraph.Config().copy(entityExtraction = true, entityRecall = true, seedFloor = 0.9, emitFloor = 0.9),
            now = clock, store = store,
        )
        val model = CannedLlm()
        // "Rafi" mentioned by 2 engrams (so it wins canonical), the variants by 1 each.
        val a = g.remember("Rafi 来公司报到了", 0.6, Engram.Layer.EPISODIC)
        val b = g.remember("Rafi 又发了消息", 0.6, Engram.Layer.EPISODIC)
        val c = g.remember("Rafi Karim Ahmed 在会上介绍了新项目", 0.6, Engram.Layer.EPISODIC)
        val d = g.remember("rafikarim 说要来上海出差", 0.6, Engram.Layer.EPISODIC)

        EntityTestPasses.resolve(g, EntityResolver(model))
        // Pre-merge: three separate entity rows.
        assertEquals(3, store.load().entities.size, "three variant rows before canonicalization")

        val merged = EntityTestPasses.canonicalize(g, EntityAliasResolver(model))
        assertEquals(2, merged, "two aliases folded away")

        run {
            val loaded = store.load()
            assertEquals(1, loaded.entities.size, "one canonical entity remains")
            val e = loaded.entities.single()
            assertEquals("Rafi", e.canonicalName, "the most-mentioned form survives (short cue still matches)")
            assertEquals(4, e.mentionCount, "mention_count recomputed as the 4 distinct mentioning engrams")
            assertEquals(e, g.entities().single(), "the graph's row matches the stored one")
            assertEquals(4, loaded.entityLinks.count { it.second == e.id }, "every mention link repointed to the canonical")
        }

        // A short "Rafi" cue now reaches EVERY merged mention (incl. the variant-only ones c, d).
        val ids = g.recall("关于 Rafi 我都记得什么", 10, applyEmitGate = false).map { it.engram.id }.toSet()
        assertTrue(ids.containsAll(setOf(a, b, c, d)), "recall for the canonical reaches all folded mentions: $ids")

        // Live RAM matches a fresh reload off the merged store.
        val reopened = LatticeGraph(
            LocalHashingEmbedder(),
            LatticeGraph.Config().copy(entityExtraction = true, entityRecall = true, seedFloor = 0.9, emitFloor = 0.9),
            now = clock, store = TestLatticeStore(initial = store.load()),
        )
        val reloaded = reopened.recall("关于 Rafi 我都记得什么", 10, applyEmitGate = false).map { it.engram.id }.toSet()
        assertEquals(ids, reloaded, "merged entity graph is consistent after reopen")
    }
}
