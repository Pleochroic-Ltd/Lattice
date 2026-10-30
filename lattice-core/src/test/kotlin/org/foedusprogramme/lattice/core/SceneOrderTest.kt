package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order of a chat's scenes ([SCENE_ORDER]) and of everything ordered by it (threads, periods, a period's scenes):
 * positions say nothing across conversations and ids nothing across id slots, so neither may decide it.
 */
class SceneOrderTest {

    private val clock = { Instant.parse("2026-09-25T00:00:00Z") }

    private fun graphOn(store: LatticeStore) = LatticeGraph(LocalHashingEmbedder(), now = clock, store = store)

    /** An id of [slot] that is numerically far from slot 0's. */
    private fun slotId(slot: Int, n: Long) = (slot.toLong() shl IdAllocator.COUNTER_BITS) or n

    private fun scene(id: Long, conversation: String, first: Long, startedAt: String?, seq: Long) = SceneRecord(
        sceneId = id, title = "scene $id", conversationId = conversation, chatKey = CHAT,
        firstMessageId = first, lastMessageId = first + 10,
        dateFrom = startedAt?.substring(0, 10), dateTo = startedAt?.substring(0, 10),
        startedAt = startedAt?.let(Instant::parse), seq = seq,
    )

    /**
     * Four scenes of one chat over two conversations and two id slots. In time: B1 (conversation b, slot 1, high
     * positions), A1 (conversation a, low positions), A2, then B2 — the opposite of both id order and position order.
     */
    private fun scenes(): List<SceneRecord> = listOf(
        scene(1, "a", first = 1_000, startedAt = "2026-09-01T09:00:00Z", seq = 2),
        scene(2, "a", first = 2_000, startedAt = "2026-09-01T12:00:00Z", seq = 3),
        scene(slotId(1, 1), "b", first = 9_000_000, startedAt = "2026-08-31T22:00:00Z", seq = 1),
        scene(3, "b", first = 1, startedAt = "2026-09-02T08:00:00Z", seq = 4),
    )

    @Test
    fun `a chat's scenes come back in time order across conversations and id slots, also after a reload`() {
        val store = TestLatticeStore()
        val g = graphOn(store)
        scenes().shuffled(java.util.Random(7)).forEach(g::putScene)

        val expected = listOf(slotId(1, 1), 1L, 2L, 3L)
        assertEquals(expected, g.scenes(CHAT).map { it.sceneId })
        assertEquals(expected, graphOn(TestLatticeStore(initial = store.load())).scenes(CHAT).map { it.sceneId })
        assertEquals(5L, g.nextSceneSeq(CHAT))
    }

    @Test
    fun `the start instant breaks a same-day tie, then the capture sequence, and an undated scene goes last`() {
        val g = graphOn(TestLatticeStore())
        g.putScene(scene(10, "a", first = 50, startedAt = "2026-09-01T18:00:00Z", seq = 1))
        g.putScene(scene(11, "b", first = 1, startedAt = "2026-09-01T07:00:00Z", seq = 2))
        g.putScene(scene(12, "b", first = 900, startedAt = "2026-09-01T18:00:00Z", seq = 3))
        g.putScene(scene(13, "c", first = 1, startedAt = null, seq = 0))

        assertEquals(listOf(11L, 10L, 12L, 13L), g.scenes(CHAT).map { it.sceneId })

        // A scene whose start moves is re-sorted, not left at its old place.
        g.putScene(g.scene(11)!!.copy(dateFrom = "2026-09-03", startedAt = Instant.parse("2026-09-03T07:00:00Z")))
        assertEquals(listOf(10L, 12L, 11L, 13L), g.scenes(CHAT).map { it.sceneId })
    }

    @Test
    fun `threads follow the scene that opened them, and periods and their scenes follow the scenes`() {
        val g = graphOn(TestLatticeStore())
        scenes().forEach(g::putScene)
        val early = slotId(1, 1)
        // Thread ids in the opposite order of their opening scenes; one thread with no known opening scene.
        g.putThread(ThreadRecord(slotId(2, 5), CHAT, "late promise", openedSceneId = 3))
        g.putThread(ThreadRecord(90, CHAT, "early promise", openedSceneId = early))
        g.putThread(ThreadRecord(91, CHAT, "middle promise", openedSceneId = 2))
        g.putThread(ThreadRecord(92, CHAT, "unknown origin", openedSceneId = null))
        assertEquals(listOf(92L, 90L, 91L, slotId(2, 5)), g.threads(CHAT).map { it.id })
        assertEquals(listOf(92L, 90L, 91L, slotId(2, 5)), g.threads().map { it.id })

        val later = g.allocatePeriodId()
        val earlier = g.allocatePeriodId()
        g.applyPeriodWrite(
            PeriodWrite(
                CHAT,
                periods = listOf(PeriodRecord(later, CHAT, "autumn"), PeriodRecord(earlier, CHAT, "summer")),
                members = listOf(
                    PeriodMemberRecord(3, later),
                    PeriodMemberRecord(2, earlier),
                    PeriodMemberRecord(early, earlier),
                    PeriodMemberRecord(1, earlier),
                ),
            ),
        )
        assertEquals(listOf(earlier, later), g.periods(CHAT).map { it.id })
        assertEquals(listOf(early, 1L, 2L), g.periodScenes(earlier))
    }

    private companion object {
        const val CHAT = "chat-1"
    }
}
