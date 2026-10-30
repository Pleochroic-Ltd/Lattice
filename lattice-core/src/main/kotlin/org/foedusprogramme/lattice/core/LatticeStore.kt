package org.foedusprogramme.lattice.core

import java.time.Instant

/**
 * Where a graph lives between runs. The graph is held whole in memory ([LatticeGraph]); the store is written through,
 * one change at a time or in a [batch], and read back whole by [load] at start.
 *
 * How a store works:
 * - every id is minted by the caller from [IdAllocator] (entities too), so a store never allocates and an op log can
 *   carry every write;
 * - an edge carries its direction ([EdgeRecord.from] → [EdgeRecord.to]); a store keys it by the unordered pair;
 * - merge verdicts load with the rest ([Loaded.verdicts]) instead of being queried one by one;
 * - every method is abstract, and a delete states its cascade: a store that can't cascade in SQL (the remote op log)
 *   must emit the dependent deletes itself.
 *
 * Calls come from one thread at a time (the graph thread or, for scene and period writes, the narration thread under
 * the graph's lock); implementations need not be thread-safe beyond that.
 */
interface LatticeStore : AutoCloseable {
    /** This device's [IdAllocator] slot: 0 for a store only one device writes. */
    val idSlot: Int

    /** The embedding model and dimension the graph was created with, or null for a graph with no engram yet. */
    val embedding: EmbeddingSpec?

    /** Records [spec] for a new graph; a store whose spec is already set refuses a different one. */
    fun declareEmbedding(spec: EmbeddingSpec)

    fun load(): Loaded

    fun putEngram(engram: Engram)

    /**
     * Deletes the engram and everything that names it: its edges, entity links, resolved marker, scene memberships and
     * thread links; a scene whose hub it was keeps the scene with no hub.
     */
    fun deleteEngram(id: Long)

    /** Upserts the edge between [EdgeRecord.from] and [EdgeRecord.to], replacing one stored in either direction. */
    fun putEdge(edge: EdgeRecord)

    /** Deletes the edge between [a] and [b], whichever way it points. */
    fun deleteEdge(a: Long, b: Long)

    fun putVerdict(verdict: VerdictRecord)

    fun putEntity(entity: EntityRecord)

    fun linkEngramEntity(engramId: Long, entityId: Long)

    fun markEntityResolved(engramId: Long)

    /** Repoints [fromId]'s links to [toId] (a link both already have is kept once) and deletes [fromId]. */
    fun mergeEntities(fromId: Long, toId: Long)

    fun putScene(scene: SceneRecord)

    fun putSceneMember(sceneId: Long, engramId: Long)

    /** Moves the scene memberships of [fromEngramId] to [toEngramId] (a membership both have is kept once). */
    fun redirectSceneMembers(fromEngramId: Long, toEngramId: Long)

    fun putThread(thread: ThreadRecord)

    fun putThreadLink(link: ThreadLinkRecord)

    fun redirectThreadLinks(fromEngramId: Long, toEngramId: Long)

    fun deleteThreadLinks(engramId: Long)

    fun putPeriod(period: PeriodRecord)

    /** Deletes the period with its members and aliases. */
    fun deletePeriod(periodId: Long)

    fun putPeriodMember(member: PeriodMemberRecord)

    fun putPeriodAlias(alias: PeriodAliasRecord)

    fun putEra(era: EraRecord)

    /** Deletes the era with its aliases; its periods keep no era. */
    fun deleteEra(eraId: Long)

    fun putEraAlias(alias: EraAliasRecord)

    fun putPeriodExclusion(exclusion: PeriodExclusionRecord)

    /** Upserts the failure; a [PeriodFailureRecord.count] of 0 or less deletes it. */
    fun putPeriodFailure(failure: PeriodFailureRecord)

    fun putPeriodGeneration(chatKey: String, generation: Long)

    fun putCatalogueSnapshot(snapshot: CatalogueSnapshot)

    /** Deletes every period, era, member, alias, exclusion, failure, generation and catalogue of [chatKey]. */
    fun deletePeriodLayer(chatKey: String)

    /** Runs [block] as one unit: all of its writes land, or none (one SQLite transaction, one op batch). */
    fun batch(block: (LatticeStore) -> Unit)

    override fun close() {}

    /** One graph's whole persisted state. */
    data class Loaded(
        val engrams: List<Engram> = emptyList(),
        val edges: List<EdgeRecord> = emptyList(),
        val verdicts: List<VerdictRecord> = emptyList(),
        val entities: List<EntityRecord> = emptyList(),
        /** (engramId, entityId) */
        val entityLinks: List<Pair<Long, Long>> = emptyList(),
        val resolvedEntityIds: Set<Long> = emptySet(),
        val scenes: List<SceneRecord> = emptyList(),
        /** (sceneId, engramId) */
        val sceneMembers: List<Pair<Long, Long>> = emptyList(),
        val threads: List<ThreadRecord> = emptyList(),
        val threadLinks: List<ThreadLinkRecord> = emptyList(),
        val periods: List<PeriodRecord> = emptyList(),
        val eras: List<EraRecord> = emptyList(),
        val periodMembers: List<PeriodMemberRecord> = emptyList(),
        val periodAliases: List<PeriodAliasRecord> = emptyList(),
        val eraAliases: List<EraAliasRecord> = emptyList(),
        val periodExclusions: List<PeriodExclusionRecord> = emptyList(),
        val periodFailures: List<PeriodFailureRecord> = emptyList(),
        val periodGenerations: Map<String, Long> = emptyMap(),
        val catalogueSnapshots: List<CatalogueSnapshot> = emptyList(),
    ) {
        /** Every id in the state, for seeding an [IdAllocator]. */
        fun allIds(): Sequence<Long> =
            engrams.asSequence().map { it.id } + entities.asSequence().map { it.id } + scenes.asSequence().map { it.sceneId } +
                threads.asSequence().map { it.id } + periods.asSequence().map { it.id } + eras.asSequence().map { it.id }
    }
}

data class EmbeddingSpec(val model: String, val dimension: Int)

/** A directed edge: [from] → [to] is the meaning of a directional [type] (parent → child, hub → member, newer → older). */
data class EdgeRecord(
    val from: Long,
    val to: Long,
    val type: Edge.Type,
    val weight: Double,
    val lastReinforcedAt: Instant,
    val weightDecayedAt: Instant = lastReinforcedAt,
)

/**
 * A cached merge-judge verdict on two contents, keyed by their SHA-256 hashes in sorted order and the judge's
 * [MergeJudge.verdictKey].
 */
data class VerdictRecord(
    val hashLo: String,
    val hashHi: String,
    val judgeKey: String,
    val verdict: String,
    val votes: Int,
    val createdAt: Instant,
)

data class EntityRecord(
    val id: Long,
    val canonicalName: String,
    val nameLc: String,
    val mentionCount: Int = 1,
    val firstEventDate: String? = null,
)

/**
 * A scene: a stretch of one conversation about one thing. Message ids are the host's positions in that conversation
 * (monotonic within it), so [firstMessageId]..[lastMessageId] is a range.
 */
data class SceneRecord(
    val sceneId: Long,
    val title: String,
    val conversationId: String,
    val chatKey: String,
    val firstMessageId: Long,
    val lastMessageId: Long,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val inProgress: Boolean = false,
    val hubEngramId: Long? = null,
)

enum class ThreadState { OPEN, CLOSED, DROPPED, STALE }

enum class ThreadRole { SETUP, RESOLVE }

data class ThreadRecord(
    val id: Long,
    val chatKey: String,
    val description: String,
    val state: ThreadState = ThreadState.OPEN,
    val openedSceneId: Long? = null,
    val closedSceneId: Long? = null,
    val staleCount: Int = 0,
    val name: String? = null,
)

data class ThreadLinkRecord(val threadId: Long, val engramId: Long, val role: ThreadRole)

enum class PeriodState { PROVISIONAL, CLOSED }

data class PeriodRecord(
    val id: Long,
    val chatKey: String,
    val name: String,
    val gist: String? = null,
    val state: PeriodState = PeriodState.CLOSED,
    val eraId: Long? = null,
    val needsRename: Boolean = false,
    val namingSceneIds: Set<Long> = emptySet(),
    val dateFrom: String? = null,
    val dateTo: String? = null,
)

data class EraRecord(
    val id: Long,
    val chatKey: String,
    val name: String,
    val gist: String? = null,
    val frozen: Boolean = false,
    val namingSceneIds: Set<Long> = emptySet(),
    val dateFrom: String? = null,
    val dateTo: String? = null,
)

data class PeriodMemberRecord(val sceneId: Long, val periodId: Long)

data class PeriodAliasRecord(val chatKey: String, val alias: String, val periodId: Long)

data class EraAliasRecord(val chatKey: String, val alias: String, val eraId: Long)

data class PeriodExclusionRecord(val sceneId: Long, val chatKey: String, val reason: String? = null)

enum class PeriodFailureKind { FORMATION, ERA }

data class PeriodFailureRecord(
    val chatKey: String,
    val kind: PeriodFailureKind,
    val firstSceneId: Long,
    val count: Int,
    val updatedAt: Instant? = null,
)

data class CatalogueSnapshot(
    val chatKey: String,
    val language: String,
    val text: String,
    val coverage: String? = null,
    val sourceGeneration: Long = 0,
    val contentHash: String = "",
    val publishedAt: Instant = Instant.EPOCH,
)
