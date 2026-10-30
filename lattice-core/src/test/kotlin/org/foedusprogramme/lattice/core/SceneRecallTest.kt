package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scene reinstatement at recall: a SEED member brings its scene hub (and on-topic siblings) after the base, never
 * into it. (Scene-hub formatting belongs to the recall formatter's tests.)
 *
 * Fixture geometry ([BgeBandFakeEmbedder]): the cue `trip|tunnel|cue` sits at 0.83 to every `trip|tunnel|*`
 * member (on-topic) and at 0.64 to every `trip|food|*` member (same trip, other topic). The recall
 * [LatticeGraph.Config.seedFloor] is 0.70, so tunnel members are seeds and food members never are.
 */
class SceneRecallTest {

    private val clock = { Instant.parse("2026-09-25T00:00:00Z") }

    companion object {
        const val CONV = "telegram:42:s1"
        const val CHAT = "telegram:42"
        const val CUE = "trip|tunnel|cue"
        const val SEED_FLOOR = 0.70
        const val CAP = 6
    }

    private fun config(emitFloor: Double = 0.0) = LatticeGraph.Config(
        episodicBinding = true,
        episodeReinstatement = true,
        temporalLayer = true,
        autoLinkThreshold = 0.99, // no incidental auto-links: only the edges a test writes
        dedupThreshold = 0.99,
        seedFloor = SEED_FLOOR,
        emitFloor = emitFloor,
        seedCount = 3,
        activationFloor = 0.15,
        spreadAppendSlots = 0,
        aggregativeAppendCap = CAP,
        episodeReinstateCap = CAP,
        recencyGain = 0.0,
    )

    private class Built(val g: LatticeGraph, val tunnel: List<Long>, val food: List<Long>, val hub: Long?)

    /**
     * One scene of [tunnel] on-topic + [food] off-topic members, with a hub when [withHub]. Identical insertion
     * order with and without the hub, so member ids (and embeddings) match between the two graphs.
     */
    private fun build(tunnel: Int, food: Int, withHub: Boolean = true, emitFloor: Double = 0.0): Built {
        val g = LatticeGraph(BgeBandFakeEmbedder(), config(emitFloor), now = clock)
        val t = (1..tunnel).map { g.remember("trip|tunnel|t$it", layer = Engram.Layer.SEMANTIC) }
        val f = (1..food).map { g.remember("trip|food|f$it", layer = Engram.Layer.SEMANTIC) }
        val hub = if (withHub) scene(g, 1, "海底隧道", t + f, "narr|undersea|story") else null
        return Built(g, t, f, hub)
    }

    private fun scene(
        g: LatticeGraph,
        sceneId: Long,
        title: String,
        members: List<Long>,
        narration: String,
        dateFrom: String? = "2026-09-20",
        dateTo: String? = "2026-09-21",
        inProgress: Boolean = false,
    ): Long {
        g.putScene(SceneRecord(sceneId, title, CONV, CHAT, sceneId * 100, sceneId * 100 + 50, dateFrom, dateTo, inProgress))
        g.addSceneMembers(sceneId, members)
        val hub = g.materializeScene(g.scene(sceneId)!!, narration, members)
        assertTrue(hub > 0, "hub written")
        return hub
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun edgeSnapshot(g: LatticeGraph): Map<Pair<Long, Long>, Pair<Edge.Type, Double>> =
        g.engrams().flatMap { e -> g.neighbors(e.id).map { (it.from to it.to) to (it.type to it.weight) } }.toMap()

    // --- reinstatement ---------------------------------------------------------------------------------

    @Test
    fun `a SEED member reinstates its scene hub after the base - base order unchanged`() {
        val plain = build(tunnel = 3, food = 5, withHub = false)
        val hubbed = build(tunnel = 3, food = 5)

        val before = plain.g.recall(CUE, limit = 2, reinforce = Reinforce.NONE, applyEmitGate = false)
        val after = hubbed.g.recall(CUE, limit = 2, reinforce = Reinforce.NONE, applyEmitGate = false)

        assertEquals(before.take(2).map { it.engram.id }, after.take(2).map { it.engram.id }, "base untouched")
        val tail = after.drop(2).map { it.engram.id }
        assertTrue(hubbed.hub!! in tail, "the hub is appended after the base: $tail")
        assertEquals(hubbed.hub, tail.first(), "the hub leads the scene append")
        assertTrue(hubbed.food.none { it in after.map { h -> h.engram.id } }, "off-topic siblings stay out")
    }

    @Test
    fun `a 20-member scene appends only siblings that clear the cosine gate, within the cap`() {
        val b = build(tunnel = 4, food = 16)
        val cueVec = BgeBandFakeEmbedder().let { e ->
            // Same allocation order as the graph's embedder: replay the writes, then embed the cue.
            (1..4).forEach { e.embed("trip|tunnel|t$it") }
            (1..16).forEach { e.embed("trip|food|f$it") }
            e.embed("narr|undersea|story")
            e.embed(CUE)
        }

        val hits = b.g.recall(CUE, limit = 1, reinforce = Reinforce.NONE, applyEmitGate = false)

        val appended = hits.drop(1)
        assertTrue(appended.size <= CAP, "shared cap: ${appended.size}")
        assertEquals(1, appended.count { it.engram.id == b.hub }, "the hub once")
        val siblings = appended.filter { !it.engram.isEpisodeHub }
        assertEquals(3, siblings.size, "the other three on-topic members, nothing else: ${siblings.map { it.engram.content }}")
        for (s in siblings) {
            assertTrue(cosine(s.engram.embedding, cueVec) >= SEED_FLOOR, "sibling ${s.engram.content} passes the gate")
        }
        assertTrue(b.food.none { f -> hits.any { it.engram.id == f } }, "no off-topic sibling of the 20")
    }

    @Test
    fun `a member reached by spreading, not seeded, does not reinstate its scene`() {
        val g = LatticeGraph(BgeBandFakeEmbedder(), config(), now = clock)
        val seed = g.remember("trip|tunnel|t1", layer = Engram.Layer.SEMANTIC)
        val food = (1..3).map { g.remember("trip|food|f$it", layer = Engram.Layer.SEMANTIC) }
        g.link(seed, food[0], Edge.Type.CAUSAL, 0.9)
        val hub = scene(g, 2, "拉面", food, "narr|ramen|story")

        val hits = g.recall(CUE, limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false)

        val reached = hits.firstOrNull { it.engram.id == food[0] }
        assertTrue(reached != null && reached.via == Activation.Reach.SPREAD, "the food member is a spread hit: $hits")
        assertFalse(hits.any { it.engram.id == hub }, "a spread hit never reinstates its scene")
        assertTrue(hits.none { it.engram.id == food[1] || it.engram.id == food[2] })
    }

    @Test
    fun `several seeds in one scene append its hub once`() {
        val b = build(tunnel = 3, food = 0)

        val hits = b.g.recall(CUE, limit = 3, reinforce = Reinforce.NONE, applyEmitGate = false)

        assertEquals(3, hits.take(3).count { it.via == Activation.Reach.SEED })
        assertEquals(1, hits.count { it.engram.id == b.hub })
    }

    @Test
    fun `reinstatement is read-only - NONE changes no edge, SOFT never wires the appended tail`() {
        val b = build(tunnel = 4, food = 4)
        val snapshot = edgeSnapshot(b.g)

        b.g.recall(CUE, limit = 1, reinforce = Reinforce.NONE, applyEmitGate = false)
        assertEquals(snapshot, edgeSnapshot(b.g), "Reinforce.NONE leaves every edge as it was")

        val hits = b.g.recall(CUE, limit = 1, reinforce = Reinforce.SOFT, applyEmitGate = false)
        val baseId = hits.first().engram.id
        val appendedIds = hits.drop(1).map { it.engram.id }.toSet()
        assertTrue(appendedIds.isNotEmpty())
        val wired = b.g.neighbors(baseId).filter { it.to in appendedIds && it.type == Edge.Type.CO_RETRIEVAL }
        assertTrue(wired.isEmpty(), "no Hebbian edge from the base into the scene append: $wired")
    }

    // --- over-breadth: the hub must not lift siblings into the base -------------------------------------

    @Test
    fun `a hub does not relay activation that lifts off-topic siblings into the base`() {
        val plain = build(tunnel = 2, food = 18, withHub = false)
        val hubbed = build(tunnel = 2, food = 18)

        val before = plain.g.recall(CUE, limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false)
        val after = hubbed.g.recall(CUE, limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false)

        // Without a hub only the two seeds come back; the hub may add itself (and gated siblings — none here),
        // never an off-topic sibling in the base.
        assertEquals(before.map { it.engram.id }, after.filterNot { it.engram.isEpisodeHub }.map { it.engram.id }, "$after")
        assertTrue(hubbed.food.none { f -> after.any { it.engram.id == f } }, "no off-topic sibling surfaces")

        // And the graph does not learn seed <-> off-topic sibling co-retrieval from it.
        hubbed.g.recall(CUE, limit = 6, reinforce = Reinforce.SOFT, applyEmitGate = false)
        val coRetrieval = hubbed.tunnel.flatMap { t -> hubbed.g.neighbors(t) }
            .filter { it.type == Edge.Type.CO_RETRIEVAL && it.to in hubbed.food }
        assertTrue(coRetrieval.isEmpty(), "no learned edge into off-topic siblings: $coRetrieval")
    }

    @Test
    fun `a hub does not open the emission gate for a cue that is otherwise too weak`() {
        // emitFloor above the on-topic cosine (0.83): with no meaningful edge the passive turn stays silent.
        val plain = build(tunnel = 2, food = 6, withHub = false, emitFloor = 0.90)
        val hubbed = build(tunnel = 2, food = 6, emitFloor = 0.90)

        assertTrue(plain.g.recall(CUE, limit = 6, reinforce = Reinforce.NONE).isEmpty())
        assertTrue(hubbed.g.recall(CUE, limit = 6, reinforce = Reinforce.NONE).isEmpty(), "the hub is not a meaningful-edge bypass")
    }

    // --- the scene cache ----------------------------------------------------------------------------------

    @Test
    fun `the scene cache follows a hub update - the same hub is re-narrated in place`() {
        val g = LatticeGraph(BgeBandFakeEmbedder(), config(), now = clock)
        val m1 = g.remember("trip|tunnel|t1", layer = Engram.Layer.SEMANTIC)
        val hub = scene(g, 3, "隧道", listOf(m1), "narr|tunnel|story", inProgress = true)
        assertEquals(g.scene(3), g.sceneOfHub(hub))

        // The scene ended: the pipeline rewrites the meta (hub kept) and narration updates the same hub.
        g.putScene(g.scene(3)!!.copy(inProgress = false, dateTo = "2026-09-23"))
        assertEquals(hub, g.materializeScene(g.scene(3)!!, "narr|tunnel|story2", listOf(m1)))

        val scene = g.sceneOfHub(hub)!!
        assertFalse(scene.inProgress, "the formatter reads the ended flag from the graph")
        assertEquals("2026-09-20" to "2026-09-23", scene.dateFrom to scene.dateTo)
        assertEquals("narr|tunnel|story2", g.engram(hub)!!.content)
    }
}
