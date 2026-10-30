package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a memory was said (the span of its source messages, widened by every re-mention and merge), a plan's target date
 * apart from its event date, and value-aware dedup (a near-duplicate stating a different value is an update).
 */
class LatticeSaidSpanTest {

    private val t1 = Instant.parse("2023-01-11T23:15:00Z")
    private val t2 = Instant.parse("2023-03-15T08:18:00Z")
    private val clock = { Instant.parse("2023-06-01T00:00:00Z") }

    /** Every text mentioning eggs shares one axis (cosine 1.0, a near-duplicate); anything else its own. */
    private class TopicEmbedder : Embedder {
        override val model = "topic"
        override val dimension = 8
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val axis = when {
                "egg" in text -> 0
                "Apex" in text -> 1
                "summary" in text -> 2
                "mouse" in text -> 3
                else -> 7
            }
            v[axis] = 1f
            return v
        }
    }

    private fun graph(valueAware: Boolean = true, embedder: Embedder = TopicEmbedder(), store: LatticeStore = NoOpLatticeStore) =
        LatticeGraph(embedder, LatticeGraph.Config(temporalLayer = true, valueAwareFold = valueAware), now = clock, store = store)

    @Test
    fun aReMentionWidensTheSaidSpanOfTheKeptMemory() {
        val g = graph()
        val id = g.remember("The user keeps a stock of eggs in the fridge", saidFrom = t1, saidTo = t1)
        val again = g.remember("The user keeps eggs stocked in the fridge", saidFrom = t2, saidTo = t2)
        assertEquals(id, again, "a restatement folds into the existing memory")
        assertEquals(t1, g.engram(id)!!.saidFrom)
        assertEquals(t2, g.engram(id)!!.saidTo)
    }

    @Test
    fun aDifferentValueIsStoredBesideTheOldOneInsteadOfFoldedAway() {
        val g = graph(valueAware = true)
        val old = g.remember("The user has about 30 dozen eggs in the fridge", saidFrom = t1, saidTo = t1)
        val new = g.remember("The user has 20 dozen eggs in the fridge", saidFrom = t2, saidTo = t2)
        assertTrue(old != new, "an update stays its own memory")
        assertEquals(t2, g.engram(new)!!.saidFrom)
        assertEquals(t1, g.engram(old)!!.saidTo, "the old memory keeps its own date")
        assertEquals(0, g.mergeDuplicates(runJudgedPass = false), "the sleep merge keeps them apart too")
        assertEquals(2, g.engrams().count { "eggs" in it.content })
    }

    @Test
    fun withTheSwitchOffTheUpdateFoldsAsBefore() {
        val g = graph(valueAware = false)
        val old = g.remember("The user has about 30 dozen eggs in the fridge", saidFrom = t1, saidTo = t1)
        val new = g.remember("The user has 20 dozen eggs in the fridge", saidFrom = t2, saidTo = t2)
        assertEquals(old, new)
        assertEquals("The user has about 30 dozen eggs in the fridge", g.engram(old)!!.content)
    }

    @Test
    fun valuesConflictOnlyWhenNeitherSideContainsTheOther() {
        assertTrue(LatticeGraph.valuesConflict("around 30 dozen eggs", "20 dozen eggs"))
        assertTrue(LatticeGraph.valuesConflict("reach level 100", "reach level 150"))
        assertTrue(LatticeGraph.valuesConflict("bought three tops", "bought five tops"))
        assertTrue(LatticeGraph.valuesConflict("冰箱里有三十打鸡蛋", "冰箱里有二十打鸡蛋"))
        assertTrue(LatticeGraph.valuesConflict("ran 5 km", "ran 5 km in 30 minutes"), "a fold would drop the added value")
        assertTrue(LatticeGraph.valuesConflict("is thinking of buying more tops", "already owns five tops"), "a value appears")
        assertFalse(LatticeGraph.valuesConflict("likes eggs a lot", "really likes eggs"), "no value on either side")
        assertFalse(LatticeGraph.valuesConflict("我们一起去了海边", "我们一同去了海边"), "一 alone is not a value")
        assertFalse(LatticeGraph.valuesConflict("one of my friends has 2 cats", "my friend has two cats"))
        assertTrue(LatticeGraph.valuesConflict("has 2 cats and 3 dogs", "has 3 cats and 2 dogs"), "swapped values")
        assertFalse(LatticeGraph.valuesConflict("is twenty-two years old", "is 22 years old"), "a compound number word")
        assertFalse(LatticeGraph.valuesConflict("bought two hundred eggs", "bought 200 eggs"))
        assertFalse(LatticeGraph.valuesConflict("three thousand seats", "3000 seats"))
        assertFalse(LatticeGraph.valuesConflict("twenty two years", "22 years"))
        assertFalse(LatticeGraph.valuesConflict("one hundred and five pages", "105 pages"))
        assertTrue(LatticeGraph.valuesConflict("has 2 dogs", "has 3 dogs and 2 cats"), "a changed value plus an added one")
        assertTrue(LatticeGraph.valuesConflict("ran twice a week", "ran 3 times a week"))
        assertTrue(LatticeGraph.valuesConflict("用户有一个苹果", "用户有三个苹果"), "一 before a measure word is a count")
        assertTrue(LatticeGraph.valuesConflict("The user has one cat", "The user has three cats"), "one as a count")
        assertFalse(LatticeGraph.valuesConflict("the one I bought costs 30 dollars", "it costs 30 dollars"), "one as a pronoun")
        assertFalse(LatticeGraph.valuesConflict("one day I will run 5 km", "I will run 5 km"), "one day is an idiom")
        assertFalse(LatticeGraph.valuesConflict("lives in room 05", "lives in room 5"), "a leading zero")
        assertFalse(LatticeGraph.valuesConflict("ran 5 km in 30 minutes", "ran 5 km in 30 minutes again"))
        assertEquals(250L, LatticeGraph.chineseNumber("两百五十"))
        assertEquals(12L, LatticeGraph.chineseNumber("十二"))
        assertEquals(12000L, LatticeGraph.chineseNumber("一万二千"))
    }

    @Test
    fun aQuoteWithADifferentValueIsKeptWordForWord() {
        val g = graph()
        val gist = g.remember("The user keeps a stock of eggs", saidFrom = t1, saidTo = t1)
        val a = g.addVerbatim(gist, "we have 30 dozen eggs in the fridge", Speaker.USER, saidFrom = t1, saidTo = t1)
        val b = g.addVerbatim(gist, "we have 20 dozen eggs in the fridge", Speaker.USER, saidFrom = t2, saidTo = t2)
        assertTrue(a != b, "both utterances stay")
    }

    @Test
    fun aMorePreciseTargetOfTheSamePlanReplacesTheCoarserOne() {
        val g = graph()
        val id = g.remember("The user aims for level 100 in Apex", dueDate = "2025-04", saidFrom = t1, saidTo = t1)
        assertEquals(id, g.remember("The user aims for level 100 in Apex", dueDate = "2025-04-15", saidFrom = t2, saidTo = t2))
        assertEquals("2025-04-15", g.engram(id)!!.dueDate)
    }

    @Test
    fun anExplicitSaveIsSaidWhenItIsSaved() {
        val g = graph()
        val id = g.remember("The user likes a gaming mouse with side buttons", origin = Engram.Origin.MANUAL)
        assertEquals(clock(), g.engram(id)!!.saidFrom)
        assertEquals(clock(), g.engram(id)!!.saidTo)
    }

    @Test
    fun aSynthesizedSummarySpansItsChildren() {
        val g = graph(embedder = LocalHashingEmbedder())
        val a = g.remember("alpha beta gamma delta", saidFrom = t1, saidTo = t1)
        val b = g.remember("epsilon zeta eta theta", saidFrom = t2, saidTo = t2)
        val p = g.engram(g.synthesizeParent(listOf(a, b), "iota kappa lambda mu nu xi"))!!
        assertEquals(t1, p.saidFrom)
        assertEquals(t2, p.saidTo)
    }

    @Test
    fun theSpanAndTargetSurviveAReload() {
        val store = TestLatticeStore()
        val id = graph(store = store).remember("The user aims for level 100 in Apex", saidFrom = t1, saidTo = t2, dueDate = "2023-12-31")
        val e = graph(store = store).engram(id)!!
        assertEquals(t1, e.saidFrom)
        assertEquals(t2, e.saidTo)
        assertEquals("2023-12-31", e.dueDate)
    }
}
