package org.foedusprogramme.lattice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.foedusprogramme.lattice.LatticeTestKit.FakeModel
import org.foedusprogramme.lattice.LatticeTestKit.FlushingStore
import org.foedusprogramme.lattice.core.BundledTexts
import org.foedusprogramme.lattice.core.EmbeddingSpec
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.Reinforce
import org.foedusprogramme.lattice.core.TestLatticeStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Opening (the embedding spec), the graph thread's deadline, cancellation, and closing. */
class DefaultLatticeLifecycleTest {

    // --- the embedding spec ------------------------------------------------------------------------------------------

    @Test
    fun aNewGraphTakesTheEmbeddersSpec() = runBlocking {
        val store = TestLatticeStore()
        val lattice = LatticeTestKit.open(store = store, embedder = LocalHashingEmbedder(256))
        assertEquals(EmbeddingSpec("local-hashing-256", 256), store.embedding)
        val stats = lattice.stats()
        assertEquals("local-hashing-256", stats.embeddingModel)
        assertEquals(256, stats.embeddingDimension)
        lattice.close()
    }

    @Test
    fun aGraphOpensAgainWithTheSameEmbedder() = runBlocking {
        val store = TestLatticeStore()
        val first = LatticeTestKit.open(store = store)
        val id = first.remember("the user keeps bees on the roof")
        first.close()
        val second = LatticeTestKit.open(store = store)
        assertEquals(id, second.withGraph { g -> g.engram(id)?.id })
        second.close()
    }

    @Test
    fun aGraphEmbeddedWithAnotherModelRefusesToOpen() {
        val store = TestLatticeStore().apply { declareEmbedding(EmbeddingSpec("bge-m3", 1024)) }
        val e = assertFailsWith<IllegalStateException> {
            openLattice(
                "alice",
                LatticeTestKit.settings(),
                LatticeDependencies(store, LocalHashingEmbedder(512), model = null, texts = BundledTexts),
            )
        }
        val message = e.message.orEmpty()
        assertTrue("bge-m3" in message && "local-hashing-512" in message, message)
        assertTrue("re-embed" in message && "embedder for bge-m3" in message, "the message says how to fix it: $message")
        assertEquals(EmbeddingSpec("bge-m3", 1024), store.embedding, "the stored spec is untouched")
    }

    @Test
    fun aDifferentDimensionOfTheSameModelRefusesToOpen() {
        val store = TestLatticeStore().apply { declareEmbedding(EmbeddingSpec("local-hashing-512", 256)) }
        assertFailsWith<IllegalStateException> { LatticeTestKit.open(store = store, embedder = LocalHashingEmbedder(512)) }
    }

    // --- the graph thread --------------------------------------------------------------------------------------------

    @Test
    fun aLiveRecallTimesOutInsteadOfWaitingBehindALongGraphTask() = runBlocking {
        val lattice = LatticeTestKit.open(settings = LatticeTestKit.settings(graphReadTimeout = 200.milliseconds))
        lattice.remember("hello world")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        // Occupy the graph thread the way a long offline pass would.
        val occupier = launch(Dispatchers.IO) { lattice.onGraph { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        assertFailsWith<GraphBusyException> { lattice.recall("hello", RecallOptions(reinforce = Reinforce.FULL)) }
        assertFailsWith<GraphBusyException> { lattice.remember("another memory") }
        assertFailsWith<GraphBusyException> { lattice.withGraph { it.size } }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs < 5_000, "gave up promptly (${elapsedMs}ms)")

        release.countDown()
        occupier.join()
        val after = lattice.withGraph { g -> g.engrams().single() }
        assertEquals(0, after.activationCount, "the recall that timed out before it started never ran")
        assertEquals(1, lattice.recall("hello", RecallOptions(reinforce = Reinforce.NONE)).hits.size, "the graph works again")
        assertEquals(1, lattice.stats().engrams, "the remember that timed out never ran either")
        lattice.close()
    }

    @Test
    fun aCancelledRecallNeverRunsAndTheGraphKeepsWorking() = runBlocking {
        val lattice = LatticeTestKit.open()
        val id = lattice.remember("the user plays the cello on sundays")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val occupier = launch(Dispatchers.IO) { lattice.onGraph { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val recall = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            lattice.recall("cello sundays", RecallOptions(explicit = true, reinforce = Reinforce.FULL))
        }
        yield()
        recall.cancelAndJoin()
        release.countDown()
        occupier.join()

        assertEquals(0, lattice.withGraph { it.engram(id)!!.activationCount }, "the cancelled recall was dropped before it ran")
        assertEquals(id, lattice.recall("cello sundays", RecallOptions(explicit = true)).hits.single().id)
        lattice.close()
    }

    @Test
    fun aStartedGraphMutationRunsToCompletionWhenItsCallerIsCancelled() = runBlocking {
        val lattice = LatticeTestKit.open()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = launch(Dispatchers.IO) {
            lattice.onGraph { g ->
                inside.countDown()
                release.await(10, TimeUnit.SECONDS)
                g.remember("the user moved to Lisbon")
                g.remember("the user learns Portuguese")
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        withTimeout(1.seconds) { caller.cancelAndJoin() } // returns at once, while the task still runs
        release.countDown()
        assertEquals(2, lattice.stats().engrams, "both writes landed: the task was not cut short")
        assertTrue(lattice.recall("Lisbon", RecallOptions(explicit = true)).hits.isNotEmpty())
        lattice.close()
    }

    // --- close -------------------------------------------------------------------------------------------------------

    @Test
    fun closeLetsACaptureInFlightFinishThenClosesTheStoreAndRefusesEveryCall() = runBlocking {
        val store = FlushingStore()
        val inExtraction = CountDownLatch(1)
        val release = CountDownLatch(1)
        val model = FakeModel { _, _ ->
            inExtraction.countDown()
            release.await(10, TimeUnit.SECONDS)
            LatticeTestKit.extraction("the user adopted a cat named Byte")
        }
        val lattice = LatticeTestKit.open(store = store, model = model)
        val capture = async(Dispatchers.IO) {
            lattice.capture(Transcript(listOf(Transcript.Line(Transcript.Role.USER, "I adopted a cat, Byte"))))
        }
        assertTrue(inExtraction.await(5, TimeUnit.SECONDS))
        val closing = launch(Dispatchers.IO) { lattice.close() }
        // Closing has begun: new calls are refused, the capture in flight is not.
        withTimeout(5.seconds) { while (runCatching { lattice.stats() }.isSuccess) yield() }
        assertFalse(store.closed, "the store stays open while a call is in flight")
        release.countDown()
        assertEquals(1, capture.await(), "the capture in flight finished")
        closing.join()

        assertTrue(store.closed)
        assertEquals("lattice-test-graph", store.closedOn, "the store closes as the graph thread's last task")
        assertEquals(1, store.flushes.get(), "a flushable store is flushed before it closes")
        assertEquals(1, store.engrams.size, "the captured memory reached the store")
        val calls: List<suspend () -> Unit> = listOf(
            { lattice.recall("cat") },
            { lattice.remember("x") },
            { lattice.capture(Transcript(emptyList())) },
            { lattice.saveEpisode("a session") },
            { lattice.sleep() },
            { lattice.stats() },
            { lattice.withGraph { it.size } },
        )
        for (c in calls) assertFailsWith<IllegalStateException> { c() }
        lattice.close() // a second close is a no-op
    }

    @Test
    fun aSleepStopsAtItsNextStepWhenCloseStarts() = runBlocking {
        val inSynthesis = CountDownLatch(1)
        val release = CountDownLatch(1)
        val model = FakeModel { task, _ ->
            if (task == MemoryTask.ABSTRACT) {
                inSynthesis.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            ""
        }
        val lattice = LatticeTestKit.open(model = model, embedder = org.foedusprogramme.lattice.core.BgeBandFakeEmbedder())
        lattice.withGraph { g ->
            listOf("A|s1|m1", "A|s1|m2", "A|s1|m3", "B|s1|m1", "B|s1|m2", "B|s1|m3").forEach { g.remember(it) }
        }
        val sleep = async(Dispatchers.IO) { lattice.sleep(setOf(SleepPhase.SYNTHESIZE, SleepPhase.DECAY)) }
        assertTrue(inSynthesis.await(5, TimeUnit.SECONDS))
        val closing = launch(Dispatchers.IO) { lattice.close() }
        withTimeout(5.seconds) { while (runCatching { lattice.stats() }.isSuccess) yield() }
        release.countDown()
        val report = sleep.await()
        closing.join()
        assertEquals(1, model.calls(MemoryTask.ABSTRACT), "no second cluster was sent to the model once closing began")
        val decay = report.phases.single { it.phase == SleepPhase.DECAY }
        assertTrue(decay.skipped)
        assertEquals("The graph is closing.", decay.skipReason)
    }
}
