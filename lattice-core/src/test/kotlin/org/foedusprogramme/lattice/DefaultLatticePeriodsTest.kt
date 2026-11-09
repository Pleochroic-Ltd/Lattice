package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.EraModel
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.FormationModel
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.PeriodFormer
import org.foedusprogramme.lattice.core.PeriodRig
import org.foedusprogramme.lattice.core.SceneRecord
import org.foedusprogramme.lattice.core.SceneWorkRecord
import org.foedusprogramme.lattice.core.SceneWorkState
import org.foedusprogramme.lattice.core.TopicModel
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The façade with the period layer wired over its scene pipeline: the sleep's PERIODS forms through the real
 * [org.foedusprogramme.lattice.core.PeriodService], a drained chat forms on its own, the catalogue and the timeline are
 * read back, recall labels periods under scene trust, and without scenes there is no period layer.
 */
class DefaultLatticePeriodsTest {

    private val source = FakeConversationSource(start = Instant.parse("2026-07-30T09:00:00Z"))
    private val formation = FormationModel()
    private val era = EraModel()
    private val topic = TopicModel()
    private val model = MemoryModel { task, system, user ->
        when {
            task == MemoryTask.PERIODS && system == PeriodFormer.SYSTEM_PROMPT -> formation.complete(user)
            task == MemoryTask.PERIODS -> era.complete(user)
            task == MemoryTask.EXTRACT -> LatticeTestKit.extraction("The user talked about it")
            else -> topic.complete(task, system, user)
        }
    }
    private val opened = ArrayList<DefaultLattice>()

    private fun open(
        trust: SceneTrustPolicy = PeriodRig.TRUSTING,
        scenes: SceneSettings = SceneSettings(enabled = true, sceneEpisodes = true),
        conversations: ConversationSource? = source,
        formPeriods: SleepPass? = null,
    ): DefaultLattice = LatticeTestKit.open(
        settings = LatticeTestKit.settings().copy(scenes = scenes, language = Language.ZH),
        model = model,
        conversations = conversations,
        sceneTrust = trust,
        graphConfig = LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99),
        formPeriods = formPeriods,
    ).also { opened += it }

    @AfterTest
    fun closeAll() = runBlocking { opened.forEach { it.close() } }

    /** Waits for the startup task queued at open, so the scenes a test seeds are formed by the step it means. */
    private fun DefaultLattice.settled(): DefaultLattice = apply { checkNotNull(scenes).awaitIdle() }

    /** A narrated scene of [CHAT] in its own conversation on [day], holding one memory, [memory]. */
    private fun scene(g: LatticeGraph, title: String, day: String, memory: String): SceneRecord {
        val conversation = "$CHAT:$title"
        source.open(conversation, CHAT).at(Instant.parse("${day}T12:00:00Z"))
        val first = source.add(conversation, "我们聊聊$title")
        val last = source.add(conversation, "好，说说$title。", ConversationMessage.Role.ASSISTANT)
        val record = SceneRecord(
            g.allocateSceneId(), title, conversation, CHAT, first.position, last.position, day, day,
            startedAt = first.at, seq = g.nextSceneSeq(CHAT),
        )
        g.putSceneState(record, SceneWorkRecord(record.sceneId, SceneWorkState.MATERIALIZED))
        val id = g.remember(memory, layer = Engram.Layer.SEMANTIC, origin = Engram.Origin.EXTRACTED, saidFrom = first.at)
        g.addSceneMembers(record.sceneId, listOf(id))
        return record
    }

    /** Each new scene becomes a period of its own, named [names] in the order the formation lists the scenes. */
    private fun eachItsOwn(vararg names: String) {
        formation.reply = { user ->
            val n = FormationModel.newScenes(user)
            check(n == names.size) { "expected ${names.size} new scenes, got $n" }
            FormationModel.periods(*names.mapIndexed { i, name -> FormationModel.entry(i + 1, i + 1, name) }.toTypedArray())
        }
    }

    @Test
    fun `the sleep forms periods, publishes the catalogue and reads the timeline in chat order`() = runBlocking {
        val lattice = open().settled()
        // Seeded out of date order: only SCENE_ORDER orders them.
        lattice.withGraph { g ->
            scene(g, "山里", "2026-07-24", "The user hiked in the mountains")
            scene(g, "海边", "2026-07-22", "The user swam at the beach")
            scene(g, "城里", "2026-07-26", "The user shopped in the city")
        }
        assertNull(lattice.catalogue(CHAT), "nothing formed yet")
        eachItsOwn("海边那天", "山里那天", "城里那天")

        val report = lattice.sleep(setOf(SleepPhase.PERIODS)).phases.single()

        assertTrue(!report.skipped && report.error == null, "$report")
        assertEquals(1, report.count, "one chat changed")
        assertEquals(listOf("lattice-test-narrate"), formation.threads.distinct(), "formation runs on the narration thread")
        assertEquals(3, lattice.stats().periods)

        val catalogue = assertNotNull(lattice.catalogue(CHAT))
        for (name in listOf("海边那天", "山里那天", "城里那天")) assertTrue("「$name」" in catalogue, catalogue)
        assertEquals(catalogue, lattice.catalogue(CHAT, Language.ZH), "null means the settings' language")
        assertNotNull(lattice.catalogue(CHAT, Language.EN), "rendered in English too")
        assertNull(lattice.catalogue("another-chat"))

        val timeline = assertNotNull(lattice.timeline(CHAT))
        assertEquals(listOf("海边", "山里", "城里"), timeline.scenes.map { it.title })
        assertEquals(listOf("海边那天", "山里那天", "城里那天"), timeline.periods.map { it.record.name })
        assertEquals(timeline.scenes.map { listOf(it.id) }, timeline.periods.map { it.sceneIds })
        assertTrue(timeline.periods.all { it.shown } && timeline.scenes.all { it.trusted })
        assertEquals("2026-07-22" to "2026-07-26", timeline.coveredFrom to timeline.coveredTo)

        assertEquals(0, lattice.sleep(setOf(SleepPhase.PERIODS)).phases.single().count, "nothing left to form")
    }

    @Test
    fun `recall labels a period by its name when its scenes are trusted`() = runBlocking {
        val lattice = open().settled()
        lattice.withGraph { g -> scene(g, "海边", "2026-07-22", "The user swam at the beach") }
        eachItsOwn("海边那天")
        lattice.sleep(setOf(SleepPhase.PERIODS))

        val text = lattice.recall("The user swam at the beach", RecallOptions(chatKey = CHAT)).text

        assertTrue("〔时期：海边那天〕" in text, text)
    }

    @Test
    fun `under the conservative policy recall and the catalogue show a period by its dates`() = runBlocking {
        val lattice = open(trust = SceneTrustPolicy.Conservative).settled()
        lattice.withGraph { g -> scene(g, "海边", "2026-07-22", "The user swam at the beach") }
        eachItsOwn("海边那天")
        lattice.sleep(setOf(SleepPhase.PERIODS))
        assertEquals(1, lattice.stats().periods)

        val text = lattice.recall("The user swam at the beach", RecallOptions(chatKey = CHAT)).text
        assertTrue("〔时期：2026-07-22〕" in text, text)
        assertFalse("海边那天" in text, text)

        val catalogue = assertNotNull(lattice.catalogue(CHAT))
        assertFalse("海边那天" in catalogue, catalogue)
        val timeline = assertNotNull(lattice.timeline(CHAT))
        assertFalse(timeline.periods.single().shown)
        assertFalse(timeline.scenes.single().trusted)
    }

    @Test
    fun `a sealed conversation forms its periods once its narration drains, without a sleep`() = runBlocking {
        val conversation = "$CHAT:c1"
        source.open(conversation, CHAT)
        for (t in listOf("kyoto", "gradle")) source.talk(conversation, "topic=$t", 4)
        source.seal(conversation)
        formation.reply = FormationModel.continuing("京都和构建")
        val lattice = open().settled()

        lattice.conversationSealed(conversation)
        checkNotNull(lattice.scenes).awaitIdle()

        assertEquals(2, topic.narrations.size, "both scenes were narrated")
        assertEquals(1, formation.calls.size)
        assertEquals(1, lattice.stats().periods)
        assertTrue("「京都和构建」" in assertNotNull(lattice.catalogue(CHAT)))
    }

    @Test
    fun `the startup task forms a chat whose scenes were left unformed`() = runBlocking {
        val store = org.foedusprogramme.lattice.core.TestLatticeStore()
        val first = LatticeTestKit.open(
            settings = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = true), language = Language.ZH),
            store = store,
            model = null,
        )
        first.withGraph { g -> scene(g, "海边", "2026-07-22", "The user swam at the beach") }
        first.close()
        formation.reply = FormationModel.continuing("海边那天")

        val lattice = LatticeTestKit.open(
            settings = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = true), language = Language.ZH),
            store = store,
            model = model,
            conversations = source,
            sceneTrust = PeriodRig.TRUSTING,
        ).also { opened += it }.settled()

        assertEquals(1, formation.calls.size, "formed at startup")
        assertTrue("「海边那天」" in assertNotNull(lattice.catalogue(CHAT)))
    }

    @Test
    fun `an explicit formPeriods still replaces the pass of the service`() = runBlocking {
        val lattice = open(formPeriods = { _ -> 7 }).settled()
        lattice.withGraph { g -> scene(g, "海边", "2026-07-22", "The user swam at the beach") }

        assertEquals(7, lattice.sleep(setOf(SleepPhase.PERIODS)).phases.single().count)
        assertTrue(formation.calls.isEmpty())
        assertNotNull(lattice.periods, "the service still runs for the live path")
        Unit
    }

    @Test
    fun `without scenes there is no period layer`() = runBlocking {
        for (lattice in listOf(open(scenes = SceneSettings(enabled = false)), open(conversations = null))) {
            assertNull(lattice.periods)
            assertNull(lattice.catalogue(CHAT))
            assertNull(lattice.timeline(CHAT))
        }
        val noModel = LatticeTestKit.open(
            settings = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = true)),
            conversations = source,
        ).also { opened += it }
        assertNull(noModel.catalogue(CHAT))
        assertEquals("There is no model.", noModel.sleep(setOf(SleepPhase.PERIODS)).phases.single().skipReason)
    }

    private companion object {
        const val CHAT = "telegram:7"
    }
}
