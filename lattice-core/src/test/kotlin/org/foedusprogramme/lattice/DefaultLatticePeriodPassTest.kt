package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.FormationModel
import org.foedusprogramme.lattice.core.GraphAccess
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.PeriodRig
import org.foedusprogramme.lattice.core.PeriodService
import org.foedusprogramme.lattice.core.SceneRecord
import org.foedusprogramme.lattice.core.SceneTrust
import org.foedusprogramme.lattice.core.SceneWorkRecord
import org.foedusprogramme.lattice.core.SceneWorkState
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [periodFormationPass] as [DefaultLattice]'s `formPeriods`: the PERIODS sleep phase settles the scene work first, then
 * forms every chat with ended scenes on the service's narration thread and reports the chats it changed.
 */
class DefaultLatticePeriodPassTest {

    private val chat = "telegram:7"

    /** A scene of [chat] in its own conversation: the owner's line and a reply on [day]. */
    private fun scene(g: LatticeGraph, source: FakeConversationSource, title: String, day: String): SceneRecord {
        val conversation = "$chat:$title"
        source.open(conversation, chat).at(Instant.parse("${day}T12:00:00Z"))
        val first = source.add(conversation, "我们聊聊$title")
        val last = source.add(conversation, "好，说说$title。", ConversationMessage.Role.ASSISTANT)
        val record = SceneRecord(
            g.allocateSceneId(), title, conversation, chat, first.position, last.position, day, day,
            startedAt = first.at, seq = g.nextSceneSeq(chat),
        )
        g.putSceneState(record, SceneWorkRecord(record.sceneId, SceneWorkState.MATERIALIZED))
        return record
    }

    @Test
    fun `the PERIODS phase settles the scene work, then forms on the narration thread and counts the chats it changed`() = runBlocking {
        val formation = FormationModel(FormationModel.continuing("最初的日子"))
        val model = MemoryModel { task, _, user ->
            assertEquals(MemoryTask.PERIODS, task)
            formation.complete(user)
        }
        val settled = AtomicBoolean()
        var periods: PeriodService? = null
        val settings = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = true))
        val lattice = LatticeTestKit.open(
            settings = settings,
            model = model,
            formPeriods = SleepPass { l -> periodFormationPass(checkNotNull(periods), settle = { settled.set(true) }).run(l) },
        )
        val source = FakeConversationSource()
        val access = object : GraphAccess {
            override fun <T> withGraph(block: (LatticeGraph) -> T): T = runBlocking { lattice.withGraph(block) }
        }
        val service = PeriodService(
            access, source, model, SceneTrust(access, source, PeriodRig.TRUSTING), Executor { it.run() }, language = { Language.ZH },
        ).also { periods = it }
        lattice.withGraph { g -> listOf("海边" to "2026-07-22", "山里" to "2026-07-23").map { (t, d) -> scene(g, source, t, d) } }

        val report = lattice.sleep(setOf(SleepPhase.PERIODS)).phases.single()

        assertTrue(!report.skipped && report.error == null, "$report")
        assertEquals(1, report.count, "one chat changed")
        assertTrue(settled.get(), "the scene work settles first")
        assertEquals(listOf("lattice-test-narrate"), formation.threads.toList(), "formation runs on the narration thread")
        assertEquals(1, lattice.stats().periods)
        assertTrue("「最初的日子」" in assertNotNull(service.catalogue(chat, Language.ZH)))

        val again = lattice.sleep(setOf(SleepPhase.PERIODS)).phases.single()
        assertEquals(0, again.count, "nothing left to form")
        assertEquals(1, formation.calls.size)
        lattice.close()
    }

    @Test
    fun `without scenes the PERIODS phase is skipped and the pass never runs`() = runBlocking {
        val ran = AtomicBoolean()
        val lattice = LatticeTestKit.open(
            settings = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = false)),
            model = MemoryModel { _, _, _ -> error("no model call") },
            formPeriods = SleepPass { ran.set(true); 0 },
        )

        val report = lattice.sleep(setOf(SleepPhase.PERIODS)).phases.single()

        assertTrue(report.skipped, "$report")
        assertTrue(!ran.get())
        lattice.close()
    }
}
