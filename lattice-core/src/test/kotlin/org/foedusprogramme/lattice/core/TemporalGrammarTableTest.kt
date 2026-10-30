package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Table-driven coverage for the two offline temporal grammar seams, adding cases the existing
 * [TemporalIntentClassifierTest] / [TemporalAnchorResolverTest] do not exercise: additional PERIOD/
 * RELEVANCE/NONE cues (incl. adversarial near-misses that must NOT trip a date token), and the
 * [TemporalAnchorResolver] CN-numeral / bare-month / precedence grammar against a fixed clock. Pure —
 * no embedding, graph, or ambient clock.
 */
class TemporalGrammarTableTest {

    // --- TemporalIntentClassifier: extended CN/EN + adversarial table -----------------------------

    @Test
    fun intentClassificationTable() {
        val cases = listOf(
            // PERIOD — bare back-references with no concrete date token.
            "那时候我们在忙什么" to TemporalIntent.PERIOD,
            "那段时间过得很快" to TemporalIntent.PERIOD,
            "at the time we were discussing it" to TemporalIntent.PERIOD,
            "that period was hectic" to TemporalIntent.PERIOD,
            // RELEVANCE — a topical query carrying a resolvable date token.
            "去年发生了什么" to TemporalIntent.RELEVANCE,           // bare 去年
            "本月的目标" to TemporalIntent.RELEVANCE,               // 本月
            "十一个月前的那次讨论" to TemporalIntent.RELEVANCE,      // CN-numeral N-months-ago
            "what did we ship this month" to TemporalIntent.RELEVANCE,
            "the December release notes" to TemporalIntent.RELEVANCE, // bare month name
            "2024/11 的复盘" to TemporalIntent.RELEVANCE,           // YYYY/MM absolute
            // NONE — adversarial near-misses that must NOT read as a date token.
            "the year 2025 was great" to TemporalIntent.NONE,       // bare 4-digit year, no month part
            "I read the monthly report" to TemporalIntent.NONE,     // "monthly" is not "months ago"
            "过去的经验很宝贵" to TemporalIntent.NONE,               // 过去 carries no resolvable token
            "我最喜欢的编程语言" to TemporalIntent.NONE,
        )
        for ((cue, expected) in cases) {
            assertEquals(expected, TemporalIntentClassifier.classify(cue), "classify(\"$cue\")")
        }
    }

    // --- TemporalAnchorResolver: grammar table against a fixed clock ------------------------------

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val utc = ZoneOffset.UTC

    private fun r(cue: String, focus: List<YearMonth> = emptyList(), margin: Int = 1) =
        TemporalAnchorResolver.resolve(cue, focus, now, utc, margin)

    @Test
    fun cnNumeralMonthsAgoTable() {
        // Fixed clock 2026-08; N-months-ago walks back from there. Covers CN numerals with 十 composition
        // and an ASCII digit inside the CN grammar — neither is in the existing resolver test.
        assertEquals(YearMonth.of(2026, 6), r("两个月前发生的事")!!.yearMonth, "两 -> 2")
        assertEquals(YearMonth.of(2025, 9), r("十一个月前那次")!!.yearMonth, "十一 -> 11")
        assertEquals(YearMonth.of(2025, 8), r("十二个月前")!!.yearMonth, "十二 -> 12")
        assertEquals(YearMonth.of(2024, 9), r("二十三个月前的决定")!!.yearMonth, "二十三 -> 23")
        assertEquals(YearMonth.of(2026, 3), r("5个月前我换了工作")!!.yearMonth, "ASCII digit in the CN grammar -> 5")
    }

    @Test
    fun cnLastYearMonthAndBareMonthTable() {
        // 去年X月 resolves the month against last year; a bare month name picks its most-recent occurrence.
        assertEquals(YearMonth.of(2025, 12), r("去年十二月的旅行")!!.yearMonth, "去年十二月 -> last-year December")
        assertEquals(YearMonth.of(2025, 1), r("去年一月发生的事")!!.yearMonth, "去年一月 -> last-year January")
        // Bare month <= current month -> this year; strictly future month -> last year.
        assertEquals(YearMonth.of(2026, 8), r("the August summary")!!.yearMonth, "August == now -> this year")
        assertEquals(YearMonth.of(2025, 10), r("the october retro")!!.yearMonth, "October > August -> last year")
        assertEquals(YearMonth.of(2026, 2), r("back in feb")!!.yearMonth, "abbreviation feb -> this-year February")
    }

    @Test
    fun absoluteWinsOverRelativeInPrecedence() {
        // A cue carrying BOTH an absolute date and a relative phrase resolves via the higher-precedence
        // absolute rule, not the relative one.
        val a = r("2023-04 上个月我们聊过")!!
        assertEquals(YearMonth.of(2023, 4), a.yearMonth, "absolute YYYY-MM wins over 上个月")
        assertEquals(TemporalAnchor.Kind.POINT, a.kind)
    }

    @Test
    fun invalidAbsoluteMonthFallsThroughToAbstain() {
        // Month 13 fails the 1..12 guard and, with no other grammar, the resolver abstains.
        assertNull(r("2025-13 的计划"), "an out-of-range absolute month is not a valid anchor")
    }

    @Test
    fun backReferenceRangeUsesMostRecentFocusAndAbstainsWhenEmpty() {
        assertNull(r("那会儿还有什么别的"), "bare back-reference + empty focus stack => ABSTAIN")
        val a = r("那会儿还有什么别的", focus = listOf(YearMonth.of(2025, 6), YearMonth.of(2024, 1)), margin = 2)!!
        assertEquals(YearMonth.of(2025, 6), a.yearMonth, "most-recent focus (stack head) wins")
        assertEquals(TemporalAnchor.Kind.RANGE, a.kind)
        assertEquals(2, a.marginMonths)
        assertEquals(
            setOf("2025-04", "2025-05", "2025-06", "2025-07", "2025-08"),
            a.window(),
            "margin=2 => a 5-month inclusive window",
        )
    }
}
