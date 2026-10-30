package org.foedusprogramme.lattice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryModelInterruptedException
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.TestLatticeStore
import org.foedusprogramme.lattice.core.TopicModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A close that runs out of time: what is left waiting on the threads it stops, and when the store closes. */
class DefaultLatticeShutdownTest {

    private val shortClose = LatticeTestKit.settings(shutdownTimeout = 300.milliseconds)

    private fun openWithScenes(): DefaultLattice = LatticeTestKit.open(
        settings = shortClose.copy(scenes = SceneSettings(enabled = true)),
        model = TopicModel(),
        conversations = FakeConversationSource(),
        formPeriods = { 0 },
    )

    @Test
    fun aNarrationTaskQueuedBehindALongOneFailsInsteadOfHangingAfterClose() = runBlocking {
        val lattice = LatticeTestKit.open(settings = shortClose)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val long = async(Dispatchers.IO) { lattice.onNarration { started.countDown(); holdUninterruptibly(release) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val queued = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { lattice.onNarration { "ran" } } }

        withTimeout(5.seconds) { lattice.close() }
        val outcome = withTimeout(5.seconds) { queued.await() }

        assertIs<IllegalStateException>(outcome.exceptionOrNull(), "the dropped task failed its caller: $outcome")
        release.countDown()
        long.await()
    }

    @Test
    fun aCatchUpQueuedBehindLongCaptureWorkEndsTheSleepAfterClose() = runBlocking {
        val lattice = openWithScenes()
        lattice.scenes!!.awaitIdle()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val long = async(Dispatchers.IO) { lattice.onCapture { started.countDown(); holdUninterruptibly(release) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val sleep = async(Dispatchers.IO) { runCatching { lattice.sleep(setOf(SleepPhase.CATCH_UP, SleepPhase.DECAY)) } }
        delay(100) // the catch-up is queued on the capture thread

        withTimeout(5.seconds) { lattice.close() }
        val outcome = withTimeout(5.seconds) { sleep.await() }

        assertSleepEndedByClose(outcome, SleepPhase.CATCH_UP)
        release.countDown()
        long.await()
    }

    @Test
    fun periodsWaitingOnQueuedNarrationEndTheSleepAfterClose() = runBlocking {
        val lattice = openWithScenes()
        lattice.scenes!!.awaitIdle()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val long = async(Dispatchers.IO) { lattice.onNarration { started.countDown(); holdUninterruptibly(release) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val sleep = async(Dispatchers.IO) { runCatching { lattice.sleep(setOf(SleepPhase.PERIODS, SleepPhase.DECAY)) } }
        delay(100) // the idle barrier is queued on the narration thread

        withTimeout(5.seconds) { lattice.close() }
        val outcome = withTimeout(5.seconds) { sleep.await() }

        assertSleepEndedByClose(outcome, SleepPhase.PERIODS)
        release.countDown()
        long.await()
    }

    @Test
    fun aCloseThatTimedOutOnACallStillGivesTheGraphItsOwnWaitAndThenClosesTheStore() = runBlocking {
        val store = WatchedStore()
        val lattice = LatticeTestKit.open(settings = LatticeTestKit.settings(shutdownTimeout = 1.seconds), store = store)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // One call in flight past the drain deadline, holding the graph thread.
        val busy = async(Dispatchers.IO) {
            runCatching { lattice.withGraph { g -> started.countDown(); holdUninterruptibly(release); g.remember(LATE) } }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val closing = async(Dispatchers.IO) { lattice.close(); store.closed }
        delay(1_500) // past the drain deadline, inside the graph's own wait
        release.countDown()

        assertTrue(withTimeout(5.seconds) { closing.await() }, "close returned with the store closed")
        assertTrue(store.engrams.values.any { it.content == LATE }, "the graph's last write landed before the close")
        assertFalse(store.writeAfterClose, "nothing was written after the store closed")
        assertTrue(busy.await().isSuccess)
    }

    @Test
    fun aGraphTaskBusyPastItsWaitClosesTheStoreWhenItEndsNeverBefore() = runBlocking {
        val store = WatchedStore()
        val lattice = LatticeTestKit.open(settings = shortClose, store = store)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val busy = async(Dispatchers.IO) { lattice.onGraph { g -> started.countDown(); holdUninterruptibly(release); g.remember(LATE) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        withTimeout(5.seconds) { lattice.close() }
        assertFalse(store.closed, "the graph task may still write: the store stays open")
        release.countDown()
        busy.await()
        withTimeout(5.seconds) { while (!store.closed) delay(10) }
        assertTrue(store.engrams.values.any { it.content == LATE })
        assertFalse(store.writeAfterClose)
    }

    // --- an interrupted wait for the graph ----------------------------------------------------------------------------

    @Test
    fun anInterruptWithdrawsAGraphBlockThatHasNotStarted() = runBlocking {
        val lattice = LatticeTestKit.open()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val occupier = async(Dispatchers.IO) { lattice.onGraph { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val call = BlockingCall { lattice.graphAccess.withGraph { g -> g.remember("queued behind the occupier") } }
        call.interruptWhileWaiting()
        release.countDown()
        occupier.await()

        assertIs<MemoryModelInterruptedException>(call.result().exceptionOrNull())
        assertTrue(call.interruptedAfter, "the interrupt flag is set again")
        assertEquals(0, lattice.stats().engrams, "the withdrawn block never ran")
        lattice.close()
    }

    @Test
    fun anInterruptWaitsForAGraphBlockThatStartedAndReturnsWhatItWrote() = runBlocking {
        val lattice = LatticeTestKit.open()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = BlockingCall {
            lattice.graphAccess.withGraph { g -> inside.countDown(); release.await(10, TimeUnit.SECONDS); g.remember("written while interrupted") }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        call.interruptWhileWaiting()
        release.countDown()

        val id = call.result().getOrThrow()
        assertTrue(call.interruptedAfter, "the interrupt flag is set again")
        assertEquals(id, lattice.withGraph { it.engram(id)?.id }, "the call reports the write that landed")
        lattice.close()
    }

    @Test
    fun aChunkWhoseIngestLandedDespiteAnInterruptIsNotExtractedAgainAfterARestart() = runBlocking {
        val source = FakeConversationSource().apply { open(CONV, CHAT) }
        source.talk(CONV, "topic=kyoto", 4)
        source.seal(CONV)
        val store = TestLatticeStore()
        val model = ExtractingModel()
        val embedder = GateEmbedder()
        fun open() = LatticeTestKit.open(
            settings = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = true)),
            store = store,
            embedder = embedder,
            model = model,
            conversations = source,
            graphConfig = LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99),
        )

        val first = open()
        first.conversationSealed(CONV)
        assertTrue(embedder.inIngest.await(5, TimeUnit.SECONDS), "the chunk's ingest holds the graph thread")
        val capture = checkNotNull(model.captureThread)
        withTimeout(5.seconds) { while (capture.state != Thread.State.WAITING) delay(5) }
        capture.interrupt() // the capture thread waits for the ingest
        delay(200)
        embedder.release.countDown()
        first.scenes!!.awaitIdle()
        first.close()
        assertEquals(1, model.extractions.get())

        val second = open()
        second.sleep(setOf(SleepPhase.CATCH_UP))
        second.scenes!!.awaitIdle()
        second.close()
        assertEquals(1, model.extractions.get(), "the chunk was extracted once: its bookkeeping landed with its memories")
    }

    /** Runs [block] on a thread of its own, to interrupt it while it waits for the graph. */
    private class BlockingCall<T>(block: () -> T) {
        private var outcome: Result<T>? = null

        @Volatile
        var interruptedAfter = false
        private val thread = Thread {
            outcome = runCatching(block)
            interruptedAfter = Thread.currentThread().isInterrupted
        }.apply { start() }

        fun interruptWhileWaiting() {
            val deadline = System.nanoTime() + 5_000_000_000
            while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(5)
            thread.interrupt()
            Thread.sleep(100) // the waiter takes the interrupt before the graph moves on
        }

        fun result(): Result<T> {
            thread.join(5_000)
            return checkNotNull(outcome) { "the call never returned" }
        }
    }

    /** Extracts one memory per said line (on the capture thread, which it notes); the scene calls go to [TopicModel]. */
    private class ExtractingModel : MemoryModel {
        private val topic = TopicModel()
        val extractions = AtomicInteger()

        @Volatile
        var captureThread: Thread? = null

        override fun complete(task: MemoryTask, system: String, user: String): String {
            if (task != MemoryTask.EXTRACT) return topic.complete(task, system, user)
            captureThread = Thread.currentThread()
            extractions.incrementAndGet()
            val lines = Regex("topic=(\\w+) message (\\d+)").findAll(user)
                .map { "The user talked about ${it.groupValues[1]} (message ${it.groupValues[2]})" }
            return LatticeTestKit.extraction(*lines.toList().toTypedArray())
        }
    }

    /** Holds the graph thread on the first extracted memory it embeds there, until [release]. */
    private class GateEmbedder(private val delegate: Embedder = LocalHashingEmbedder()) : Embedder by delegate {
        val inIngest = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val armed = AtomicBoolean(true)

        override fun embed(text: String): FloatArray {
            if ("talked about" in text && Thread.currentThread().name.endsWith("-graph") && armed.compareAndSet(true, false)) {
                inIngest.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            return delegate.embed(text)
        }
    }

    /** A store that notes a write after its close. */
    private class WatchedStore : LatticeTestKit.FlushingStore() {
        @Volatile
        var writeAfterClose = false

        override fun putEngram(engram: Engram) {
            if (closed) writeAfterClose = true
            super.putEngram(engram)
        }
    }

    /** The sleep ended (no hang), never as a cancellation; a report it returned names the phase the close cut off. */
    private fun assertSleepEndedByClose(outcome: Result<SleepReport>, phase: SleepPhase) {
        val failure = outcome.exceptionOrNull()
        assertFalse(failure is CancellationException, "the close does not read as the sleep's own cancellation: $failure")
        outcome.getOrNull()?.let { report ->
            assertNotNull(report.phases.single { it.phase == phase }.error, "$phase failed: $report")
            assertTrue(report.phases.single { it.phase == SleepPhase.DECAY }.skipped, "the rest is skipped: $report")
        }
    }

    private companion object {
        const val LATE = "the user wrote this as the graph closed"
        const val CHAT = "chat-1"
        const val CONV = "chat-1:c1"

        /** Waits for [release] whatever interrupts arrive (a task that ignores the shutdown), then re-sets the flag. */
        fun holdUninterruptibly(release: CountDownLatch) {
            var interrupted = false
            while (true) {
                try {
                    release.await(10, TimeUnit.SECONDS)
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
