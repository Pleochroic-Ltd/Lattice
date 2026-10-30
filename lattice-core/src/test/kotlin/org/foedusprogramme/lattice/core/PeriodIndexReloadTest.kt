package org.foedusprogramme.lattice.core

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Periods, eras, aliases, members, exclusion markers, failure counters, the per-chat generation and the
 * per-language catalogue snapshots through a reload of the graph from its store ([TestLatticeStore]): derived date
 * ranges, the graph-side constraints, and a write whose batch fails leaving the index untouched.
 * Offline ([LocalHashingEmbedder]).
 */
class PeriodIndexReloadTest {

    private val clock = { Instant.parse("2026-09-28T00:00:00Z") }

    private fun graphOn(store: LatticeStore = NoOpLatticeStore) = LatticeGraph(LocalHashingEmbedder(), now = clock, store = store)

    /** A second graph over everything [store] holds. */
    private fun reopen(store: TestLatticeStore) = graphOn(TestLatticeStore(initial = store.load()))

    private fun scene(id: Long, first: Long, date: String, dateTo: String = date, chat: String = CHAT) = SceneRecord(
        sceneId = id, title = "场景$id", conversationId = "$chat:s1", chatKey = chat,
        firstMessageId = first, lastMessageId = first + 5, dateFrom = date, dateTo = dateTo,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `every period-layer row survives a reopen`() {
        val store = TestLatticeStore()
        val era: Long
        val p1: Long
        val p2: Long
        run {
            val g = graphOn(store)
            g.putScene(scene(1, 10, "2026-07-22"))
            g.putScene(scene(2, 20, "2026-07-23", "2026-07-24"))
            g.putScene(scene(3, 30, "2026-07-29"))
            g.putScene(scene(4, 40, "2026-08-01", "2026-08-04"))
            g.putScene(scene(9, 45, "2026-08-02"))
            era = g.allocateEraId()
            p1 = g.allocatePeriodId()
            p2 = g.allocatePeriodId()
            val generation = g.applyPeriodWrite(
                PeriodWrite(
                    chatKey = CHAT,
                    eras = listOf(EraRecord(era, CHAT, "最初的日子", gist = "刚开始的那几天", frozen = true, namingSceneIds = setOf(1, 2))),
                    periods = listOf(
                        PeriodRecord(p1, CHAT, "工作与通勤", gist = "聊上班", eraId = era, namingSceneIds = setOf(1, 2)),
                        PeriodRecord(
                            p2, CHAT, "新家与朋友", gist = "生活的转折", state = PeriodState.PROVISIONAL,
                            needsRename = true, namingSceneIds = setOf(3, 4),
                        ),
                    ),
                    members = listOf(PeriodMemberRecord(1, p1), PeriodMemberRecord(2, p1), PeriodMemberRecord(3, p2), PeriodMemberRecord(4, p2)),
                    periodAliases = listOf(PeriodAliasRecord(CHAT, "新家之前", p2)),
                    eraAliases = listOf(EraAliasRecord(CHAT, "起初", era)),
                    exclusions = listOf(PeriodExclusionRecord(9, CHAT, reason = "no-user-message")),
                    failures = listOf(
                        PeriodFailureRecord(CHAT, PeriodFailureKind.FORMATION, firstSceneId = 5, count = 2),
                        PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, firstSceneId = 1, count = 1),
                    ),
                    catalogues = listOf(
                        CatalogueSnapshot(CHAT, "zh-CN", "目录：工作与通勤；新家与朋友", coverage = "始于 7/22；截至 8/4"),
                        CatalogueSnapshot(CHAT, "en", "Catalogue: commuting; the new place", coverage = "from 7/22 to 8/4"),
                    ),
                ),
            )
            assertEquals(1L, generation, "one formation write bumps the generation once")
        }

        val g2 = reopen(store)
        val first = assertNotNull(g2.period(p1))
        assertEquals("工作与通勤", first.name)
        assertEquals("聊上班", first.gist)
        assertEquals(PeriodState.CLOSED, first.state)
        assertEquals(era, first.eraId)
        assertEquals(setOf(1L, 2L), first.namingSceneIds)
        assertEquals("2026-07-22" to "2026-07-24", first.dateFrom to first.dateTo, "range derived from the member scenes")
        val second = assertNotNull(g2.period(p2))
        assertEquals(PeriodState.PROVISIONAL, second.state)
        assertTrue(second.needsRename)
        assertNull(second.eraId)
        assertEquals("2026-07-29" to "2026-08-04", second.dateFrom to second.dateTo)
        assertEquals(listOf(p1, p2), g2.periods(CHAT).map { it.id })
        assertEquals(listOf(1L, 2L), g2.periodScenes(p1))
        assertEquals(p2, g2.periodOfScene(4)?.id)
        assertEquals(p2, g2.provisionalPeriod(CHAT)?.id)
        assertEquals(listOf("新家之前"), g2.periodAliases(p2).map { it.alias })

        val theEra = assertNotNull(g2.era(era))
        assertEquals("最初的日子", theEra.name)
        assertEquals("刚开始的那几天", theEra.gist)
        assertTrue(theEra.frozen)
        assertEquals(setOf(1L, 2L), theEra.namingSceneIds)
        assertEquals("2026-07-22" to "2026-07-24", theEra.dateFrom to theEra.dateTo, "era range derived from its periods")
        assertEquals(listOf(p1), g2.eraPeriods(era).map { it.id })
        assertEquals(listOf("起初"), g2.eraAliases(era).map { it.alias })

        assertTrue(g2.isExcludedFromPeriods(9))
        assertEquals(listOf(PeriodExclusionRecord(9, CHAT, "no-user-message")), g2.periodExclusions(CHAT))
        assertEquals(2, g2.periodFailureCount(CHAT, PeriodFailureKind.FORMATION, 5))
        assertEquals(1, g2.periodFailureCount(CHAT, PeriodFailureKind.ERA, 1))
        assertEquals(0, g2.periodFailureCount(CHAT, PeriodFailureKind.FORMATION, 1))
        assertEquals(1L, g2.periodGeneration(CHAT))

        val zh = assertNotNull(g2.catalogueSnapshot(CHAT, "zh-CN"))
        assertEquals("目录：工作与通勤；新家与朋友", zh.text)
        assertEquals("始于 7/22；截至 8/4", zh.coverage)
        assertEquals(1L, zh.sourceGeneration, "rendered from the state this write produced")
        assertEquals(sha256(zh.text), zh.contentHash)
        assertEquals(clock(), zh.publishedAt)
        assertEquals("Catalogue: commuting; the new place", g2.catalogueSnapshot(CHAT, "en")?.text)
        assertEquals(setOf(CHAT), g2.periodChatKeys())

        // The raw store load carries the same rows.
        val loaded = store.load()
        assertEquals(2, loaded.periods.size)
        assertEquals(1, loaded.eras.size)
        assertEquals(4, loaded.periodMembers.size)
        assertEquals(1, loaded.periodAliases.size)
        assertEquals(1, loaded.eraAliases.size)
        assertEquals(1, loaded.periodExclusions.size)
        assertEquals(2, loaded.periodFailures.size)
        assertEquals(mapOf(CHAT to 1L), loaded.periodGenerations)
        assertEquals(2, loaded.catalogueSnapshots.size)
        // Ids continue after a reload, past every loaded id of any kind (one sequence).
        val nextPeriod = g2.allocatePeriodId()
        val nextEra = g2.allocateEraId()
        assertTrue(nextPeriod > maxOf(p1, p2, era), "period ids continue: $nextPeriod")
        assertTrue(nextEra > nextPeriod, "era ids share the sequence: $nextEra")
    }

    @Test
    fun `a scene can belong to only one period`() {
        val store = TestLatticeStore()
        val (p1, p2) = run {
            val g = graphOn(store)
            g.putScene(scene(1, 10, "2026-07-22"))
            g.putScene(scene(2, 20, "2026-07-23"))
            val p1 = g.allocatePeriodId()
            val p2 = g.allocatePeriodId()
            g.applyPeriodWrite(
                PeriodWrite(
                    CHAT,
                    periods = listOf(PeriodRecord(p1, CHAT, "甲"), PeriodRecord(p2, CHAT, "乙")),
                    members = listOf(PeriodMemberRecord(1, p1), PeriodMemberRecord(2, p2)),
                ),
            )
            p1 to p2
        }

        // A write moving the scene into another period is refused before anything reaches the store or RAM.
        val g = reopen(store)
        assertEquals(p1, g.periodOfScene(1)?.id)
        assertFailsWith<IllegalArgumentException> {
            g.applyPeriodWrite(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(1, p2))))
        }
        assertFailsWith<IllegalArgumentException>("a member needs its scene row") {
            g.applyPeriodWrite(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(77, p2))))
        }
        assertEquals(p1, g.periodOfScene(1)?.id)
        assertEquals(p1, reopen(store).periodOfScene(1)?.id)
    }

    @Test
    fun `a chat holds at most one provisional period`() {
        val g = graphOn()
        g.putScene(scene(1, 10, "2026-07-22"))
        g.putScene(scene(2, 20, "2026-07-23"))
        val a = g.allocatePeriodId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(PeriodRecord(a, CHAT, "甲", state = PeriodState.PROVISIONAL)),
                members = listOf(PeriodMemberRecord(1, a)),
            ),
        )
        val b = g.allocatePeriodId()
        assertFailsWith<IllegalArgumentException> {
            g.applyPeriodWrite(
                PeriodWrite(
                    CHAT,
                    periods = listOf(PeriodRecord(b, CHAT, "乙", state = PeriodState.PROVISIONAL)),
                    members = listOf(PeriodMemberRecord(2, b)),
                ),
            )
        }
        // Closing the old one and opening the next in the same write is the normal formation step.
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(
                    PeriodRecord(b, CHAT, "乙", state = PeriodState.PROVISIONAL),
                    PeriodRecord(a, CHAT, "甲", state = PeriodState.CLOSED),
                ),
                members = listOf(PeriodMemberRecord(2, b)),
            ),
        )
        assertEquals(b, g.provisionalPeriod(CHAT)?.id)
    }

    @Test
    fun `a new name may not be another period's alias`() {
        val g = graphOn()
        g.putScene(scene(1, 10, "2026-07-22"))
        g.putScene(scene(2, 20, "2026-07-23"))
        val a = g.allocatePeriodId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(PeriodRecord(a, CHAT, "海边的夏天")),
                members = listOf(PeriodMemberRecord(1, a)),
                periodAliases = listOf(PeriodAliasRecord(CHAT, "夏天", a)),
            ),
        )
        val b = g.allocatePeriodId()
        assertFailsWith<IllegalArgumentException>("a new name may not be another period's alias") {
            g.applyPeriodWrite(
                PeriodWrite(CHAT, periods = listOf(PeriodRecord(b, CHAT, "夏天")), members = listOf(PeriodMemberRecord(2, b))),
            )
        }
        assertFailsWith<IllegalArgumentException>("normalized: full-width and spacing do not dodge it") {
            g.applyPeriodWrite(
                PeriodWrite(CHAT, periods = listOf(PeriodRecord(b, CHAT, " 夏　天 ")), members = listOf(PeriodMemberRecord(2, b))),
            )
        }
        assertNull(g.period(b))
        assertNull(g.periodOfScene(2))
        // Renamed back to its own old name: fine. Names are per chat.
        g.applyPeriodWrite(PeriodWrite(CHAT, periods = listOf(g.period(a)!!.copy(name = "夏天"))))
        assertEquals("夏天", g.period(a)?.name)
    }

    @Test
    fun `a late scene widens its period and era in the same write`() {
        val store = TestLatticeStore()
        val era: Long
        val p: Long
        run {
            val g = graphOn(store)
            g.putScene(scene(2, 20, "2026-07-23"))
            g.putScene(scene(3, 30, "2026-07-25"))
            // The late scene: an earlier gap filled by a catch-up (written later, earlier first message and date).
            g.putScene(scene(8, 5, "2026-07-20"))
            era = g.allocateEraId()
            p = g.allocatePeriodId()
            g.applyPeriodWrite(
                PeriodWrite(
                    CHAT,
                    eras = listOf(EraRecord(era, CHAT, "开端")),
                    periods = listOf(PeriodRecord(p, CHAT, "起初", eraId = era)),
                    members = listOf(PeriodMemberRecord(2, p), PeriodMemberRecord(3, p)),
                ),
            )
            assertEquals("2026-07-23", g.period(p)?.dateFrom)
            assertEquals("2026-07-23", g.era(era)?.dateFrom)

            g.applyPeriodWrite(PeriodWrite(CHAT, members = listOf(PeriodMemberRecord(8, p))))
            assertEquals("2026-07-20" to "2026-07-25", g.period(p)!!.let { it.dateFrom to it.dateTo })
            assertEquals("2026-07-20" to "2026-07-25", g.era(era)!!.let { it.dateFrom to it.dateTo })
            assertEquals(listOf(8L, 2L, 3L), g.periodScenes(p), "ordered by first message id, not scene id")
        }
        val g2 = reopen(store)
        assertEquals("2026-07-20" to "2026-07-25", g2.period(p)!!.let { it.dateFrom to it.dateTo })
        assertEquals("2026-07-20" to "2026-07-25", g2.era(era)!!.let { it.dateFrom to it.dateTo })
        assertEquals(2L, g2.periodGeneration(CHAT))
    }

    @Test
    fun `a batch that fails midway leaves no half period in memory`() {
        val failing = FailingCatalogueStore(TestLatticeStore())
        val g = graphOn(failing)
        g.putScene(scene(1, 10, "2026-07-22"))
        g.putScene(scene(2, 20, "2026-07-23"))
        g.putScene(scene(3, 30, "2026-07-29"))
        val a = g.allocatePeriodId()
        val b = g.allocatePeriodId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(PeriodRecord(a, CHAT, "甲", state = PeriodState.PROVISIONAL)),
                members = listOf(PeriodMemberRecord(1, a)),
                catalogues = listOf(CatalogueSnapshot(CHAT, "zh-CN", "旧目录")),
            ),
        )

        failing.failCatalogue = true
        assertFailsWith<IllegalStateException> {
            g.applyPeriodWrite(
                PeriodWrite(
                    CHAT,
                    periods = listOf(
                        PeriodRecord(a, CHAT, "甲之后改名", state = PeriodState.CLOSED),
                        PeriodRecord(b, CHAT, "乙", state = PeriodState.PROVISIONAL),
                    ),
                    members = listOf(PeriodMemberRecord(2, a), PeriodMemberRecord(3, b)),
                    periodAliases = listOf(PeriodAliasRecord(CHAT, "甲", a)),
                    catalogues = listOf(CatalogueSnapshot(CHAT, "zh-CN", "新目录")),
                ),
            )
        }

        // Memory: exactly the state before the failed write.
        assertEquals(listOf(a), g.periods(CHAT).map { it.id })
        assertEquals("甲", g.period(a)?.name)
        assertEquals(PeriodState.PROVISIONAL, g.period(a)?.state)
        assertEquals("2026-07-22" to "2026-07-22", g.period(a)!!.let { it.dateFrom to it.dateTo }, "the range did not widen")
        assertNull(g.period(b))
        assertNull(g.periodOfScene(2))
        assertNull(g.periodOfScene(3))
        assertTrue(g.periodAliases(a).isEmpty())
        assertEquals("旧目录", g.catalogueSnapshot(CHAT, "zh-CN")?.text)
        assertEquals(1L, g.periodGeneration(CHAT))
        // The store's rollback is the store's job (TestLatticeStore's batch is not transactional).
    }

    /** A store whose batch view throws at the catalogue step (after the periods and members were written). */
    private class FailingCatalogueStore(private val real: LatticeStore) : LatticeStore by real {
        var failCatalogue = false

        override fun batch(block: (LatticeStore) -> Unit) = real.batch { s ->
            block(object : LatticeStore by s {
                override fun putCatalogueSnapshot(snapshot: CatalogueSnapshot) {
                    if (failCatalogue) throw IllegalStateException("disk full")
                    s.putCatalogueSnapshot(snapshot)
                }
            })
        }
    }

    private companion object {
        const val CHAT = "telegram:1"
        const val OTHER_CHAT = "telegram:2"
    }
}
