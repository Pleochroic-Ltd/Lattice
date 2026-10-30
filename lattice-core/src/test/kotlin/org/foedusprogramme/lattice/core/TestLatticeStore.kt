package org.foedusprogramme.lattice.core

/**
 * A store for graph tests that keeps every write in memory and hands it back from [load], so a test can open a second
 * graph over the first one's state. It follows the [LatticeStore] contract (cascades included) but checks nothing.
 * [writes] counts calls by method name, for tests that care what was written through.
 */
open class TestLatticeStore(
    override val idSlot: Int = 0,
    initial: LatticeStore.Loaded = LatticeStore.Loaded(),
) : LatticeStore {
    override var embedding: EmbeddingSpec? = null

    val engrams = LinkedHashMap<Long, Engram>()
    /** Keyed by the unordered pair (min, max); the value keeps the written direction. */
    val edges = LinkedHashMap<Pair<Long, Long>, EdgeRecord>()
    val verdicts = LinkedHashMap<Triple<String, String, String>, VerdictRecord>()
    val entities = LinkedHashMap<Long, EntityRecord>()
    val entityLinks = LinkedHashSet<Pair<Long, Long>>()
    val resolved = LinkedHashSet<Long>()
    val scenes = LinkedHashMap<Long, SceneRecord>()
    val sceneMembers = LinkedHashSet<Pair<Long, Long>>()
    val threads = LinkedHashMap<Long, ThreadRecord>()
    val threadLinks = LinkedHashSet<ThreadLinkRecord>()
    val periods = LinkedHashMap<Long, PeriodRecord>()
    val eras = LinkedHashMap<Long, EraRecord>()
    val periodMembers = LinkedHashMap<Long, PeriodMemberRecord>()
    val periodAliases = ArrayList<PeriodAliasRecord>()
    val eraAliases = ArrayList<EraAliasRecord>()
    val exclusions = LinkedHashMap<Long, PeriodExclusionRecord>()
    val failures = LinkedHashMap<Triple<String, PeriodFailureKind, Long>, PeriodFailureRecord>()
    val generations = LinkedHashMap<String, Long>()
    val catalogues = LinkedHashMap<Pair<String, String>, CatalogueSnapshot>()
    val writes = HashMap<String, Int>()

    init {
        initial.engrams.forEach { engrams[it.id] = it.copy() }
        initial.edges.forEach { edges[key(it.from, it.to)] = it }
        initial.verdicts.forEach { verdicts[Triple(it.hashLo, it.hashHi, it.judgeKey)] = it }
        initial.entities.forEach { entities[it.id] = it }
        entityLinks += initial.entityLinks
        resolved += initial.resolvedEntityIds
        initial.scenes.forEach { scenes[it.sceneId] = it }
        sceneMembers += initial.sceneMembers
        initial.threads.forEach { threads[it.id] = it }
        threadLinks += initial.threadLinks
        initial.periods.forEach { periods[it.id] = it }
        initial.eras.forEach { eras[it.id] = it }
        initial.periodMembers.forEach { periodMembers[it.sceneId] = it }
        periodAliases += initial.periodAliases
        eraAliases += initial.eraAliases
        initial.periodExclusions.forEach { exclusions[it.sceneId] = it }
        initial.periodFailures.forEach { failures[Triple(it.chatKey, it.kind, it.firstSceneId)] = it }
        generations += initial.periodGenerations
        initial.catalogueSnapshots.forEach { catalogues[it.chatKey to it.language] = it }
    }

    private fun key(a: Long, b: Long) = minOf(a, b) to maxOf(a, b)

    private fun count(name: String) {
        writes.merge(name, 1, Int::plus)
    }

    fun edge(a: Long, b: Long): EdgeRecord? = edges[key(a, b)]

    override fun declareEmbedding(spec: EmbeddingSpec) {
        embedding = spec
    }

    override fun load(): LatticeStore.Loaded = LatticeStore.Loaded(
        engrams = engrams.values.map { it.copy() },
        edges = edges.values.toList(),
        verdicts = verdicts.values.toList(),
        entities = entities.values.toList(),
        entityLinks = entityLinks.toList(),
        resolvedEntityIds = resolved.toSet(),
        scenes = scenes.values.toList(),
        sceneMembers = sceneMembers.toList(),
        threads = threads.values.toList(),
        threadLinks = threadLinks.toList(),
        periods = periods.values.toList(),
        eras = eras.values.toList(),
        periodMembers = periodMembers.values.toList(),
        periodAliases = periodAliases.toList(),
        eraAliases = eraAliases.toList(),
        periodExclusions = exclusions.values.toList(),
        periodFailures = failures.values.toList(),
        periodGenerations = generations.toMap(),
        catalogueSnapshots = catalogues.values.toList(),
    )

    override fun putEngram(engram: Engram) {
        count("putEngram")
        engrams[engram.id] = engram.copy()
    }

    override fun deleteEngram(id: Long) {
        count("deleteEngram")
        engrams.remove(id)
        edges.keys.removeIf { it.first == id || it.second == id }
        entityLinks.removeIf { it.first == id }
        resolved.remove(id)
        sceneMembers.removeIf { it.second == id }
        threadLinks.removeIf { it.engramId == id }
        scenes.replaceAll { _, s -> if (s.hubEngramId == id) s.copy(hubEngramId = null) else s }
    }

    override fun putEdge(edge: EdgeRecord) {
        count("putEdge")
        edges[key(edge.from, edge.to)] = edge
    }

    override fun deleteEdge(a: Long, b: Long) {
        count("deleteEdge")
        edges.remove(key(a, b))
    }

    override fun putVerdict(verdict: VerdictRecord) {
        count("putVerdict")
        verdicts[Triple(verdict.hashLo, verdict.hashHi, verdict.judgeKey)] = verdict
    }

    override fun putEntity(entity: EntityRecord) {
        count("putEntity")
        entities[entity.id] = entity
    }

    override fun linkEngramEntity(engramId: Long, entityId: Long) {
        count("linkEngramEntity")
        entityLinks += engramId to entityId
    }

    override fun markEntityResolved(engramId: Long) {
        count("markEntityResolved")
        resolved += engramId
    }

    override fun mergeEntities(fromId: Long, toId: Long) {
        count("mergeEntities")
        val moved = entityLinks.filter { it.second == fromId }
        entityLinks.removeAll(moved.toSet())
        moved.forEach { entityLinks += it.first to toId }
        entities.remove(fromId)
    }

    override fun putScene(scene: SceneRecord) {
        count("putScene")
        scenes[scene.sceneId] = scene
    }

    override fun putSceneMember(sceneId: Long, engramId: Long) {
        count("putSceneMember")
        sceneMembers += sceneId to engramId
    }

    override fun redirectSceneMembers(fromEngramId: Long, toEngramId: Long) {
        count("redirectSceneMembers")
        val moved = sceneMembers.filter { it.second == fromEngramId }
        sceneMembers.removeAll(moved.toSet())
        moved.forEach { sceneMembers += it.first to toEngramId }
    }

    override fun putThread(thread: ThreadRecord) {
        count("putThread")
        threads[thread.id] = thread
    }

    override fun putThreadLink(link: ThreadLinkRecord) {
        count("putThreadLink")
        threadLinks += link
    }

    override fun redirectThreadLinks(fromEngramId: Long, toEngramId: Long) {
        count("redirectThreadLinks")
        val moved = threadLinks.filter { it.engramId == fromEngramId }
        threadLinks.removeAll(moved.toSet())
        moved.forEach { threadLinks += it.copy(engramId = toEngramId) }
    }

    override fun deleteThreadLinks(engramId: Long) {
        count("deleteThreadLinks")
        threadLinks.removeIf { it.engramId == engramId }
    }

    override fun putPeriod(period: PeriodRecord) {
        count("putPeriod")
        periods[period.id] = period
    }

    override fun deletePeriod(periodId: Long) {
        count("deletePeriod")
        periods.remove(periodId)
        periodMembers.values.removeIf { it.periodId == periodId }
        periodAliases.removeIf { it.periodId == periodId }
    }

    override fun putPeriodMember(member: PeriodMemberRecord) {
        count("putPeriodMember")
        periodMembers[member.sceneId] = member
    }

    override fun putPeriodAlias(alias: PeriodAliasRecord) {
        count("putPeriodAlias")
        periodAliases += alias
    }

    override fun putEra(era: EraRecord) {
        count("putEra")
        eras[era.id] = era
    }

    override fun deleteEra(eraId: Long) {
        count("deleteEra")
        eras.remove(eraId)
        eraAliases.removeIf { it.eraId == eraId }
        periods.replaceAll { _, p -> if (p.eraId == eraId) p.copy(eraId = null) else p }
    }

    override fun putEraAlias(alias: EraAliasRecord) {
        count("putEraAlias")
        eraAliases += alias
    }

    override fun putPeriodExclusion(exclusion: PeriodExclusionRecord) {
        count("putPeriodExclusion")
        exclusions.putIfAbsent(exclusion.sceneId, exclusion)
    }

    override fun putPeriodFailure(failure: PeriodFailureRecord) {
        count("putPeriodFailure")
        val k = Triple(failure.chatKey, failure.kind, failure.firstSceneId)
        if (failure.count <= 0) failures.remove(k) else failures[k] = failure
    }

    override fun putPeriodGeneration(chatKey: String, generation: Long) {
        count("putPeriodGeneration")
        generations[chatKey] = generation
    }

    override fun putCatalogueSnapshot(snapshot: CatalogueSnapshot) {
        count("putCatalogueSnapshot")
        catalogues[snapshot.chatKey to snapshot.language] = snapshot
    }

    override fun deletePeriodLayer(chatKey: String) {
        count("deletePeriodLayer")
        val ids = periods.values.filter { it.chatKey == chatKey }.map { it.id }.toSet()
        periods.keys.removeAll(ids)
        periodMembers.values.removeIf { it.periodId in ids }
        periodAliases.removeIf { it.chatKey == chatKey }
        eras.values.removeIf { it.chatKey == chatKey }
        eraAliases.removeIf { it.chatKey == chatKey }
        exclusions.values.removeIf { it.chatKey == chatKey }
        failures.keys.removeIf { it.first == chatKey }
        catalogues.keys.removeIf { it.first == chatKey }
    }

    override fun batch(block: (LatticeStore) -> Unit) = block(this)
}
