package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Episodic session nodes (theme-recallable whole-session summaries kept off the structural graph and ranked below
 * specific facts), drill-to-source anchors, and verbatim quotes as their own EVIDENCE-linked, faster-fading engrams.
 */
class LatticeEpisodeAndVerbatimTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }

    private fun graph(config: LatticeGraph.Config = LatticeGraph.Config(), store: LatticeStore = NoOpLatticeStore) =
        LatticeGraph(LocalHashingEmbedder(), config, now = clock, store = store)

    // --- episodic session nodes --------------------------------------------------------------------------------------

    @Test
    fun anEpisodeNodeStaysOffTheStructuralGraph() {
        val g = graph()
        // A fact and an episode with the same content would normally auto-link (cosine ~1).
        val fact = g.remember("用户在准备一场关于海马体索引的分享", salience = 0.6)
        val episode = g.remember("用户在准备一场关于海马体索引的分享", salience = 0.6, isEpisode = true)
        assertTrue(g.neighbors(episode).isEmpty(), "an episode node neither links out nor is linked to")
        assertTrue(g.neighbors(fact).none { it.to == episode })
        assertTrue(episode != fact, "not folded into the fact")
    }

    @Test
    fun anEpisodeRanksBelowAnEquallyActivatedFact() {
        val g = graph()
        val fact = g.remember("关于海马体索引的讨论要点", salience = 0.6)
        val episode = g.remember("关于海马体索引的讨论要点", salience = 0.6, isEpisode = true)
        val order = g.recall("海马体索引", reinforce = Reinforce.NONE).map { it.engram.id }
        assertTrue(fact in order && episode in order)
        assertTrue(order.indexOf(fact) < order.indexOf(episode), "the specific fact outranks the session summary")
    }

    @Test
    fun anEpisodeDoesNotCrowdAFactOutOfAScarceSeedPool() {
        val g = graph(LatticeGraph.Config(seedCount = 1))
        val fact = g.remember("关于海马体索引的讨论要点", salience = 0.6)
        val episode = g.remember("关于海马体索引的讨论要点", salience = 0.9, isEpisode = true) // even at higher salience
        val ids = g.recall("海马体索引", reinforce = Reinforce.NONE).map { it.engram.id }
        assertTrue(fact in ids, "the fact takes the one seed slot")
        assertFalse(episode in ids)
    }

    @Test
    fun anEpisodeIsNotMergedIntoAFactBySleep() {
        val g = graph(LatticeGraph.Config(dedupThreshold = 0.999))
        val fact = g.remember("用户喜欢喝美式咖啡不加糖", salience = 0.6)
        val episode = g.remember("用户喜欢喝美式咖啡不加糖", salience = 0.7, isEpisode = true)
        assertEquals(2, g.size)
        g.mergeDuplicates(threshold = 0.7)
        assertNotNull(g.engram(fact))
        assertNotNull(g.engram(episode), "episode/fact is a merge boundary")
        assertTrue(g.engram(episode)!!.isEpisode)
    }

    @Test
    fun anEpisodeAnchorIsExemptFromSizeBudgetEviction() {
        val g = graph(LatticeGraph.Config(maxNodes = 3))
        val episode = g.remember("上一场会话的完整回顾", salience = 0.2, isEpisode = true)
        repeat(6) { g.remember("互不相同的琐碎事实编号$it", salience = 0.9) }
        g.decay()
        assertNotNull(g.engram(episode), "the degree-0 episode anchor is spared")
    }

    @Test
    fun theEpisodeFlagAndSourceSurviveAReload() {
        val store = TestLatticeStore()
        val id = graph(store = store).remember("会话回顾：讨论了记忆系统", salience = 0.6, sourceMessageId = 4242L, isEpisode = true)
        val e = graph(store = store).engram(id)!!
        assertTrue(e.isEpisode)
        assertEquals(4242L, e.sourceMessageId)
    }

    @Test
    fun aReMentionAdoptsASourceAnchorWhenTheKeptNodeLackedOne() {
        val g = graph()
        val first = g.remember("用户偏好简体中文", salience = 0.5)
        assertNull(g.engram(first)!!.sourceMessageId)
        val again = g.remember("用户偏好简体中文", salience = 0.5, sourceMessageId = 9L)
        assertEquals(first, again)
        assertEquals(9L, g.engram(first)!!.sourceMessageId)
    }

    // --- verbatim quotes ---------------------------------------------------------------------------------------------

    @Test
    fun recallShowsTheQuoteWithItsSpeaker() {
        val g = graph()
        NativeMemoryIngestor.ingest(
            g,
            Extraction(
                listOf(
                    ExtractedMemory(
                        "m1", "用户借长风破浪会有时给自己打气", 0.8, Engram.Layer.SEMANTIC,
                        quotes = listOf(ExtractedQuote(Speaker.USER, "长风破浪会有时")),
                    ),
                ),
                emptyList(),
            ),
        )
        val lookup = object : RecallFormatter.Lookup {
            override fun evidenceQuotes(id: Long) = g.evidenceQuotes(id)
        }
        val rendered = RecallFormatter.format(g.recall("长风破浪会有时给自己打气"), RecallMarkers.ZH, g.temporalLayerEnabled, lookup)
        assertTrue("长风破浪会有时" in rendered, rendered)
        assertTrue("用户" in rendered, "with the speaker: $rendered")
    }

    @Test
    fun aVerbatimsSpeakerAndSourceSurviveAReload() {
        val store = TestLatticeStore()
        val g = graph(store = store)
        val gist = g.remember("用户很在意自我反思", 0.7, Engram.Layer.SEMANTIC)
        val vid = g.addVerbatim(gist, "长风破浪会有时", Speaker.USER, sourceMessageId = 42L)
        val v = graph(store = store).engram(vid)!!
        assertEquals(Speaker.USER, v.speaker)
        assertEquals(42L, v.sourceMessageId)
    }

    @Test
    fun aVerbatimIdenticalToItsGistStillGetsItsOwnEngramAndEvidenceEdge() {
        val g = graph()
        val gist = g.remember("长风破浪会有时", 0.7, Engram.Layer.SEMANTIC)
        val vid = g.addVerbatim(gist, "长风破浪会有时", Speaker.USER)
        assertTrue(vid != gist, "its own engram, not folded into the gist")
        assertEquals(Speaker.USER, g.engram(vid)!!.speaker)
        assertNull(g.engram(gist)!!.speaker, "the gist is not tagged with a speaker")
        assertTrue(g.evidenceQuotes(gist).any { it.id == vid })
    }

    @Test
    fun theMergeDoesNotFoldAVerbatimIntoItsGist() {
        val g = graph()
        val gist = g.remember("长风破浪会有时", 0.7, Engram.Layer.SEMANTIC)
        val vid = g.addVerbatim(gist, "长风破浪会有时", Speaker.USER)
        g.mergeDuplicates(0.9)
        assertNotNull(g.engram(vid))
        assertEquals(Speaker.USER, g.engram(vid)!!.speaker)
        assertNull(g.engram(gist)!!.speaker)
    }

    @Test
    fun aLaterGistDoesNotAutoLinkToAVerbatim() {
        // A verbatim is reached only through its gist's EVIDENCE edge, never as a bare, unattributed hit.
        val g = graph()
        val gist = g.remember("用户很在意自我反思", salience = 0.7, layer = Engram.Layer.SEMANTIC)
        g.addVerbatim(gist, "长风破浪会有时", Speaker.USER)
        val verbatim = g.engrams().first { it.content == "长风破浪会有时" }
        val laterGist = g.remember("长风破浪会有时这句诗很有分量", salience = 0.7, layer = Engram.Layer.SEMANTIC)
        assertTrue(g.neighbors(laterGist).none { it.to == verbatim.id })
    }

    @Test
    fun decayFadesAnUnrehearsedEpisodicMemoryButKeepsASemanticOne() {
        val g = graph(LatticeGraph.Config(episodicSalienceDecay = 0.1, nodePruneFloor = 0.5))
        val episodic = g.remember("一次性的小事", salience = 0.4, layer = Engram.Layer.EPISODIC)
        val semantic = g.remember("用户偏好用简体中文", salience = 0.7, layer = Engram.Layer.SEMANTIC)
        g.decay()
        assertNull(g.engram(episodic), "words fade")
        assertNotNull(g.engram(semantic), "meaning stays")
    }
}
