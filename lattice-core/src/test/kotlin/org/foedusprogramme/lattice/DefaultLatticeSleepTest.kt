package org.foedusprogramme.lattice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.LatticeTestKit.FakeModel
import org.foedusprogramme.lattice.LatticeTestKit.FlushingStore
import org.foedusprogramme.lattice.core.BgeBandFakeEmbedder
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.EmbedderUnavailableException
import org.foedusprogramme.lattice.core.MemoryModelShutdownException
import org.foedusprogramme.lattice.core.MemoryTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sleep pipeline: phase order, skips, fail-soft phases, one sleep at a time, the scene seams. */
class DefaultLatticeSleepTest {

    /** Two clusters of three related memories each (pairwise 0.83 under the bge-band embedder). */
    private suspend fun seedClusters(lattice: DefaultLattice) = lattice.withGraph { g ->
        listOf("A|s1|m1", "A|s1|m2", "A|s1|m3", "B|s1|m1", "B|s1|m2", "B|s1|m3").map { g.remember(it) }
    }

    private fun SleepReport.of(phase: SleepPhase) = phases.single { it.phase == phase }

    @Test
    fun withoutAModelTheModelPhasesAreSkippedAndTheRestRun() = runBlocking {
        val settings = LatticeTestKit.settings().copy(entityExtraction = true, sameDayEpisodes = true)
        val lattice = LatticeTestKit.open(settings = settings, embedder = BgeBandFakeEmbedder())
        seedClusters(lattice)
        val report = lattice.sleep()

        assertEquals(SleepPhase.entries, report.phases.map { it.phase }, "every phase, in order")
        for (phase in listOf(SleepPhase.JUDGED_DEDUP, SleepPhase.SYNTHESIZE, SleepPhase.ENTITIES, SleepPhase.CANONICALIZE_ENTITIES, SleepPhase.EPISODES)) {
            assertTrue(report.of(phase).skipped, "$phase needs a model")
            assertEquals("There is no model.", report.of(phase).skipReason)
        }
        for (phase in listOf(SleepPhase.CONSOLIDATE, SleepPhase.DECAY)) {
            assertFalse(report.of(phase).skipped, "$phase needs no model")
            assertNull(report.of(phase).error)
        }
        assertEquals("Scenes are off.", report.of(SleepPhase.CATCH_UP).skipReason)
        assertEquals("Scenes are off.", report.of(SleepPhase.PERIODS).skipReason)
        assertEquals(6, report.engramsBefore)
        assertEquals(6, report.engramsAfter)
        lattice.close()
    }

    @Test
    fun phasesTurnedOffBySettingsSayWhy() = runBlocking {
        val model = FakeModel { _, _ -> "" }
        val lattice = LatticeTestKit.open(model = model)
        val report = lattice.sleep()
        assertEquals("Entity extraction is off.", report.of(SleepPhase.ENTITIES).skipReason)
        assertEquals("Entity extraction is off.", report.of(SleepPhase.CANONICALIZE_ENTITIES).skipReason)
        assertEquals("Same-day episodes are off.", report.of(SleepPhase.EPISODES).skipReason)
        assertFalse(report.of(SleepPhase.SYNTHESIZE).skipped)
        assertFalse(report.of(SleepPhase.JUDGED_DEDUP).skipped)
        lattice.close()
    }

    @Test
    fun aFailingPhaseIsReportedAndTheOthersStillRun() = runBlocking {
        // The abstractor's gist can't be embedded: SYNTHESIZE fails, DECAY after it still runs.
        val bge = BgeBandFakeEmbedder()
        val embedder = object : Embedder by bge {
            override fun embed(text: String): FloatArray =
                if (text == "BOOM") throw EmbedderUnavailableException("the embedder dropped the gist") else bge.embed(text)
        }
        val model = FakeModel { task, _ -> if (task == MemoryTask.ABSTRACT) "BOOM" else "" }
        val lattice = LatticeTestKit.open(model = model, embedder = embedder)
        seedClusters(lattice)
        val report = lattice.sleep(setOf(SleepPhase.CONSOLIDATE, SleepPhase.SYNTHESIZE, SleepPhase.DECAY))

        assertEquals(listOf(SleepPhase.CONSOLIDATE, SleepPhase.SYNTHESIZE, SleepPhase.DECAY), report.phases.map { it.phase })
        assertEquals("the embedder dropped the gist", report.of(SleepPhase.SYNTHESIZE).error)
        assertFalse(report.of(SleepPhase.SYNTHESIZE).skipped)
        assertNull(report.of(SleepPhase.DECAY).error)
        assertFalse(report.of(SleepPhase.DECAY).skipped, "the phase after the failure ran")
        lattice.close()
    }

    @Test
    fun anUnreachableEmbedderSkipsOnlyThePhasesThatEmbed() = runBlocking {
        var up = true
        val bge = BgeBandFakeEmbedder()
        val embedder = object : Embedder by bge {
            override fun embed(text: String): FloatArray = if (up) bge.embed(text) else throw EmbedderUnavailableException("down")
        }
        val settings = LatticeTestKit.settings().copy(sameDayEpisodes = true)
        val model = FakeModel { _, _ -> "" }
        val lattice = LatticeTestKit.open(settings = settings, model = model, embedder = embedder)
        seedClusters(lattice)
        up = false
        val report = lattice.sleep()
        assertEquals("The embedder is unreachable.", report.of(SleepPhase.SYNTHESIZE).skipReason)
        assertEquals("The embedder is unreachable.", report.of(SleepPhase.EPISODES).skipReason)
        assertFalse(report.of(SleepPhase.CONSOLIDATE).skipped)
        assertFalse(report.of(SleepPhase.JUDGED_DEDUP).skipped, "judging needs no embedding")
        assertFalse(report.of(SleepPhase.DECAY).skipped)
        assertEquals(0, model.calls(MemoryTask.ABSTRACT))
        lattice.close()
    }

    @Test
    fun aSecondSleepWhileOneRunsSkipsEverything() = runBlocking {
        val inSynthesis = CountDownLatch(1)
        val release = CountDownLatch(1)
        val model = FakeModel { task, _ ->
            if (task == MemoryTask.ABSTRACT) {
                inSynthesis.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            ""
        }
        val lattice = LatticeTestKit.open(model = model, embedder = BgeBandFakeEmbedder())
        seedClusters(lattice)
        val first = async(Dispatchers.IO) { lattice.sleep(setOf(SleepPhase.SYNTHESIZE)) }
        assertTrue(inSynthesis.await(5, TimeUnit.SECONDS))

        val second = lattice.sleep(setOf(SleepPhase.CONSOLIDATE, SleepPhase.DECAY))
        assertTrue(second.phases.all { it.skipped && it.skipReason == "Another sleep of this graph is running." }, "$second")
        assertEquals(6, second.engramsBefore)

        release.countDown()
        assertFalse(first.await().of(SleepPhase.SYNTHESIZE).skipped)
        assertFalse(lattice.sleep(setOf(SleepPhase.DECAY)).of(SleepPhase.DECAY).skipped, "the slot is free again")
        lattice.close()
    }

    @Test
    fun aModelShuttingDownStopsTheSleep() = runBlocking {
        val model = FakeModel { task, _ ->
            if (task == MemoryTask.ABSTRACT) throw MemoryModelShutdownException("the host is stopping") else ""
        }
        val lattice = LatticeTestKit.open(model = model, embedder = BgeBandFakeEmbedder())
        seedClusters(lattice)
        val report = lattice.sleep(setOf(SleepPhase.SYNTHESIZE, SleepPhase.DECAY))
        assertEquals(1, model.calls(MemoryTask.ABSTRACT), "the second cluster was never sent")
        assertEquals(0, report.of(SleepPhase.SYNTHESIZE).count)
        assertEquals("The model is shutting down.", report.of(SleepPhase.DECAY).skipReason)
        // The next sleep starts afresh.
        assertFalse(lattice.sleep(setOf(SleepPhase.DECAY)).of(SleepPhase.DECAY).skipped)
        lattice.close()
    }

    @Test
    fun theSceneSeamsRunWhenScenesAreOnAndProvided() = runBlocking {
        val scenesOn = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = true))
        val model = FakeModel { _, _ -> "" }

        val without = LatticeTestKit.open(settings = scenesOn, model = model)
        val bare = without.sleep(setOf(SleepPhase.CATCH_UP, SleepPhase.PERIODS))
        assertEquals("The host provides no conversations to read scenes from.", bare.of(SleepPhase.CATCH_UP).skipReason)
        assertEquals("Period formation is not available in this build.", bare.of(SleepPhase.PERIODS).skipReason)
        without.close()

        val order = ArrayList<String>()
        val with = LatticeTestKit.open(
            settings = scenesOn,
            model = model,
            catchUp = { l -> order += "catch-up"; l.onGraph { it.size } + 3 },
            formPeriods = { _ -> order += "periods"; 2 },
        )
        val report = with.sleep(setOf(SleepPhase.PERIODS, SleepPhase.CATCH_UP))
        assertEquals(listOf("catch-up", "periods"), order, "in SleepPhase order, whatever order they were asked in")
        assertEquals(3, report.of(SleepPhase.CATCH_UP).count)
        assertEquals(2, report.of(SleepPhase.PERIODS).count)

        val noModel = LatticeTestKit.open(settings = scenesOn, catchUp = { _ -> 1 }, formPeriods = { _ -> 1 })
        val skipped = noModel.sleep(setOf(SleepPhase.CATCH_UP, SleepPhase.PERIODS))
        assertTrue(skipped.phases.all { it.skipReason == "There is no model." })
        with.close()
        noModel.close()
    }

    @Test
    fun aFlushableStoreIsFlushedAtTheEndOfEachSleep() = runBlocking {
        val store = FlushingStore()
        val lattice = LatticeTestKit.open(store = store)
        lattice.sleep(setOf(SleepPhase.DECAY))
        lattice.sleep(setOf(SleepPhase.CONSOLIDATE))
        assertEquals(2, store.flushes.get())
        lattice.close()
        assertEquals(3, store.flushes.get(), "and once more on close")
    }

    @Test
    fun consolidateCountsPromotions() = runBlocking {
        val lattice = LatticeTestKit.open()
        val id = lattice.withGraph { g ->
            g.remember("the user rehearses the violin concerto every evening", salience = 0.9).also { g.engram(it)!!.activationCount = 8 }
        }
        val report = lattice.sleep(setOf(SleepPhase.CONSOLIDATE))
        assertEquals(1, report.of(SleepPhase.CONSOLIDATE).count, "one promotion, no merge")
        assertEquals(Engram.Layer.SEMANTIC, lattice.withGraph { it.engram(id)!!.layer })
        lattice.close()
    }
}
