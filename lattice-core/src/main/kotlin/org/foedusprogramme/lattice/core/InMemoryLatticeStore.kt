package org.foedusprogramme.lattice.core

import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [LatticeStore] that keeps the whole graph in memory and enforces every constraint and cascade the SQLite store
 * does: foreign keys (an edge, link or membership names rows that exist), a period's and an era's name unique per chat
 * and never another one's alias, one provisional period per chat, a scene either in a period or excluded from them,
 * and the deletes documented on [LatticeStore]. A write that breaks a rule throws [IllegalArgumentException] and
 * changes nothing; [batch] is all or nothing, nested batches included.
 *
 * It serves tests and throwaway graphs, and it is the mirror of the remote store: [onChange] hears every row a call
 * wrote or deleted (cascades included, dependents before the row they depend on) once the call, or the outermost
 * batch, has succeeded, on the calling thread under the store's lock; [applyRemote], [reset] and
 * [repair] let the mirror take in rows written elsewhere. Engrams are copied in and out, so a caller mutating an
 * [Engram] after [putEngram] or [load] doesn't change the stored one. All calls are serialized on one lock.
 */
class InMemoryLatticeStore(
    override val idSlot: Int = 0,
    private val onChange: (Change) -> Unit = {},
) : LatticeStore {
    /** One stored row of any kind; [key] is its identity within its kind (an edge's is the unordered pair). */
    sealed interface Row {
        val key: Any

        data class EngramRow(val engram: Engram) : Row {
            override val key: Any get() = engram.id
        }

        data class EdgeRow(val edge: EdgeRecord) : Row {
            override val key: Any get() = edgeKey(edge.from, edge.to)
        }

        data class VerdictRow(val record: VerdictRecord) : Row {
            override val key: Any get() = Triple(record.hashLo, record.hashHi, record.judgeKey)
        }

        data class EntityRow(val record: EntityRecord) : Row {
            override val key: Any get() = record.id
        }

        data class EntityLinkRow(val engramId: Long, val entityId: Long) : Row {
            override val key: Any get() = engramId to entityId
        }

        data class ResolvedRow(val engramId: Long) : Row {
            override val key: Any get() = engramId
        }

        data class SceneRow(val record: SceneRecord) : Row {
            override val key: Any get() = record.sceneId
        }

        data class SceneMemberRow(val sceneId: Long, val engramId: Long) : Row {
            override val key: Any get() = sceneId to engramId
        }

        data class SceneWorkRow(val record: SceneWorkRecord) : Row {
            override val key: Any get() = record.sceneId
        }

        data class SceneCursorRow(val record: SceneCursorRecord) : Row {
            override val key: Any get() = record.conversationId
        }

        data class ThreadRow(val record: ThreadRecord) : Row {
            override val key: Any get() = record.id
        }

        data class ThreadLinkRow(val record: ThreadLinkRecord) : Row {
            override val key: Any get() = record
        }

        data class PeriodRow(val record: PeriodRecord) : Row {
            override val key: Any get() = record.id
        }

        data class EraRow(val record: EraRecord) : Row {
            override val key: Any get() = record.id
        }

        /** Keyed by the scene: a scene belongs to one period at most. */
        data class PeriodMemberRow(val record: PeriodMemberRecord) : Row {
            override val key: Any get() = record.sceneId
        }

        /** Keyed by the chat and [periodNameKey] of the alias. */
        data class PeriodAliasRow(val record: PeriodAliasRecord) : Row {
            override val key: Any get() = record.chatKey to periodNameKey(record.alias)
        }

        data class EraAliasRow(val record: EraAliasRecord) : Row {
            override val key: Any get() = record.chatKey to periodNameKey(record.alias)
        }

        data class ExclusionRow(val record: PeriodExclusionRecord) : Row {
            override val key: Any get() = record.sceneId
        }

        data class FailureRow(val record: PeriodFailureRecord) : Row {
            override val key: Any get() = Triple(record.chatKey, record.kind, record.firstSceneId)
        }

        data class GenerationRow(val chatKey: String, val generation: Long) : Row {
            override val key: Any get() = chatKey
        }

        data class CatalogueRow(val record: CatalogueSnapshot) : Row {
            override val key: Any get() = record.chatKey to record.language
        }
    }

    /** A row written ([removed] false) or deleted ([removed] true, [row] as it was). */
    data class Change(val row: Row, val removed: Boolean)

    private val lock = ReentrantLock()

    @Volatile
    private var spec: EmbeddingSpec? = null

    /** Undo entries of the open batches: the table, the key and the row it held before (null = none). */
    private val undo = ArrayList<Triple<Table<*>, Any, Row?>>()

    /** Changes of the open batches, told to [onChange] once the outermost one succeeds. */
    private val pending = ArrayList<Change>()
    private var depth = 0
    private var silent = false

    private val tables = ArrayList<Table<*>>()
    private val engrams = Table<Row.EngramRow>()
    private val edges = Table<Row.EdgeRow> { listOf(it.edge.from, it.edge.to) }
    private val verdicts = Table<Row.VerdictRow>()
    private val entities = Table<Row.EntityRow> { listOf(it.record.nameLc) }
    private val entityLinks = Table<Row.EntityLinkRow> { listOf(it.engramId, it.entityId) }
    private val resolved = Table<Row.ResolvedRow>()
    private val scenes = Table<Row.SceneRow> { listOfNotNull(it.record.hubEngramId) }
    private val sceneMembers = Table<Row.SceneMemberRow> { listOf(it.sceneId, it.engramId) }
    private val sceneWork = Table<Row.SceneWorkRow>()
    private val sceneCursors = Table<Row.SceneCursorRow>()
    private val threads = Table<Row.ThreadRow>()
    private val threadLinks = Table<Row.ThreadLinkRow> { listOf(it.record.threadId, it.record.engramId) }
    private val periods = Table<Row.PeriodRow> { listOfNotNull(it.record.chatKey, it.record.eraId) }
    private val eras = Table<Row.EraRow> { listOf(it.record.chatKey) }
    private val periodMembers = Table<Row.PeriodMemberRow> { listOf(it.record.periodId) }
    private val periodAliases = Table<Row.PeriodAliasRow> { listOf(it.record.periodId, it.record.chatKey) }
    private val eraAliases = Table<Row.EraAliasRow> { listOf(it.record.eraId, it.record.chatKey) }
    private val exclusions = Table<Row.ExclusionRow>()
    private val failures = Table<Row.FailureRow>()
    private val generations = Table<Row.GenerationRow>()
    private val catalogues = Table<Row.CatalogueRow>()

    override val embedding: EmbeddingSpec? get() = spec

    override fun declareEmbedding(spec: EmbeddingSpec) = lock.withLock {
        val current = this.spec
        if (current == null) {
            this.spec = spec
        } else {
            check(current == spec) {
                "This graph's embeddings come from ${current.model} (${current.dimension} dimensions), not ${spec.model} " +
                    "(${spec.dimension}); use the model the graph was created with, or re-embed it into a new graph."
            }
        }
    }

    override fun load(): LatticeStore.Loaded = lock.withLock {
        LatticeStore.Loaded(
            engrams = engrams.rows.values.map { it.engram.snapshot() },
            edges = edges.rows.values.map { it.edge },
            verdicts = verdicts.rows.values.map { it.record },
            entities = entities.rows.values.map { it.record },
            entityLinks = entityLinks.rows.values.map { it.engramId to it.entityId },
            resolvedEntityIds = resolved.rows.values.mapTo(LinkedHashSet()) { it.engramId },
            scenes = scenes.rows.values.map { it.record },
            sceneMembers = sceneMembers.rows.values.map { it.sceneId to it.engramId },
            sceneWork = sceneWork.rows.values.map { it.record },
            sceneCursors = sceneCursors.rows.values.map { it.record },
            threads = threads.rows.values.map { it.record },
            threadLinks = threadLinks.rows.values.map { it.record },
            periods = periods.rows.values.map { it.record },
            eras = eras.rows.values.map { it.record },
            periodMembers = periodMembers.rows.values.map { it.record },
            periodAliases = periodAliases.rows.values.map { it.record },
            eraAliases = eraAliases.rows.values.map { it.record },
            periodExclusions = exclusions.rows.values.map { it.record },
            periodFailures = failures.rows.values.map { it.record },
            periodGenerations = generations.rows.values.associate { it.chatKey to it.generation },
            catalogueSnapshots = catalogues.rows.values.map { it.record },
        )
    }

    override fun putEngram(engram: Engram) = write {
        spec?.let {
            require(engram.embedding.size == it.dimension) {
                "Engram ${engram.id} has a ${engram.embedding.size}-dimensional embedding, but this graph's are " +
                    "${it.dimension}-dimensional (${it.model}); embed it with the graph's model."
            }
        }
        engrams.put(Row.EngramRow(engram.snapshot()))
    }

    override fun deleteEngram(id: Long) = write { deleteEngramCascade(id) }

    private fun deleteEngramCascade(id: Long) {
        edges.referencing(id).filter { it.edge.from == id || it.edge.to == id }.forEach { edges.remove(it.key) }
        entityLinks.referencing(id).filter { it.engramId == id }.forEach { entityLinks.remove(it.key) }
        resolved.remove(id)
        sceneMembers.referencing(id).filter { it.engramId == id }.forEach { sceneMembers.remove(it.key) }
        threadLinks.referencing(id).filter { it.record.engramId == id }.forEach { threadLinks.remove(it.key) }
        scenes.referencing(id).filter { it.record.hubEngramId == id }
            .forEach { scenes.put(Row.SceneRow(it.record.copy(hubEngramId = null))) }
        engrams.remove(id)
    }

    override fun putEdge(edge: EdgeRecord) = write {
        requireEngram(edge.from, "edge ${edge.from} → ${edge.to}")
        requireEngram(edge.to, "edge ${edge.from} → ${edge.to}")
        edges.put(Row.EdgeRow(edge))
    }

    override fun deleteEdge(a: Long, b: Long) = write { edges.remove(edgeKey(a, b)) }

    override fun putVerdict(verdict: VerdictRecord) = write { verdicts.put(Row.VerdictRow(verdict)) }

    override fun putEntity(entity: EntityRecord) = write {
        val clash = entities.referencing(entity.nameLc).firstOrNull { it.record.nameLc == entity.nameLc && it.record.id != entity.id }
        require(clash == null) {
            "Entity ${entity.id} has the name key '${entity.nameLc}' of entity ${clash?.record?.id}; merge the two instead."
        }
        entities.put(Row.EntityRow(entity))
    }

    override fun linkEngramEntity(engramId: Long, entityId: Long) = write {
        requireEngram(engramId, "the link to entity $entityId")
        requireEntity(entityId, "the link from engram $engramId")
        if (entityLinks.get(engramId to entityId) == null) entityLinks.put(Row.EntityLinkRow(engramId, entityId))
    }

    override fun markEntityResolved(engramId: Long) = write {
        requireEngram(engramId, "the entity-resolved marker")
        if (resolved.get(engramId) == null) resolved.put(Row.ResolvedRow(engramId))
    }

    override fun mergeEntities(fromId: Long, toId: Long) = write {
        if (fromId == toId) return@write
        for (link in entityLinks.referencing(fromId).filter { it.entityId == fromId }) {
            entityLinks.remove(link.key)
            if (entityLinks.get(link.engramId to toId) == null) {
                requireEntity(toId, "the merge of entity $fromId")
                entityLinks.put(Row.EntityLinkRow(link.engramId, toId))
            }
        }
        entities.remove(fromId)
    }

    override fun putScene(scene: SceneRecord) = write {
        scene.hubEngramId?.let { requireEngram(it, "scene ${scene.sceneId}'s hub") }
        scenes.put(Row.SceneRow(scene))
    }

    override fun putSceneWork(work: SceneWorkRecord) = write {
        requireScene(work.sceneId, "its work state")
        sceneWork.put(Row.SceneWorkRow(work))
    }

    override fun putSceneCursor(cursor: SceneCursorRecord) = write { sceneCursors.put(Row.SceneCursorRow(cursor)) }

    override fun putSceneMember(sceneId: Long, engramId: Long) = write {
        requireScene(sceneId, "the membership of engram $engramId")
        requireEngram(engramId, "the membership in scene $sceneId")
        if (sceneMembers.get(sceneId to engramId) == null) sceneMembers.put(Row.SceneMemberRow(sceneId, engramId))
    }

    override fun redirectSceneMembers(fromEngramId: Long, toEngramId: Long) = write {
        if (fromEngramId == toEngramId) return@write
        for (member in sceneMembers.referencing(fromEngramId).filter { it.engramId == fromEngramId }) {
            sceneMembers.remove(member.key)
            if (sceneMembers.get(member.sceneId to toEngramId) == null) {
                requireEngram(toEngramId, "the redirect of engram $fromEngramId's scene memberships")
                sceneMembers.put(Row.SceneMemberRow(member.sceneId, toEngramId))
            }
        }
    }

    override fun putThread(thread: ThreadRecord) = write { threads.put(Row.ThreadRow(thread)) }

    override fun putThreadLink(link: ThreadLinkRecord) = write {
        require(threads.get(link.threadId) != null) {
            "Thread ${link.threadId} doesn't exist; write the thread before its link to engram ${link.engramId}."
        }
        if (threadLinks.get(link) == null) threadLinks.put(Row.ThreadLinkRow(link))
    }

    override fun redirectThreadLinks(fromEngramId: Long, toEngramId: Long) = write {
        if (fromEngramId == toEngramId) return@write
        for (link in threadLinks.referencing(fromEngramId).filter { it.record.engramId == fromEngramId }) {
            threadLinks.remove(link.key)
            val moved = link.record.copy(engramId = toEngramId)
            if (threadLinks.get(moved) == null) threadLinks.put(Row.ThreadLinkRow(moved))
        }
    }

    override fun deleteThreadLinks(engramId: Long) = write {
        threadLinks.referencing(engramId).filter { it.record.engramId == engramId }.forEach { threadLinks.remove(it.key) }
    }

    override fun putPeriod(period: PeriodRecord) = write {
        val key = requireNameKey(period.name, "Period ${period.id}")
        period.eraId?.let { era ->
            require(eras.get(era) != null) { "Period ${period.id} names era $era, which doesn't exist; write the era first." }
        }
        val sameChat = periods.referencing(period.chatKey).filter { it.record.chatKey == period.chatKey && it.record.id != period.id }
        sameChat.firstOrNull { periodNameKey(it.record.name) == key }?.let {
            throw IllegalArgumentException("Period ${period.id}'s name '${period.name}' is period ${it.record.id}'s name; pick another.")
        }
        if (period.state == PeriodState.PROVISIONAL) {
            sameChat.firstOrNull { it.record.state == PeriodState.PROVISIONAL }?.let {
                throw IllegalArgumentException(
                    "Chat ${period.chatKey} already has the provisional period ${it.record.id}; close it before opening ${period.id}.",
                )
            }
        }
        periodAliases.get(period.chatKey to key)?.takeIf { it.record.periodId != period.id }?.let {
            throw IllegalArgumentException("Period ${period.id}'s name '${period.name}' is an alias of period ${it.record.periodId}; pick another.")
        }
        periods.put(Row.PeriodRow(period))
    }

    override fun deletePeriod(periodId: Long) = write { deletePeriodCascade(periodId) }

    private fun deletePeriodCascade(periodId: Long) {
        periodMembers.referencing(periodId).filter { it.record.periodId == periodId }.forEach { periodMembers.remove(it.key) }
        periodAliases.referencing(periodId).filter { it.record.periodId == periodId }.forEach { periodAliases.remove(it.key) }
        periods.remove(periodId)
    }

    override fun putPeriodMember(member: PeriodMemberRecord) = write {
        require(exclusions.get(member.sceneId) == null) {
            "Scene ${member.sceneId} is excluded from periods, so it can't join period ${member.periodId}."
        }
        periodMembers.get(member.sceneId)?.let {
            throw IllegalArgumentException("Scene ${member.sceneId} already belongs to period ${it.record.periodId}; a scene has one period.")
        }
        requireScene(member.sceneId, "its membership in period ${member.periodId}")
        require(periods.get(member.periodId) != null) {
            "Period ${member.periodId} doesn't exist; write the period before its member scene ${member.sceneId}."
        }
        periodMembers.put(Row.PeriodMemberRow(member))
    }

    override fun putPeriodAlias(alias: PeriodAliasRecord) = write {
        val key = requireNameKey(alias.alias, "An alias of period ${alias.periodId}")
        require(periodAliases.get(alias.chatKey to key) == null) {
            "Chat ${alias.chatKey} already has the period alias '${alias.alias}'; an alias names one period."
        }
        require(periods.get(alias.periodId) != null) {
            "Period ${alias.periodId} doesn't exist; write the period before its alias '${alias.alias}'."
        }
        periods.referencing(alias.chatKey)
            .firstOrNull { it.record.chatKey == alias.chatKey && it.record.id != alias.periodId && periodNameKey(it.record.name) == key }
            ?.let { throw IllegalArgumentException("The alias '${alias.alias}' is period ${it.record.id}'s name; pick another.") }
        periodAliases.put(Row.PeriodAliasRow(alias))
    }

    override fun putEra(era: EraRecord) = write {
        val key = requireNameKey(era.name, "Era ${era.id}")
        eras.referencing(era.chatKey)
            .firstOrNull { it.record.chatKey == era.chatKey && it.record.id != era.id && periodNameKey(it.record.name) == key }
            ?.let { throw IllegalArgumentException("Era ${era.id}'s name '${era.name}' is era ${it.record.id}'s name; pick another.") }
        eraAliases.get(era.chatKey to key)?.takeIf { it.record.eraId != era.id }?.let {
            throw IllegalArgumentException("Era ${era.id}'s name '${era.name}' is an alias of era ${it.record.eraId}; pick another.")
        }
        eras.put(Row.EraRow(era))
    }

    override fun deleteEra(eraId: Long) = write { deleteEraCascade(eraId) }

    private fun deleteEraCascade(eraId: Long) {
        eraAliases.referencing(eraId).filter { it.record.eraId == eraId }.forEach { eraAliases.remove(it.key) }
        periods.referencing(eraId).filter { it.record.eraId == eraId }
            .forEach { periods.put(Row.PeriodRow(it.record.copy(eraId = null))) }
        eras.remove(eraId)
    }

    override fun putEraAlias(alias: EraAliasRecord) = write {
        val key = requireNameKey(alias.alias, "An alias of era ${alias.eraId}")
        require(eraAliases.get(alias.chatKey to key) == null) {
            "Chat ${alias.chatKey} already has the era alias '${alias.alias}'; an alias names one era."
        }
        require(eras.get(alias.eraId) != null) { "Era ${alias.eraId} doesn't exist; write the era before its alias '${alias.alias}'." }
        eras.referencing(alias.chatKey)
            .firstOrNull { it.record.chatKey == alias.chatKey && it.record.id != alias.eraId && periodNameKey(it.record.name) == key }
            ?.let { throw IllegalArgumentException("The alias '${alias.alias}' is era ${it.record.id}'s name; pick another.") }
        eraAliases.put(Row.EraAliasRow(alias))
    }

    override fun putPeriodExclusion(exclusion: PeriodExclusionRecord) = write {
        periodMembers.get(exclusion.sceneId)?.let {
            throw IllegalArgumentException(
                "Scene ${exclusion.sceneId} belongs to period ${it.record.periodId}, so it can't be excluded from periods.",
            )
        }
        if (exclusions.get(exclusion.sceneId) == null) exclusions.put(Row.ExclusionRow(exclusion))
    }

    override fun putPeriodFailure(failure: PeriodFailureRecord) = write {
        val row = Row.FailureRow(failure)
        if (failure.count <= 0) failures.remove(row.key)
        else failures.put(Row.FailureRow(failure.copy(updatedAt = failure.updatedAt ?: Instant.now())))
    }

    override fun putPeriodGeneration(chatKey: String, generation: Long) = write {
        generations.put(Row.GenerationRow(chatKey, generation))
    }

    override fun putCatalogueSnapshot(snapshot: CatalogueSnapshot) = write { catalogues.put(Row.CatalogueRow(snapshot)) }

    override fun deletePeriodLayer(chatKey: String) = write {
        periods.referencing(chatKey).filter { it.record.chatKey == chatKey }.forEach { deletePeriodCascade(it.record.id) }
        periodAliases.referencing(chatKey).filter { it.record.chatKey == chatKey }.forEach { periodAliases.remove(it.key) }
        eras.referencing(chatKey).filter { it.record.chatKey == chatKey }.forEach { deleteEraCascade(it.record.id) }
        eraAliases.referencing(chatKey).filter { it.record.chatKey == chatKey }.forEach { eraAliases.remove(it.key) }
        exclusions.rows.values.filter { it.record.chatKey == chatKey }.forEach { exclusions.remove(it.key) }
        failures.rows.values.filter { it.record.chatKey == chatKey }.forEach { failures.remove(it.key) }
        generations.remove(chatKey)
        catalogues.rows.values.filter { it.record.chatKey == chatKey }.forEach { catalogues.remove(it.key) }
    }

    override fun batch(block: (LatticeStore) -> Unit) = write { block(this) }

    /**
     * Takes in rows written elsewhere (a remote op log) as they are: no constraint is checked, nothing cascades and
     * [onChange] hears nothing. A deleted engram leaves what names it dangling, so follow a run of changes with
     * [repair], which deletes those rows and reports them: the writer may not have known them.
     */
    fun applyRemote(changes: List<Change>) = lock.withLock {
        val wasSilent = silent
        silent = true
        try {
            for (change in changes) {
                val row = change.row
                when {
                    change.removed -> tableOf(row).remove(row.key)
                    row is Row.EngramRow -> engrams.put(Row.EngramRow(row.engram.snapshot()))
                    else -> tableOf(row).putAny(row)
                }
            }
        } finally {
            silent = wasSilent
        }
    }

    /** Empties the store and sets its embedding spec, telling [onChange] nothing (a mirror reloading a snapshot). */
    fun reset(spec: EmbeddingSpec?) = lock.withLock {
        check(depth == 0) { "A store can't be reset inside a batch; reset it after the batch." }
        tables.forEach { it.clear() }
        this.spec = spec
    }

    /**
     * Drops or detaches rows that name a row that isn't there, as the SQLite store's foreign keys would have: edges,
     * entity links, resolved markers, scene and period memberships, scene work of a missing scene, thread links of a
     * missing thread, aliases of a missing period or era; a missing hub or era is cleared. Changes are reported to [onChange]. Only rows taken in
     * through [applyRemote] can dangle.
     */
    fun repair() = write {
        val engramIds = engrams.rows.keys
        edges.rows.values.filter { it.edge.from !in engramIds || it.edge.to !in engramIds }.forEach { edges.remove(it.key) }
        entityLinks.rows.values.filter { it.engramId !in engramIds || entities.get(it.entityId) == null }
            .forEach { entityLinks.remove(it.key) }
        resolved.rows.values.filter { it.engramId !in engramIds }.forEach { resolved.remove(it.key) }
        scenes.rows.values.filter { it.record.hubEngramId?.let { hub -> hub !in engramIds } == true }
            .forEach { scenes.put(Row.SceneRow(it.record.copy(hubEngramId = null))) }
        sceneMembers.rows.values.filter { it.engramId !in engramIds || scenes.get(it.sceneId) == null }
            .forEach { sceneMembers.remove(it.key) }
        sceneWork.rows.values.filter { scenes.get(it.record.sceneId) == null }.forEach { sceneWork.remove(it.key) }
        threadLinks.rows.values.filter { threads.get(it.record.threadId) == null }.forEach { threadLinks.remove(it.key) }
        periods.rows.values.filter { it.record.eraId?.let { era -> eras.get(era) == null } == true }
            .forEach { periods.put(Row.PeriodRow(it.record.copy(eraId = null))) }
        periodMembers.rows.values.filter { periods.get(it.record.periodId) == null || scenes.get(it.record.sceneId) == null }
            .forEach { periodMembers.remove(it.key) }
        periodAliases.rows.values.filter { periods.get(it.record.periodId) == null }.forEach { periodAliases.remove(it.key) }
        eraAliases.rows.values.filter { eras.get(it.record.eraId) == null }.forEach { eraAliases.remove(it.key) }
    }

    /** Every row, the rows others name before the rows that name them: what a snapshot of this store holds. */
    fun rows(): List<Row> = lock.withLock {
        listOf(
            engrams, entities, scenes, threads, eras, periods, edges, verdicts, entityLinks, resolved, sceneMembers,
            sceneWork, sceneCursors, threadLinks, periodMembers, periodAliases, eraAliases, exclusions, failures,
            generations, catalogues,
        ).flatMap { it.rows.values.toList() }
    }

    private fun tableOf(row: Row): Table<*> = when (row) {
        is Row.EngramRow -> engrams
        is Row.EdgeRow -> edges
        is Row.VerdictRow -> verdicts
        is Row.EntityRow -> entities
        is Row.EntityLinkRow -> entityLinks
        is Row.ResolvedRow -> resolved
        is Row.SceneRow -> scenes
        is Row.SceneMemberRow -> sceneMembers
        is Row.SceneWorkRow -> sceneWork
        is Row.SceneCursorRow -> sceneCursors
        is Row.ThreadRow -> threads
        is Row.ThreadLinkRow -> threadLinks
        is Row.PeriodRow -> periods
        is Row.EraRow -> eras
        is Row.PeriodMemberRow -> periodMembers
        is Row.PeriodAliasRow -> periodAliases
        is Row.EraAliasRow -> eraAliases
        is Row.ExclusionRow -> exclusions
        is Row.FailureRow -> failures
        is Row.GenerationRow -> generations
        is Row.CatalogueRow -> catalogues
    }

    /** Runs [block] as one unit: on a throw, every row it changed is put back as it was. */
    private fun write(block: () -> Unit): Unit = lock.withLock {
        val mark = undo.size
        val changeMark = pending.size
        depth++
        try {
            block()
        } catch (t: Throwable) {
            rollbackTo(mark)
            while (pending.size > changeMark) pending.removeAt(pending.size - 1)
            throw t
        } finally {
            depth--
            if (depth == 0) undo.clear()
        }
        if (depth == 0 && pending.isNotEmpty()) {
            val committed = pending.toList()
            pending.clear()
            committed.forEach(onChange)
        }
    }

    private fun rollbackTo(mark: Int) {
        val wasSilent = silent
        silent = true
        try {
            while (undo.size > mark) {
                val (table, key, previous) = undo.removeAt(undo.size - 1)
                table.restore(key, previous)
            }
        } finally {
            silent = wasSilent
        }
    }

    private fun requireEngram(id: Long, what: String) =
        require(engrams.get(id) != null) { "Engram $id doesn't exist; write it before $what." }

    private fun requireEntity(id: Long, what: String) =
        require(entities.get(id) != null) { "Entity $id doesn't exist; write it before $what." }

    private fun requireScene(id: Long, what: String) =
        require(scenes.get(id) != null) { "Scene $id doesn't exist; write it before $what." }

    private fun requireNameKey(name: String, owner: String): String =
        periodNameKey(name).also { require(it.isNotEmpty()) { "$owner has the name '$name', which is blank once normalized; name it." } }

    /** One kind of row, keyed by [Row.key], with an optional index from referenced values to the keys that name them. */
    private inner class Table<R : Row>(private val indexOf: ((R) -> List<Any>)? = null) {
        val rows = LinkedHashMap<Any, R>()
        private val index = HashMap<Any, LinkedHashSet<Any>>()

        init {
            tables += this
        }

        fun get(key: Any): R? = rows[key]

        /** Rows whose indexed values include [value]; callers still check which field matched. */
        fun referencing(value: Any): List<R> = index[value]?.mapNotNull { rows[it] } ?: emptyList()

        fun put(row: R) {
            val previous = rawPut(row)
            logged(row.key, previous)
            if (!silent) pending += Change(row, removed = false)
        }

        @Suppress("UNCHECKED_CAST")
        fun putAny(row: Row) = put(row as R)

        fun remove(key: Any): R? {
            val previous = rawRemove(key) ?: return null
            logged(key, previous)
            if (!silent) pending += Change(previous, removed = true)
            return previous
        }

        @Suppress("UNCHECKED_CAST")
        fun restore(key: Any, previous: Row?) {
            if (previous == null) rawRemove(key) else rawPut(previous as R)
        }

        fun clear() {
            rows.clear()
            index.clear()
        }

        private fun logged(key: Any, previous: Row?) {
            if (depth > 0) undo += Triple(this, key, previous)
        }

        private fun rawPut(row: R): R? {
            val previous = rows.put(row.key, row)
            previous?.let(::unindex)
            indexOf?.invoke(row)?.forEach { index.getOrPut(it) { LinkedHashSet() } += row.key }
            return previous
        }

        private fun rawRemove(key: Any): R? = rows.remove(key)?.also(::unindex)

        private fun unindex(row: R) {
            indexOf?.invoke(row)?.forEach { value ->
                index[value]?.let { keys ->
                    keys -= row.key
                    if (keys.isEmpty()) index -= value
                }
            }
        }
    }

    private companion object {
        fun edgeKey(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a

        fun Engram.snapshot(): Engram = copy(embedding = embedding.copyOf())
    }
}

private val NAME_KEY_IGNORED: Set<Int> = "「」『』“”‘’\"'`《》〈〉·・•".codePoints().toArray().toSet()

/**
 * The key a period's or an era's name and aliases are unique under within a chat: NFKC, lower case, with whitespace,
 * quotes, interpuncts and invisible characters dropped, so `「海边」` and ` 海 边 ` collide.
 */
fun periodNameKey(name: String): String {
    val folded = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val out = StringBuilder(folded.length)
    var i = 0
    while (i < folded.length) {
        val cp = folded.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp in NAME_KEY_IGNORED) continue
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR, Character.SURROGATE, Character.UNASSIGNED,
            -> continue
        }
        out.appendCodePoint(cp)
    }
    return out.toString()
}
