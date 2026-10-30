package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RAM index [LatticeGraph] keeps over the period layer (scene -> period, period -> scenes, chat -> periods in
 * first-message order, name / alias -> period, eras, the scene trust cache) and the write validation that mirrors
 * the database constraints.
 */
class PeriodIndexTest {

    private val clock = { Instant.parse("2026-09-28T00:00:00Z") }

    private fun graph(store: LatticeStore = NoOpLatticeStore) = LatticeGraph(LocalHashingEmbedder(), now = clock, store = store)

    private fun scene(id: Long, first: Long, date: String, chat: String = CHAT) = SceneRecord(
        sceneId = id, title = "场景$id", conversationId = "$chat:s1", chatKey = chat,
        firstMessageId = first, lastMessageId = first + 5, dateFrom = date, dateTo = date,
    )

    /** Three periods whose allocation order differs from their chronological (first message id) order. */
    private fun threePeriods(g: LatticeGraph): Triple<Long, Long, Long> {
        // Scene ids out of chronological order, as a catch-up filling an earlier gap produces them.
        g.putScene(scene(5, 10, "2026-07-22"))
        g.putScene(scene(2, 20, "2026-07-23"))
        g.putScene(scene(3, 30, "2026-07-29"))
        g.putScene(scene(1, 40, "2026-08-01"))
        g.putScene(scene(4, 50, "2026-08-04"))
        val late = g.allocatePeriodId()
        val early = g.allocatePeriodId()
        val middle = g.allocatePeriodId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(
                    PeriodRecord(early, CHAT, "Summer Trip"),
                    PeriodRecord(middle, CHAT, "新家与朋友"),
                    PeriodRecord(late, CHAT, "球赛", state = PeriodState.PROVISIONAL),
                ),
                members = listOf(
                    PeriodMemberRecord(2, early), PeriodMemberRecord(5, early),
                    PeriodMemberRecord(3, middle), PeriodMemberRecord(1, middle),
                    PeriodMemberRecord(4, late),
                ),
                periodAliases = listOf(PeriodAliasRecord(CHAT, "新家之前", middle)),
            ),
        )
        return Triple(early, middle, late)
    }

    @Test
    fun `scene to period, period to scenes and the chat's periods follow first message order`() {
        val g = graph()
        val (early, middle, late) = threePeriods(g)

        assertEquals(early, g.periodOfScene(5)?.id)
        assertEquals(middle, g.periodOfScene(1)?.id)
        assertNull(g.periodOfScene(99))
        assertEquals(listOf(5L, 2L), g.periodScenes(early), "by first message id, never by scene id")
        assertEquals(listOf(3L, 1L), g.periodScenes(middle))
        assertEquals(listOf(early, middle, late), g.periods(CHAT).map { it.id }, "by the first scene's first message id")
        assertTrue(g.periods(OTHER_CHAT).isEmpty())
        assertEquals(late, g.provisionalPeriod(CHAT)?.id)
        assertNull(g.provisionalPeriod(OTHER_CHAT))
    }

    @Test
    fun `lookup by current name or alias is normalized and per chat`() {
        val g = graph()
        val (early, middle, _) = threePeriods(g)

        assertEquals(early, g.periodByName(CHAT, "Summer Trip")?.id)
        assertEquals(early, g.periodByName(CHAT, "  summer   TRIP ")?.id, "case and spacing do not matter")
        assertEquals(early, g.periodByName(CHAT, "ＳＵＭＭＥＲ　ＴＲＩＰ")?.id, "full-width forms fold (NFKC)")
        assertEquals(middle, g.periodByName(CHAT, "新家与朋友")?.id)
        assertEquals(middle, g.periodByName(CHAT, "「新家之前」")?.id, "an alias finds its period; render quotes are ignored")
        assertNull(g.periodByName(CHAT, "新家"), "exact match only (substring search is the tool's job)")
        assertNull(g.periodByName(OTHER_CHAT, "Summer Trip"), "another chat's names are invisible")
    }

    @Test
    fun `a rename keeps the old name reachable through its alias`() {
        val g = graph()
        val (_, middle, late) = threePeriods(g)
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(g.period(late)!!.copy(name = "新家、朋友与球赛")),
                periodAliases = listOf(PeriodAliasRecord(CHAT, "球赛", late)),
            ),
        )
        assertEquals(late, g.periodByName(CHAT, "新家、朋友与球赛")?.id)
        assertEquals(late, g.periodByName(CHAT, "球赛")?.id)
        assertEquals(listOf("球赛"), g.periodAliases(late).map { it.alias })
        assertEquals(middle, g.periodByName(CHAT, "新家之前")?.id)
    }

    @Test
    fun `eras list their periods and are found by name or alias`() {
        val g = graph()
        val (early, middle, _) = threePeriods(g)
        val older = g.allocateEraId()
        val newer = g.allocateEraId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                eras = listOf(
                    EraRecord(newer, CHAT, "新家", namingSceneIds = setOf(3, 1)),
                    EraRecord(older, CHAT, "夏天", frozen = true, namingSceneIds = setOf(5, 2)),
                ),
                periods = listOf(g.period(middle)!!.copy(eraId = newer), g.period(early)!!.copy(eraId = older)),
                eraAliases = listOf(EraAliasRecord(CHAT, "最初", older)),
            ),
        )
        assertEquals(listOf(older, newer), g.eras(CHAT).map { it.id }, "eras by their first period")
        assertEquals(listOf(early), g.eraPeriods(older).map { it.id })
        assertEquals(older, g.eraByName(CHAT, "夏天")?.id)
        assertEquals(older, g.eraByName(CHAT, "最初")?.id)
        assertNull(g.eraByName(CHAT, "Summer Trip"), "eras are a layer of their own")
        assertNull(g.periodByName(CHAT, "夏天"))
        assertEquals("2026-07-29" to "2026-08-01", g.era(newer)!!.let { it.dateFrom to it.dateTo })

        // Deleting an era leaves its periods without one (the database's ON DELETE SET NULL).
        g.applyPeriodWrite(PeriodWrite(CHAT, deleteEraIds = setOf(newer)))
        assertNull(g.era(newer))
        assertNull(g.period(middle)!!.eraId)
    }

    @Test
    fun `the write is validated against the state it would produce`() {
        val g = graph()
        val (early, _, late) = threePeriods(g)
        g.putScene(scene(6, 60, "2026-08-05"))
        g.putScene(scene(7, 70, "2026-08-06", chat = OTHER_CHAT))
        g.applyPeriodWrite(PeriodWrite(CHAT, exclusions = listOf(PeriodExclusionRecord(6, CHAT, "no-user-message"))))
        val next = g.allocatePeriodId()

        fun refused(write: PeriodWrite) = assertFailsWith<IllegalArgumentException> { g.applyPeriodWrite(write) }

        refused(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(6, late)))) // an excluded scene
        refused(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(7, late)))) // another chat's scene
        refused(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(404, late)))) // no scene row
        refused(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(5, late)))) // already in another period
        refused(PeriodWrite(CHAT, periods = listOf(PeriodRecord(next, CHAT, "空的")))) // a period without scenes
        refused(PeriodWrite(CHAT, periods = listOf(PeriodRecord(next, OTHER_CHAT, "别的聊天")))) // chat mismatch
        refused(PeriodWrite(CHAT, periods = listOf(g.period(late)!!.copy(name = "summer trip")))) // name taken
        refused(PeriodWrite(CHAT, periods = listOf(g.period(late)!!.copy(name = "「」 ")))) // nothing left of the name
        refused(PeriodWrite(CHAT, periods = listOf(g.period(late)!!.copy(eraId = 12345)))) // unknown era
        refused(PeriodWrite(CHAT, exclusions = listOf(PeriodExclusionRecord(5, CHAT)))) // a member cannot be excluded

        // Nothing of the refused writes reached the index.
        assertNull(g.period(next))
        assertEquals("球赛", g.period(late)?.name)
        assertEquals(listOf(4L), g.periodScenes(late))
        assertTrue(g.isExcludedFromPeriods(6))

        // Re-adding a scene to the period it already belongs to is a no-op, not a conflict (idempotent re-run).
        g.applyPeriodWrite(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(5, early))))
        assertEquals(listOf(5L, 2L), g.periodScenes(early))
    }

    @Test
    fun `fallback periods can be deleted and their scenes re-split in one write`() {
        val g = graph()
        val (early, middle, late) = threePeriods(g)
        val merged = g.allocatePeriodId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                deletePeriodIds = setOf(middle, late),
                periods = listOf(PeriodRecord(merged, CHAT, "新家与朋友", state = PeriodState.PROVISIONAL)),
                members = listOf(PeriodMemberRecord(3, merged), PeriodMemberRecord(1, merged), PeriodMemberRecord(4, merged)),
            ),
        )
        assertEquals(listOf(early, merged), g.periods(CHAT).map { it.id })
        assertEquals(merged, g.periodOfScene(4)?.id)
        assertTrue(g.periodAliases(middle).isEmpty(), "a deleted period's aliases go with it")
        assertNull(g.periodByName(CHAT, "新家之前"))
    }

    @Test
    fun `generation moves with the period structure, not with failure counters`() {
        val g = graph()
        assertEquals(0L, g.periodGeneration(CHAT))
        threePeriods(g)
        assertEquals(1L, g.periodGeneration(CHAT))
        g.applyPeriodWrite(PeriodWrite(CHAT, failures = listOf(PeriodFailureRecord(CHAT, PeriodFailureKind.FORMATION, 6, 1))))
        assertEquals(1L, g.periodGeneration(CHAT), "a failure count alone changes no period")
        assertEquals(1, g.periodFailureCount(CHAT, PeriodFailureKind.FORMATION, 6))
        g.applyPeriodWrite(PeriodWrite(CHAT, failures = listOf(PeriodFailureRecord(CHAT, PeriodFailureKind.FORMATION, 6, 0))))
        assertEquals(0, g.periodFailureCount(CHAT, PeriodFailureKind.FORMATION, 6), "count 0 clears the counter")
        g.applyPeriodWrite(PeriodWrite(CHAT, catalogues = listOf(CatalogueSnapshot(CHAT, "zh-CN", "目录"))))
        assertEquals(1L, g.periodGeneration(CHAT), "a re-render publishes without a new generation")
        assertEquals(1L, g.catalogueSnapshot(CHAT, "zh_CN")?.sourceGeneration, "language keys normalize")
        assertEquals(0L, g.periodGeneration(OTHER_CHAT))
    }

    @Test
    fun `a reset drops the chat's period layer but keeps counting generations`() {
        val g = graph()
        threePeriods(g)
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                exclusions = listOf(PeriodExclusionRecord(99, CHAT)),
                failures = listOf(PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, 5, 2)),
                catalogues = listOf(CatalogueSnapshot(CHAT, "zh-CN", "目录")),
            ),
        )
        val generation = g.periodGeneration(CHAT)
        g.applyPeriodWrite(PeriodWrite(CHAT, reset = true))
        assertTrue(g.periods(CHAT).isEmpty())
        assertNull(g.periodOfScene(5))
        assertTrue(g.periodExclusions(CHAT).isEmpty())
        assertEquals(0, g.periodFailureCount(CHAT, PeriodFailureKind.ERA, 5))
        assertNull(g.catalogueSnapshot(CHAT, "zh-CN"))
        assertTrue(g.periodGeneration(CHAT) > generation, "an in-flight result against the old state stays stale")
        threePeriods(g) // the same scenes can be formed again
        assertEquals(3, g.periods(CHAT).size)
    }

    @Test
    fun `the scene trust cache answers only what was put into it`() {
        val g = graph()
        assertNull(g.sceneTrust(1))
        g.putSceneTrust(mapOf(1L to true, 2L to false))
        assertEquals(true, g.sceneTrust(1))
        assertEquals(false, g.sceneTrust(2))
        g.putSceneTrust(mapOf(1L to false))
        assertEquals(false, g.sceneTrust(1), "a later verdict replaces the cached one")
        assertNull(g.sceneTrust(3))
    }

    private companion object {
        const val CHAT = "telegram:1"
        const val OTHER_CHAT = "telegram:2"
    }
}
