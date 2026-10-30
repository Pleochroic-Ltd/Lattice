package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * Back-fills a thread's EARLIER set-ups. The narrator only sees one scene, so a thread it recognises one scene late
 * ("the surprise promised for the weekend" opened in scene 4 while scene 3 already set it up) never linked the first set-ups,
 * and recall later showed them as still pending.
 *
 * When [SceneThreadLedger] opens or closes a thread it asks this helper once:
 *  1. [candidates] (graph thread; the caller embeds the thread description beforehand, off it): member memories of the
 *     chat's scenes before the current one ([SCENE_ORDER]), not hubs, not quotes and not already linked to the
 *     thread, scored by the max cosine to the thread description and to the thread's existing SETUP memories; the top
 *     [maxCandidates] at or above [floor] (null = the graph's seed floor, the cue-to-memory floor recall itself uses);
 *  2. [confirm] (off the graph thread): ONE model call ([MemoryTask.JUDGE]) that returns which candidates genuinely
 *     belong to this specific thread — a set-up, re-mention or reminder of the same promise or suspense, not merely
 *     the same topic. Any failure (outage, unreadable reply) returns null and the caller skips: no retry loop.
 */
class SceneThreadBackfill(
    private val model: MemoryModel,
    val floor: Double? = null,
    val maxCandidates: Int = MAX_CANDIDATES,
) {
    private val logger = LoggerFactory.getLogger(SceneThreadBackfill::class.java)

    /** One candidate as shown to the model: `[ref] content`. */
    data class Candidate(val ref: Int, val engramId: Long, val content: String)

    /** What the model is told about the thread. */
    data class ThreadView(val description: String, val setups: List<String>, val resolved: Boolean)

    /**
     * Candidates for [threadId] from the scenes of [scene]'s chat that come before it; call on the graph thread. Empty
     * when there is nothing to ask. [descriptionEmbedding] is the thread description's vector, embedded by the caller
     * OFF the graph thread; null embeds it here, inside the graph call.
     */
    fun candidates(
        g: LatticeGraph,
        threadId: Long,
        scene: SceneRecord,
        descriptionEmbedding: FloatArray? = null,
    ): List<Candidate> {
        val thread = g.thread(threadId) ?: return emptyList()
        val linked = g.threadLinks(threadId).mapTo(HashSet()) { it.engramId }
        val pool = g.scenes(scene.chatKey)
            .filter { SCENE_ORDER.compare(it, scene) < 0 }
            .flatMapTo(LinkedHashSet()) { g.sceneMembers(it.sceneId) }
            .filter { it !in linked }
            .mapNotNull { g.engram(it)?.takeIf { e -> !e.isEpisodeHub && e.speaker == null } }
        if (pool.isEmpty()) return emptyList()
        val probes = buildList {
            add(descriptionEmbedding ?: g.embed(thread.description))
            g.threadLinks(threadId)
                .filter { it.role == ThreadRole.SETUP }
                .forEach { l -> g.engram(l.engramId)?.embedding?.let(::add) }
        }
        val minScore = floor ?: g.recallSeedFloor
        return pool
            .map { e -> e to probes.maxOf { p -> if (p.size == e.embedding.size) cosine(p, e.embedding) else -1.0 } }
            .filter { it.second >= minScore }
            .sortedWith(compareByDescending<Pair<Engram, Double>> { it.second }.thenBy { it.first.createdAt }.thenBy { it.first.id })
            .take(maxCandidates)
            .mapIndexed { i, (e, _) -> Candidate(i + 1, e.id, e.content) }
    }

    /** The engram ids the model confirms, or null when the call failed or the reply was unreadable. */
    fun confirm(thread: ThreadView, candidates: List<Candidate>): List<Long>? {
        if (candidates.isEmpty()) return emptyList()
        val raw = try {
            model.complete(MemoryTask.JUDGE, SYSTEM_PROMPT, render(thread, candidates))
        } catch (e: Exception) {
            // Nothing is counted either way (a backfill has no attempts: one try per transition, failure = skip).
            if (e.isMemoryModelShutdown() || e.isMemoryModelInterrupted()) {
                logger.info("Thread backfill skipped: cut off by the shutdown")
            } else {
                logger.warn("Thread backfill skipped: model unavailable: {}", e.message)
            }
            return null
        }
        val root = NativeMemoryExtractor.parseLenientObject(raw)
        val refs = root?.get("belongs") as? JsonArray
        if (refs == null) {
            logger.warn("Thread backfill skipped: unreadable reply ({} chars)", raw.length)
            return null
        }
        val byRef = candidates.associate { it.ref to it.engramId }
        return refs.mapNotNull { e ->
            (e as? JsonPrimitive)?.contentOrNull?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }?.let(byRef::get)
        }.distinct()
    }

    private fun render(thread: ThreadView, candidates: List<Candidate>): String = buildString {
        append("## Thread\n").append(thread.description.trim()).append('\n')
        append("Status: ").append(if (thread.resolved) "resolved (closed) — find its earlier set-ups" else "open").append('\n')
        if (thread.setups.isNotEmpty()) {
            append("\n## Known set-up memories\n")
            thread.setups.forEach { append("- ").append(it.trim()).append('\n') }
        }
        append("\n## Candidates\n")
        candidates.forEach { append('[').append(it.ref).append("] ").append(it.content.trim()).append('\n') }
    }

    companion object {
        /** Candidates offered to the model per thread transition. */
        const val MAX_CANDIDATES = 12

        internal val SYSTEM_PROMPT = """
            You check which earlier memories of a chat belong to ONE story thread. A thread is one concrete
            promise, plan, surprise, unanswered question or unresolved suspense with an expected outcome.
            A candidate belongs to the thread only when it sets up, re-mentions or reminds of THIS specific
            promise or suspense. Sharing a topic, a place, a person or a mood is not enough.
            Output JSON only, listing the numbers of the candidates that belong (empty when none):
            {"belongs": [1, 3]}
        """.trimIndent()
    }
}
