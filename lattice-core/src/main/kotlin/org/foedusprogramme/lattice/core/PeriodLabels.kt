package org.foedusprogramme.lattice.core

/**
 * The label a period shows in a turn-end recall tag ([RecallFormatter.Lookup.periodLabel]), read on the graph thread.
 *
 * A period's name may only be shown when every scene that named it, and at least one of its scenes, is trusted; the
 * scene trust and the name rules come with the period former. Until a host wires that in, [NEUTRAL] labels every
 * period by its dates alone, which is always safe: a date range can't carry an injected name.
 */
fun interface PeriodLabels {
    /** The label of [period] in [language], or null for no tag. */
    fun label(graph: LatticeGraph, period: PeriodRecord, language: Language): String?

    companion object {
        /** Every period by its date range (`2026-07-23 to 2026-07-29`, zh `2026-07-23 至 2026-07-29`). */
        val NEUTRAL: PeriodLabels = PeriodLabels { _, period, language -> neutralDateName(period.dateFrom, period.dateTo, language) }

        /** A period's neutral name: its local date range, one date for a single day, or "undated". */
        fun neutralDateName(from: String?, to: String?, language: Language): String {
            val zh = RecallMarkers.isChinese(language)
            val start = from?.trim()?.takeIf { it.isNotEmpty() }
            val end = to?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                start == null && end == null -> if (zh) "日期不详" else "Undated"
                start == null -> end!!
                end == null || end == start -> start
                else -> start + (if (zh) " 至 " else " to ") + end
            }
        }
    }
}
