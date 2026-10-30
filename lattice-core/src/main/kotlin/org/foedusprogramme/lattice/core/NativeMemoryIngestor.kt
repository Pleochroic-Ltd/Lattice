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

    /**
     * (event date, due date) after moving an event date that lies after [saidTo]'s day (a month-only one: after its
     * month) into the due date — unless a due date was already given, in which case the impossible event date is
     * dropped. No [saidTo] = no check.
     */
    internal fun splitFutureEventDate(eventDate: String?, dueDate: String?, saidTo: Instant?, zone: ZoneId): Pair<String?, String?> {
        if (eventDate == null || saidTo == null) return eventDate to dueDate
        val said = LocalDate.ofInstant(saidTo, zone)
        val future = if (eventDate.length >= 10) {
            runCatching { LocalDate.parse(eventDate.take(10)) }.getOrNull()?.isAfter(said) == true
        } else {
            eventDate.take(7) > said.toString().take(7)
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
