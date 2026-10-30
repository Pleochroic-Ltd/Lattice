package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The temporal layer in the graph: the event date is an attribute (content stays dateless), two near-duplicates with
 * different dates are kept apart at write and at sleep, an undated memory adopts a date, and the recall tag is gated on
 * the switch, not the data. The extraction half lives in [NativeMemoryExtractorTest], the block in [RecallFormatterTest].
 */
class LatticeEventDateTest {

    private val fixedClock = { Instant.parse("2025-03-22T10:00:00Z") }

    /** Each sentence lands on one topic axis by keyword: same topic 1.0, different topic 0.0. */
    private class TopicEmbedder : Embedder {
        override val model = "topic"
        override val dimension: Int = 8
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            val axis = when {
                "截止" in text || "deadline" in text -> 5
                "语言" in text || "简体" in text || "language" in text -> 1
                "Rust" in text -> 2
                "马拉松" in text || "marathon" in text -> 3
                "咖啡" in text || "coffee" in text -> 4
                "领养" in text || "Byte" in text -> 0
                else -> 7
            }
            v[axis] = 1f
            return v
        }
    }

    private fun graph(on: Boolean, store: LatticeStore = NoOpLatticeStore) =
        LatticeGraph(TopicEmbedder(), LatticeGraph.Config.bgeM3().copy(temporalLayer = on), now = fixedClock, store = store)

    private fun block(g: LatticeGraph, cue: String, gate: Boolean = false) =
        RecallFormatter.format(g.recall(cue, limit = 6, reinforce = Reinforce.NONE, applyEmitGate = gate), RecallMarkers.ZH, g.temporalLayerEnabled)

    @Test
    fun theDateIsAnAttributeTheContentStaysDatelessAndBothSurviveAReload() {
        val store = TestLatticeStore()
        val id = graph(on = true, store = store).remember("项目截止日期原定完成", salience = 0.7, eventDate = "2025-03-10")
        val e = graph(on = true, store = store).engram(id)!!
        assertEquals("项目截止日期原定完成", e.content)
        assertNull(LatticeGraph.absoluteDateSignature(e.content), "the content carries no date")
        assertEquals("2025-03-10", e.eventDate)
    }

    @Test
    fun withTheSwitchOffNoEventDateIsKept() {
        val g = graph(on = false)
        val id = g.remember("项目截止日期原定完成", salience = 0.7, eventDate = "2025-03-10")
        assertNull(g.engram(id)!!.eventDate)
    }

    @Test
    fun eventDatesDifferAtTheirCoarsestCommonGranularity() {
        assertTrue(LatticeGraph.eventDatesDiffer("2025-03", "2025-06"))
        assertTrue(LatticeGraph.eventDatesDiffer("2025-03-10", "2025-03-20"))
        assertFalse(LatticeGraph.eventDatesDiffer("2026-05", "2026-05-15"), "a month and a day in it are not distinct")
        assertFalse(LatticeGraph.eventDatesDiffer("2025-03-22", "2025-03-22"))
    }

    @Test
    fun twoDifferentDatesAreKeptApartOnlyWithTheSwitchOn() {
        val on = graph(on = true)
        on.remember("项目截止日期", eventDate = "2025-03-10"); on.remember("项目截止日期", eventDate = "2025-06-20")
        assertEquals(2, on.size, "on: distinct dates do not fold")
        val off = graph(on = false)
        off.remember("项目截止日期", eventDate = "2025-03-10"); off.remember("项目截止日期", eventDate = "2025-06-20")
        assertEquals(1, off.size, "off: the near-duplicate folds and the date is lost")
    }

    @Test
    fun sameDateCoarserAndUndatedReMentionsStillFold() {
        val sameDate = graph(on = true)
        sameDate.remember("项目截止日期", eventDate = "2025-03-10"); sameDate.remember("项目截止日期", eventDate = "2025-03-10")
        assertEquals(1, sameDate.size)
        val coarser = graph(on = true)
        coarser.remember("项目截止日期", eventDate = "2025-03-10"); coarser.remember("项目截止日期", eventDate = "2025-03")
        assertEquals(1, coarser.size, "a coarser same-month re-mention is not a rescheduling")
        val oneUndated = graph(on = true)
        oneUndated.remember("项目截止日期", eventDate = "2025-03-10"); oneUndated.remember("项目截止日期", eventDate = null)
        assertEquals(1, oneUndated.size, "never invents a date")
    }

    @Test
    fun anUndatedMemoryAdoptsALaterMentionedDate() {
        val g = graph(on = true)
        val id = g.remember("项目截止日期", eventDate = null)
        assertEquals(id, g.remember("项目截止日期", eventDate = "2025-06-20"))
        assertEquals(1, g.size)
        assertEquals("2025-06-20", g.engram(id)!!.eventDate)
    }

    @Test
    fun theSleepMergeKeepsDistinctDatesApartToo() {
        val on = graph(on = true)
        on.remember("项目截止日期", salience = 0.7, eventDate = "2025-03-10")
        on.remember("项目截止日期", salience = 0.7, eventDate = "2025-06-20")
        on.mergeDuplicates()
        assertEquals(2, on.size, "the sleep merge honours the write-time anti-fold")
    }

    @Test
    fun anUndatedSurvivorOfASleepMergeAdoptsTheDroppedDate() {
        val g = graph(on = true)
        val keep = g.remember("用户在学习 Rust 编程", salience = 0.9, eventDate = null)
        g.remember("用户每天早上喝咖啡", salience = 0.3, eventDate = "2025-06-20")
        g.mergeDuplicates(threshold = 0.0) // force the fold regardless of cosine
        assertEquals(1, g.size)
        assertEquals("2025-06-20", g.engram(keep)!!.eventDate)
    }

    @Test
    fun theDateTagIsSwitchGatedSoARollbackOverStoredDatesShowsNone() {
        val store = TestLatticeStore()
        val cue = "项目截止日期是哪一天"
        graph(on = true, store = store).remember("项目截止日期", salience = 0.7, eventDate = "2025-03-10")
        val onBlock = block(graph(on = true, store = store), cue)
        assertTrue("[2025-03]" in onBlock, onBlock)
        val offBlock = block(graph(on = false, store = store), cue)
        assertFalse("[2025-03]" in offBlock, offBlock)
        assertFalse("[" in offBlock, "no tag at all with the switch off")
    }

    @Test
    fun bothDatedAnchorsComeBackWithTheSwitchOnAndAnUndatedFactIsUnchanged() {
        val deadlineCue = "项目截止日期是哪一天"
        val controlCue = "我平时用什么语言交流"
        val controlFact = "用户偏好用简体中文交流"
        fun populate(g: LatticeGraph) {
            g.remember("项目截止日期", salience = 0.7, eventDate = "2025-03-10")
            g.remember("项目截止日期", salience = 0.7, eventDate = "2025-06-20")
            g.remember(controlFact, salience = 0.7)
            g.remember("用户在学习 Rust 编程", salience = 0.5)
            g.remember("用户喜欢周末去跑马拉松", salience = 0.5)
            g.remember("用户每天早上喝咖啡", salience = 0.5)
        }
        val on = graph(on = true).also(::populate)
        val off = graph(on = false).also(::populate)
        for (gate in listOf(true, false)) {
            val onBlock = block(on, deadlineCue, gate)
            assertTrue("[2025-03]" in onBlock && "[2025-06]" in onBlock, "on (gate=$gate): both dates, so the order shows:\n$onBlock")
            val offBlock = block(off, deadlineCue, gate)
            assertFalse("[2025-06]" in offBlock, "off (gate=$gate): the second date was folded away")
            assertFalse("[2025-03]" in offBlock)
        }
        fun control(g: LatticeGraph) = RecallFormatter.format(
            g.recall(controlCue, limit = 6, reinforce = Reinforce.NONE, applyEmitGate = false).filter { it.engram.content == controlFact },
            RecallMarkers.ZH,
            g.temporalLayerEnabled,
        )
        assertEquals("- $controlFact", control(off))
        assertEquals(control(off), control(on), "the undated fact renders the same either way")
    }
}
