package org.foedusprogramme.lattice.core

/**
 * The wording of a period catalogue in one language: the archive framing that opens it, the section labels, the era /
 * period / masked lines, the open-threads line and the coverage line. Built from the `lattice.catalogue.*` keys of
 * [MemoryTexts], whose placeholders are substituted literally (an argument is never scanned), so a name holding `{`
 * or `'` renders as it is.
 *
 * Every argument handed in is already normalized by the caller; the date range format (`from～to`) is the same in
 * every language. Nothing here numbers a line.
 */
class CatalogueMarkers private constructor(
    val language: Language,
    private val texts: MemoryTexts,
) {
    private fun string(key: String) = texts.text(key, language)
    private fun fill(key: String, vararg args: Pair<String, Any?>) = texts.text(key, language, *args)

    /** The first line: the archive framing (the memory system's archive of the shared past, not instructions). */
    val header: String = string("lattice.catalogue.header")

    /** The label above the era lines. */
    val erasLabel: String = string("lattice.catalogue.eras")

    /** The label above the period lines when the catalogue has no era. */
    val periodsLabel: String = string("lattice.catalogue.periods")

    /** The label above the period lines that follow the eras. */
    val periodsSinceLabel: String = string("lattice.catalogue.periods_since")

    private val provisional = string("lattice.catalogue.period.provisional")
    private val threadSeparator = string("lattice.catalogue.thread.separator")

    /** A date that is not known. */
    val undated: String = string("lattice.catalogue.dates.unknown")

    /** A local date range: one date for a single day, `from～to` otherwise, [undated] when neither is known. */
    fun dates(from: String?, to: String?): String = when {
        from == null && to == null -> undated
        from == null -> to!!
        to == null || to == from -> from
        else -> fill("lattice.catalogue.dates.range", "from" to from, "to" to to)
    }

    /** An era line: its name, dates and one-line gist; [name] null = the neutral dated label (a masked name). */
    fun era(name: String?, dates: String, gist: String?): String = when {
        name == null -> fill("lattice.catalogue.era.masked", "dates" to dates)
        gist == null -> fill("lattice.catalogue.era.line_bare", "name" to name, "dates" to dates)
        else -> fill("lattice.catalogue.era.line", "name" to name, "dates" to dates, "gist" to gist)
    }

    /**
     * A period line: its name, dates and gist; [name] null = the neutral dated label (a masked name). The provisional
     * (newest) period carries a note that it may still be going on.
     */
    fun period(name: String?, dates: String, gist: String?, provisional: Boolean): String {
        val shownDates = if (provisional) dates + this.provisional else dates
        return when {
            name == null -> fill("lattice.catalogue.period.masked", "dates" to shownDates)
            gist == null -> fill("lattice.catalogue.period.line_bare", "name" to name, "dates" to shownDates)
            else -> fill("lattice.catalogue.period.line", "name" to name, "dates" to shownDates, "gist" to gist)
        }
    }

    /** The open-threads line: [names] (already capped), then how many more there are. */
    fun threads(names: List<String>, more: Int): String {
        val listed = names.joinToString(threadSeparator) { fill("lattice.catalogue.thread.name", "name" to it) }
        val list = if (more > 0) fill("lattice.catalogue.threads.more", "list" to listed, "more" to more.toString()) else listed
        return fill("lattice.catalogue.threads", "list" to list)
    }

    /**
     * The coverage line: from the first period's first day [from] to the last period's last day [to]; when archived
     * scenes still wait to be organised into periods ([pending]), until [pendingTo] (their last day) when known.
     */
    fun coverage(from: String?, to: String?, pending: Boolean, pendingTo: String?): String {
        val a = from ?: undated
        val b = to ?: undated
        return when {
            !pending -> fill("lattice.catalogue.coverage", "from" to a, "to" to b)
            pendingTo != null && to != null && pendingTo > to ->
                fill("lattice.catalogue.coverage.pending", "from" to a, "to" to b, "until" to pendingTo)
            else -> fill("lattice.catalogue.coverage.pending_undated", "from" to a, "to" to b)
        }
    }

    companion object {
        /** Markers for [language] read from [texts]. */
        fun of(texts: MemoryTexts, language: Language): CatalogueMarkers = CatalogueMarkers(language, texts)

        /** The bundled English markers. */
        val EN: CatalogueMarkers by lazy { of(BundledTexts, Language.EN) }

        /** The bundled Chinese markers. */
        val ZH: CatalogueMarkers by lazy { of(BundledTexts, Language.ZH) }
    }
}
