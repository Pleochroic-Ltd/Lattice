package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.core.SceneNarrator.AdvancedThread
import org.foedusprogramme.lattice.core.SceneNarrator.ClosedThread
import org.foedusprogramme.lattice.core.SceneNarrator.LedgerChanges
import org.foedusprogramme.lattice.core.SceneNarrator.OpenedThread
import org.foedusprogramme.lattice.core.SceneWorkState.ABANDONED
import org.foedusprogramme.lattice.core.SceneWorkState.MATERIALIZED
import org.foedusprogramme.lattice.core.SceneWorkState.NARRATING
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Thread closure at recall: a SEED hit on any SETUP memory of a CLOSED thread appends that thread's RESOLVE memories in
 * two reserved slots, and the recall block annotates it with the thread's name (`（「周末的惊喜」后来已了结）`), also on an
 * ABSTRACTS parent and on a scene hub. Then the ledger end to end through scene narration, including a narration that
 * fails: its thread changes are applied once, when the scene is given up.
 *
 * Geometry ([BgeBandFakeEmbedder]): the cue `dusk|surprise|cue` sits at 0.83 to `dusk|surprise|*` (seeds), 0.64 to
 * other `dusk|*` memories and 0.60 to everything else; seedFloor is 0.70.
 */
class ThreadClosureRecallTest {

    private val clock = { Instant.parse("2026-09-25T00:00:00Z") }
    private val zh = RecallMarkers.ZH

    private fun config(spreadSlots: Int = 2, sharedCap: Int = 2) = LatticeGraph.Config(
        episodicBinding = true,
        episodeReinstatement = true,
        temporalLayer = true,
        autoLinkThreshold = 0.99,
        dedupThreshold = 0.99,
        seedFloor = 0.70,
        emitFloor = 0.0,
        seedCount = 3,
        activationFloor = 0.15,
        spreadAppendSlots = spreadSlots,
        aggregativeAppendCap = sharedCap,
        episodeReinstateCap = 6,
        recencyGain = 0.0,
    )

    private val embedder = BgeBandFakeEmbedder()

    private fun graph(config: LatticeGraph.Config) = LatticeGraph(embedder, config, now = clock)

    private fun ledger(g: LatticeGraph) = SceneThreadLedger(GraphAccess.locked(g), embedder)

    private fun scene(id: Long) = SceneRecord(id, "scene $id", CONV, CHAT, id * 100, id * 100 + 9)

    private fun format(hits: List<Activation>, g: LatticeGraph, markers: RecallMarkers = zh) =
        RecallFormatter.format(hits, markers, g.temporalLayerEnabled, GraphLookup(g))

    /** The set-up in scene 1, a re-mention in a 9-member scene 2 (with hub), the reveal in scene 3. */
    private class Fixture(
        val g: LatticeGraph,
        val setup: Long,
        val remention: Long,
        val walk: List<Long>,
        val spread: List<Long>,
        val resolves: List<Long>,
        val hub: Long,
        val thread: Long,
    )

    private fun fixture(close: Boolean = true, spreadSlots: Int = 2, sharedCap: Int = 2): Fixture {
        val g = graph(config(spreadSlots, sharedCap))
        val ledger = ledger(g)
        fun mem(c: String) = g.remember(c, layer = Engram.Layer.SEMANTIC)
        val setup = mem("suzhou|morning|promise") // the original set-up: off the cue's topic
        val remention = mem("dusk|surprise|again") // the re-mention the cue hits (SEED)
        val walk = (1..8).map { mem("dusk|walk|m$it") } // the rest of the re-mention's scene: below seedFloor
        val spread = listOf(mem("misc1|a|x"), mem("misc2|b|y")) // CAUSAL neighbours that fill the spread slots
        spread.forEach { g.link(remention, it, Edge.Type.CAUSAL, 0.9) }
        val resolves = listOf(mem("dusk|gift|lantern"), mem("dusk|gift|cake"), mem("zz|other|x"))
        g.putScene(SceneRecord(2, "公园散步", CONV, CHAT, 200, 209, "2026-07-20", "2026-07-20"))
        g.addSceneMembers(2, listOf(remention) + walk)
        val hub = g.materializeScene(g.scene(2)!!, "narr|walk|story", listOf(remention) + walk)

        ledger.apply(scene(1), null, LedgerChanges(opened = listOf(OpenedThread("周末的惊喜", listOf(setup)))), false)
        val thread = g.threads(CHAT).single().id
        ledger.apply(g.scene(2)!!, hub, LedgerChanges(advanced = listOf(AdvancedThread(thread, listOf(remention)))), false)
        if (close) ledger.apply(scene(3), null, LedgerChanges(closed = listOf(ClosedThread(thread, resolves))), false)
        return Fixture(g, setup, remention, walk, spread, resolves, hub, thread)
    }

    private fun ids(hits: List<Activation>) = hits.map { it.engram.id }

    private fun recall(g: LatticeGraph, cue: String = CUE, limit: Int = 1) =
        g.recall(cue, limit = limit, reinforce = Reinforce.NONE, applyEmitGate = false)

    @Test
    fun `a SEED re-mention in a big scene with full spread slots still appends the reveal`() {
        val f = fixture()

        val hits = recall(f.g)

        assertEquals(f.remention, hits.first().engram.id, "the re-mention is the base hit")
        assertEquals(f.spread.toSet(), ids(hits).drop(1).take(2).toSet(), "the spread slots are full")
        val tail = ids(hits).drop(3)
        assertEquals(f.resolves.take(2).toSet(), tail.toSet(), "the two most cue-relevant reveals are appended: $tail")
        assertFalse(f.resolves[2] in ids(hits), "at most two reveals, best cosine first")
        assertFalse(f.hub in ids(hits), "the shared ceiling is spent: no scene reinstatement, not expanded by closure")
        assertTrue(hits.drop(3).all { it.via == Activation.Reach.SPREAD }, "appended reveals are never seeds")

        val lines = format(hits, f.g).lines()
        assertEquals("- dusk|surprise|again$SETTLED", lines[0])
        assertEquals(
            f.resolves.take(2).map { f.g.engram(it)!!.content }.toSet(),
            lines.subList(1, 3).map { it.removePrefix("- ") }.toSet(),
            "the reveals follow their set-up directly: $lines",
        )
        assertTrue(lines.drop(3).none { SETTLED in it }, "nothing else is annotated")
    }

    @Test
    fun `closure slots neither squeeze nor expand scene reinstatement`() {
        for (sharedCap in listOf(2, 3, 6)) {
            val open = fixture(close = false, sharedCap = sharedCap)
            val closed = fixture(close = true, sharedCap = sharedCap)

            val before = ids(recall(open.g))
            val after = ids(recall(closed.g))

            assertEquals(before, after.filterNot { it in closed.resolves }, "sharedCap=$sharedCap: only the reveals differ")
            assertEquals(2, after.count { it in closed.resolves }, "sharedCap=$sharedCap")
            // The reveals sit right after the spread, before reinstatement.
            assertEquals(closed.resolves.take(2).toSet(), after.subList(3, 5).toSet(), "sharedCap=$sharedCap: $after")
        }
        val r = fixture(sharedCap = 3)
        val roomy = ids(recall(r.g))
        assertEquals(r.hub, roomy.last(), "one shared slot left: the hub still gets it: $roomy")
    }

    @Test
    fun `a SEED on the original set-up also appends the reveal`() {
        val f = fixture()
        val hits = recall(f.g, "suzhou|morning|cue")
        assertEquals(f.setup, hits.first().engram.id)
        assertTrue(ids(hits).count { it in f.resolves } in 1..2, "$hits")
    }

    @Test
    fun `a set-up of a thread that is not closed appends nothing and is not annotated`() {
        val f = fixture(close = false)

        val hits = recall(f.g)

        assertTrue(f.resolves.none { it in ids(hits) })
        assertFalse(format(hits, f.g).contains(zh.settledMark))
    }

    @Test
    fun `a set-up reached by spreading, not seeded, does not append the reveal`() {
        val g = graph(config(spreadSlots = 0, sharedCap = 6))
        val ledger = ledger(g)
        val seed = g.remember("dusk|surprise|again", layer = Engram.Layer.SEMANTIC)
        val setup = g.remember("suzhou|morning|promise", layer = Engram.Layer.SEMANTIC)
        val reveal = g.remember("zz|cake|x", layer = Engram.Layer.SEMANTIC)
        g.link(seed, setup, Edge.Type.CAUSAL, 0.9)
        ledger.apply(scene(1), null, LedgerChanges(opened = listOf(OpenedThread("惊喜", listOf(setup)))), false)
        ledger.apply(scene(2), null, LedgerChanges(closed = listOf(ClosedThread(g.threads(CHAT).single().id, listOf(reveal)))), false)

        val hits = recall(g, limit = 4)

        assertTrue(hits.any { it.engram.id == setup && it.via == Activation.Reach.SPREAD }, "$hits")
        assertFalse(reveal in ids(hits))
    }

    @Test
    fun `a reveal already emitted is not appended twice`() {
        val g = graph(config(spreadSlots = 0, sharedCap = 6))
        val ledger = ledger(g)
        val setup = g.remember("dusk|surprise|promise", layer = Engram.Layer.SEMANTIC)
        val inBase = g.remember("dusk|surprise|reveal", layer = Engram.Layer.SEMANTIC)
        val other = g.remember("zz|cake|x", layer = Engram.Layer.SEMANTIC)
        ledger.apply(scene(1), null, LedgerChanges(opened = listOf(OpenedThread("惊喜", listOf(setup)))), false)
        ledger.apply(scene(2), null, LedgerChanges(closed = listOf(ClosedThread(g.threads(CHAT).single().id, listOf(inBase, other)))), false)

        val hits = recall(g, limit = 2)

        assertEquals(1, ids(hits).count { it == inBase })
        assertEquals(1, ids(hits).count { it == other }, "$hits")
    }

    @Test
    fun `closure append is read-only - Reinforce NONE changes no edge`() {
        val f = fixture()
        fun edges() = f.g.engrams().flatMap { e -> f.g.neighbors(e.id).map { Triple(it.from, it.to, it.type to it.weight) } }.toSet()
        val snapshot = edges()
        recall(f.g)
        assertEquals(snapshot, edges())
    }

    @Test
    fun `an ABSTRACTS parent with a settled child is annotated too`() {
        val f = fixture()
        val parent = f.g.remember("summary|dusk|pending", layer = Engram.Layer.SEMANTIC)
        f.g.link(parent, f.remention, Edge.Type.ABSTRACTS, 0.8)
        val unrelatedParent = f.g.remember("summary|food|x", layer = Engram.Layer.SEMANTIC)
        f.g.link(unrelatedParent, f.walk[0], Edge.Type.ABSTRACTS, 0.8)

        val text = format(listOf(parent, unrelatedParent, f.walk[0]).map { Activation(f.g.engram(it)!!, 0.5, Activation.Reach.SEED) }, f.g)

        assertEquals(listOf("- summary|dusk|pending$SETTLED", "- summary|food|x", "- dusk|walk|m1", zh.legendThread), text.lines())
    }

    // --- named annotations ---------------------------------------------------------------------------------------------

    private fun seedOf(g: LatticeGraph, id: Long) = Activation(g.engram(id)!!, 0.5, Activation.Reach.SEED)

    @Test
    fun `a compound memory names every closed thread it set up and the one still open`() {
        // A memory of two promises owed was once marked wholly settled while one of them was still open.
        val g = graph(config())
        val ledger = ledger(g)
        val owes = g.remember("owes|two|things", layer = Engram.Layer.SEMANTIC)
        val r1 = g.remember("gift|cake|x", layer = Engram.Layer.SEMANTIC)
        val r2 = g.remember("third|question|x", layer = Engram.Layer.SEMANTIC)
        val longStory = "讲他小时候为什么会在外婆家住了整整三年的故事"
        ledger.apply(
            scene(1), null,
            LedgerChanges(
                opened = listOf(
                    OpenedThread("周末的惊喜", listOf(owes)),
                    OpenedThread("借走的那把伞", listOf(owes)),
                    OpenedThread(longStory, listOf(owes)),
                ),
            ),
            false,
        )
        val (dusk, question, _) = g.threads(CHAT).map { it.id }
        ledger.apply(scene(2), null, LedgerChanges(closed = listOf(ClosedThread(dusk, listOf(r1)), ClosedThread(question, listOf(r2)))), false)

        val story = longStory.take(RecallFormatter.THREAD_NAME_CHARS) + "…"
        assertEquals(
            "- owes|two|things（「周末的惊喜」、「借走的那把伞」后来已了结）（「$story」尚未了结）\n${zh.legendThread}",
            format(listOf(seedOf(g, owes)), g),
        )

        // Its ABSTRACTS parent reads the same way.
        val parent = g.remember("summary|owes|x", layer = Engram.Layer.SEMANTIC)
        g.link(parent, owes, Edge.Type.ABSTRACTS, 0.8)
        assertEquals(
            "- summary|owes|x（「周末的惊喜」、「借走的那把伞」后来已了结）（「$story」尚未了结）\n${zh.legendThread}",
            format(listOf(seedOf(g, parent)), g),
        )
    }

    @Test
    fun `the annotation uses the thread's short name, not its long description`() {
        val g = graph(config())
        val ledger = ledger(g)
        val promise = g.remember("dusk|surprise|promise", layer = Engram.Layer.SEMANTIC)
        val ski = g.remember("winter|ski|promise", layer = Engram.Layer.SEMANTIC)
        val r1 = g.remember("gift|cake|x", layer = Engram.Layer.SEMANTIC)
        ledger.apply(
            scene(1), null,
            LedgerChanges(
                opened = listOf(
                    OpenedThread("Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物", listOf(promise), name = "周末的惊喜礼物"),
                    OpenedThread("Ethan说冬天一定要带我去一次真正的雪场滑雪", listOf(promise, ski), name = "冬天的滑雪之约"),
                ),
            ),
            false,
        )
        val gift = g.threads(CHAT).first().id
        ledger.apply(scene(2), null, LedgerChanges(closed = listOf(ClosedThread(gift, listOf(r1)))), false)

        assertEquals(
            "- dusk|surprise|promise（「周末的惊喜礼物」后来已了结）（「冬天的滑雪之约」尚未了结）\n${zh.legendThread}",
            format(listOf(seedOf(g, promise)), g),
        )
    }

    @Test
    fun `the hub of a scene holding a closed thread's set-up is annotated with the thread's name`() {
        val f = fixture()
        // A thread opened and closed inside scene 2 itself: its narration already tells the outcome.
        val ledger = ledger(f.g)
        val scene2 = f.g.scene(2)!!
        ledger.apply(scene2, f.hub, LedgerChanges(opened = listOf(OpenedThread("当场的约定", listOf(f.walk[0])))), false)
        val local = f.g.threads(CHAT).single { it.description == "当场的约定" }.id
        ledger.apply(scene2, f.hub, LedgerChanges(closed = listOf(ClosedThread(local, listOf(f.walk[1])))), false)

        val line = format(listOf(Activation(f.g.engram(f.hub)!!, 0.5, Activation.Reach.SPREAD)), f.g)
        assertEquals("- 〔场景：公园散步 · 2026-07-20〕narr|walk|story$SETTLED\n${zh.legendSceneAndThread}", line)

        val open = fixture(close = false)
        val pending = format(listOf(Activation(open.g.engram(open.hub)!!, 0.5, Activation.Reach.SPREAD)), open.g)
        assertEquals("- 〔场景：公园散步 · 2026-07-20〕narr|walk|story\n${zh.legendScene}", pending, "no closed thread: no annotation")
    }

    @Test
    fun `the hub line and its annotation render in the turn's language`() {
        val f = fixture()
        val hit = listOf(Activation(f.g.engram(f.hub)!!, 0.5, Activation.Reach.SPREAD))

        assertEquals("- 〔场景：公园散步 · 2026-07-20〕narr|walk|story$SETTLED\n${zh.legendSceneAndThread}", format(hit, f.g))
        assertEquals(
            "- [Scene: 公园散步 · 2026-07-20] narr|walk|story (later resolved: \"周末的惊喜\")\n${RecallMarkers.EN.legendSceneAndThread}",
            format(hit, f.g, RecallMarkers.EN),
        )
    }

    // --- an explicit search served by the soft fallback --------------------------------------------------------------

    @Test
    fun `hits of the soft fallback still get the closure and the scene hub appended, read-only`() {
        for (temporal in listOf(false, true)) {
            val g = graph(config(spreadSlots = 2, sharedCap = 6).copy(abstractRecall = true, softFallbackFloor = 0.62, temporalRetrieval = temporal))
            val ledger = ledger(g)
            val setup = g.remember("dusk|surprise|promise", layer = Engram.Layer.SEMANTIC)
            val sibling = g.remember("misc|a|x", layer = Engram.Layer.SEMANTIC)
            val reveal = g.remember("zz|cake|x", layer = Engram.Layer.SEMANTIC)
            g.putScene(SceneRecord(1, "苏州", CONV, CHAT, 100, 109, "2026-07-20", "2026-07-20"))
            g.addSceneMembers(1, listOf(setup, sibling))
            val hub = g.materializeScene(g.scene(1)!!, "narr|suzhou|story", listOf(setup, sibling))
            ledger.apply(g.scene(1)!!, hub, LedgerChanges(opened = listOf(OpenedThread("周末的惊喜", listOf(setup)))), false)
            ledger.apply(scene(3), null, LedgerChanges(closed = listOf(ClosedThread(g.threads(CHAT).single().id, listOf(reveal)))), false)
            fun edges() = g.engrams().flatMap { e -> g.neighbors(e.id).map { Triple(it.from, it.to, it.type to it.weight) } }.toSet()
            val before = edges()
            val cue = "dusk|other|cue" // 0.64 to the set-up: below seedFloor (0.70), above the soft floor (0.62)

            assertTrue(recall(g, cue, limit = 6).isEmpty(), "base recall has no seed")
            val hits = g.appendSceneRecall(cue, g.softFallback(cue, 6), 6)

            assertEquals(listOf(setup, reveal, hub), ids(hits), "temporal=$temporal: base, then closure, then the scene hub")
            assertFalse(sibling in ids(hits), "siblings stay gated by the cue cosine")
            val text = format(hits, g)
            assertTrue(text.lines().first().endsWith(SETTLED), text)
            assertTrue(text.contains("〔场景：苏州"), text)
            assertEquals(before, edges(), "the appended tail is never learned")
        }
    }

    // --- end to end through scene narration ---------------------------------------------------------------------------

    /** Three scenes (set-up, re-mention, reveal) narrated through [SceneHubNarration]; [reveal] answers scene 3. */
    private fun e2e(
        mode: SceneNarrator.Mode = SceneNarrator.Mode.ANCHORS,
        backfill: Boolean = true,
        reveal: (system: String, close: String) -> String = { _, close -> """{"narration":"蛋糕 的经历。","threads":$close}""" },
    ): Pair<NarrationRig, List<SceneRecord>> {
        // floor -1: every earlier member is a backfill candidate, so each open or close transition asks once.
        val r = NarrationRig(embedder, config(spreadSlots = 0, sharedCap = 6), mode, backfillFloor = if (backfill) -1.0 else null)
        r.model.reply = { _, system, user ->
            val title = Regex("Title: (.+)").find(user)?.groupValues?.get(1)?.trim() // the backfill call has none
            val threadId = Regex("id=(\\d+) 周末的惊喜").find(user)?.groupValues?.get(1)
            when {
                system == SceneThreadBackfill.SYSTEM_PROMPT -> """{"belongs":[]}"""
                title == "蛋糕" -> reveal(system, if (threadId == null) "{}" else """{"closed":[{"id":$threadId,"resolve":[1]}]}""")
                system == SceneNarrator.SELF_CHECK_PROMPT -> """{"faithful":true}"""
                title == "苏州的早晨" ->
                    """{"narration":"$title 的经历。","threads":{"opened":[{"name":"周末的惊喜","description":"Ethan答应周末揭晓的惊喜礼物","setup":[1]}]}}"""
                title == "再提惊喜" && threadId != null ->
                    """{"narration":"$title 的经历。","threads":{"advanced":[{"id":$threadId,"refs":[1]}]}}"""
                else -> """{"narration":"$title 的经历。"}"""
            }
        }
        val scenes = listOf(
            r.scene("苏州的早晨", listOf("suzhou|morning|promise", "suzhou|morning|breakfast"), count = 2),
            r.scene("再提惊喜", listOf("dusk|surprise|again", "dusk|walk|m1"), count = 2),
            r.scene("蛋糕", listOf("dusk|gift|cake", "food|dinner|x"), count = 2),
        )
        return r to scenes
    }

    private fun NarrationRig.thread() = access.withGraph { it.threads(SceneRig.CHAT).single() }

    private fun NarrationRig.resolveLinks() = access.withGraph { g ->
        g.threadLinks(g.threads(SceneRig.CHAT).single().id).filter { it.role == ThreadRole.RESOLVE }.map { g.engram(it.engramId)!!.content }
    }

    private fun NarrationRig.recallText(): Pair<List<Activation>, String> = access.withGraph { g ->
        val h = g.recall(CUE, limit = 1, reinforce = Reinforce.NONE, applyEmitGate = false)
        h to RecallFormatter.format(h, zh, g.temporalLayerEnabled, GraphLookup(g))
    }

    @Test
    fun `the narrator opens, advances and closes the dusk surprise, then recall from the re-mention returns the reveal`() {
        val (r, scenes) = e2e(backfill = false)

        scenes.forEach(r::ready)

        assertTrue(scenes.all { r.state(it) == MATERIALIZED })
        val thread = r.thread()
        assertEquals(ThreadState.CLOSED, thread.state)
        assertEquals(scenes[0].sceneId, thread.openedSceneId)
        assertEquals(scenes[2].sceneId, thread.closedSceneId)
        assertEquals("周末的惊喜", thread.name, "the narrator's short name is stored beside the description")

        val (hits, text) = r.recallText()
        assertEquals("dusk|surprise|again", hits.first().engram.content)
        assertTrue(hits.any { it.engram.content == "dusk|gift|cake" }, "the reveal comes back with the re-mention: $hits")
        assertTrue(text.lines().first().endsWith(SETTLED), text)
        assertEquals("- dusk|gift|cake", text.lines()[1], text)
    }

    @Test
    fun `a scene vetoed three times is abandoned without a hub but its thread close is applied`() {
        val (r, scenes) = e2e(mode = SceneNarrator.Mode.SELF_CHECK) { system, close ->
            if (system == SceneNarrator.SELF_CHECK_PROMPT) """{"faithful":false,"issue":"wrong town"}"""
            else """{"narration":"蛋糕 的经历。","threads":$close}"""
        }
        r.ready(scenes[0])
        assertEquals(MATERIALIZED, r.state(scenes[1]))
        assertEquals(NARRATING, r.state(scenes[2]))
        val backfillBefore = r.model.backfillCalls
        r.ready(scenes[2])
        assertEquals(ThreadState.OPEN, r.thread().state, "nothing applied while the scene is still retried")
        r.ready(scenes[2])

        assertEquals(ABANDONED, r.state(scenes[2]))
        assertNull(r.hubOf(scenes[2]), "no hub for an abandoned scene")
        val thread = r.thread()
        assertEquals(ThreadState.CLOSED, thread.state)
        assertEquals(scenes[2].sceneId, thread.closedSceneId)
        assertEquals(listOf("dusk|gift|cake"), r.resolveLinks())
        assertEquals(backfillBefore + 1, r.model.backfillCalls, "the close's backfill asks once, not once per attempt")

        val (hits, text) = r.recallText()
        assertEquals("dusk|surprise|again", hits.first().engram.content)
        assertTrue(hits.any { it.engram.content == "dusk|gift|cake" }, "the reveal comes back with the re-mention: $hits")
        assertTrue(text.lines().first().endsWith(SETTLED), text)

        r.ready(scenes[2]) // re-offering the abandoned scene changes nothing
        assertEquals(backfillBefore + 1, r.model.backfillCalls)
    }

    @Test
    fun `when the final attempt is unparseable the latest parseable attempt's changes apply`() {
        var attempt = 0
        val (r, scenes) = e2e { _, close ->
            attempt++
            if (attempt == 1) """{"narration":"","threads":$close}""" else "not json at all"
        }
        r.ready(scenes[0])
        val backfillBefore = r.model.backfillCalls
        r.ready(scenes[2])
        r.ready(scenes[2])

        assertEquals(3, attempt)
        assertEquals(ABANDONED, r.state(scenes[2]))
        assertEquals(ThreadState.CLOSED, r.thread().state)
        assertEquals(listOf("dusk|gift|cake"), r.resolveLinks())
        assertEquals(backfillBefore + 1, r.model.backfillCalls)
    }

    @Test
    fun `an unparseable narration three times applies nothing`() {
        val (r, scenes) = e2e { _, _ -> "the model rambled without any json" }
        r.ready(scenes[0])
        val backfillBefore = r.model.backfillCalls
        r.ready(scenes[2])
        r.ready(scenes[2])

        assertEquals(ABANDONED, r.state(scenes[2]))
        assertNull(r.hubOf(scenes[2]))
        assertEquals(ThreadState.OPEN, r.thread().state)
        assertTrue(r.resolveLinks().isEmpty())
        assertEquals(backfillBefore, r.model.backfillCalls)
    }

    @Test
    fun `a failure then a success applies the changes once, from the success`() {
        var attempt = 0
        val (r, scenes) = e2e { _, close ->
            attempt++
            """{"narration":"${if (attempt == 1) "x" else "蛋糕 的经历。"}","threads":$close}"""
        }
        r.ready(scenes[0])
        assertEquals(ThreadState.OPEN, r.thread().state)
        val backfillBefore = r.model.backfillCalls
        r.ready(scenes[2])

        assertEquals(MATERIALIZED, r.state(scenes[2]))
        assertEquals(ThreadState.CLOSED, r.thread().state)
        assertEquals(backfillBefore + 1, r.model.backfillCalls)
    }

    private companion object {
        const val CHAT = "telegram:42"
        const val CONV = "telegram:42:s1"
        const val CUE = "dusk|surprise|cue"
        const val SETTLED = "（「周末的惊喜」后来已了结）"
    }
}
