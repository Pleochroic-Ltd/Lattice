package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [TrustedPeriodLabels] through the recall formatter over a live graph: a turn-end recall names the period of a hit of
 * this chat — its name only when its naming scenes and some member are trusted in the graph's trust cache, else a
 * neutral date label; the name normalized to one line and capped.
 */
class TrustedPeriodLabelsTest {

    private companion object {
        const val CHAT = "telegram:42"
        const val CONV = "telegram:42:s1"
        const val PERIOD_NAME = "去海边的一日"

        /** 2026-09-28 11:00 in Shanghai. */
        val NOW: Instant = Instant.parse("2026-09-28T03:00:00Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    private class Fixture(val g: LatticeGraph, val ticket: Long, val undated: Long, val period: Long)

    private fun fixture(
        trust: Map<Long, Boolean> = mapOf(1L to true, 2L to true),
        periodName: String = PERIOD_NAME,
        naming: Set<Long> = setOf(1, 2),
    ): Fixture {
        val g = LatticeGraph(
            LocalHashingEmbedder(),
            LatticeGraph.Config(episodicBinding = true, temporalLayer = true, autoLinkThreshold = 0.99, dedupThreshold = 0.99),
            now = { NOW },
        )
        val ticket = g.remember("在车站买了去海边的车票", eventDate = "2026-08-03")
        val undated = g.remember("喜欢靠窗的座位")
        val both = g.remember("海边的温泉", eventDate = "2026-08-04")
        g.putScene(SceneRecord(1, "车站", CONV, CHAT, 100, 150, "2026-08-03", "2026-08-03"))
        g.putScene(SceneRecord(2, "海边", CONV, CHAT, 200, 250, "2026-08-04", "2026-08-04"))
        g.addSceneMembers(1, listOf(ticket, undated))
        g.addSceneMembers(2, listOf(both))
        val period = g.allocatePeriodId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(PeriodRecord(period, CHAT, periodName, gist = "一日游", namingSceneIds = naming)),
                members = listOf(PeriodMemberRecord(1, period), PeriodMemberRecord(2, period)),
            ),
        )
        if (trust.isNotEmpty()) g.putSceneTrust(trust)
        return Fixture(g, ticket, undated, period)
    }

    private fun Fixture.line(id: Long, language: Language = Language.ZH): String {
        val lookup = object : RecallFormatter.Lookup by GraphLookup(g) {
            override fun periodLabel(period: PeriodRecord): String = TrustedPeriodLabels.label(g, period, language)
        }
        return RecallFormatter.format(
            listOf(Activation(g.engram(id)!!, 0.8, Activation.Reach.SEED)),
            RecallMarkers.of(BundledTexts, language),
            temporalLayer = true,
            lookup = lookup,
            dating = RecallDating.turnEnd(CHAT, NOW, ZONE),
        ).lines().first()
    }

    @Test
    fun `a period whose naming scenes are trusted is tagged with its name`() {
        val f = fixture()
        assertEquals("- [2026-08-03 · 约八周前] 在车站买了去海边的车票〔时期：$PERIOD_NAME〕", f.line(f.ticket))
    }

    @Test
    fun `a period named from a scene now untrusted shows a neutral date label, never its name`() {
        val f = fixture(trust = mapOf(1L to false, 2L to true))
        val line = f.line(f.ticket)
        assertEquals("- [2026-08-03 · 约八周前] 在车站买了去海边的车票〔时期：2026-08-03 至 2026-08-04〕", line)
        assertFalse(PERIOD_NAME in line)
    }

    @Test
    fun `scenes missing from the trust cache count as untrusted - the recall reads only the cache`() {
        val f = fixture(trust = emptyMap())
        assertEquals("- [2026-08-03 · 约八周前] 在车站买了去海边的车票〔时期：2026-08-03 至 2026-08-04〕", f.line(f.ticket))
    }

    @Test
    fun `an untrusted member the naming call never saw does not mask the name`() {
        val f = fixture(trust = mapOf(1L to true, 2L to false), naming = setOf(1))
        assertEquals("- [2026-08-03 · 约八周前] 在车站买了去海边的车票〔时期：$PERIOD_NAME〕", f.line(f.ticket))
    }

    @Test
    fun `a period with no naming scene still needs a trusted member`() {
        val f = fixture(trust = mapOf(1L to false, 2L to false), naming = emptySet())
        assertEquals("- 喜欢靠窗的座位〔时期：2026-08-03 至 2026-08-04〕", f.line(f.undated))
    }

    @Test
    fun `the tag text is normalized to one line without render marks, and capped`() {
        val f = fixture(periodName = "「海边」\n之旅​·终")
        assertEquals("- 喜欢靠窗的座位〔时期：海边 之旅 终〕", f.line(f.undated))
        val blank = fixture(periodName = "**")
        assertEquals("- 喜欢靠窗的座位〔时期：2026-08-03 至 2026-08-04〕", blank.line(blank.undated), "nothing left: the date label")
        val long = fixture(periodName = "很".repeat(PeriodFields.MAX_NAME_CODE_POINTS + 5))
        assertEquals("- 喜欢靠窗的座位〔时期：${"很".repeat(PeriodFields.MAX_NAME_CODE_POINTS)}〕", long.line(long.undated))
    }

    @Test
    fun `an English chat gets the English date label`() {
        val f = fixture(trust = emptyMap())
        val period = f.g.period(f.period)!!
        assertEquals("2026-08-03 to 2026-08-04", TrustedPeriodLabels.label(f.g, period, Language.EN))
        assertEquals("2026-08-03 至 2026-08-04", TrustedPeriodLabels.label(f.g, period, Language.ZH))
        assertEquals(PeriodLabels.NEUTRAL.label(f.g, period, Language.EN), TrustedPeriodLabels.label(f.g, period, Language.EN), "the same as the neutral labels")
    }
}
