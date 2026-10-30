package org.foedusprogramme.lattice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.TopicModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
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
