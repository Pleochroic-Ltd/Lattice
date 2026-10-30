package org.foedusprogramme.lattice.core

import java.time.LocalDate
import java.time.YearMonth

/**
 * RETRIEVAL-SIDE TEMPORAL CHANNEL — the resolved calendar target of a time-bearing cue.
 *
 * Deliberately embedding-free: an anchor is pure calendar arithmetic (a focal [YearMonth] plus an
 * optional symmetric month margin) produced by [TemporalAnchorResolver] from date grammar / a symbolic
 * focus stack — NEVER from a cosine. Every downstream consumer ([LatticeGraph.periodFetch] window match,
 * [LatticeGraph] time-rerank month-distance) reads only this structure and the structured
 * [Engram.eventDate] column, so the embedder is never consulted about time (the root-cause invariant
 * that keeps this channel out of the logged bge-semantic-blindness dead ends).
 */
data class TemporalAnchor(
    /** Focal month (the window center / the rerank reference month). */
    val yearMonth: YearMonth,
    /** POINT = a single month; RANGE = a window of half-width [marginMonths] around the center. */
    val kind: Kind,
    /** HIGH = deterministic grammar/back-reference; LOW = llm/fuzzy (scoped out, never produced yet). */
    val confidence: Confidence,
    /** Window half-width in months; POINT => 0. Only meaningful for a RANGE (period) anchor. */
    val marginMonths: Int = 0,
    /**
     * A day- or week-level cue ("four weeks ago", "上周", "yesterday") also carries its inclusive day window, read by
     * [LatticeGraph.dayWindowFetch]; null for a month-level anchor. [yearMonth] is then the window's middle month.
     */
    val fromDay: LocalDate? = null,
    val toDay: LocalDate? = null,
) {
    enum class Kind { POINT, RANGE }
    enum class Confidence { HIGH, LOW }

    /** Inclusive `YYYY-MM` strings `[center-margin .. center+margin]` — the [LatticeGraph.periodFetch] window. */
    fun window(): Set<String> =
        (-marginMonths..marginMonths).map { yearMonth.plusMonths(it.toLong()).toString() }.toSet()
}
