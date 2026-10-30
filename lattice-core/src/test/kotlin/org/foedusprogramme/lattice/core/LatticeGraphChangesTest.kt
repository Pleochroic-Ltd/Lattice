package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The graph's store-facing rules: one id sequence, explicit edge direction, loaded verdicts, minted entities, taint. */
class LatticeGraphChangesTest {
    private val t0: Instant = Instant.parse("2026-10-01T00:00:00Z")
    private var clock: Instant = t0
    private val now: () -> Instant = { clock }
    private val emb = LocalHashingEmbedder()

    private fun engram(id: Long, text: String) =
        Engram(id, text, emb.embed(text), 0.8, Engram.Layer.SEMANTIC, t0, t0)

    // --- ids --------------------------------------------------------------------------------------------------

    @Test
    fun theAllocatorIsSeededPastEveryLoadedIdOfEveryKind() {
        val store = TestLatticeStore(
            initial = LatticeStore.Loaded(
                engrams = listOf(engram(5, "alpha")),
                entities = listOf(EntityRecord(9, "Ann", "ann")),
                scenes = listOf(SceneRecord(11, "s", "c", "chat", 1, 2)),
                threads = listOf(ThreadRecord(12, "chat", "a plan")),
                periods = listOf(PeriodRecord(15, "chat", "spring")),
                periodMembers = listOf(PeriodMemberRecord(11, 15)),
                eras = listOf(EraRecord(14, "chat", "youth")),
            ),
        )
        val g = LatticeGraph(emb, now = now, store = store)
        val ids = listOf(
            g.remember("an unrelated new memory"),
            g.allocateSceneId(),
            g.putThread(ThreadRecord(0, "chat", "another plan")),
            g.allocatePeriodId(),
            g.allocateEraId(),
        )
        assertEquals(listOf(16L, 17L, 18L, 19L, 20L), ids, "one sequence, continuing past the largest loaded id")
    }

    @Test
    fun aGraphOnAnotherSlotMintsIdsInItsOwnRangeAndIgnoresOtherSlots() {
        val foreign = (2L shl IdAllocator.COUNTER_BITS) or 900L
        val own = (3L shl IdAllocator.COUNTER_BITS) or 7L
        val store = TestLatticeStore(
            idSlot = 3,
            initial = LatticeStore.Loaded(engrams = listOf(engram(foreign, "from device two"), engram(own, "from this device"))),
        )
        val g = LatticeGraph(emb, now = now, store = store)
        val id = g.remember("a fresh memory here")
        assertEquals(3L, id ushr IdAllocator.COUNTER_BITS)
        assertEquals(8L, id and IdAllocator.COUNTER_MASK, "seeded past this slot's ids only")
    }

    // --- explicit edge direction -----------------------------------------------------------------------------

    @Test
    fun anAbstractsParentWithASmallerIdThanItsChildrenStillDrills() {
        // Ids from different slots (or a parent minted first) are not ordered by age: only the stored direction says
        // which end is the parent.
        val parent = engram(1, "summary of the cooking club")
        val c1 = engram(50, "the cooking club meets on fridays")
        val c2 = engram(60, "the cooking club made dumplings")
        val store = TestLatticeStore(
            initial = LatticeStore.Loaded(
                engrams = listOf(parent, c1, c2),
                edges = listOf(
                    EdgeRecord(1, 50, Edge.Type.ABSTRACTS, 0.85, t0),
                    EdgeRecord(1, 60, Edge.Type.ABSTRACTS, 0.85, t0),
                ),
                threads = listOf(ThreadRecord(70, "chat", "the dumplings", state = ThreadState.CLOSED)),
                threadLinks = listOf(ThreadLinkRecord(70, 60, ThreadRole.SETUP)),
            ),
        )
        val g = LatticeGraph(emb, now = now, store = store)
        assertTrue(g.isThreadSettled(1), "the parent reads as settled through its child")
        assertEquals(listOf(70L), g.threadStatusOf(1).closed.map { it.id })
        assertFalse(g.isThreadSettled(50), "a child never treats its parent as a child")
        assertTrue(g.threadStatusOf(50).closed.isEmpty())
        assertEquals(1L, g.edgeBetween(60, 1)?.from)
    }

    @Test
    fun synthesisWritesParentToChildAndAReloadKeepsIt() {
        val store = TestLatticeStore()
        val g = LatticeGraph(emb, now = now, store = store)
        val a = g.remember("tea in the morning with lemon")
        val b = g.remember("green tea after lunch every day")
        val c = g.remember("oolong tea on weekends at home")
        val parent = g.synthesizeParent(listOf(a, b, c), "drinks several kinds of tea")
        assertTrue(parent > 0)
        for (child in listOf(a, b, c)) {
            val stored = assertNotNull(store.edge(parent, child))
            assertEquals(Edge.Type.ABSTRACTS, stored.type)
            assertEquals(parent, stored.from)
            assertEquals(child, stored.to)
        }
        val reloaded = LatticeGraph(emb, now = now, store = store)
        assertEquals(setOf(a, b, c), reloaded.edgesOf(parent).filter { it.from == parent }.map { it.to }.toSet())
        // The perspective view still reads "to" as the neighbour.
        assertTrue(reloaded.neighbors(a).any { it.from == a && it.to == parent && it.type == Edge.Type.ABSTRACTS })
    }

    @Test
    fun anUpTypedEdgeTakesTheDirectionOfTheNewRelation() {
        val g = LatticeGraph(emb, LatticeGraph.Config(maxDegree = 8), now = now)
        val old = g.remember("the office moved to the third floor")
        val new = g.remember("the office now sits on the fourth floor downtown")
        assertNotEquals(old, new)
        // remember links the new node to the old one: new -> old.
        assertEquals(Edge.Type.SEMANTIC, g.edgeBetween(old, new)?.type)
        assertEquals(new, g.edgeBetween(old, new)?.from)
        // A later relation in the other direction up-types the edge and turns it around.
        g.markSuperseded(old, new)
        val edge = assertNotNull(g.edgeBetween(old, new))
        assertEquals(Edge.Type.SUPERSEDES, edge.type)
        assertEquals(old, edge.from)
        assertEquals(new, edge.to)
    }

    @Test
    fun aMergeMovesADroppedParentsEdgesWithTheirDirection() {
        val store = TestLatticeStore()
        val g = LatticeGraph(emb, now = now, store = store)
        val a = g.remember("walks the dog in the park")
        val b = g.remember("feeds the dog twice daily")
        val c = g.remember("bathes the cat on sundays")
        val d = g.remember("brushes the cat every night")
        val p1 = g.synthesizeParent(listOf(a, b), "looks after the pets")
        clock = clock.plusSeconds(60)
        val p2 = g.synthesizeParent(listOf(c, d), "looks after the pets")
        assertTrue(p1 > 0 && p2 > 0)
        assertEquals(1, g.mergeDuplicates())
        val keep = if (g.engram(p1) != null) p1 else p2
        assertNull(g.engram(if (keep == p1) p2 else p1))
        for (child in listOf(a, b, c, d)) {
            val edge = assertNotNull(g.edgeBetween(keep, child))
            assertEquals(Edge.Type.ABSTRACTS, edge.type)
            assertEquals(keep, edge.from, "the survivor is the parent of every child")
            assertEquals(keep, store.edge(keep, child)?.from)
        }
    }

    @Test
    fun decayPersistsEachEdgeOnceInItsStoredDirection() {
        val store = TestLatticeStore()
        val g = LatticeGraph(emb, now = now, store = store)
        val a = g.remember("a quiet cafe near the river")
        val b = g.remember("a quiet cafe near the station")
        g.link(b, a, Edge.Type.CAUSAL, 0.9)
        val before = store.writes["putEdge"] ?: 0
        g.decay()
        assertEquals(before + 1, store.writes["putEdge"], "one association, one write")
        assertEquals(b, store.edge(a, b)?.from)
    }

    // --- verdicts ---------------------------------------------------------------------------------------------

    private class CountingJudge(private val verdict: MergeJudge.Verdict) : MergeJudge {
        var calls = 0
        override val modelId = "fake"
        override fun judge(noteA: String, noteB: String, crossLingualHint: Boolean): MergeJudge.Verdict {
            calls++
            return verdict
        }
    }

    @Test
    fun verdictsLoadWithTheGraphSoAPairIsJudgedOnce() {
        val store = TestLatticeStore()
        val first = CountingJudge(MergeJudge.Verdict.DIFFERENT)
        val g = LatticeGraph(emb, now = now, store = store, judge = first)
        g.remember("alpha beta gamma delta")
        g.remember("alpha beta gamma delta epsilon")
        g.judgedSupersedePass()
        assertEquals(1, first.calls)
        assertEquals(listOf("KEEP"), store.verdicts.values.map { it.verdict })

        val second = CountingJudge(MergeJudge.Verdict.SAME)
        val reloaded = LatticeGraph(emb, now = now, store = store, judge = second)
        assertEquals(0, reloaded.judgedSupersedePass())
        assertEquals(0, second.calls, "the cached verdict answers without a call")
    }

    // --- taint ------------------------------------------------------------------------------------------------

    @Test
    fun aFoldIntoACleanMemoryTaintsIt() {
        val store = TestLatticeStore()
        val g = LatticeGraph(emb, now = now, store = store)
        val id = g.remember("the router password is on the fridge")
        assertFalse(g.engram(id)!!.tainted)
        assertEquals(id, g.remember("the router password is on the fridge", tainted = true))
        assertTrue(g.engram(id)!!.tainted)
        assertTrue(store.engrams.getValue(id).tainted)
        // A clean re-mention never clears it.
        g.remember("the router password is on the fridge")
        assertTrue(g.engram(id)!!.tainted)
    }

    @Test
    fun aMergedNodeIsTaintedIfEitherWas() {
        val store = TestLatticeStore()
        val g = LatticeGraph(emb, now = now, store = store)
        val clean = g.remember("alpha beta gamma delta", salience = 0.9)
        val dirty = g.remember("alpha beta gamma delta epsilon", salience = 0.2, tainted = true)
        assertNotEquals(clean, dirty, "below the write-time fold")
        assertEquals(1, g.mergeDuplicates(threshold = 0.85))
        assertNull(g.engram(dirty))
        assertTrue(g.engram(clean)!!.tainted, "the clean survivor absorbed tainted content")
        assertTrue(store.engrams.getValue(clean).tainted)
    }

    @Test
    fun aJudgedSupersedeTaintsTheCanonicalNote() {
        val g = LatticeGraph(emb, now = now, judge = CountingJudge(MergeJudge.Verdict.SAME))
        val rich = g.remember("alpha beta gamma delta epsilon")
        val thin = g.remember("alpha beta gamma delta", tainted = true)
        assertEquals(1, g.judgedSupersedePass())
        assertEquals(rich, g.engram(thin)!!.supersededBy)
        assertTrue(g.engram(rich)!!.tainted)
    }

    @Test
    fun aSynthesizedParentIsTaintedIfAnyChildWas() {
        val g = LatticeGraph(emb, now = now)
        val a = g.remember("tea in the morning with lemon")
        val b = g.remember("green tea after lunch every day", tainted = true)
        val c = g.remember("oolong tea on weekends at home")
        val d = g.remember("coffee in the office on mondays")
        val e = g.remember("espresso after dinner sometimes")
        val tainted = g.synthesizeParent(listOf(a, b, c), "drinks several kinds of tea")
        val clean = g.synthesizeParent(listOf(d, e), "drinks coffee now and then")
        assertTrue(g.engram(tainted)!!.tainted)
        assertFalse(g.engram(clean)!!.tainted)
    }

    @Test
    fun aVerbatimAndASceneHubCarryTaint() {
        val g = LatticeGraph(emb, now = now)
        val gist = g.remember("the user quoted the readme")
        val quote = g.addVerbatim(gist, "run make install first", Speaker.USER, tainted = true)
        assertTrue(g.engram(quote)!!.tainted)
        val sceneId = g.allocateSceneId()
        g.putScene(SceneRecord(sceneId, "install", "conv", "chat", 1, 4))
        g.addSceneMembers(sceneId, listOf(gist))
        val hub = g.materializeScene(g.scene(sceneId)!!, "they went through the install steps together", tainted = true)
        assertTrue(hub > 0)
        assertTrue(g.engram(hub)!!.tainted)
    }

    // --- entities ---------------------------------------------------------------------------------------------

    @Test
    fun mentionCountsCountEngramsAndKeepTheFirstNameAndDate() {
        val store = TestLatticeStore()
        val cfg = LatticeGraph.Config(entityExtraction = true, temporalLayer = true)
        val g = LatticeGraph(emb, cfg, now = now, store = store)
        val a = g.remember("met old chen for lunch")
        val b = g.remember("old chen bought a pixel phone", eventDate = "2025-03-01")
        val c = g.remember("the pixel phone broke", eventDate = "2025-04-02")
        g.applyEntityResolution(listOf(a, b), mapOf(a to listOf("老陈"), b to listOf("老陈", "Pixel")))
        g.applyEntityResolution(listOf(c), mapOf(c to listOf("pixel ")))

        val byKey = g.entities().associateBy { it.nameLc }
        val chen = byKey.getValue("老陈")
        val pixel = byKey.getValue("pixel")
        assertEquals(2, chen.mentionCount)
        assertEquals("2025-03-01", chen.firstEventDate, "the first known date stays (COALESCE)")
        assertEquals(2, pixel.mentionCount)
        assertEquals("Pixel", pixel.canonicalName, "the first canonical name stays")
        assertEquals("2025-03-01", pixel.firstEventDate)
        assertEquals(chen, store.entities.getValue(chen.id))
        assertEquals(pixel, store.entities.getValue(pixel.id))
        assertTrue(listOf(a, b, c).none { it == chen.id || it == pixel.id }, "entity ids share the engram sequence")

        // A reload continues from the stored counts.
        val reloaded = LatticeGraph(emb, cfg, now = now, store = store)
        val d = reloaded.remember("old chen moved to beijing")
        reloaded.applyEntityResolution(listOf(d), mapOf(d to listOf("老陈")))
        assertEquals(3, reloaded.entities().single { it.nameLc == "老陈" }.mentionCount)
        assertEquals(chen.id, reloaded.entities().single { it.nameLc == "老陈" }.id)
    }

    @Test
    fun anAliasMergeRecountsTheSurvivorFromItsDistinctMentions() {
        val store = TestLatticeStore()
        val g = LatticeGraph(emb, LatticeGraph.Config(entityExtraction = true), now = now, store = store)
        val a = g.remember("rafi called in the morning")
        val b = g.remember("rafi sent a photo of the lake")
        val c = g.remember("rafi karim started a new job")
        g.applyEntityResolution(
            listOf(a, b, c),
            mapOf(a to listOf("Rafi"), b to listOf("Rafi"), c to listOf("Rafi Karim", "Rafi")),
        )
        assertEquals(1, g.applyEntityMerges(listOf(listOf("Rafi", "Rafi Karim"))))
        val survivor = g.entities().single()
        assertEquals("Rafi", survivor.canonicalName, "the most-mentioned form survives")
        assertEquals(3, survivor.mentionCount, "three distinct engrams, the shared one counted once")
        assertEquals(survivor, store.entities.values.single())
        assertEquals(setOf(survivor.id), g.entitiesOf(c))
    }

    @Test
    fun entityRecallWorksOverAStoreThatKeepsNothing() {
        val cfg = LatticeGraph.Config(entityExtraction = true, entityRecall = true)
        val g = LatticeGraph(emb, cfg, now = now)
        val a = g.remember("lunch at the noodle shop")
        g.applyEntityResolution(listOf(a), mapOf(a to listOf("Lao Chen")))
        val hits = g.recall("what do I remember about Lao Chen", reinforce = Reinforce.NONE, applyEmitGate = false)
        assertTrue(hits.any { it.engram.id == a })
    }
}
