package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * EPISODIC BINDING reload round-trip: an episode hub (isEpisodeHub) and its EPISODE_OF edges survive a reload of
 * the graph from its store, the edges keep their hub → member direction, and a plain fact reloads with
 * isEpisodeHub == false.
 */
class LatticeEpisodicBindingPersistenceTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }

    // Orthogonal-ish embedder + autoLink off => distinct facts neither fold nor auto-link; the round-trip is
    // exactly the hub + EPISODE_OF star we materialize.
    private fun graphOn(store: LatticeStore) = LatticeGraph(
        BgeBandFakeEmbedder(),
        LatticeGraph.Config(episodicBinding = true, temporalLayer = true, autoLinkThreshold = 0.99),
        now = clock,
        store = store,
    )

    @Test
    fun hubAndEpisodeEdgesSurviveReopen() {
        val store = TestLatticeStore()
        val d = "2025-03-10"
        val hubId: Long
        val members: List<Long>
        val plainFact: Long
        run {
            val g = graphOn(store)
            // Shared-topic members (cosine 0.64) clear the episode coherence gate at materialization.
            members = listOf("ep|alpha|m|u1", "ep|bravo|m|u2", "ep|charlie|m|u3").map { g.remember(it, eventDate = d) }
            plainFact = g.remember("delta-plain-fact")
            hubId = g.materializeEpisode(members, "那天完成的一段经历", d)
            assertTrue(hubId > 0)
        }

        val g2 = graphOn(TestLatticeStore(initial = store.load()))
        val hub = g2.engram(hubId)
        assertNotNull(hub, "hub reloads")
        assertTrue(hub.isEpisodeHub, "isEpisodeHub survives the round-trip")
        assertEquals(Engram.Layer.SEMANTIC, hub.layer)
        assertEquals(d, hub.eventDate, "hub event_date survives")
        assertEquals(members.sorted(), g2.episodeMembers(hubId).sorted(), "EPISODE_OF edges reload")
        members.forEach { m ->
            assertTrue(
                g2.neighbors(m).any { it.type == Edge.Type.EPISODE_OF && it.to == hubId },
                "member->hub EPISODE_OF edge reloads with the correct type",
            )
            val stored = assertNotNull(g2.edgeBetween(hubId, m))
            assertEquals(hubId to m, stored.from to stored.to, "the edge keeps its hub -> member direction")
        }
        assertFalse(g2.engram(plainFact)!!.isEpisodeHub, "a plain fact reloads with isEpisodeHub == false")
    }
}
