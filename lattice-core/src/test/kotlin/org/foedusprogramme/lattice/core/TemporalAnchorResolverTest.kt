package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Deterministic-grammar anchor resolution — pure calendar arithmetic against an injected clock/zone.
 * No embedding is ever consulted about time.
 */
class TemporalAnchorResolverTest {

    // Mid-month so month-boundary/zone rounding never confounds the non-zone cases.
    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val utc = ZoneOffset.UTC

    private fun r(cue: String, focus: List<YearMonth> = emptyList(), margin: Int = 1) =
        TemporalAnchorResolver.resolve(cue, focus, now, utc, margin)

    @Test
    fun absoluteDateIsPointHigh() {
        val a = r("2025-03 的项目进展")!!
        assertEquals(YearMonth.of(2025, 3), a.yearMonth)
        assertEquals(TemporalAnchor.Kind.POINT, a.kind)
        assertEquals(TemporalAnchor.Confidence.HIGH, a.confidence)
        // CN 年/月 form resolves the same.
        assertEquals(YearMonth.of(2025, 3), r("2025年3月我们聊了什么")!!.yearMonth)
    }

    @Test
    fun aMonthNameWithItsYearKeepsThatYear() {
        // Asked in August 2026: the most recent May is 2026's, but the cue names 2023 / 2027.
        for ((cue, expected) in listOf(
            "Who did Maria have dinner with on May 3, 2023?" to YearMonth.of(2023, 5),
            "What happened in May 2023?" to YearMonth.of(2023, 5),
            "What did Audrey do the weekend before 4th October, 2023?" to YearMonth.of(2023, 10),
            "the 3rd of December 2024" to YearMonth.of(2024, 12),
            "Sept 2022 plans" to YearMonth.of(2022, 9),
            "our 2027 March trip" to YearMonth.of(2027, 3),
            "What is planned for October 2027?" to YearMonth.of(2027, 10),
        )) {
            val a = assertNotNull(r(cue), cue)
            assertEquals(expected, a.yearMonth, cue)
            assertEquals(TemporalAnchor.Kind.POINT, a.kind, cue)
            assertNull(a.fromDay, cue)
            assertEquals(TemporalIntent.RELEVANCE, TemporalIntentClassifier.classify(cue), "classify() and resolve() agree: $cue")
        }
        // Without a year the most recent occurrence still wins.
        assertEquals(YearMonth.of(2026, 5), r("on May 3 we met")!!.yearMonth)
        assertEquals(YearMonth.of(2025, 10), r("in October")!!.yearMonth)
    }

    @Test
    fun relativeGrammarResolvesAgainstTheQueryClock() {
        assertEquals(YearMonth.of(2025, 7), r("去年七月发生的事")!!.yearMonth)
        assertEquals(YearMonth.of(2026, 7), r("上个月的决定")!!.yearMonth)
        assertEquals(YearMonth.of(2026, 8), r("这个月的进展")!!.yearMonth)
        assertEquals(YearMonth.of(2026, 5), r("三个月前我换了工作")!!.yearMonth)
        assertEquals(YearMonth.of(2025, 8), r("去年发生的事")!!.yearMonth) // bare 去年 -> same month last year
    }

    @Test
    fun englishRelativesAndMonthNames() {
        assertEquals(YearMonth.of(2026, 7), r("the deadline last month")!!.yearMonth)
        assertEquals(YearMonth.of(2026, 5), r("3 months ago I changed jobs")!!.yearMonth)
        assertEquals(YearMonth.of(2025, 8), r("last year sometime")!!.yearMonth)
        // Bare month name -> most-recent occurrence (March 2026 <= now, so this year).
        assertEquals(YearMonth.of(2026, 3), r("what happened around March")!!.yearMonth)
        // A month later than "now" resolves to LAST year (December is in the future this year).
        assertEquals(YearMonth.of(2025, 12), r("that December trip")!!.yearMonth)
    }

    @Test
    fun backReferenceUsesFocusStackAndAbstainsWhenEmpty() {
        assertNull(r("那阵子还聊了什么"), "empty focus stack => ABSTAIN (safe fall-through)")

        val a = r("那阵子还聊了什么", focus = listOf(YearMonth.of(2025, 3)))!!
        assertEquals(YearMonth.of(2025, 3), a.yearMonth)
        assertEquals(TemporalAnchor.Kind.RANGE, a.kind)
        assertEquals(TemporalAnchor.Confidence.HIGH, a.confidence)
        assertEquals(1, a.marginMonths)
        assertEquals(setOf("2025-02", "2025-03", "2025-04"), a.window())

        // Most-recent focus wins; a wider margin widens the window.
        val b = r("那段时间", focus = listOf(YearMonth.of(2026, 1), YearMonth.of(2025, 3)), margin = 2)!!
        assertEquals(YearMonth.of(2026, 1), b.yearMonth)
        assertEquals(2, b.marginMonths)
    }

    @Test
    fun noTemporalGrammarAbstains() {
        assertNull(r("我喜欢喝什么咖啡"))
        assertNull(r("what programming language do I use"))
    }

    @Test
    fun relativesResolveInTheUsersZoneNotUtc() {
        // 00:30Z on the 1st is still July the previous evening two zones west.
        val boundary = Instant.parse("2026-08-01T00:30:00Z")
        val minus2 = ZoneOffset.ofHours(-2)
        assertEquals(
            YearMonth.of(2026, 8),
            TemporalAnchorResolver.resolve("这个月的进展", emptyList(), boundary, utc)!!.yearMonth,
            "UTC: still August",
        )
        assertEquals(
            YearMonth.of(2026, 7),
            TemporalAnchorResolver.resolve("这个月的进展", emptyList(), boundary, minus2)!!.yearMonth,
            "UTC-2: rolled back to July — the zone matters",
        )
    }
}
