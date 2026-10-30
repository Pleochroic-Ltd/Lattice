package org.foedusprogramme.lattice.sqlite

import org.foedusprogramme.lattice.core.GraphAccess
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.PeriodCatalogue
import org.foedusprogramme.lattice.core.PeriodMemberRecord
import org.foedusprogramme.lattice.core.PeriodPublisher
import org.foedusprogramme.lattice.core.PeriodRecord
import org.foedusprogramme.lattice.core.PeriodState
import org.foedusprogramme.lattice.core.PeriodWrite
import org.foedusprogramme.lattice.core.SceneRecord
import org.foedusprogramme.lattice.core.SceneTrust
import org.foedusprogramme.lattice.core.ThreadRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The period layer's catalogue and generation through a SQLite file and a reopen. */
class SqlitePeriodLayerTest {
    @TempDir
    lateinit var dir: Path

    private val chat = "telegram:1"

    private class Opened(val store: SqliteLatticeStore, val graph: LatticeGraph) {
        val access = GraphAccess.locked(graph)
        val publisher = PeriodPublisher(access)
        val catalogue = PeriodCatalogue(access, publisher, MemoryModel { _, _, _ -> error("a re-render makes no model call") })

        fun trustAll(chat: String) = SceneTrust.ChatTrust(chat, graph.scenes(chat).associate { it.sceneId to SceneTrust.Verdict(it.sceneId, emptySet()) }, failed = false)
    }

    private fun open(): Opened {
        val store = SqliteLatticeStore(dir.resolve("graph.db"))
        return Opened(store, LatticeGraph(LocalHashingEmbedder(), now = { Instant.parse("2026-09-28T00:00:00Z") }, store = store))
    }

    private fun Opened.seed() {
        graph.putScene(SceneRecord(graph.allocateSceneId(), "海边", "$chat:a", chat, 10, 20, "2026-07-22", "2026-07-22"))
        graph.putScene(SceneRecord(graph.allocateSceneId(), "山里", "$chat:b", chat, 10, 20, "2026-07-23", "2026-07-24"))
        val scenes = graph.scenes(chat)
        graph.putThread(ThreadRecord(0, chat, "冬天去滑雪", openedSceneId = scenes.first().sceneId, name = "滑雪之约"))
        publisher.commit { g ->
            val id = g.allocatePeriodId()
            PeriodWrite(
                chat,
                periods = listOf(PeriodRecord(id, chat, "山海之间", gist = "从海边到山里。", state = PeriodState.PROVISIONAL, namingSceneIds = scenes.map { it.sceneId }.toSet())),
                members = scenes.map { PeriodMemberRecord(it.sceneId, id) },
            )
        }
    }

    @Test
    fun `a published catalogue survives a reopen, a withdrawal lasts until the next write`() {
        val text: String
        open().run {
            seed()
            val calibration = assertNotNull(catalogue.calibrate(chat, trustAll(chat)))
            publisher.commit { assertNotNull(calibration.write) }
            text = assertNotNull(publisher.published(chat, Language.ZH))
            assertTrue("- 「山海之间」2026-07-22～2026-07-24（可能仍在延续）：从海边到山里。" in text.lines(), text)
            assertTrue("尚未了结的线索：「滑雪之约」" in text.lines(), text)
            publisher.withdraw(chat)
            assertNull(publisher.published(chat, Language.ZH))
            store.close()
        }
        open().run {
            assertEquals(text, publisher.published(chat, Language.ZH), "the snapshot rows stayed: a new run publishes them")
            assertNotNull(publisher.published(chat, Language.EN))
            publisher.withdraw(chat)
            assertNull(publisher.published(chat, Language.EN))
            // The threads line was recorded with the snapshot: a re-render without a formation keeps it.
            val calibration = assertNotNull(catalogue.calibrate(chat, trustAll(chat)))
            assertTrue(calibration.changed.isNotEmpty(), "withdrawn: compared with nothing, so every language is offered again")
            publisher.commit { assertNotNull(calibration.write) }
            assertEquals(text, publisher.published(chat, Language.ZH), "published again by the next committed write")
            store.close()
        }
    }

    @Test
    fun `a layer reset writes the generation back`() {
        val generation: Long
        open().run {
            seed()
            val before = graph.periodGeneration(chat)
            generation = assertNotNull(publisher.commit { PeriodWrite(chat, reset = true) })
            assertEquals(before + 1, generation)
            assertTrue(graph.periods(chat).isEmpty())
            store.close()
        }
        open().run {
            assertEquals(generation, graph.periodGeneration(chat), "the generation survives the reset and the reopen")
            assertTrue(graph.periods(chat).isEmpty())
            assertTrue(graph.catalogueSnapshots(chat).isEmpty())
            store.close()
        }
    }
}
