package org.foedusprogramme.lattice.core

import java.text.Normalizer
import java.util.Locale

/**
 * Whether a period's (or an era's) name and gist may be shown — by the catalogue, recall labels, the timeline or a
 * model call. Hidden (a neutral date label instead) when any scene its naming call saw in full ([namingSceneIds]) is
 * untrusted now, or when none of its [memberIds] is trusted: a name is model-written from scene content, so it may
 * carry whatever an untrusted scene put there. [trust] = the chat's current verdicts; a scene without one is untrusted.
 */
fun periodNameShown(namingSceneIds: Set<Long>, memberIds: Collection<Long>, trust: SceneTrust.ChatTrust): Boolean =
    periodNameShown(namingSceneIds, memberIds) { trust.trusted(it) }

/** [periodNameShown] over a verdict function ([trusted] = the scene is judged and trusted). */
fun periodNameShown(namingSceneIds: Set<Long>, memberIds: Collection<Long>, trusted: (Long) -> Boolean): Boolean =
    namingSceneIds.all(trusted) && memberIds.any(trusted)

/**
 * A scene as a period round sees it: its row, its place in the chat ([order], its index in [SCENE_ORDER] when the
 * round read it) and its hub narration. Positions ([firstMessageId], [lastMessageId]) are per conversation and never
 * order scenes.
 */
data class PeriodSceneView(
    val sceneId: Long,
    val conversationId: String,
    val firstMessageId: Long,
    val lastMessageId: Long,
    val order: Int,
    /** Null for a scene the pipeline did not write (it counts as ended). */
    val state: SceneWorkState?,
    /** The scene's title (the segmenter's). */
    val title: String,
    /** The hub narration; null when the scene has no hub. */
    val gist: String?,
    /** The hub is an in-progress one (covers only part of the scene). */
    val gistPartial: Boolean,
    val dateFrom: String?,
    val dateTo: String?,
)

/** A period with its member scene ids in [SCENE_ORDER]. */
data class PeriodView(val period: PeriodRecord, val sceneIds: List<Long>)

/**
 * One formation round of chat [chatKey], as handed to [PeriodPostFormation]: the chat's period layer before the round
 * ([before], [erasBefore], [generation]), every scene of the chat ([scenes], by id), the scene trust the round used
 * ([trust]; untrusted scenes may reach a model call only as dates) and how many model calls the round made so far
 * ([modelCalls]: 0 or 1; a round makes at most two).
 */
class PeriodRound(
    val chatKey: String,
    /** The chat's language (names are written in it); null = unknown. */
    val language: Language?,
    val before: List<PeriodView>,
    val erasBefore: List<EraRecord>,
    val generation: Long,
    val scenes: Map<Long, PeriodSceneView>,
    val trust: SceneTrust.ChatTrust,
    val modelCalls: Int,
    /**
     * Another round of the same trigger follows if this one commits (its input left scenes past the cap): the catalogue
     * is not published by this round — [PeriodPostFormation.publish] publishes it once the trigger's rounds end.
     */
    val catalogueDeferred: Boolean = false,
) {
    /**
     * The chat's periods once [write] is applied (in [SCENE_ORDER] of their first scene), with their member scenes and
     * date ranges derived from [scenes] as the period index derives them.
     */
    fun after(write: PeriodWrite): List<PeriodView> {
        require(write.chatKey == chatKey) { "A write of ${write.chatKey} was applied to the round of $chatKey; pass the round's own write." }
        val periods = LinkedHashMap<Long, PeriodRecord>()
        val members = LinkedHashMap<Long, Long>()
        if (!write.reset) {
            before.filter { it.period.id !in write.deletePeriodIds }.forEach { v ->
                periods[v.period.id] = v.period
                v.sceneIds.forEach { members[it] = v.period.id }
            }
        }
        write.periods.forEach { periods[it.id] = it }
        write.members.forEach { if (it.periodId in periods) members[it.sceneId] = it.periodId }
        val order = { id: Long -> scenes[id]?.order ?: Int.MAX_VALUE }
        val byPeriod = members.entries.groupBy({ it.value }, { it.key })
            .mapValues { (_, ids) -> ids.sortedWith(compareBy({ order(it) }, { it })) }
        return periods.values.map { p ->
            val ids = byPeriod[p.id].orEmpty()
            val views = ids.mapNotNull(scenes::get)
            PeriodView(
                p.copy(
                    dateFrom = views.mapNotNull { it.dateFrom }.minOrNull(),
                    dateTo = views.mapNotNull { it.dateTo ?: it.dateFrom }.maxOrNull(),
                ),
                ids,
            )
        }.sortedWith(compareBy({ it.sceneIds.firstOrNull()?.let(order) ?: Int.MAX_VALUE }, { it.period.id }))
    }
}

/**
 * The catalogue's seam in a formation round: called on the narration thread after a round computed a STRUCTURAL write
 * and before it is committed; returns the write to commit — [write] itself, or [write] with era changes and catalogue
 * snapshots added, so everything lands in the SAME batch. It may make at most one model call (a round makes at most
 * two: the formation and the era assignment). A throw is logged and the formation is committed without the additions;
 * a write for another chat is ignored. The commit may still drop the result on a conflict (nothing written).
 */
fun interface PeriodPostFormation {
    fun extend(round: PeriodRound, write: PeriodWrite): PeriodWrite

    /** Publish the chat's catalogue as committed now (after rounds that deferred it); no model call. Nothing by default. */
    fun publish(chatKey: String, trust: SceneTrust.ChatTrust) {}

    companion object {
        /** No eras, no catalogue. */
        val NoOp = PeriodPostFormation { _, write -> write }
    }
}

/**
 * Field rules for everything a model writes into the period layer: one line, without control (Cc — NUL, U+0085 …),
 * format (Cf — bidi, zero-width), line or paragraph separator (Zl / Zp) code points, with whitespace collapsed, without
 * the separators the catalogue renders with (`「」『』`, `·` / `・`, the date range dashes `–` `—` and the wave dashes
 * `～` `〜`), capped in code points by the caller. Applied when a name or gist is written and again wherever it is
 * rendered (a snapshot may come from an older version or another device).
 */
object PeriodFields {
    /** Hard cap on a period / era name (the prompt asks for far less). */
    const val MAX_NAME_CODE_POINTS = 40

    /** Hard cap on a gist; a longer one fails the formation call (the prompt asks for at most 100 CJK characters / 30 words). */
    const val MAX_GIST_CODE_POINTS = 240

    /** Render quotes: dropped. */
    private val QUOTES: Set<Int> = "「」『』".codePoints().toArray().toSet()

    /** Render separators (the catalogue's dot and date-range dashes): read as a space. */
    private val BREAKS: Set<Int> = "·・–—～〜".codePoints().toArray().toSet()

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp).toByte()
            val space = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 || cp in BREAKS ||
                type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
            when {
                space -> pendingSpace = out.isNotEmpty()
                cp in QUOTES -> Unit
                dropped(type) -> Unit
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(cp)
                }
            }
        }
        return out.toString().trim('"', '*', '\'', '`', ' ')
    }

    /**
     * The lighter rule for text shown to a model call (titles, hub narrations, thread names): one line, without
     * control, format, line or paragraph separator code points, whitespace collapsed — render marks are kept.
     */
    fun oneLine(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp).toByte()
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 ||
                    type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR -> pendingSpace = out.isNotEmpty()
                dropped(type) -> Unit
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(cp)
                }
            }
        }
        return out.toString()
    }

    private fun dropped(type: Byte): Boolean =
        type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE ||
            type == Character.UNASSIGNED || type == Character.PRIVATE_USE

    /** [normalize]d [name] cut at [MAX_NAME_CODE_POINTS] code points (no ellipsis: a label, not a line). */
    fun cappedName(name: String): String {
        val s = normalize(name)
        if (s.codePointCount(0, s.length) <= MAX_NAME_CODE_POINTS) return s
        return s.substring(0, s.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)).trimEnd()
    }

    /** A local date range as a formation or era input shows it: `2026-07-23` or `2026-07-23 to 2026-07-29`. */
    internal fun dateRange(from: String?, to: String?): String? = when {
        from == null -> to
        to == null || to == from -> from
        else -> "$from to $to"
    }

    /**
     * How the prompts name [language]: `Simplified Chinese` for Chinese, else its English display name (`English`,
     * `German`), else its tag.
     */
    fun languageLabel(language: Language): String {
        if (RecallMarkers.isChinese(language)) return "Simplified Chinese"
        val locale = Locale.forLanguageTag(language.tag.replace('_', '-'))
        val name = locale.getDisplayName(Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(language.tag, ignoreCase = true) } ?: language.tag
    }

    /**
     * The opening sections every period-layer model input shares (the formation's and the era call's): `## Language`
     * ([language]'s label, else the conversation's dominant language), `## Who is who` (the assistant with its
     * [selfAliases]; the human counterpart by [userName], else [counterpartFallback]) and `## Names already used`
     * ([usedNames] [normalize]d, blanks and repeats dropped; `(none)` when empty).
     */
    internal fun appendInputHeader(
        out: StringBuilder,
        language: Language?,
        selfAliases: Set<String>,
        userName: String?,
        counterpartFallback: String,
        usedNames: List<String>,
        /** The people of a chat the assistant takes no part in ([SceneNarrator.observedSpeakers]); empty = none. */
        observed: List<String> = emptyList(),
    ) {
        out.append("## Language\n")
        out.append(language?.let(::languageLabel) ?: "the conversation's dominant language").append('\n')
        out.append('\n')
        out.append("## Who is who\n")
        if (observed.isNotEmpty()) {
            out.append("You take no part in this chat: it is between ").append(SceneNarrator.observedList(observed))
                .append(". Write about each of them in the third person, by name.\n")
            out.append('\n')
            appendUsedNames(out, usedNames)
            return
        }
        out.append("You — the assistant, written in the first person")
        val aliases = selfAliases.map { it.trim() }.filter { it.isNotEmpty() }
        if (aliases.isNotEmpty()) out.append("; also addressed as ").append(aliases.joinToString(", ")).append(" (all of these are you)")
        out.append('\n')
        out.append("The human counterpart: ").append(userName?.trim()?.takeIf { it.isNotEmpty() } ?: counterpartFallback).append('\n')
        out.append('\n')
        appendUsedNames(out, usedNames)
    }

    private fun appendUsedNames(out: StringBuilder, usedNames: List<String>) {
        out.append("## Names already used\n")
        val used = usedNames.map(::normalize).filter { it.isNotEmpty() }.distinct()
        out.append(if (used.isEmpty()) "(none)" else used.joinToString("、") { "「$it」" }).append('\n')
    }

    /** Null when [name] (already [normalize]d) is usable as a period or era name; else why not. */
    fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "the name is empty"
        name.codePointCount(0, name.length) > MAX_NAME_CODE_POINTS -> "the name 「$name」 is longer than $MAX_NAME_CODE_POINTS code points"
        periodNameKey(name).isEmpty() -> "the name 「$name」 is empty once normalized"
        isOrdinal(name) -> "the name 「$name」 numbers the period"
        else -> null
    }

    private const val CN_NUM = "[0-9零〇一二三四五六七八九十百两]"
    private const val UNITS_ZH = "(?:时期|阶段|期|段|章|节|部分|部|篇|幕|卷|回|集|季|单元|时代|纪)"
    private const val UNITS_EN = "(?:period|phase|stage|part|chapter|era|act|section|episode|season|volume|book|arc)"
    private const val ORDINAL_WORDS =
        "(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|last|final|next|\\d+(?:st|nd|rd|th))"

    private val ORDINAL_PATTERNS = listOf(
        // 第一时期 / 第3阶段 / 第十二章
        Regex("^第$CN_NUM+个?$UNITS_ZH"),
        Regex("第$CN_NUM+个?$UNITS_ZH$"),
        // 时期一 / 阶段 3 / 第二部分 at the end
        Regex("^$UNITS_ZH\\s*$CN_NUM+"),
        Regex("$UNITS_ZH\\s*$CN_NUM+$"),
        // Period 3 / Part II / chapter #2 (start or end)
        Regex("^$UNITS_EN\\s*(?:#?\\d+|[ivxlc]+)\\b"),
        Regex("\\b$UNITS_EN\\s*(?:#?\\d+|[ivxlc]+)$"),
        // the first period / second chapter
        Regex("^(?:the\\s+)?$ORDINAL_WORDS\\s+(?:\\w+\\s+)?$UNITS_EN\\b"),
        // list numbering: 1. / (1) / （一） / 一、 / II.
        Regex("^[(（\\[]?\\s*(?:\\d{1,2}|[一二三四五六七八九十]{1,3}|[ivx]{1,4})\\s*[)）\\].、:：]\\s*[^0-9\\s]"),
        Regex("^[(（\\[]\\s*(?:\\d{1,2}|[一二三四五六七八九十]{1,3}|[ivx]{1,4})\\s*[)）\\]]"),
        // a trailing (2) / （2）
        Regex("[(（]\\s*\\d{1,2}\\s*[)）]$"),
        // #3 anywhere
        Regex("#\\d+"),
    )

    /** Circled / parenthesized / full-stop numbers (①, ⑴, ⒈, ❶, ㈠, ㊀). */
    private val ENCLOSED_NUMBER = Regex("[\\u2460-\\u24FF\\u2776-\\u2793\\u3220-\\u3229\\u3280-\\u3289]")

    /** True when [name] numbers the period by its position (only ordinal forms; a number of the content is fine). */
    fun isOrdinal(name: String): Boolean {
        if (ENCLOSED_NUMBER.containsMatchIn(name)) return true // before NFKC, which turns ① into 1
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        return ORDINAL_PATTERNS.any { it.containsMatchIn(folded) }
    }
}
