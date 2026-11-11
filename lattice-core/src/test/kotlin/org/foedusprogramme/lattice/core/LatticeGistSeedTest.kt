package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gist seeds ([LatticeGraph.Config.explicitGistSeeds]): an explicit search may cue scene hubs directly by their
 * narration, at most the cap, after the base and its appends; a hub stays absorb-only (it relays nothing, so none of
 * its members comes with it) and is never reinforced. Passive recall never gets one.
 */
class LatticeGistSeedTest {

    private val clock = { Instant.parse("2023-11-01T00:00:00Z") }

    /**
     * Text -> cosine to the cue (axis 0); each text on its own private axis, so texts barely resemble each other. Any
     * text not in [cosines] is the cue itself.
     */
    private class ConeEmbedder(private val cosines: Map<String, Double>) : Embedder {
        override val model = "test"
        override val dimension = 128
        private val axes = HashMap<String, Int>()
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val a = cosines[text] ?: return v.also { it[0] = 1f }
            v[0] = a.toFloat()
            val axis = synchronized(axes) { axes.getOrPut(text) { 1 + axes.size } }
            v[axis] = sqrt(1 - a * a).toFloat()
            return v
        }
    }

    private val cue = "What color did Nate choose for his hair?"
    private val facts = listOf("Nate plays Xenoblade", "Nate adopted a turtle", "Nate won a video game tournament")
    private val narrations = listOf(
        "Nate showed Joanna his new purple hair, bright and bold so he stands out." to 0.66,
        "Nate and Joanna talked about hair salons and his new look." to 0.62,
        "Nate told Joanna about the hair dye brands he compared." to 0.58,
        "Joanna shared her screenplay draft with Nate." to 0.30,
    )

    private fun config(gists: Int) = LatticeGraph.Config(
        episodicBinding = true, episodeReinstatement = true, temporalLayer = true,
        autoLinkThreshold = 0.99, dedupThreshold = 0.99, seedFloor = 0.50, emitFloor = 0.60, explicitSeedFloor = 0.40,
        seedCount = 4, spreadAppendSlots = 0, recencyGain = 0.0, explicitGistSeeds = gists,
    )

    private fun member(i: Int) = "scene $i member: Joanna's dog barked at the mailman"

    private class Built(val g: LatticeGraph, val facts: List<Long>, val hubs: List<Long>, val members: List<Long>)

    private fun build(gists: Int): Built {
        val cosines = HashMap<String, Double>()
        facts.forEachIndexed { i, f -> cosines[f] = 0.70 - i * 0.02 }
        narrations.forEach { (n, c) -> cosines[n] = c }
        narrations.indices.forEach { cosines[member(it)] = 0.05 }
        val g = LatticeGraph(ConeEmbedder(cosines), config(gists), now = clock)
        val factIds = facts.map { g.remember(it, salience = 0.6, layer = Engram.Layer.SEMANTIC) }
        val hubs = ArrayList<Long>()
        val members = ArrayList<Long>()
        narrations.forEachIndexed { i, (narration, _) ->
            // A member far from the cue (0.05): only the hub could bring it, by relaying.
            val member = g.remember(member(i), salience = 0.6, layer = Engram.Layer.SEMANTIC)
            members += member
            val sceneId = i + 1L
            g.putScene(SceneRecord(sceneId, "scene $i", "c:s1", "c", sceneId * 100, sceneId * 100 + 50, "2022-04-0${i + 1}", "2022-04-0${i + 1}", false))
            g.addSceneMembers(sceneId, listOf(member))
            hubs += g.materializeScene(g.scene(sceneId)!!, narration, listOf(member)).also { assertTrue(it > 0) }
        }
        return Built(g, factIds, hubs, members)
    }

    @Test
    fun anExplicitSearchCuesAtMostTheCapOfGistsAfterTheBase() {
        val b = build(gists = 2)
        val hits = b.g.recall(cue, limit = 3, reinforce = Reinforce.NONE, applyEmitGate = false)
        val ids = hits.map { it.engram.id }
        assertEquals(b.facts, ids.take(3), "the base is the facts, in order")
        assertEquals(listOf(b.hubs[0], b.hubs[1]), ids.drop(3), "the two closest gists over the floor, best first, after the base")
        assertTrue(hits.drop(3).all { it.via == Activation.Reach.SEED && it.engram.isEpisodeHub })
        assertTrue(b.hubs[2] !in ids, "capped at two")
        assertTrue(b.hubs[3] !in ids, "below the seed floor")
    }

    @Test
    fun aGistSeedRelaysNothing() {
        val b = build(gists = 3)
        val ids = b.g.recall(cue, limit = 8, reinforce = Reinforce.NONE, applyEmitGate = false).map { it.engram.id }
        assertTrue(b.hubs.take(3).all { it in ids }, "three gists cued")
        assertTrue(b.members.none { it in ids }, "no member arrives through its hub: hubs are absorb-only")
    }

    @Test
    fun aGistSeedIsNeverLearnedFrom() {
        val b = build(gists = 2)
        val before = b.hubs.map { b.g.engram(it)!!.let { e -> Triple(e.salience, e.lastActivatedAt, e.activationCount) } }
        val degree = b.hubs.map { b.g.neighbors(it).size }
        b.g.recall(cue, limit = 3, reinforce = Reinforce.FULL, applyEmitGate = false)
        assertEquals(before, b.hubs.map { b.g.engram(it)!!.let { e -> Triple(e.salience, e.lastActivatedAt, e.activationCount) } })
        assertEquals(degree, b.hubs.map { b.g.neighbors(it).size }, "no co-retrieval edge to a hub")
    }

    @Test
    fun passiveRecallAndTheLegacyConfigGetNoGist() {
        val on = build(gists = 2)
        assertTrue(on.g.recall(cue, limit = 3, reinforce = Reinforce.NONE, applyEmitGate = true).none { it.engram.isEpisodeHub }, "passive")
        val off = build(gists = 0)
        assertTrue(off.g.recall(cue, limit = 3, reinforce = Reinforce.NONE, applyEmitGate = false).none { it.engram.isEpisodeHub }, "0 = legacy")
        assertEquals(2, LatticeGraph.Config.bgeM3().explicitGistSeeds)
        assertEquals(0, LatticeGraph.Config().explicitGistSeeds)
    }
}
