package org.foedusprogramme.lattice.core

import kotlin.math.abs

/**
 * The model-facing markers of a recall block ([RecallFormatter]) in one language: the scene line, the in-progress
 * note, the past-session lead, quote attribution, the thread annotations, the period tag, said / due dates, relative
 * time and the legend. Built from the `lattice.recall.*` keys of [MemoryTexts].
 *
 * Rendered in the turn's language. The zh set is pinned byte for byte by its tests: the model reads these markers in
 * every recall block.
 */
class RecallMarkers private constructor(
    val language: Language,
    private val texts: MemoryTexts,
) {
    private fun string(key: String) = texts.text(key, language)
    private fun fill(key: String, vararg args: Pair<String, Any?>) = texts.text(key, language, *args)

    private val sceneUntitled = string("lattice.recall.scene.untitled")
    private val inProgress = string("lattice.recall.scene.in_progress")
    private val separator = string("lattice.recall.thread.separator")
    private val spanSeparator = string("lattice.recall.date.span_separator")

    /** The lead of a whole-session summary (an episode node): it reads as a past session, not a discrete fact. */
    val episode: String = string("lattice.recall.episode")

    /** How the assistant's own quotes are attributed: in the first person, since the block is fed back to it. */
    val quoteSelf: String = string("lattice.recall.quote.self")

    /** How the human's quotes are attributed when the caller knows no name. */
    val quoteUser: String = string("lattice.recall.quote.user")

    /** The legend for the said / due date tags: alone, or appended to another legend on the same line. */
    val legendDates: String = string("lattice.recall.legend.dates")
    val legendDatesSuffix: String = string("lattice.recall.legend.dates_suffix")

    /** The fixed text every settled-thread annotation contains (zh `后来已了结）`, en `(later resolved: `). */
    val settledMark: String = string("lattice.recall.thread.settled_mark")

    val legendScene: String = string("lattice.recall.legend.scene")
    val legendThread: String = string("lattice.recall.legend.thread")
    val legendSceneAndThread: String = string("lattice.recall.legend.scene_and_thread")

    /** The legend explaining the period tag alone (a block without scene lines or thread notes). */
    val legendPeriod: String = string("lattice.recall.legend.period")

    /** The period explanation appended to the scene / thread legend, on the same line. */
    val legendPeriodSuffix: String = string("lattice.recall.legend.period_suffix")

    /**
     * A scene hub's lead: `〔场景：标题 · 起止日期〕` (zh) / `[Scene: title · dates] ` (en); the head alone when the
     * title is blank, one date for a single-day scene, the in-progress note inside the bracket while the scene is
     * open. The date range format (`from～to`) is the same in every language.
     */
    fun sceneTag(title: String, dateFrom: String?, dateTo: String?, inProgress: Boolean): String {
        val dates = when {
            dateFrom == null -> dateTo
            dateTo == null || dateTo == dateFrom -> dateFrom
            else -> "$dateFrom～$dateTo"
        }
        val t = title.trim()
        val head = if (t.isEmpty()) sceneUntitled else fill("lattice.recall.scene.titled", "title" to t)
        val body = listOfNotNull(head, dates).joinToString(" · ")
        return fill("lattice.recall.scene.wrap", "body" to body + if (inProgress) this.inProgress else "")
    }

    /** A verbatim quote under its memory, attributed to [speaker] (null or blank = unattributed). */
    fun quote(text: String, speaker: String?): String =
        if (speaker.isNullOrEmpty()) fill("lattice.recall.quote.unattributed", "text" to text)
        else fill("lattice.recall.quote", "text" to text, "speaker" to speaker)

    /**
     * What follows a hit's content: the closed threads it set up, then those still open — empty unless at least one
     * is closed. [closed] / [open] are the threads' labels (name, else description); each is whitespace-normalized,
     * cut at [RecallFormatter.THREAD_NAME_CHARS] code points (never inside a surrogate pair), de-duplicated, and at
     * most [RecallFormatter.MAX_NAMED_THREADS] are named per part.
     */
    fun threadNote(closed: List<String>, open: List<String>): String {
        if (closed.isEmpty()) return ""
        val settledPart = fill("lattice.recall.thread.settled", "names" to names(closed))
        return if (open.isEmpty()) settledPart else settledPart + fill("lattice.recall.thread.open", "names" to names(open))
    }

    /**
     * A turn-end recall hit's period, `〔时期：名字〕` (zh) / ` [Period: name]` (en), appended after the hit's content.
     * [label] is used as given: the caller passes the normalized name, or a neutral date label.
     */
    fun periodTag(label: String): String = fill("lattice.recall.period.tag", "label" to label)

    /** When a memory was said: one day, or first..latest mention. */
    fun said(from: String, to: String): String =
        fill("lattice.recall.date.said", "span" to if (from == to) from else from + spanSeparator + to)

    /** A plan's / goal's target date. */
    fun due(date: String): String = fill("lattice.recall.date.due", "date" to date)

    /**
     * How a person says how long ago a day was, [daysAgo] calendar days before today (negative = ahead): today /
     * yesterday / N days, then about N weeks (up to 8), about N months, about N years (zh with Chinese numerals and
     * 两 for two; 约一周后 style ahead). Only ever shown NEXT TO the absolute date, never instead of it.
     */
    fun relativeTime(daysAgo: Long): String {
        val past = daysAgo >= 0
        val days = abs(daysAgo)
        fun pick(ago: String, ahead: String) = if (past) ago else ahead
        fun counted(ago: String, ahead: String, n: Long) =
            fill("lattice.recall.relative." + pick(ago, ahead), "count" to count(n))
        fun fixed(ago: String, ahead: String) = string("lattice.recall.relative." + pick(ago, ahead))
        return when {
            days == 0L -> string("lattice.recall.relative.today")
            days == 1L -> fixed("yesterday", "tomorrow")
            days < DAYS_PER_WEEK -> counted("days_ago", "days_ahead", days)
            days < WEEKS_UNTIL_DAYS -> {
                val weeks = (days + DAYS_PER_WEEK / 2) / DAYS_PER_WEEK
                if (weeks == 1L) fixed("week_ago", "week_ahead") else counted("weeks_ago", "weeks_ahead", weeks)
            }
            else -> {
                val months = Math.round(days / DAYS_PER_MONTH)
                if (months < 12) return counted("months_ago", "months_ahead", months)
                val years = maxOf(1L, Math.round(days / DAYS_PER_YEAR))
                if (years == 1L) fixed("year_ago", "year_ahead") else counted("years_ago", "years_ahead", years)
            }
        }
    }

    /** The one legend line explaining the markers the block shows, or null when it shows none. */
    fun legend(scene: Boolean, thread: Boolean): String? = when {
        scene && thread -> legendSceneAndThread
        scene -> legendScene
        thread -> legendThread
        else -> null
    }

    /**
     * The one legend line for a block that also shows period tags: the scene / thread legend with the period
     * explanation appended, or the period legend alone; unchanged when [period] is false.
     */
    fun legend(scene: Boolean, thread: Boolean, period: Boolean): String? {
        val base = legend(scene, thread)
        return when {
            !period -> base
            base == null -> legendPeriod
            else -> base + legendPeriodSuffix
        }
    }

    /** [legend] plus, when the block shows said / due tags, their explanation on the same line. */
    fun legend(scene: Boolean, thread: Boolean, period: Boolean, dates: Boolean): String? {
        val base = legend(scene, thread, period)
        return when {
            !dates -> base
            base == null -> legendDates
            else -> base + legendDatesSuffix
        }
    }

    /** A count in this language: Chinese numerals (两 for two) in Chinese up to 99, digits otherwise. */
    private fun count(n: Long): String = if (isChinese(language)) chineseCount(n) else n.toString()

    private fun names(labels: List<String>): String {
        val distinct = labels.map(::threadName).distinct()
        val shown = distinct.take(RecallFormatter.MAX_NAMED_THREADS)
            .joinToString(separator) { fill("lattice.recall.thread.name", "name" to it) }
        return if (distinct.size > RecallFormatter.MAX_NAMED_THREADS) fill("lattice.recall.thread.more", "names" to shown) else shown
    }

    private fun threadName(label: String): String {
        val s = label.trim().replace(WHITESPACE, " ")
        val max = RecallFormatter.THREAD_NAME_CHARS
        // By code point: a cut inside a surrogate pair would leave a lone surrogate, which a UTF-8 encoder turns into
        // '?' — the block the model sees would then differ from the one a host strips again.
        if (s.codePointCount(0, s.length) <= max) return s
        return fill("lattice.recall.thread.truncated", "text" to s.substring(0, s.offsetByCodePoints(0, max)))
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val DAYS_PER_WEEK = 7L

        /** From this many days on, months: weeks run up to "about 8 weeks" (56 days is 约八周前). */
        private const val WEEKS_UNTIL_DAYS = 60L

        private const val DAYS_PER_MONTH = 30.4375
        private const val DAYS_PER_YEAR = 365.25

        private const val CN_DIGITS = "零一二三四五六七八九"

        /** [n] as a Chinese count: 两 for two, 十一, 二十二; digits from 100 on. */
        internal fun chineseCount(n: Long): String = when (n) {
            2L -> "两"
            in 0L..9L -> CN_DIGITS[n.toInt()].toString()
            in 10L..99L -> {
                val tens = (n / 10).toInt()
                val ones = (n % 10).toInt()
                (if (tens == 1) "" else CN_DIGITS[tens].toString()) + "十" + (if (ones == 0) "" else CN_DIGITS[ones].toString())
            }
            else -> n.toString()
        }

        internal fun isChinese(language: Language): Boolean =
            language.tag.replace('_', '-').substringBefore('-').equals("zh", ignoreCase = true)

        /** Markers for [language] read from [texts]. */
        fun of(texts: MemoryTexts, language: Language): RecallMarkers = RecallMarkers(language, texts)

        /** The bundled English markers. */
        val EN: RecallMarkers by lazy { of(BundledTexts, Language.EN) }

        /** The bundled Chinese markers. */
        val ZH: RecallMarkers by lazy { of(BundledTexts, Language.ZH) }
    }
}
