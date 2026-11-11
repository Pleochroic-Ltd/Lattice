package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Inserts an [Extraction] into a [LatticeGraph]: each memory becomes an engram (carrying its salience, layer, dates and
 * said span), each quote a verbatim engram EVIDENCE-linked to it, and each relation an edge. Semantic edges come from
 * [LatticeGraph.remember] itself; this adds the CAUSAL edges an embedder cannot infer, and routes SUPERSEDES to
 * [LatticeGraph.markSuperseded] (the extractor emits no other relation type).
 *
 * Runs on the graph thread.
 */
object NativeMemoryIngestor {

    /**
     * Ingests [extraction] and returns ref → engram id.
     *
     * @param sourceMessageId the anchor every memory and quote of this capture drills back to.
     * @param sceneId the scene the memories were said in: every gist joins it, including a node write-time dedup folded
     *   the memory into (the scene is where it was said again). Skipped when the graph has no such scene.
     * @param saidFrom first source-message time: when these memories were said.
     * @param saidTo last source-message time.
     * @param zone the zone the span's calendar days are read in (the future-date check).
     * @param tainted the capture read untrusted content; every engram it writes or folds into is tainted.
     * @param namedLines the capture's user lines that name their speaker, as (name, text): a person's quote is
     *   attributed to whoever said it ([quoteSpeaker]); empty = every person's quote is the user's, as before.
     */
    fun ingest(
        graph: LatticeGraph,
        extraction: Extraction,
        sourceMessageId: Long? = null,
        sceneId: Long? = null,
        saidFrom: Instant? = null,
        saidTo: Instant? = null,
        zone: ZoneId = ZoneOffset.UTC,
        tainted: Boolean = false,
        namedLines: List<Pair<String, String>> = emptyList(),
    ): Map<String, Long> {
        val idByRef = HashMap<String, Long>()
        for (memory in extraction.memories) {
            // An event "date" after the last source message cannot have happened yet: it is a plan's target the
            // model put in the wrong field. Keep it as the target date instead of letting it pose as an event.
            val (eventDate, dueDate) = splitFutureEventDate(memory.eventDate, memory.dueDate, saidTo, zone)
            // Extracted memories carry a neuromodulatory signal: their encode salience is computed from the model's
            // importance (base), embedding-grounded novelty and the model's emotional intensity (arousal).
            val gistId = graph.remember(
                memory.content, memory.salience, memory.layer,
                sourceMessageId = sourceMessageId,
                salienceSignal = LatticeGraph.SalienceSignal(base = memory.salience, arousal = memory.arousal),
                eventDate = eventDate,
                origin = Engram.Origin.EXTRACTED,
                saidFrom = saidFrom,
                saidTo = saidTo,
                dueDate = dueDate,
                tainted = tainted,
            )
            idByRef[memory.ref] = gistId
            for (quote in memory.quotes) {
                graph.addVerbatim(
                    gistId, quote.text, quote.speaker,
                    sourceMessageId = sourceMessageId, saidFrom = saidFrom, saidTo = saidTo, tainted = tainted,
                    speakerName = if (quote.speaker == Speaker.USER) quoteSpeaker(quote, namedLines) else null,
                )
            }
        }
        for (relation in extraction.relations) {
            val a = idByRef[relation.from] ?: continue
            val b = idByRef[relation.to] ?: continue
            // SUPERSEDES is directional (from = newer, to = older): mark the old memory stale so recall prefers the
            // current one, instead of adding a symmetric association.
            if (relation.type == Edge.Type.SUPERSEDES) graph.markSuperseded(newId = a, oldId = b)
            else graph.link(a, b, relation.type, weightFor(relation.type))
        }
        if (sceneId != null && idByRef.isNotEmpty() && graph.scene(sceneId) != null) {
            graph.addSceneMembers(sceneId, idByRef.values.toSet())
        }
        return idByRef
    }

    private val QUOTE_NOISE = Regex("[\\s\\p{Punct}“”‘’「」『』，。！？：；…]+")

    private fun quoteKey(text: String): String = QUOTE_NOISE.replace(text.lowercase(), " ").trim()

    /**
     * Who said a person's [quote], when the capture's lines name their speakers ([namedLines], as (name, text)): the
     * one speaker whose lines contain the quoted words (spacing, case and punctuation aside), else the name the model
     * gave when it is one of those speakers (spelled as the transcript spells it); null when the lines name nobody or
     * the quote can't be placed — the formatter then shows the user label, as before. Reading the words back from the
     * transcript beats the model's word: between two named people it once gave both people's lines to "the user".
     */
    internal fun quoteSpeaker(quote: ExtractedQuote, namedLines: List<Pair<String, String>>): String? {
        if (namedLines.isEmpty()) return null
        val key = quoteKey(quote.text)
        if (key.isNotEmpty()) {
            val saidBy = namedLines.filter { (_, text) -> key in quoteKey(text) }.map { it.first }.distinct()
            if (saidBy.size == 1) return saidBy.single()
        }
        val given = quote.speakerName?.trim()?.lowercase() ?: return null
        return namedLines.map { it.first }.distinct().firstOrNull { it.lowercase() == given }
    }

    /**
     * (event date, due date) after moving an event date that lies after [saidTo]'s day (a month-only one: after its
     * month; a year-only one: after its year) into the due date — unless a due date was already given, in which case
     * the impossible event date is dropped. No [saidTo] = no check.
     */
    internal fun splitFutureEventDate(eventDate: String?, dueDate: String?, saidTo: Instant?, zone: ZoneId): Pair<String?, String?> {
        if (eventDate == null || saidTo == null) return eventDate to dueDate
        val said = LocalDate.ofInstant(saidTo, zone)
        val future = if (eventDate.length >= 10) {
            runCatching { LocalDate.parse(eventDate.take(10)) }.getOrNull()?.isAfter(said) == true
        } else {
            // Compared at the date's own precision: "2023" said in 2023-05 is this year, not the future.
            val precision = if (eventDate.length >= 7) 7 else 4
            eventDate.take(precision) > said.toString().take(precision)
        }
        return if (future) null to (dueDate ?: eventDate) else eventDate to dueDate
    }

    /**
     * Only CAUSAL relations reach [LatticeGraph.link] here, and a causal link is strong: it carries retrieval across
     * topics. Fails loud if another relation type ever reaches this path.
     */
    private fun weightFor(type: Edge.Type): Double {
        require(type == Edge.Type.CAUSAL) { "unexpected link relation type: $type" }
        return 0.9
    }
}
