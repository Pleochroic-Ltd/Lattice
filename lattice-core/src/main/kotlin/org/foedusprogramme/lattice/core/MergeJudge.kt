package org.foedusprogramme.lattice.core

/**
 * The SOLE merge decider for the cross-lingual dedup path ([LatticeGraph.judgedSupersedePass]).
 *
 * Geometry (cosine) only nominates a CANDIDATE band strictly below the same-language
 * [LatticeGraph.Config.dedupThreshold]; it never decides a merge. A naive geometry band-merge
 * false-merges ~57% of script-cross-lingual pairs (same-fact and different-fact cosines fully
 * interleave for bge-m3 across zh↔en), so the decision is delegated to a semantic judge that
 * reads the two notes and answers whether they assert the SAME fact.
 *
 * One [judge] call = one verdict. [LatticeGraph] calls it N times and requires UNANIMOUS SAME to
 * merge (self-consistency vote), because a single model call is measurably non-deterministic and
 * a false-merge is the cardinal sin. [modelId] is folded into the verdict-cache key so a model
 * swap forces a re-judge.
 */
interface MergeJudge {
    /** Stable identifier of the underlying model. */
    val modelId: String

    /** The verdict-cache key part: the model and anything else a verdict depends on (the prompt's version). */
    val verdictKey: String get() = modelId

    /**
     * Compare two memory notes. [crossLingualHint] annotates the prompt when the pair looks
     * cross-lingual (script detection); it is advisory, never gating.
     */
    fun judge(noteA: String, noteB: String, crossLingualHint: Boolean): Verdict

    enum class Verdict {
        /** One note restates the other: most of the less detailed note is already in the other. */
        SAME,

        /** A specific of its own on the less detailed side, only a shared theme, a different value, or a contradiction. */
        DIFFERENT,

        /** Unparseable / empty / backend unavailable — treated as fail-closed, NOT cached. */
        AMBIGUOUS,
    }
}

/**
 * A [MergeJudge] backed by a [MemoryModel] ([MemoryTask.JUDGE]); [modelId] names the model behind it.
 *
 * The decision is a RESTATEMENT test: SAME only when most of what the less detailed note says (its events,
 * details, quotes, dates, reasons) is already in the other, so retiring it behind the other loses next to nothing.
 * Two notes that only share a subject, theme or relationship are DIFFERENT, as is a less detailed note that keeps a
 * substantial specific of its own. The earlier rule merged on bare CONTAINMENT of one note's fact; on a
 * relationship-heavy graph it retired distinct memories behind thinner notes and synthesized bundles (measured
 * 2026-10-07: about 7 of the 29 merges one night held up). "If unsure, DIFFERENT" keeps the safe direction.
 * [verdictKey] carries the prompt's version, so verdicts given under an earlier prompt are never re-applied.
 */
class ModelMergeJudge(
    private val model: MemoryModel,
    override val modelId: String,
) : MergeJudge {

    override val verdictKey: String get() = "$modelId#$PROMPT_VERSION"

    override fun judge(noteA: String, noteB: String, crossLingualHint: Boolean): MergeJudge.Verdict {
        val hint = if (crossLingualHint) {
            "\n(These two notes may be written in different languages — Chinese and English.)"
        } else {
            ""
        }
        val user = "Note A: ${noteA.trim()}\nNote B: ${noteB.trim()}$hint"
        val raw = runCatching { model.complete(MemoryTask.JUDGE, SYSTEM_PROMPT, user) }.getOrDefault("").trim().uppercase()
        if (raw.isEmpty()) return MergeJudge.Verdict.AMBIGUOUS
        // The prompt asks for a bare SAME/DIFFERENT, but a chatty model prepends the verdict and then
        // explains — and that prose can contain the OTHER word ("...does not make them different").
        // So read the LEADING verdict token first (the reliable answer), and only fall back to a
        // whole-text scan (DIFFERENT-first, the safe/no-merge direction) when the head is inconclusive.
        val head = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val leadToken = Regex("[A-Z]+").find(head)?.value
        return when {
            leadToken == "SAME" -> MergeJudge.Verdict.SAME
            leadToken == "DIFFERENT" -> MergeJudge.Verdict.DIFFERENT
            "DIFFERENT" in raw -> MergeJudge.Verdict.DIFFERENT
            "SAME" in raw -> MergeJudge.Verdict.SAME
            else -> MergeJudge.Verdict.AMBIGUOUS
        }
    }

    companion object {
        /** Bumped whenever [SYSTEM_PROMPT]'s decision rule changes; part of [verdictKey]. */
        const val PROMPT_VERSION = "restatement-v2"

        val SYSTEM_PROMPT = """
            You compare two memory notes about the same chat, possibly in different languages (Chinese/English);
            treat translation and paraphrase as identical. One of them may be retired behind the other, so decide
            whether the less detailed note is a restatement of the other.
            SAME: most of what the less detailed note says (its events, details, quotes, dates, reasons) is already
            in the other note, so retiring it would lose next to nothing. The other note may say more.
            DIFFERENT: the less detailed note keeps a substantial specific of its own that the other lacks (a
            separate event or moment, a detail, a quote, a date, a reason); or the two only share a subject, theme
            or relationship; or they state different values; or they contradict each other.
            If unsure, answer DIFFERENT. Answer exactly SAME or DIFFERENT.
        """.trimIndent()
    }
}
