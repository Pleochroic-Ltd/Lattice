package org.foedusprogramme.lattice.core

/**
 * Routes a cue to a [TemporalIntent] by STATIC whole-cue regex — no embedding, no graph, no clock.
 * Mirrors the style of [LatticeGraph]'s `AGGREGATIVE_INTENT` gate (lowercased whole cue, `containsMatchIn`).
 *
 * PERIOD wins over RELEVANCE: a bare back-reference ("那阵子") carries no date token, so the two classes
 * do not overlap in practice; the precedence only matters for a contrived cue that carries both, where a
 * back-reference reading is the safer route (period-fetch is read-only and window-bounded).
 */
object TemporalIntentClassifier {

    /** Bare back-reference cues ("what else was around then") — no concrete date, resolved via focus stack. */
    internal val PERIOD_RX = Regex(
        "那阵子|那段时间|那时候|那会儿|前段时间|back then|around that|around then|that period|at the time",
    )

    private const val EN_MONTHS =
        "january|february|march|april|may|june|july|august|september|october|november|december" +
            "|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"

    /**
     * Presence of a resolvable date-grammar token — mirrors [TemporalAnchorResolver]'s deterministic
     * grammar (absolute `YYYY-年/月`, CN/EN relatives, and CONTEXT-QUALIFIED month names). A bare standalone
     * English month word ("may"/"march"/"august") is deliberately NOT a token — it needs a day, a year, or
     * a temporal determiner/preposition (mirroring [TemporalAnchorResolver.EN_MONTH_CONTEXT] so classify()
     * and resolve() agree). A cue that matches this but is NOT a bare PERIOD cue is a RELEVANCE query.
     */
    internal val DATE_TOKEN_RX = Regex(
        "\\b\\d{4}[-/年]\\s*\\d{1,2}\\b" +
            "|去年|上个月|上月|这个月|本月" +
            "|[0-9〇零一二两三四五六七八九十]{1,3}\\s*个月前" +
            "|\\blast month\\b|\\blast year\\b|\\bthis month\\b" +
            // Day / week cues (TemporalAnchorResolver's day windows).
            "|${TemporalAnchorResolver.DAY_TOKENS}" +
            "|\\b\\d{1,3}\\s+months?\\s+ago\\b" +
            // Context-qualified English month only (no standalone bare month — see KDoc); CN 月 cues above untouched.
            "|\\b(?:in|on|by|since|during|until|till|before|after|around|early|late|mid|last|this|next|the|that)\\s+(?:$EN_MONTHS)\\b" +
            "|\\b(?:$EN_MONTHS)\\s+\\d{1,2}(?:st|nd|rd|th)?\\b" +
            "|\\b(?:$EN_MONTHS)\\s+\\d{4}\\b" +
            "|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:$EN_MONTHS)\\b" +
            "|\\b\\d{4}\\s+(?:$EN_MONTHS)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun classify(cue: String): TemporalIntent {
        val c = cue.lowercase()
        if (PERIOD_RX.containsMatchIn(c)) return TemporalIntent.PERIOD
        if (DATE_TOKEN_RX.containsMatchIn(c)) return TemporalIntent.RELEVANCE
        return TemporalIntent.NONE
    }
}
