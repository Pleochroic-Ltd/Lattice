package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Day-window recall: a day- or week-level cue ("the milestone I mentioned four weeks ago", "上周") reaches the memories
 * said (or dated) in that window, most relevant first, ahead of the semantic hits.
 */
class LatticeDayWindowTest {

    private val now = Instant.parse("2023-03-28T20:35:00Z")
    private val today = LocalDate.parse("2023-03-28")

    private fun anchor(cue: String) = TemporalAnchorResolver.resolve(cue, emptyList(), now, ZoneOffset.UTC)

    private fun window(cue: String) = anchor(cue)!!.let { it.fromDay to it.toDay }

    /** The cue is axis 0; memory i sits at cosine a_i to it on its own axis, so memories barely resemble each other. */
    private class ConeEmbedder(private val cosines: Map<String, Double>) : Embedder {
        override val model = "test"
        override val dimension = 32
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val a = cosines[text]
            if (a == null) {
                v[0] = 1f
                return v
            }
            v[0] = a.toFloat()
            v[1 + (text.hashCode() and 0x7fffffff) % (dimension - 1)] = sqrt(1 - a * a).toFloat()
            return v
        }
    }

    @Test
    fun `week and day cues resolve to a day window around the day they point at`() {
        // Asked 2023-03-28, the milestone was said 2023-03-01.
        assertEquals(LocalDate.parse("2023-02-24") to LocalDate.parse("2023-03-04"), window("What was the significant business milestone I mentioned four weeks ago?"))
        assertEquals(today.minusDays(17) to today.minusDays(11), window("两周前我说的那家店"))
        assertEquals(today.minusDays(4) to today.minusDays(2), window("三天前的事"))
        assertEquals(today.minusDays(1) to today.minusDays(1), window("昨天聊了什么"))
        assertEquals(today.minusDays(2) to today.minusDays(2), window("what did we talk about the day before yesterday"))
        assertEquals(today.minusDays(10) to today.minusDays(4), window("上周说的计划"))
        assertEquals(today.minusDays(6) to today, window("这周"))
        assertEquals(today.minusDays(17) to today.minusDays(11), window("a couple of weeks ago"))
        assertEquals(today.minusDays(4) to today.minusDays(2), window("a few days ago"))
        assertEquals(today.minusDays(7) to today.minusDays(2), window("前几天你说的"))
        assertNull(anchor("what did I do last month")!!.fromDay, "a month cue keeps its month anchor")
        assertNull(anchor("which grocery store do I like"), "no time in the cue, no anchor")
    }

    @Test
    fun `week and day cues are temporal cues`() {
        assertEquals(TemporalIntent.RELEVANCE, TemporalIntentClassifier.classify("the milestone I mentioned four weeks ago"))
        assertEquals(TemporalIntent.RELEVANCE, TemporalIntentClassifier.classify("上周说的那家店"))
        assertEquals(TemporalIntent.RELEVANCE, TemporalIntentClassifier.classify("what did I say yesterday"))
        assertEquals(TemporalIntent.NONE, TemporalIntentClassifier.classify("which grocery store do I like"))
        // Words that merely contain the characters: 目前天气 (current weather), 这周围 (around here), 提前天数.
        for (cue in listOf("目前天气怎么样", "这周围有什么好吃的", "提前天数够吗")) {
            assertEquals(TemporalIntent.NONE, TemporalIntentClassifier.classify(cue), cue)
            assertNull(anchor(cue), cue)
        }
    }

    @Test
    fun `what was said in the window and is about the cue is fetched`() {
        val cue = "What was the significant business milestone I mentioned four weeks ago?"
        val contract = "The user signed a contract with their first freelance client."
        val influencer = "The user collaborated with an influencer who promoted their jewelry."
        val website = "The user launched their business website."
        val g = LatticeGraph(
            ConeEmbedder(mapOf(contract to 0.42, influencer to 0.40, website to 0.55)),
            LatticeGraph.Config.bgeM3().copy(temporalLayer = true, temporalRetrieval = true),
            now = { now },
        )
        val then = Instant.parse("2023-03-01T02:43:00Z")
        val earlier = Instant.parse("2023-02-10T02:03:00Z")
        g.remember(contract, saidFrom = then, saidTo = then)
        g.remember(influencer, saidFrom = earlier, saidTo = earlier)
        g.remember(website, saidFrom = earlier, saidTo = earlier)

        val inWindow = g.dayWindowFetch(cue, LocalDate.parse("2023-02-24"), LocalDate.parse("2023-03-04"), ZoneOffset.UTC, cap = 4)
        assertEquals(listOf(contract), inWindow.map { it.engram.content })
    }

    @Test
    fun `off by config, the cue is read for its month at most`() {
        val cue = "the milestone I mentioned four weeks ago"
        val contract = "The user signed a contract with their first freelance client."
        val g = LatticeGraph(
            ConeEmbedder(mapOf(contract to 0.42)),
            LatticeGraph.Config.bgeM3().copy(temporalLayer = true, temporalRetrieval = true, dayWindowRecall = false),
            now = { now },
        )
        val then = Instant.parse("2023-03-01T02:43:00Z")
        g.remember(contract, saidFrom = then, saidTo = then)
        assertTrue(g.dayWindowFetch(cue, LocalDate.parse("2023-02-24"), LocalDate.parse("2023-03-04"), ZoneOffset.UTC, cap = 4).isEmpty())
    }
}
