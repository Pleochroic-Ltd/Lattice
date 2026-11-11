package org.foedusprogramme.lattice.core

import java.time.Instant

/** Who uttered a verbatim quote. */
enum class Speaker { USER, ASSISTANT }

/**
 * A single memory trace — the engineering analogue of an *engram*, the sparse
 * ensemble of neurons that encodes one memory in the brain.
 *
 * P0 keeps engrams in memory. The fields mirror what a SQLite table would hold,
 * so persistence is a later, mechanical step (see [LatticeGraph] KDoc).
 */
data class Engram(
    val id: Long,
    val content: String,
    /** L2-normalized embedding used for cue-based seeding (CA3 pattern completion entry point). */
    val embedding: FloatArray,
    /**
     * Neuromodulatory salience in [0,1], set at encode time. For an extracted memory it is a
     * base-anchored, base-proportional modulation of LLM-estimated importance by embedding-grounded
     * novelty (band-recalibrated to the embedder's cosine range) and LLM-estimated emotional intensity
     * — a functional approximation of neuromodulatory tagging, not a literal novelty×emotion×reward
     * product (see [NeuromodulatorySalience]). The explicit path (memory.save/episodes/verbatims)
     * stores a fixed salience verbatim. Gates consolidation and protects a node from forgetting.
     */
    var salience: Double,
    var layer: Layer,
    val createdAt: Instant,
    var lastActivatedAt: Instant,
    /** Genuine replays — explicit memory.search retrievals (the "testing effect"). */
    var activationCount: Int = 0,
    /** Passive relevances — times this memory was surfaced (gate-passed) on the automatic per-turn
     *  path. Counts fractionally toward consolidation, so a repeatedly-relevant fact can eventually
     *  promote even if the model never explicitly searches it. */
    var softReplays: Int = 0,
    /** For a verbatim engram: who said the exact words (null for gist/abstract memories). */
    var speaker: Speaker? = null,
    /**
     * Provenance anchor (the hippocampal index → trace link): the message this memory drills back to.
     * For a verbatim engram it is the exact message that was quoted; for a gist it is an anchor in the
     * conversation slice the memory was distilled from. Lets recall "expand to source" and show the
     * original conversation. null if unmatched / not captured from a transcript.
     */
    var sourceMessageId: Long? = null,
    /**
     * True for an *episodic session node*: a durable, embeddable summary of one whole conversation
     * (its rolling summary at seal). Seedable so a past session resurfaces by theme, but ranked
     * below specific facts (see [LatticeGraph.rankScore]) so the broad summary never drowns them.
     */
    var isEpisode: Boolean = false,
    /**
     * Id of the newer memory that supersedes this one (e.g. an updated fact). A superseded
     * memory is kept but down-weighted at recall so the current fact wins. null = current.
     */
    var supersededBy: Long? = null,
    /**
     * Watermark for cadence-independent salience decay: the instant `salience` was last brought
     * current (decayed or recovered). [LatticeGraph.decayDetailed] decays for the elapsed time since
     * this instant and then advances it to now, so N scheduler passes over T hours apply exactly the
     * same total decay as ONE pass over T hours (forgetting tracks real time, not scheduler cadence).
     * Defaults to [createdAt] so a brand-new node decays from its OWN age; recovery-on-use
     * ([LatticeGraph.touch]) resets it to now alongside the salience boost. Persisted; existing rows are
     * backfilled to the migration instant (their salience already reflects decay up to the last pass,
     * so measuring the next pass from createdAt would re-inflict the whole history). Unused when
     * [Config.cadenceIndependentDecay] is false (legacy per-lastActivatedAt rollback path).
     */
    var salienceDecayedAt: Instant = createdAt,
    /**
     * Structured event-date attribute (TEMPORAL LAYER): WHEN the event this memory records actually
     * happened, as an ISO `YYYY`, `YYYY-MM` or `YYYY-MM-DD` string — distinct from [createdAt] (when the memory
     * was STORED). null = undated. Written by extraction (a resolved absolute date, incl. relatives
     * resolved against a trusted reference-time) so ordering/adjacency is derivable from a first-class
     * field instead of being baked into embedded [content] (which perturbs the embedding). Only ever
     * populated when [LatticeGraph.Config.temporalLayer] is on; an additive, all-null column when off.
     */
    var eventDate: String? = null,
    /**
     * True for a materialized episode-hub gist (star center of EPISODE_OF edges). Kept SEPARATE
     * from [isEpisode]: an episode HUB is a durable SEMANTIC gist (slow [Layer.SEMANTIC] decay,
     * isolated-semantic prune only), NOT an [isEpisode] session-summary (fast episodic decay,
     * prunes at nodePruneFloor). Additive column, default false; a null/0 legacy row is a plain
     * fact — byte-identical when [LatticeGraph.Config.episodicBinding] is OFF (no hub is ever created).
     * A hub always carries >= 1 EPISODE_OF edge, so degree > 0 spares it the isolated-semantic prune.
     */
    var isEpisodeHub: Boolean = false,
    /**
     * Where this memory came from (see [Origin]). null = not yet classified (a legacy row before the
     * [EngramOriginBackfill] ran). Metadata only: nothing in recall or forgetting reads it; the scene
     * rebuild uses it to decide which memories to carry over (MANUAL) and which to re-derive.
     */
    var origin: Origin? = null,
    /**
     * When this was said in the conversation: the time span of the source messages it was distilled from, widened by
     * every later re-mention folded into it and every node merged into it (first mention .. latest mention). Null =
     * unknown (a memory written before the span was recorded); never guessed afterwards.
     */
    var saidFrom: Instant? = null,
    var saidTo: Instant? = null,
    /** TEMPORAL LAYER: the target date of a plan, goal, deadline or appointment (ISO YYYY-MM(-DD)), kept apart from
     *  [eventDate], which is when something actually happened. */
    var dueDate: String? = null,
    /**
     * Distilled from a turn that read untrusted content (a tool result, a fetched page). Recall that surfaces it hands
     * the host an untrusted block, so a remembered injection can't come back as trusted context. Merges and folds keep
     * it: a node that absorbs a tainted one is tainted.
     */
    var tainted: Boolean = false,
) {
    enum class Layer { EPISODIC, SEMANTIC }

    /** Provenance class of an engram. */
    enum class Origin {
        /** Distilled from a transcript by the extractor (also: session-summary episode nodes). */
        EXTRACTED,

        /** Written by the model through `memory.save`. Survives a rebuild. */
        MANUAL,

        /** A sleep-synthesized ABSTRACTS parent. */
        SYNTHESIZED,

        /** A speaker-tagged verbatim quote. */
        VERBATIM,

        /** An episode / scene hub. */
        HUB,
    }

    /**
     * The month component of [eventDate] as an ISO `YYYY-MM` string (null when undated or dated to a year only). The sole time
     * key the retrieval-side temporal channel matches on — period-window membership and rerank
     * month-distance both read this, never the embedding. Day granularity is intentionally dropped:
     * the channel operates at month resolution.
     */
    val eventYearMonth: String? get() = eventDate?.takeIf { it.length >= 7 }?.take(7)

    /** Widen the said span to cover [from]..[to] (either may be null; both null changes nothing). */
    fun widenSaid(from: Instant?, to: Instant?) {
        val f = from ?: to ?: return
        val t = to ?: from ?: return
        saidFrom = saidFrom?.let { if (it < f) it else f } ?: f
        saidTo = saidTo?.let { if (it > t) it else t } ?: t
    }

    // data class with a FloatArray needs hand-written equals/hashCode by identity of id.
    override fun equals(other: Any?): Boolean = other is Engram && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * A weighted, typed association between two engrams — one edge of the memory graph.
 *
 * The graph is undirected in weight but the [type] carries directionality of meaning
 * (A caused B). Edge [weight] is the Hebbian synapse: it grows when the two engrams
 * fire together and decays during the "sleep" downscaling pass.
 */
data class Edge(
    val from: Long,
    val to: Long,
    /** Mutable so a PROTECTED relation (SUPERSEDES/CAUSAL) arriving over a pre-existing evictable
     *  auto-link can be UP-TYPED in place (see [LatticeGraph.upsertDirectedEdge]) — the synaptic budget
     *  must not count/evict a real provenance/structural relation that geometry masked as SEMANTIC. */
    var type: Type,
    var weight: Double,
    var lastReinforcedAt: Instant,
    /**
     * Watermark for cadence-independent weight decay — the edge analogue of [Engram.salienceDecayedAt].
     * The instant `weight` was last brought current (decayed or reinforced). [LatticeGraph.decayDetailed]
     * decays for the elapsed time since this instant then advances it to now, so edge forgetting tracks
     * real elapsed time, not scheduler cadence. Kept SEPARATE from [lastReinforcedAt] (which the
     * synaptic-budget tie-break and provenance read) so advancing the decay clock never makes a decayed
     * edge look freshly reinforced. Defaults to [lastReinforcedAt] (edge creation time); reinforcement
     * resets it to now. Persisted; existing rows backfilled to the migration instant. Unused when
     * [Config.cadenceIndependentDecay] is false (legacy per-lastReinforcedAt rollback path).
     */
    var weightDecayedAt: Instant = lastReinforcedAt,
) {
    enum class Type {
        /** Semantically related (high embedding similarity at encode time). */
        SEMANTIC,

        /** One led to / explains the other. */
        CAUSAL,

        /** Emergent: the two were retrieved together, so Hebb wired them. */
        CO_RETRIEVAL,

        /**
         * Hierarchy: a synthesized parent (gist/schema) abstracts a related child.
         * Formed by the sleep synthesis pass; lets retrieval drill up (child -> gist)
         * and down (gist -> instances).
         */
        ABSTRACTS,

        /**
         * A verbatim quote engram grounds the gist it was extracted from. Retrieval
         * follows it to reproduce the exact original words (of the user or the model).
         */
        EVIDENCE,

        /**
         * Directional: the `from` memory supersedes (updates/replaces) the `to` memory —
         * the fact changed. Recorded so recall can prefer the current version.
         */
        SUPERSEDES,

        /**
         * Hierarchy (EPISODIC): a materialized episode hub binds a memory that co-occurred in the
         * same real-world episode (same-day event_date + demand-gated co-retrieval). Distinct from
         * ABSTRACTS (semantic schema): an episode is a WHEN-cluster, not a WHAT-generalization.
         * PROTECTED and meaningful-for-spread, but NOT structural (never counts toward promotion).
         * Directional convention hub->member (from=hubId, to=memberId), mirroring ABSTRACTS
         * parent->child. Only ever produced behind [LatticeGraph.Config.episodicBinding] (default OFF),
         * so an OFF graph never writes one and stays byte-identical to legacy.
         */
        EPISODE_OF,

        /**
         * Thread closure marker between a set-up memory and the memory that resolved it. The thread
         * ledger (its thread links) is the authority for direction; this edge only marks the pair as
         * protected structure. PROTECTED (never budget-evicted) and skipped by spreading activation,
         * like SUPERSEDES / EVIDENCE. Only written by the scene-episode pipeline.
         */
        RESOLVES,
    }
}

/** How a recall feeds back into the graph: not at all, as a passive relevance, or as a genuine replay. */
enum class Reinforce { NONE, SOFT, FULL }

/** One hit from retrieval: the engram plus the activation it accumulated during spreading. */
data class Activation(
    val engram: Engram,
    val activation: Double,
    /** How the engram was reached — a direct cue seed, or spread from another node. */
    val via: Reach,
) {
    enum class Reach { SEED, SPREAD }
}
