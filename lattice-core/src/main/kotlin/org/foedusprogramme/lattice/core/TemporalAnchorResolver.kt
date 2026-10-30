package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Resolves a cue into a [TemporalAnchor] by DETERMINISTIC calendar grammar — the embedding is never
 * consulted about time. First match wins, in a fixed precedence; no match => `null` (ABSTAIN, so the
 * dispatcher falls through to plain semantic recall — the always-safe default).
 *
 * Precedence:
 *  1. ABSOLUTE  `YYYY-MM` / `YYYY/MM` / `YYYY年M(月)`      -> POINT / HIGH
 *  2. RELATIVE  N周前 / N天前 / 上周 / 这周 / 昨天 / 前天 / 前几天 / N weeks ago / N days ago / last week / this week /
 *               yesterday / a few days ago -> POINT / HIGH with a DAY window (said or happened then);
 *               去年X月 / N个月前 / 上个月 / 这个月 / 去年 / last month / N months ago / last year / this month
 *               and bare month names (most-recent occurrence)  -> POINT / HIGH, resolved vs the QUERY clock
 *  3. BACK-REFERENCE  a bare PERIOD cue ("那阵子") -> RANGE / HIGH centered on the most-recent [focus]
 *               month; EMPTY focus stack => `null` (ABSTAIN — safe fall-through when nothing dated has
 *               flowed through the service-owned focus producer yet).
 *  4. LLM fallback (scoped out, always off) -> `null`.
 *
 * Pure: the clock ([now]) and [zone] are parameters (the service owns the user's zone), not fields.
 */
object TemporalAnchorResolver {

    private val ABSOLUTE_RX = Regex("\\b(\\d{4})[-/年]\\s*(\\d{1,2})\\b")
    private val CN_LAST_YEAR_MONTH = Regex("去年\\s*([0-9〇零一二两三四五六七八九十]{1,3})\\s*月")
    private val CN_MONTHS_AGO = Regex("([0-9〇零一二两三四五六七八九十]{1,3})\\s*个月前")
    private val CN_LAST_MONTH = Regex("上个月|上月")
    private val CN_THIS_MONTH = Regex("这个月|本月")
    private val CN_LAST_YEAR = Regex("去年")
    private val EN_MONTHS_AGO = Regex("\\b(\\d{1,3})\\s+months?\\s+ago\\b", RegexOption.IGNORE_CASE)

    // Day / week grammar: a count of weeks or days back, last / this week, yesterday, a few days ago.
    internal const val EN_COUNT =
        "\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|a couple of|a few|several"
    private val EN_UNITS_AGO = Regex("\\b($EN_COUNT)\\s+(weeks?|days?)\\s+ago\\b", RegexOption.IGNORE_CASE)
    private val EN_LAST_WEEK = Regex("\\b(?:last|past|previous) week\\b", RegexOption.IGNORE_CASE)
    private val EN_THIS_WEEK = Regex("\\bthis week\\b", RegexOption.IGNORE_CASE)
    private val EN_DAY_BEFORE_YESTERDAY = Regex("\\bthe day before yesterday\\b", RegexOption.IGNORE_CASE)
    private val EN_YESTERDAY = Regex("\\byesterday\\b", RegexOption.IGNORE_CASE)
    private val CN_UNITS_AGO = Regex("([0-9〇零一二两三四五六七八九十几]{1,3})\\s*个?(周|星期|礼拜|天)前")
    // Guarded against the words they sit inside: 目前天气, 提前天数, 这周围.
    private val CN_LAST_WEEK = Regex("上周(?!围)|上个?星期|上个?礼拜")
    private val CN_THIS_WEEK = Regex("这周(?![围边])|本周|这个?星期|这个?礼拜")
    private val CN_DAY_BEFORE_YESTERDAY = Regex("(?<![目之提以当眼面跟空])前天")

    /** Every day / week token above, for [TemporalIntentClassifier] (the two must agree). */
    internal const val DAY_TOKENS = "[0-9〇零一二两三四五六七八九十几]{1,3}\\s*个?(?:周|星期|礼拜|天)前" +
        "|上周(?!围)|上个?星期|上个?礼拜|这周(?![围边])|本周|这个?星期|这个?礼拜|昨天|昨日|(?<![目之提以当眼面跟空])前天|前几天" +
        "|\\b(?:$EN_COUNT)\\s+(?:weeks?|days?)\\s+ago\\b|\\b(?:last|past|previous|this) week\\b|\\byesterday\\b" +
        "|\\bthe other day\\b"
    private val CN_YESTERDAY = Regex("昨天|昨日")
    private val CN_FEW_DAYS_AGO = Regex("前几天|\\ba few days ago\\b|\\bthe other day\\b", RegexOption.IGNORE_CASE)
    private val EN_COUNT_WORD = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "a couple of" to 2, "a few" to 3,
        "several" to 3,
    )
    private val EN_LAST_MONTH = Regex("\\blast month\\b", RegexOption.IGNORE_CASE)
    private val EN_THIS_MONTH = Regex("\\bthis month\\b", RegexOption.IGNORE_CASE)
    private val EN_LAST_YEAR = Regex("\\blast year\\b", RegexOption.IGNORE_CASE)
    private const val EN_MONTHS =
        "january|february|march|april|may|june|july|august|september|october|november|december" +
            "|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"
    private val EN_MONTH_RX = Regex("\\b($EN_MONTHS)\\b", RegexOption.IGNORE_CASE)

    /**
     * A bare English month word anchors a POINT ONLY when it is NOT standalone. A common-word month —
     * "may" (modal), "march" (verb), "august" (adjective) — must be accompanied by a day, a year, or an
     * explicit temporal determiner/preposition ("in august", "the December", "March 3", "3 march",
     * "march 2025") to be read as a date. This guards the false-positive where an innocuous English
     * sentence produced a spurious month anchor. CN 月 cues (数字+月) resolve on the CN rules above and are
     * untouched by this gate.
     */
    private val EN_MONTH_CONTEXT = Regex(
        "\\b(?:in|on|by|since|during|until|till|before|after|around|early|late|mid|last|this|next|the|that)\\s+(?:$EN_MONTHS)\\b" +
            "|\\b(?:$EN_MONTHS)\\s+\\d{1,2}(?:st|nd|rd|th)?\\b" +
            "|\\b(?:$EN_MONTHS)\\s+\\d{4}\\b" +
            "|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:$EN_MONTHS)\\b" +
            "|\\b\\d{4}\\s+(?:$EN_MONTHS)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val CN_DIGIT = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val EN_MONTH = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9, "october" to 10,
        "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )

    fun resolve(
        cue: String,
        focus: List<YearMonth>,
        now: Instant,
        zone: ZoneId,
        marginMonths: Int = 1,
    ): TemporalAnchor? {
        val c = cue.lowercase()
        val cur = YearMonth.from(now.atZone(zone).toLocalDate())

        // 1. ABSOLUTE
        ABSOLUTE_RX.find(cue)?.let { m ->
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            if (mo in 1..12) return point(YearMonth.of(y, mo))
        }

        // 2. RELATIVE (resolved against the query clock, in the user's zone). Days and weeks first: their window is
        //    a few days wide, because "four weeks ago" is how a person rounds "around the end of February".
        dayWindow(cue, c, now.atZone(zone).toLocalDate())?.let { return it }
        CN_LAST_YEAR_MONTH.find(cue)?.let { m ->
            parseCnInt(m.groupValues[1])?.let { mo -> if (mo in 1..12) return point(cur.minusYears(1).withMonth(mo)) }
        }
        CN_MONTHS_AGO.find(cue)?.let { m ->
            parseCnInt(m.groupValues[1])?.let { n -> return point(cur.minusMonths(n.toLong())) }
        }
        EN_MONTHS_AGO.find(c)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { n -> return point(cur.minusMonths(n.toLong())) }
        }
        if (CN_LAST_MONTH.containsMatchIn(cue) || EN_LAST_MONTH.containsMatchIn(c)) return point(cur.minusMonths(1))
        if (CN_THIS_MONTH.containsMatchIn(cue) || EN_THIS_MONTH.containsMatchIn(c)) return point(cur)
        // Bare month name => the most-recent occurrence of that month (this year if not future, else last
        // year) — but ONLY when it is not standalone: a common-word month ("may"/"march"/"august") needs a
        // day, year, or explicit temporal context ([EN_MONTH_CONTEXT]) to anchor; otherwise abstain.
        if (EN_MONTH_CONTEXT.containsMatchIn(c)) {
            EN_MONTH_RX.find(c)?.let { m ->
                EN_MONTH[m.groupValues[1].lowercase()]?.let { mo ->
                    val y = if (mo <= cur.monthValue) cur.year else cur.year - 1
                    return point(YearMonth.of(y, mo))
                }
            }
        }
        if (CN_LAST_YEAR.containsMatchIn(cue) || EN_LAST_YEAR.containsMatchIn(c)) return point(cur.minusYears(1))

        // 3. BACK-REFERENCE (bare PERIOD cue) — center on the most-recent focus month; empty stack => ABSTAIN.
        if (TemporalIntentClassifier.PERIOD_RX.containsMatchIn(c)) {
            val f = focus.firstOrNull() ?: return null
            return TemporalAnchor(f, TemporalAnchor.Kind.RANGE, TemporalAnchor.Confidence.HIGH, marginMonths)
        }

        // 4. LLM fallback (scoped out / always off): grammar miss => abstain.
        return null
    }

    /** The day window of a day- or week-level cue, or null when the cue has none. */
    private fun dayWindow(cue: String, c: String, today: LocalDate): TemporalAnchor? {
        fun around(center: LocalDate, margin: Long) = window(center.minusDays(margin), center.plusDays(margin).let { if (it.isAfter(today)) today else it })
        fun weeksAgo(n: Int) = around(today.minusWeeks(n.toLong()), 2L + n / 2)
        fun daysAgo(n: Int) = around(today.minusDays(n.toLong()), 1L + n / 7)

        CN_UNITS_AGO.find(cue)?.let { m ->
            val n = if (m.groupValues[1] == "几") 3 else parseCnInt(m.groupValues[1]) ?: return@let
            return if (m.groupValues[2] == "天") daysAgo(n) else weeksAgo(n)
        }
        EN_UNITS_AGO.find(c)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: EN_COUNT_WORD[m.groupValues[1]] ?: return@let
            return if (m.groupValues[2].startsWith("day")) daysAgo(n) else weeksAgo(n)
        }
        if (CN_DAY_BEFORE_YESTERDAY.containsMatchIn(cue) || EN_DAY_BEFORE_YESTERDAY.containsMatchIn(c)) {
            return window(today.minusDays(2), today.minusDays(2))
        }
        if (CN_YESTERDAY.containsMatchIn(cue) || EN_YESTERDAY.containsMatchIn(c)) return window(today.minusDays(1), today.minusDays(1))
        if (CN_FEW_DAYS_AGO.containsMatchIn(cue)) return window(today.minusDays(7), today.minusDays(2))
        if (CN_LAST_WEEK.containsMatchIn(cue) || EN_LAST_WEEK.containsMatchIn(c)) return around(today.minusDays(7), 3)
        if (CN_THIS_WEEK.containsMatchIn(cue) || EN_THIS_WEEK.containsMatchIn(c)) return window(today.minusDays(6), today)
        return null
    }

    private fun window(from: LocalDate, to: LocalDate): TemporalAnchor {
        val middle = from.plusDays(java.time.temporal.ChronoUnit.DAYS.between(from, to) / 2)
        return TemporalAnchor(YearMonth.from(middle), TemporalAnchor.Kind.POINT, TemporalAnchor.Confidence.HIGH, fromDay = from, toDay = to)
    }

    private fun point(ym: YearMonth) =
        TemporalAnchor(ym, TemporalAnchor.Kind.POINT, TemporalAnchor.Confidence.HIGH)

    /** Parse a small integer written in ASCII digits or CN numerals (一..九, 十, 十一..十二, 二十三, ...). */
    private fun parseCnInt(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val shi = s.indexOf('十')
        if (shi < 0) return if (s.length == 1) CN_DIGIT[s[0]] else null
        val tensPart = s.substring(0, shi)
        val onesPart = s.substring(shi + 1)
        val tens = if (tensPart.isEmpty()) 1 else CN_DIGIT[tensPart.last()] ?: return null
        val ones = if (onesPart.isEmpty()) 0 else CN_DIGIT[onesPart.last()] ?: return null
        return tens * 10 + ones
    }
}
