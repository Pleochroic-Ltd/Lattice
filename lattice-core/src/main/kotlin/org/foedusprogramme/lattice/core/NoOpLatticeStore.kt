package org.foedusprogramme.lattice.core

/** A store that keeps nothing: a graph that lives only in memory (tests, throwaway graphs). */
object NoOpLatticeStore : LatticeStore {
    override val idSlot: Int get() = 0
    override val embedding: EmbeddingSpec? get() = null

    override fun declareEmbedding(spec: EmbeddingSpec) = Unit
    override fun load(): LatticeStore.Loaded = LatticeStore.Loaded()
    override fun putEngram(engram: Engram) = Unit
    override fun deleteEngram(id: Long) = Unit
    override fun putEdge(edge: EdgeRecord) = Unit
    override fun deleteEdge(a: Long, b: Long) = Unit
    override fun putVerdict(verdict: VerdictRecord) = Unit
    override fun putEntity(entity: EntityRecord) = Unit
    override fun linkEngramEntity(engramId: Long, entityId: Long) = Unit
    override fun markEntityResolved(engramId: Long) = Unit
    override fun mergeEntities(fromId: Long, toId: Long) = Unit
    override fun putScene(scene: SceneRecord) = Unit
    override fun putSceneWork(work: SceneWorkRecord) = Unit
    override fun putSceneCursor(cursor: SceneCursorRecord) = Unit
    override fun putSceneMember(sceneId: Long, engramId: Long) = Unit
    override fun redirectSceneMembers(fromEngramId: Long, toEngramId: Long) = Unit
    override fun putThread(thread: ThreadRecord) = Unit
    override fun putThreadLink(link: ThreadLinkRecord) = Unit
    override fun redirectThreadLinks(fromEngramId: Long, toEngramId: Long) = Unit
    override fun deleteThreadLinks(engramId: Long) = Unit
    override fun putPeriod(period: PeriodRecord) = Unit
    override fun deletePeriod(periodId: Long) = Unit
    override fun putPeriodMember(member: PeriodMemberRecord) = Unit
    override fun putPeriodAlias(alias: PeriodAliasRecord) = Unit
    override fun putEra(era: EraRecord) = Unit
    override fun deleteEra(eraId: Long) = Unit
    override fun putEraAlias(alias: EraAliasRecord) = Unit
    override fun putPeriodExclusion(exclusion: PeriodExclusionRecord) = Unit
    override fun putPeriodFailure(failure: PeriodFailureRecord) = Unit
    override fun putPeriodGeneration(chatKey: String, generation: Long) = Unit
    override fun putCatalogueSnapshot(snapshot: CatalogueSnapshot) = Unit
    override fun deletePeriodLayer(chatKey: String) = Unit
    override fun batch(block: (LatticeStore) -> Unit) = block(this)
}
