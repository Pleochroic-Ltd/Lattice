package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.core.PeriodFormer.Outcome
import org.foedusprogramme.lattice.core.PeriodRig.Companion.CHAT
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The startup task ([PeriodService.startup]): the check and calibration ([PeriodCatalogue.startupCheck]) — a chat whose
 * period members no longer match its scenes is reset and its catalogue withdrawn, the others re-rendered without a
 * model call and republished only when the text changed — then one formation per chat, which rebuilds a reset chat.
 */
class PeriodStartupCheckTest {

    private companion object {
        const val CHAT_B = "telegram:2002"
    }

    /** A store whose period layer cannot be reset while [failReset] is set. */
    private class StubbornStore : TestLatticeStore() {
        @Volatile var failReset = false

        override fun deletePeriodLayer(chatKey: String) {
            if (failReset) throw IllegalStateException("disk full")
            super.deletePeriodLayer(chatKey)
        }
    }

    private fun service(r: PeriodRig) = PeriodService(r.access, r.source, r.model, r.trust, Executor { it.run() }, language = { Language.ZH })

    private fun check(r: PeriodRig) = r.catalogue().startupCheck { r.trust.refresh(it) }

    private fun modelCalls(r: PeriodRig) = r.formation.calls.size + r.era.calls.size

    private fun snapshots(r: PeriodRig, chat: String = CHAT) = r.g { it.catalogueSnapshots(chat) }.sortedBy { it.language }

    /** Forms [names] (one period per scene) for the scenes of [chat] that wait. */
    private fun PeriodRig.formEachOf(chat: String, vararg names: String) {
        formation.reply = { user ->
            FormationModel.periods(*names.mapIndexed { i, name -> FormationModel.entry(i + 1, i + 1, name) }.toTypedArray())
        }
        assertEquals(Outcome.FORMED, former(withCatalogue = true).formChat(chat))
    }

    /** Removes [scene]'s rows from the store and reopens the graph: a member scene the period layer still names. */
    private fun PeriodRig.lose(scene: SceneRecord) {
        val store = store as TestLatticeStore
        store.scenes.remove(scene.sceneId)
        store.sceneWork.remove(scene.sceneId)
        store.sceneMembers.removeIf { it.first == scene.sceneId }
        restart()
    }

    // --- calibration -------------------------------------------------------------------------------------------------

    @Test
    fun `a consistent chat whose catalogue renders the same is not republished`() {
        val r = PeriodRig()
        repeat(3) { r.scene("场景$it") }
        r.formEach("搬家前后", "旧书新读", "骑车上班")
        val before = snapshots(r)
        val text = assertNotNull(r.published())
        val calls = modelCalls(r)

        val result = check(r)

        assertEquals(listOf(CHAT), result.unchanged)
        assertTrue(result.published.isEmpty() && result.reset.isEmpty(), "${result.published} ${result.reset}")
        assertEquals(before, snapshots(r), "same rows, same publish time")
        assertEquals(text, r.published())
        assertEquals(calls, modelCalls(r), "no model call")
    }

    @Test
    fun `a chat whose rendering changed is republished without a model call, and only once`() {
        val r = PeriodRig()
        val s = (1..3).map { r.scene("场景$it") }
        r.formEach("搬家前后", "旧书新读", "骑车上班")
        val old = assertNotNull(r.published())
        val calls = modelCalls(r)
        r.taint(s[1])

        val result = check(r)

        assertEquals(listOf(CHAT), result.published)
        val text = assertNotNull(r.published())
        assertNotEquals(old, text)
        assertTrue("旧书新读" !in text && "（名称不显示）" in text, text)
        assertNotNull(r.published(Language.EN)).also { assertTrue("旧书新读" !in it, it) }
        assertEquals(calls, modelCalls(r), "no model call")

        val again = check(r)
        assertEquals(listOf(CHAT), again.unchanged, "idempotent: the next start finds nothing to publish")
        assertTrue(again.published.isEmpty())
    }

    @Test
    fun `an unreadable scene trust publishes nothing and keeps the old text`() {
        val r = PeriodRig()
        val s = (1..2).map { r.scene("场景$it") }
        r.formEach("搬家前后", "旧书新读")
        val old = r.published()
        r.taint(s[0])
        r.source.failing += s[0].conversationId
        val failing = SceneTrust(r.access, r.source, r.policy)

        val result = r.catalogue().startupCheck { failing.refresh(it) }

        assertEquals(listOf(CHAT), result.skipped)
        assertEquals(old, r.published())
    }

    @Test
    fun `a chat with only an open scene has no period and no catalogue`() {
        val r = PeriodRig()
        r.scene("还在聊", state = SceneWorkState.OPEN)

        val result = check(r)
        r.former().formAll()

        assertTrue(result.published.isEmpty() && result.reset.isEmpty())
        assertTrue(r.periods().isEmpty())
        assertNull(r.published())
        assertNull(r.published(Language.EN))
    }

    // --- the consistency check -------------------------------------------------------------------------------------

    @Test
    fun `a member scene that is gone resets the chat's period layer and withdraws its catalogue, the other chat is untouched`() {
        val r = PeriodRig(store = TestLatticeStore())
        val s = (1..3).map { r.scene("场景$it") }
        r.formEach("搬家前后", "旧书新读", "骑车上班")
        (1..2).map { r.scene("别处$it", chatKey = CHAT_B) }
        r.formEachOf(CHAT_B, "海边", "山里")
        val other = assertNotNull(r.published(chatKey = CHAT_B))
        val calls = modelCalls(r)
        r.lose(s[2])

        val result = check(r)

        assertEquals(listOf(CHAT), result.reset)
        assertEquals(listOf(CHAT_B), result.unchanged)
        assertNull(r.published(), "nothing is published for the chat")
        assertNull(r.published(Language.EN))
        assertTrue(r.periods().isEmpty(), "the period layer is reset for the formation to rebuild")
        assertTrue(snapshots(r).isEmpty())
        assertEquals(other, r.published(chatKey = CHAT_B))
        assertEquals(2, r.periods(CHAT_B).size)
        assertEquals(calls, modelCalls(r), "the check itself makes no model call")
    }

    @Test
    fun `a member scene that is no longer ended or belongs to another chat is a mismatch`() {
        val breaks: List<Pair<String, (PeriodRig, SceneRecord) -> Unit>> = listOf(
            "not ended" to { r, s -> r.g { g -> g.putSceneState(work = g.sceneWork(s.sceneId)!!.copy(state = SceneWorkState.OPEN)) } },
            "other chat" to { r, s -> r.g { g -> g.putScene(g.scene(s.sceneId)!!.copy(chatKey = CHAT_B)) } },
        )
        for ((label, breakIt) in breaks) {
            val r = PeriodRig()
            val s = (1..2).map { r.scene("场景$it") }
            r.formEach("搬家前后", "旧书新读")
            breakIt(r, s[1])

            val result = check(r)

            assertEquals(listOf(CHAT), result.reset, label)
            assertNull(r.published(), label)
            assertTrue(r.periods().isEmpty(), label)
        }
    }

    @Test
    fun `a reset that cannot be written still withdraws the chat's catalogue for this run`() {
        val store = StubbornStore()
        val r = PeriodRig(store = store)
        val s = (1..2).map { r.scene("场景$it") }
        r.formEach("搬家前后", "旧书新读")
        r.lose(s[1])
        assertNotNull(r.published(), "published from the snapshots the graph loaded")
        store.failReset = true

        val result = check(r)

        assertEquals(listOf(CHAT), result.reset)
        assertNull(r.published(), "nothing is published for the chat")
        assertNull(r.published(Language.EN))
        assertEquals(2, r.periods().size, "the layer itself could not be reset")
    }

    // --- the startup task ---------------------------------------------------------------------------------------------

    @Test
    fun `the startup task checks before it forms - a reset chat is formed again and republished, the next start is quiet`() {
        val r = PeriodRig(store = TestLatticeStore())
        val s = (1..3).map { r.scene("场景$it") }
        r.formEach("搬家前后", "旧书新读", "骑车上班")
        r.lose(s[2])
        r.formation.calls.clear()
        r.formation.reply = { user -> FormationModel.periods(FormationModel.entry(1, FormationModel.newScenes(user), "重新整理的一段")) }
        val service = service(r)

        val outcomes = service.startup()

        assertEquals(mapOf(CHAT to Outcome.FORMED), outcomes)
        assertEquals(1, r.formation.calls.size, "the layer was reset before the formation ran")
        assertEquals(2, FormationModel.newScenes(r.formation.calls.single()), "the two scenes the graph still has")
        assertEquals(listOf(listOf(s[0].sceneId, s[1].sceneId)), r.periods().map { it.second })
        assertTrue("重新整理的一段" in assertNotNull(service.catalogue(CHAT, Language.ZH)))

        val before = snapshots(r)
        assertEquals(mapOf(CHAT to Outcome.NOTHING_TO_DO), service(r).startup(), "the next start: nothing to form")
        assertEquals(1, r.formation.calls.size)
        assertEquals(before, snapshots(r), "nothing republished")
    }

    @Test
    fun `scenes narrated before any period are formed at the first start in one call, and the next start makes none`() {
        val r = PeriodRig()
        val s = (1..16).map { r.scene("场景$it") }
        r.formation.reply = { user -> FormationModel.periods(FormationModel.entry(1, FormationModel.newScenes(user), "最初的日子")) }

        assertTrue(service(r).scheduleStartup())

        assertEquals(1, r.formation.calls.size)
        assertEquals(listOf(s.map { it.sceneId }), r.periods().map { it.second })
        val text = assertNotNull(service(r).catalogue(CHAT, Language.ZH))

        r.restart()
        val service = service(r)
        service.startup()
        assertEquals(1, r.formation.calls.size, "no model call")
        assertEquals(text, service.catalogue(CHAT, Language.ZH), "the published catalogue survives the restart")
    }
}
