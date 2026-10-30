package org.foedusprogramme.lattice.core

/**
 * One consistent snapshot of a chat's history top-down — its eras, their periods, the periods' scenes — as plain data
 * for a timeline tool ("what have we been doing all this time"). Every list is in [SCENE_ORDER]: scenes directly,
 * periods by their first scene, eras by their first period.
 *
 * Trust: verdicts come from the graph's scene trust cache only ([LatticeGraph.sceneTrust], filled by period formation),
 * as recall reads them — a scene never judged is untrusted. A period or era whose name the catalogue masks
 * ([periodNameShown] false) has [Period.shown] / [Era.shown] false: a tool should list it by its dates and find it by a
 * date inside it, never by its names. An untrusted scene's title and messages are content nobody vouched for.
 */
data class ChatTimeline(
    val chatKey: String,
    val eras: List<Era>,
    val periods: List<Period>,
    val scenes: List<Scene>,
) {
    data class Scene(
        val id: Long,
        val title: String,
        val conversationId: String,
        /** The host's positions of its first and last message, within [conversationId]. */
        val firstMessageId: Long,
        val lastMessageId: Long,
        val dateFrom: String?,
        val dateTo: String?,
        /** Judged trusted by the scene trust cache; unknown (never judged) is untrusted. */
        val trusted: Boolean,
        /** Its scene pipeline state; null for a scene the pipeline did not write. */
        val state: SceneWorkState?,
        val periodId: Long?,
        /** It never joins a period (no message of a person in it). */
        val excluded: Boolean,
    )

    data class Period(
        val record: PeriodRecord,
        val aliases: List<String>,
        /** Its scenes, in order. */
        val sceneIds: List<Long>,
        /** Its name, old names and gist may be listed ([periodNameShown]). */
        val shown: Boolean,
    ) {
        val provisional: Boolean get() = record.state == PeriodState.PROVISIONAL
    }

    data class Era(
        val record: EraRecord,
        val aliases: List<String>,
        /** Its periods, in order. */
        val periodIds: List<Long>,
        /** As [Period.shown], over every scene of its periods. */
        val shown: Boolean,
    )

    val eraById: Map<Long, Era> by lazy { eras.associateBy { it.record.id } }
    val periodById: Map<Long, Period> by lazy { periods.associateBy { it.record.id } }
    val sceneById: Map<Long, Scene> by lazy { scenes.associateBy { it.id } }

    /** The first period's first day, or null without periods. */
    val coveredFrom: String? get() = periods.mapNotNull { it.record.dateFrom }.minOrNull()

    /** The last period's last day, or null without periods. */
    val coveredTo: String? get() = periods.mapNotNull { it.record.dateTo ?: it.record.dateFrom }.maxOrNull()

    /** Scenes in no period that are not excluded: still to be organised. */
    val pending: List<Scene> get() = scenes.filter { it.periodId == null && !it.excluded }

    /** The scenes of [periodId], in order. */
    fun scenesOf(periodId: Long): List<Scene> = periodById[periodId]?.sceneIds.orEmpty().mapNotNull(sceneById::get)

    /** The periods of [eraId], in order. */
    fun periodsOf(eraId: Long): List<Period> = eraById[eraId]?.periodIds.orEmpty().mapNotNull(periodById::get)

    companion object {
        /** The timeline of [chatKey] as [g] holds it now. Call on the graph thread. */
        fun read(g: LatticeGraph, chatKey: String): ChatTimeline {
            val trusted: (Long) -> Boolean = { g.sceneTrust(it) == true }
            val periods = g.periods(chatKey).map { p ->
                val scenes = g.periodScenes(p.id)
                Period(p, g.periodAliases(p.id).map { it.alias }, scenes, periodNameShown(p.namingSceneIds, scenes, trusted))
            }
            val eras = g.eras(chatKey).map { e ->
                val periodIds = g.eraPeriods(e.id).map { it.id }
                val members = periodIds.flatMap { g.periodScenes(it) }
                Era(e, g.eraAliases(e.id).map { it.alias }, periodIds, periodNameShown(e.namingSceneIds, members, trusted))
            }
            val scenes = g.scenes(chatKey).sortedWith(SCENE_ORDER).map { s ->
                Scene(
                    id = s.sceneId,
                    title = s.title,
                    conversationId = s.conversationId,
                    firstMessageId = s.firstMessageId,
                    lastMessageId = s.lastMessageId,
                    dateFrom = s.dateFrom,
                    dateTo = s.dateTo ?: s.dateFrom,
                    trusted = trusted(s.sceneId),
                    state = g.sceneWork(s.sceneId)?.state,
                    periodId = g.periodOfScene(s.sceneId)?.id,
                    excluded = g.isExcludedFromPeriods(s.sceneId),
                )
            }
            return ChatTimeline(chatKey, eras, periods, scenes)
        }
    }
}
