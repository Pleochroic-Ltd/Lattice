package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.lattice.LatticeSettings
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")
    private val coder = opener.graph("coder")

    /** The scheduler's virtual time, from [ORIGIN]. */
    private class VirtualClock(private val scope: TestScope, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun instant(): Instant = ORIGIN.plusMillis(scope.testScheduler.currentTime)

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = VirtualClock(scope, zone)
    }

    private fun TestScope.timer(
        stored: List<String> = emptyList(),
        enabled: Boolean = true,
        zone: ZoneId = ZoneOffset.UTC,
        scope: CoroutineScope = backgroundScope,
    ): SleepTimer {
        val clock = VirtualClock(this)
        val graphs = LatticeGraphs(FakeBackends(stored), opener, LatticeSettings(), scope, clock, EmptyCoroutineContext)
        return SleepTimer(graphs, enabled, LocalTime.of(3, 30), zone, scope, SystemSleepClock(clock))
    }

    @Test
    fun `every stored graph sleeps at the configured time each day, one after another`() = runTest {
        val timer = timer(stored = listOf("main", "coder"))
        timer.onOpen()

        advanceTimeBy(3.hours + 29.minutes)
        runCurrent()
        assertEquals(listOf(0, 0), listOf(main.sleeps.value, coder.sleeps.value))

        advanceTimeBy(2.minutes)
        runCurrent()
        assertEquals(listOf(1, 1), listOf(main.sleeps.value, coder.sleeps.value))
        assertEquals(Instant.parse("2026-10-30T03:30:00Z"), timer.lastSleep(AgentId.MAIN)?.at)

        advanceTimeBy(24.hours)
        runCurrent()
        assertEquals(listOf(2, 2), listOf(main.sleeps.value, coder.sleeps.value))

        timer.onClose()
    }

    @Test
    fun `the next sleep is the next time of day in the timer's zone`() = runTest {
        val shanghai = timer(zone = ZoneId.of("Asia/Shanghai"))

        assertEquals(Instant.parse("2026-10-30T19:30:00Z"), shanghai.next(ORIGIN))
        assertEquals(Instant.parse("2026-10-31T19:30:00Z"), shanghai.next(Instant.parse("2026-10-30T19:30:00Z")))
        assertEquals(Instant.parse("2026-10-30T19:30:00Z"), shanghai.nextSleep())
        assertNull(timer(enabled = false).nextSleep())
    }

    @Test
    fun `a sleep the timer is off for never starts on its own`() = runTest {
        val timer = timer(stored = listOf("main"), enabled = false)
        timer.onOpen()

        advanceTimeBy(48.hours)
        runCurrent()

        assertEquals(0, main.sleeps.value)
    }

    @Test
    fun `a started sleep runs alone`() = runTest {
        val gate = CompletableDeferred<Unit>()
        main.sleepGate = gate
        val timer = timer()

        assertTrue(timer.start(AgentId.MAIN))
        runCurrent()
        assertFalse(timer.start(AgentId.MAIN))
        gate.complete(Unit)
        runCurrent()

        assertNotNull(timer.lastSleep(AgentId.MAIN))
        assertTrue(timer.start(AgentId.MAIN))
    }

    private companion object {
        val ORIGIN: Instant = Instant.parse("2026-10-30T00:00:00Z")
    }
}
