package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Renders spreading-activation recall hits into the memory block injected into the prompt. Each hit shows its gist;
 * if the memory has EVIDENCE-linked verbatim quotes (words the user or the assistant actually said), those are shown
 * beneath with speaker attribution so the model can reproduce them exactly.
 *
 * The formatter reads nothing but the hits and a [Lookup] the caller fills from the graph, so it never touches the
 * graph itself; the caller runs it on the graph thread, right after recall, with a lookup over the live graph.
 */
object RecallFormatter {

    /** Max code points of a thread name (or description) in an annotation (longer ones end in `…`). */
    const val THREAD_NAME_CHARS = 20

    /** Max threads named per annotation part (more end in `等` / `, etc.`). */
    const val MAX_NAMED_THREADS = 3

    /** The threads a memory set up, by state: [closed] (resolved later) and [open] (still open, or stale). */
    data class Threads(val closed: List<ThreadRecord>, val open: List<ThreadRecord>) {
        companion object {
            val NONE = Threads(emptyList(), emptyList())
        }
    }

    /**
     * What the block needs from the graph beyond the hits. Every method has an empty default, so a caller without
     * scenes, threads or periods implements none of them and gets the plain block.
     * [org.foedusprogramme.lattice.DefaultLattice] implements it over the same-named [LatticeGraph] methods.
     */
    interface Lookup {
        /**
         * The verbatim quote engrams EVIDENCE-linked to [id], in the graph's neighbour order: the
         * EVIDENCE neighbours of [id] that carry a speaker.
         */
        fun evidenceQuotes(id: Long): List<Engram> = emptyList()

        /** The scene whose hub is [hubId], if any (title, dates, in-progress). */
        fun sceneOfHub(hubId: Long): SceneRecord? = null

        /**
         * The threads memory [id] set up — directly, through an ABSTRACTS child, or (for a scene hub) through a
         * scene member, except threads its own scene closed.
         */
        fun threadsOf(id: Long): Threads = Threads.NONE

        /**
         * The RESOLVE memories of the closed threads [setupId] set up, in thread order.
         */
        fun closedResolvesOf(setupId: Long): List<Long> = emptyList()

        /**
         * The live memory [id], or null. Read only for the dates of a set-up's RESOLVE memories
         * ([closedResolvesOf]) when the block's tags are labelled ([RecallDating.labels]). Lattice:
         * `LatticeGraph.engram(id)`.
         */
        fun engram(id: Long): Engram? = null

        /** The scenes memory [id] is a member of. */
        fun scenesOf(id: Long): Collection<SceneRecord> = emptyList()

        /** The period scene [sceneId] belongs to. */
        fun periodOfScene(sceneId: Long): PeriodRecord? = null

        /**
         * The label a period shows in a recall tag, or null for no tag: its name when the name may be shown, else a
         * neutral date label — never a name taken from a scene that is untrusted now. [TrustedPeriodLabels]
         * shows the name when `periodNameShown(period.namingSceneIds, graph.periodScenes(id), trust)` holds,
         * with trust from `graph.sceneTrust` only (a scene without a cached verdict is untrusted), the name through
         * `PeriodFields.normalize` and cut at `PeriodFields.MAX_NAME_CODE_POINTS`, else
         * `PeriodFields.normalize(PeriodFormer.neutralDateName(period.dateFrom, period.dateTo, language))`.
         */
        fun periodLabel(period: PeriodRecord): String? = null

        companion object {
            /** No scenes, threads, periods or quotes. */
            val NONE: Lookup = object : Lookup {}
        }
    }

    /**
     * The block for [hits], or "" when there are none.
     *
     * @param temporalLayer the graph's temporal-layer switch (`LatticeGraph.Config.temporalLayer`): every date tag
     *   needs it, so a rollback over still-populated dates renders none.
     * @param userLabel who the human's quotes are attributed to: the user's name when known, else
     *   [RecallMarkers.quoteUser]. The assistant's own quotes are attributed in the first person
     *   ([RecallMarkers.quoteSelf]): the block is fed back to the assistant, so no framework or persona name leaks in.
     * @param markers the turn's language.
     * @param dating how dates show and whether periods are named; [RecallDating.LEGACY] is the block with scene
     *   episodes off.
     */
    fun format(
        hits: List<Activation>,
        markers: RecallMarkers,
        temporalLayer: Boolean,
        lookup: Lookup = Lookup.NONE,
        userLabel: String? = null,
        dating: RecallDating = RecallDating.LEGACY,
    ): String {
        if (hits.isEmpty()) return ""
        val user = userLabel?.takeIf { it.isNotBlank() } ?: markers.quoteUser
        // Labelled tags need the temporal layer, like every date tag: a rollback renders none, and no legend for them.
        val labelled = dating.labels && temporalLayer
        val periodChat = dating.turnEnd?.chatKey
        val periodLabels = HashMap<Long, String?>()
        var sawScene = false
        var sawThread = false
        var sawPeriod = false
        var sawDates = false
        val body = ordered(hits, lookup).joinToString("\n") { hit ->
            val scene = if (hit.engram.isEpisodeHub) lookup.sceneOfHub(hit.engram.id) else null
            val lead = lead(hit, scene, temporalLayer, dating, markers, lookup)
            val annotation = annotation(hit, lookup, markers)
            // Appended only: the tag never changes which hits show, their order or anything recall learned.
            val period = if (periodChat == null) "" else periodTag(hit, scene, lookup, periodChat, markers, periodLabels)
            if (scene != null) sawScene = true
            if (annotation.isNotEmpty()) sawThread = true
            if (period.isNotEmpty()) sawPeriod = true
            if (scene == null && temporalLayer && dating.dayPrecision && (hit.engram.saidFrom != null || hit.engram.dueDate != null)) {
                sawDates = true
            }
            // Labelled, every date says what it is the date of, so the legend explains a scene's or an event's too.
            if (labelled && (if (scene != null) scene.dateFrom != null || scene.dateTo != null else hit.engram.eventDate != null)) {
                sawDates = true
            }
            val gist = "$lead${hit.engram.content.trim()}$annotation$period"
            val quotes = lookup.evidenceQuotes(hit.engram.id)
            if (quotes.isEmpty()) {
                gist
            } else {
                val shownSaid = if (scene == null) saidDays(hit.engram, dating.saidZone(), temporalLayer) else null
                gist + quotes.joinToString("") { q ->
                    val who = when (q.speaker) {
                        // A named person's quote (a chat that names its speakers) carries their name.
                        Speaker.USER -> q.speakerName?.takeIf { it.isNotBlank() } ?: user
                        Speaker.ASSISTANT -> markers.quoteSelf
                        null -> null
                    }
                    val said = if (labelled) quoteSaid(q, shownSaid, temporalLayer, dating, markers) else ""
                    if (said.isNotEmpty()) sawDates = true
                    "\n    " + markers.quote(q.content.trim(), who) + said
                }
            }
        }
        // ONE legend line under the hits explaining the markers to the model, only when the block shows one (a recall
        // without scenes, threads, periods or said / due tags is unchanged). It lives inside the recall block rather
        // than a static system prompt so a cached prompt prefix never changes, and it is in the turn's language, like
        // the markers it explains. The block never holds a blank line: the tags are single-line.
        val legend = markers.legend(sawScene, sawThread, sawPeriod, sawDates, labelled, relative = labelled && dating.relative) ?: return body
        // In words, a said date also says what a relative time inside the memory counts from: a reader took
        // "three days ago" from today, or from the event date, and counted it twice.
        val relative = when {
            !sawDates || !dating.inWords -> ""
            labelled -> markers.legendRelativeLabelledSuffix
            else -> markers.legendRelativeSuffix
        }
        return "$body\n$legend$relative"
    }

    /**
     * What precedes a hit's content:
     *  - a SCENE hub: `〔场景：标题 · 起止日期〕`, one date for a single-day scene, `（进行中）` while the scene is still
     *    open; no date tag, the range already dates it ([RecallMarkers.sceneTag]);
     *  - an episode (whole-session summary): [RecallMarkers.episode], so the model reads it as "a past session", not
     *    a discrete fact;
     *  - anything else: the temporal-layer date tag, if any.
     */
    private fun lead(
        hit: Activation,
        scene: SceneRecord?,
        temporalLayer: Boolean,
        dating: RecallDating,
        markers: RecallMarkers,
        lookup: Lookup,
    ): String {
        if (scene != null) {
            val from = scene.dateFrom?.let { if (dating.inWords) markers.isoInWords(it) else it }
            val to = scene.dateTo?.let { if (dating.inWords) markers.isoInWords(it) else it }
            return "- ${markers.sceneTag(scene.title, from, to, scene.inProgress, labelled = dating.labels && temporalLayer)}"
        }
        val prefix = if (hit.engram.isEpisode) "- ${markers.episode}" else "- "
        if (dating.labels && temporalLayer) return "$prefix${labelledPrefix(hit.engram, dating, markers, lookup)}"
        return "$prefix${datePrefix(hit, temporalLayer, dating, markers)}"
    }

    /**
     * A dated hit's structured event date as a leading `[YYYY-MM] ` tag, so the answering model can order recalled
     * facts and answer a before-after question WITHOUT the date being baked into (and perturbing) the embedded
     * content. Empty for an undated engram.
     *
     * Gated on the temporal-layer SWITCH, not on the data: a rollback over a still-populated `event_date` renders no
     * tag at all.
     *
     * With [RecallDating.dayPrecision] a complete `YYYY-MM-DD` shows the day (`[2026-08-03] `), and on the turn-end
     * recall ([RecallDating.turnEnd]) also how long ago that day was, counted in the runtime zone at render time
     * (`[2026-08-03 · 约八周前] `). A month-only date stays `[YYYY-MM] `.
     */
    private fun datePrefix(hit: Activation, enabled: Boolean, dating: RecallDating, markers: RecallMarkers): String {
        if (!enabled) return ""
        val e = hit.engram
        // With day precision a memory that knows when it was said (or a plan's target) shows that too; one that knows
        // neither renders exactly as before.
        if (dating.dayPrecision && (e.saidFrom != null || e.dueDate != null)) return spanPrefix(e, dating, markers)
        val date = e.eventDate ?: return ""
        val day = if (dating.dayPrecision) fullDay(date) else null
        if (day == null) return "[${if (dating.inWords) markers.isoInWords(date.take(7)) else date.take(7)}] "
        val shown = if (dating.inWords) markers.day(day) else day.toString()
        val turnEnd = dating.turnEnd ?: return "[$shown] "
        val daysAgo = ChronoUnit.DAYS.between(day, LocalDate.ofInstant(turnEnd.now, turnEnd.zone))
        return "[$shown · ${markers.relativeTime(daysAgo)}] "
    }

    /**
     * `[event day · due <target> · said <first>～<latest>] ` (each part only when known; a single-day span shows one
     * day). On the turn-end recall the relative time follows the date it counts from: the event day when the memory
     * has one, else the latest mention.
     */
    private fun spanPrefix(e: Engram, dating: RecallDating, markers: RecallMarkers): String {
        val parts = ArrayList<String>(5)
        val turnEnd = dating.turnEnd
        fun relative(day: LocalDate): String? =
            turnEnd?.let { markers.relativeTime(ChronoUnit.DAYS.between(day, LocalDate.ofInstant(it.now, it.zone))) }
        // In words ([RecallDating.inWords]): "19 May 2023", "May 2023", "2023"; the said days with their weekday.
        fun shown(iso: String): String {
            val day = fullDay(iso)
            if (!dating.inWords) return day?.toString() ?: iso.take(7)
            return day?.let(markers::day) ?: markers.isoInWords(iso.take(7))
        }
        val eventDay = e.eventDate?.let(::fullDay)
        e.eventDate?.let { parts += shown(it) }
        eventDay?.let { day -> relative(day)?.let { parts += it } }
        e.dueDate?.let { parts += markers.due(shown(it)) }
        val zone = turnEnd?.zone ?: dating.zone
        val from = e.saidFrom?.let { LocalDate.ofInstant(it, zone) }
        val to = (e.saidTo ?: e.saidFrom)?.let { LocalDate.ofInstant(it, zone) }
        if (from != null && to != null) {
            if (dating.inWords) {
                parts += markers.said(markers.saidDay(from), if (from == to) markers.saidDay(from) else markers.saidDay(to))
            } else {
                parts += markers.said(from.toString(), to.toString())
            }
            if (eventDay == null) relative(to)?.let { parts += it }
        }
        return if (parts.isEmpty()) "" else "[${parts.joinToString(" · ")}] "
    }

    /**
     * The labelled date tag ([RecallDating.labels]): every date says what it is the date of, so a bare or partial
     * event date can't be read past for the exact said date beside it, and a plan isn't read as done on the day it was
     * made. `[happened: <event> · due: <target> · resolved: <outcome> · mentioned: <first>..<latest>] `, each part only
     * when known; a year- or month-only event date is marked partial; the mention comes last, being the date a reader
     * most often answered with by mistake. On the turn-end recall the relative time follows the date it counts from:
     * the event day when the memory has one, else the latest mention.
     */
    private fun labelledPrefix(e: Engram, dating: RecallDating, markers: RecallMarkers, lookup: Lookup): String {
        val parts = ArrayList<String>(5)
        val turnEnd = dating.turnEnd
        fun relative(day: LocalDate): String? =
            turnEnd?.let { markers.relativeTime(ChronoUnit.DAYS.between(day, LocalDate.ofInstant(it.now, it.zone))) }
        val eventDay = e.eventDate?.let(::fullDay)
        val said = saidDays(e, dating.saidZone(), enabled = true)
        e.eventDate?.let { event ->
            val shown = markers.partial(event, shownDate(event, dating, markers))
            when {
                !dating.relative -> parts += markers.happened(shown)
                // Mention-anchored: a month-only date in the month of the first mention says no more than the mention,
                // and beside it a reader took the 1st of the month for the day.
                sameMonthAsMention(event, said) -> Unit
                // A state that has lasted "for three months" by the mention began then: since, not happened.
                ongoingSince(e.content, event, said) -> parts += markers.since(shown)
                else -> parts += markers.happened(shown)
            }
        }
        eventDay?.let { day -> relative(day)?.let { parts += it } }
        e.dueDate?.let { parts += markers.dueLabelled(shownDate(it, dating, markers)) }
        outcome(e, dating, markers, lookup)?.let { parts += it }
        if (said != null) {
            val (from, to) = said
            parts += if (dating.inWords) markers.mentioned(markers.saidDay(from), markers.saidDay(to)) else markers.mentioned(from.toString(), to.toString())
            if (eventDay == null) relative(to)?.let { parts += it }
        }
        return if (parts.isEmpty()) "" else "[${parts.joinToString(" · ")}] "
    }

    /** A month-only [event] date in the month of the first mention ([said]): it adds nothing to the mention. */
    private fun sameMonthAsMention(event: String, said: Pair<LocalDate, LocalDate>?): Boolean {
        if (said == null || !ISO_MONTH.matches(event.trim())) return false
        return said.first.toString().startsWith(event.trim())
    }

    /**
     * Whether [content] tells a state that had lasted some time when it was first mentioned ("has been living in
     * Harajuku for three months", "has been doing yoga for about 6 weeks") and [event] is where that time began: the
     * first mention minus the duration, at the event date's own precision, give or take one unit (a month-only date one
     * month, a year-only one year, a day a quarter of the duration and at least three days). English wording only; any
     * other memory keeps its `happened:`.
     */
    internal fun ongoingSince(content: String, event: String, said: Pair<LocalDate, LocalDate>?): Boolean {
        if (said == null || !ONGOING.containsMatchIn(content)) return false
        val m = DURATION.find(content) ?: return false
        val count = m.groups[2]?.value?.lowercase()?.let { COUNT_WORDS[it] ?: it.toIntOrNull() }
            ?: if (m.groups[1] != null) 1 else return false
        if (count <= 0) return false
        val unit = m.groups[3]!!.value.lowercase()
        val start = when (unit) {
            "day" -> said.first.minusDays(count.toLong())
            "week" -> said.first.minusWeeks(count.toLong())
            "month" -> said.first.minusMonths(count.toLong())
            else -> said.first.minusYears(count.toLong())
        }
        val e = event.trim()
        return when {
            ISO_YEAR.matches(e) -> abs(e.toInt() - start.year) <= 1
            ISO_MONTH.matches(e) -> {
                val ym = runCatching { YearMonth.parse(e) }.getOrNull() ?: return false
                abs(ChronoUnit.MONTHS.between(ym, YearMonth.from(start))) <= 1
            }
            else -> {
                val day = fullDay(e) ?: return false
                val span = ChronoUnit.DAYS.between(start, said.first)
                abs(ChronoUnit.DAYS.between(day, start)) <= maxOf(3L, span / 4)
            }
        }
    }

    private val ONGOING = Regex("""\b(?:has|have|had|'s|’s)\s+(?:been|lived|had|owned|used|worked|known|played|kept|run)\b""", RegexOption.IGNORE_CASE)
    private val DURATION = Regex(
        """\b(?:for|over)\s+(?:the\s+)?(?:(past|last)\s+)?(?:about\s+|around\s+|roughly\s+|nearly\s+|almost\s+|over\s+|more than\s+|just over\s+)?""" +
            """(?:(\d{1,3}|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|a few|several|a couple of)\s+)?(day|week|month|year)s?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COUNT_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "a few" to 3, "several" to 3, "a couple of" to 2,
    )
    private val ISO_MONTH = Regex("\\d{4}-\\d{2}")
    private val ISO_YEAR = Regex("\\d{4}")

    /**
     * When a set-up of a closed thread was settled, from its RESOLVE memories in thread order: the first one's event
     * date (`resolved: 25 February 2023`), else, when none has one, the first one's mention (`resolved: by Sat 25
     * February 2023`: it was settled by the day it was reported). Null for a memory that settled nothing, or whose
     * resolves carry no date.
     */
    private fun outcome(e: Engram, dating: RecallDating, markers: RecallMarkers, lookup: Lookup): String? {
        if (e.isEpisodeHub) return null
        val resolves = lookup.closedResolvesOf(e.id).mapNotNull(lookup::engram)
        resolves.firstNotNullOfOrNull { it.eventDate }?.let { return markers.resolved(markers.partial(it, shownDate(it, dating, markers))) }
        val reported = resolves.firstNotNullOfOrNull { saidDays(it, dating.saidZone(), enabled = true) }?.first ?: return null
        return markers.resolvedBy(if (dating.inWords) markers.saidDay(reported) else reported.toString())
    }

    /**
     * What follows a quote under a labelled hit: the day the words were said, when the line above doesn't already show
     * exactly that one day (it shows a span of several days, another day, or none, as a scene line does). A relative
     * time in the quote ("yesterday") then has its day next to it.
     */
    private fun quoteSaid(q: Engram, shown: Pair<LocalDate, LocalDate>?, temporalLayer: Boolean, dating: RecallDating, markers: RecallMarkers): String {
        val (from, to) = saidDays(q, dating.saidZone(), temporalLayer) ?: return ""
        if (shown != null && shown.first == shown.second && from == to && from == shown.first) return ""
        return if (dating.inWords) markers.quoteSaid(markers.saidDay(from), markers.saidDay(to)) else markers.quoteSaid(from.toString(), to.toString())
    }

    /** [e]'s said span as calendar days in [zone] (first, latest), or null when unknown or [enabled] is off. */
    private fun saidDays(e: Engram, zone: ZoneId, enabled: Boolean): Pair<LocalDate, LocalDate>? {
        if (!enabled) return null
        val from = e.saidFrom?.let { LocalDate.ofInstant(it, zone) } ?: return null
        val to = (e.saidTo ?: e.saidFrom)?.let { LocalDate.ofInstant(it, zone) } ?: return null
        return from to to
    }

    /** An ISO date as a tag shows it: in words, else a day as it is and anything else cut to its month or year. */
    private fun shownDate(iso: String, dating: RecallDating, markers: RecallMarkers): String {
        val day = fullDay(iso)
        if (!dating.inWords) return day?.toString() ?: iso.take(7)
        return day?.let(markers::day) ?: markers.isoInWords(iso.take(7))
    }

    private fun RecallDating.saidZone(): ZoneId = turnEnd?.zone ?: zone

    private val ISO_DAY = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** [date] as a calendar day when it is a complete, valid ISO `YYYY-MM-DD`; else null (shown as a month). */
    private fun fullDay(date: String): LocalDate? {
        val s = date.trim()
        if (!ISO_DAY.matches(s)) return null
        return try {
            LocalDate.parse(s)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * What follows a turn-end recall hit of chat [chatKey]: the period of its scene (`〔时期：…〕`), or nothing. The
     * scene is the hub's own scene for a scene hub, else the scenes the memory is a member of — only those of
     * [chatKey] (a memory shared with another chat's scene takes this chat's), the earliest ([SCENE_ORDER]) that is in
     * a period. A manual memory and a synthesized one carry none. [labels] caches each period's label for the
     * block.
     */
    private fun periodTag(
        hit: Activation,
        hubScene: SceneRecord?,
        lookup: Lookup,
        chatKey: String,
        markers: RecallMarkers,
        labels: MutableMap<Long, String?>,
    ): String {
        val engram = hit.engram
        if (engram.origin == Engram.Origin.MANUAL || engram.origin == Engram.Origin.SYNTHESIZED) return ""
        val scenes = if (hubScene != null) listOf(hubScene) else lookup.scenesOf(engram.id)
        val period = scenes.asSequence()
            .filter { it.chatKey == chatKey }
            .sortedWith(SCENE_ORDER)
            .firstNotNullOfOrNull { lookup.periodOfScene(it.sceneId) }
            ?: return ""
        val label = labels.getOrPut(period.id) { lookup.periodLabel(period) } ?: return ""
        return markers.periodTag(label)
    }

    /**
     * What follows a hit's content, naming its threads so a set-up is never read as still pending and a compound
     * memory is not read as wholly settled (zh shown):
     *  - `（「周末的惊喜」后来已了结）` on a SETUP memory of a CLOSED thread, on an ABSTRACTS parent whose children
     *    include one, and on a scene hub whose scene holds one (unless that scene closed it itself); several closed
     *    threads are listed `「A」、「B」`;
     *  - then `（「…」尚未了结）` for the threads it also sets up that are still OPEN (or STALE).
     * Only a memory with at least one closed thread is annotated. A thread is called by its short name, or by its
     * description when it has none; either is cut at [THREAD_NAME_CHARS] code points.
     */
    private fun annotation(hit: Activation, lookup: Lookup, markers: RecallMarkers): String {
        val threads = lookup.threadsOf(hit.engram.id)
        if (threads.closed.isEmpty()) return ""
        return markers.threadNote(threads.closed.map(::label), threads.open.map(::label))
    }

    private fun label(t: ThreadRecord): String = t.name?.takeIf { it.isNotBlank() } ?: t.description

    /**
     * Render order: the hits in order, except that a RESOLVE memory of a closed thread is pulled up to sit right after
     * the first SETUP memory of that thread that precedes it (recall appends it after the base). A RESOLVE already
     * rendered earlier stays where it is; nothing is dropped or duplicated.
     */
    private fun ordered(hits: List<Activation>, lookup: Lookup): List<Activation> {
        val byId = HashMap<Long, Activation>()
        for (h in hits) byId.putIfAbsent(h.engram.id, h)
        if (byId.size != hits.size) return hits // duplicate ids: keep the caller's order untouched
        val placed = HashSet<Long>()
        val out = ArrayList<Activation>(hits.size)
        for (h in hits) {
            if (!placed.add(h.engram.id)) continue
            out += h
            if (h.engram.isEpisodeHub) continue
            for (r in lookup.closedResolvesOf(h.engram.id)) {
                val resolve = byId[r] ?: continue
                if (placed.add(r)) out += resolve
            }
        }
        return out
    }
}

/**
 * How a recall block dates its hits and whether it names their periods. Decided by the caller from the effective
 * scene-episodes setting and from where the block will ride:
 *  - [LEGACY] — scene episodes off: `[YYYY-MM]`, no period;
 *  - [ABSOLUTE] / [absolute] — scene episodes on, a block that is persisted (baked into a stored message, returned by
 *    a search tool): a complete date at day precision and nothing that goes stale — no period (periods get renamed
 *    and re-cut), no relative time;
 *  - [turnEnd] — scene episodes on, a block that rides only the current turn (never persisted): the day plus a
 *    relative time computed from [TurnEnd.now] in [TurnEnd.zone], and the period of every hit whose scene belongs to
 *    [TurnEnd.chatKey] (none without a chat).
 */
data class RecallDating(
    /** A complete `YYYY-MM-DD` event date shows the day (else the month, as always). */
    val dayPrecision: Boolean = false,
    /** Set only on the turn-end recall: relative time and period tags. */
    val turnEnd: TurnEnd? = null,
    /** Zone a memory's said span is read in as calendar days (the turn-end recall uses its own). */
    val zone: ZoneId = ZoneOffset.UTC,
    /**
     * Dates in words ("19 May 2023", said days with their weekday) instead of ISO, and the dates legend says what a
     * relative time inside a memory counts from. With day precision only.
     */
    val inWords: Boolean = false,
    /**
     * Every date in a tag says what it is the date of: `happened:` (a year- or month-only one marked partial),
     * `mentioned:`, `due:`, `resolved:` (a settled plan's outcome date), a quote's own `said:` day, and a scene's
     * dates as `mentioned:`; the dates legend explains them. Off = the unlabelled tags, byte for byte. With day
     * precision only.
     */
    val labels: Boolean = false,
    /**
     * Labelled tags anchored to the mention: a month-only `happened:` date in the month of the first mention is left
     * out (it says no more than the mention, and beside it a reader took the 1st of the month for the day), a memory of
     * a state that had lasted some time when mentioned dates its start as `since:` ("has been living there for three
     * months" with July as its date), and the legend says a partial date is on or before the mention rather than
     * overruled by it. Only the rendering changes. Off = the labelled tags, byte for byte. With [labels] only.
     */
    val relative: Boolean = false,
) {
    init {
        require(turnEnd == null || dayPrecision) { "a turn-end recall shows the day" }
        require(!inWords || dayPrecision) { "dates in words come with day precision" }
        require(!labels || dayPrecision) { "labelled dates come with day precision" }
        require(!relative || labels) { "mention-anchored dates are labelled dates" }
    }

    /** The render-time context of a turn-end recall: the turn's chat, the clock and the runtime zone. */
    data class TurnEnd(val chatKey: String?, val now: Instant, val zone: ZoneId)

    companion object {
        val LEGACY = RecallDating()
        val ABSOLUTE = RecallDating(dayPrecision = true)

        /** [ABSOLUTE] reading said spans in [zone] (the runtime zone), dates [inWords] or ISO, [labels] or not. */
        fun absolute(zone: ZoneId, inWords: Boolean = false, labels: Boolean = false, relative: Boolean = false) =
            RecallDating(dayPrecision = true, zone = zone, inWords = inWords, labels = labels, relative = relative)

        fun turnEnd(chatKey: String?, now: Instant, zone: ZoneId, inWords: Boolean = false, labels: Boolean = false, relative: Boolean = false) =
            RecallDating(dayPrecision = true, turnEnd = TurnEnd(chatKey, now, zone), inWords = inWords, labels = labels, relative = relative)
    }
}
