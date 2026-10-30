package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.lattice.LatticeSettings
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How the graphs close while Alexandrite stops: together, within a bound, with the stores saved after it. */
@OptIn(ExperimentalCoroutinesApi::class)
class LatticeGraphsTest {
    private val opener = FakeOpener()
    private val backends = FakeBackends()

    private fun kotlinx.coroutines.test.TestScope.graphs() = LatticeGraphs(
        backends,
        opener,
        LatticeSettings(shutdownTimeout = 8.seconds),
        backgroundScope,
        Clock.systemUTC(),
        EmptyCoroutineContext,
    )

    private class SteppedClock(var now: Instant = Instant.parse("2026-10-30T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    @Test
    fun `a graph that keeps failing to open is tried less and less often, and each reason is told once`() = runTest {
        val clock = SteppedClock()
        val told = ArrayList<String>()
        val graphs = LatticeGraphs(
            backends, opener, LatticeSettings(), backgroundScope, clock, EmptyCoroutineContext,
            openFailed = { message, cause -> told += "$message: ${cause.message}" },
        )
        opener.failure = IllegalStateException("Pleochroic refused the API key.")
        suspend fun tryOpen() = runCatching { graphs.graph(AgentId.MAIN) }.isSuccess

        assertFalse(tryOpen())
        clock.now = clock.now.plusSeconds(29)
        assertFalse(tryOpen())
        assertEquals(1, opener.attempts.get(), "not tried again before 30 s")
        clock.now = clock.now.plusSeconds(1)
        assertFalse(tryOpen())
        assertEquals(2, opener.attempts.get())
        clock.now = clock.now.plusSeconds(59)
        assertFalse(tryOpen())
        assertEquals(2, opener.attempts.get(), "then not before 60 s")
        clock.now = clock.now.plusSeconds(1)
        assertFalse(tryOpen())
        assertEquals(3, opener.attempts.get())
        assertEquals(1, told.size, "the same reason is told once")
        assertTrue("agent 'main'" in told.single() && "refused the API key" in told.single(), told.single())

        opener.failure = IllegalStateException("Pleochroic is unreachable.")
        clock.now = clock.now.plusSeconds(120)
        assertFalse(tryOpen())
        assertEquals(2, told.size, "a new reason is told")
        opener.failure = null
        clock.now = clock.now.plusSeconds(240)
        assertTrue(tryOpen())
    }

    @Test
    fun `the graphs close together`() = runTest {
        val graphs = graphs()
        for (agent in listOf("main", "coder")) {
            opener.graph(agent).closeDelay = 5.seconds
            graphs.graph(AgentId(agent))
        }

        graphs.onDrain()

        assertEquals(5_000, currentTime, "two closes of 5 s each took as long as one")
        assertTrue(opener.graph("main").closed && opener.graph("coder").closed)
    }

    @Test
    fun `a close that outlasts the bound is left behind, and the stores are closed as the plugin stops`() = runTest {
        val graphs = graphs()
        opener.graph("main").closeDelay = 10.minutes
        graphs.graph(AgentId.MAIN)

        graphs.onDrain()

        assertEquals(10_000, currentTime, "the shutdown timeout and its margin")
        assertFalse(opener.graph("main").closed)
        assertTrue(backends.closedStores.isEmpty())
        graphs.onStop()
        assertEquals(listOf("main"), backends.closedStores)
    }
}
