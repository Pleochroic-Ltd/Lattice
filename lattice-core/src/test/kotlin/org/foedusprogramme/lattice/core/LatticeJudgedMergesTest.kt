package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Judged merges only: closeness alone never merges two memories; the sleep judge decides, above the old threshold too. */
class LatticeJudgedMergesTest {

    private val t1 = Instant.parse("2023-03-11T18:01:00Z")
    private val t2 = Instant.parse("2023-07-30T04:03:00Z")
    private val clock = { Instant.parse("2023-08-01T00:00:00Z") }

    /** Every tennis text shares one axis (cosine 1.0, far above the dedup threshold); anything else its own. */
    private class TopicEmbedder : Embedder {
        override val model = "test"
        override val dimension = 4
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            v[if ("tennis" in text) 0 else 3] = 1f
            return v
        }
    }

    /** SAME when both notes name the same frequency; records every pair it is asked about. */
    private class FrequencyJudge : MergeJudge {
        override val modelId = "fake"
        val calls = ArrayList<Pair<String, String>>()
        override fun judge(noteA: String, noteB: String, crossLingualHint: Boolean): MergeJudge.Verdict {
            calls += noteA to noteB
            return if (("every other week" in noteA) == ("every other week" in noteB)) {
                MergeJudge.Verdict.SAME
            } else {
                MergeJudge.Verdict.DIFFERENT
            }
        }
    }

    private fun graph(judgedOnly: Boolean, judge: MergeJudge? = null) = LatticeGraph(
        TopicEmbedder(),
        LatticeGraph.Config(temporalLayer = true, valueAwareFold = true, judgedMergesOnly = judgedOnly),
        now = clock,
        judge = judge,
    )

    @Test
    fun aCloseReMentionStaysItsOwnMemoryUntilTheJudgeReadsIt() {
        val g = graph(judgedOnly = true)
        val old = g.remember("The user plays tennis weekly with friends at the park", saidFrom = t1, saidTo = t1)
        val new = g.remember("The user plays tennis every other week with friends at the park", saidFrom = t2, saidTo = t2)
        assertNotEquals(old, new)
        assertEquals(t1, g.engram(old)!!.saidTo, "the old memory keeps its own date")
        assertEquals(0, g.mergeDuplicates(runJudgedPass = false), "the sleep geometry pass keeps them apart too")
    }

    @Test
    fun withTheSwitchOffTheReMentionFoldsIntoTheOlderText() {
        val g = graph(judgedOnly = false)
        val old = g.remember("The user plays tennis weekly with friends at the park", saidFrom = t1, saidTo = t1)
        val new = g.remember("The user plays tennis every other week with friends at the park", saidFrom = t2, saidTo = t2)
        assertEquals(old, new)
        assertEquals("The user plays tennis weekly with friends at the park", g.engram(old)!!.content)
        assertEquals(t2, g.engram(old)!!.saidTo)
    }

    @Test
    fun theSameWordsStillFold() {
        val g = graph(judgedOnly = true)
        val old = g.remember("The user plays tennis on Sundays.", saidFrom = t1, saidTo = t1)
        val again = g.remember("the user plays  tennis on Sundays", saidFrom = t2, saidTo = t2)
        assertEquals(old, again)
        assertEquals(t2, g.engram(old)!!.saidTo, "a re-mention widens the said span")
        assertTrue(LatticeGraph.sameText("Weekly tennis。", "weekly  tennis"))
    }

    @Test
    fun theJudgeDecidesPairsAboveTheOldThreshold() {
        val judge = FrequencyJudge()
        val g = graph(judgedOnly = true, judge = judge)
        val weekly = g.remember("The user plays tennis weekly with friends", saidFrom = t1, saidTo = t1)
        val biweekly = g.remember("The user plays tennis every other week with friends", saidFrom = t2, saidTo = t2)
        val restated = g.remember("The user plays tennis every other week with their friends", saidFrom = t2, saidTo = t2)
        g.judgedSupersedePass()
        assertTrue(judge.calls.isNotEmpty(), "a pair at cosine 1.0 reaches the judge")
        assertNull(g.engram(weekly)!!.supersededBy, "a changed frequency is not a restatement")
        val merged = g.engram(biweekly)!!.supersededBy == restated || g.engram(restated)!!.supersededBy == biweekly
        assertTrue(merged, "a restatement is merged by the judge")
    }

    @Test
    fun withTheSwitchOffTheJudgeNeverSeesPairsAboveTheThreshold() {
        val judge = FrequencyJudge()
        val g = LatticeGraph(TopicEmbedder(), LatticeGraph.Config(temporalLayer = true), now = clock, judge = judge)
        g.remember("The user plays tennis weekly with friends", saidFrom = t1, saidTo = t1)
        g.remember("The user likes tennis", saidFrom = t2, saidTo = t2)
        g.judgedSupersedePass()
        assertTrue(judge.calls.isEmpty())
    }
}
