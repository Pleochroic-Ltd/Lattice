package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.lattice.LatticeSettings
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import java.time.Clock
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
