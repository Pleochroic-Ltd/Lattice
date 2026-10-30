package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.lattice.LatticeSettings
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How other devices' writes reach a graph: the store's notices and pulls end in a reload. */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphSyncTest {
    private var reloads = 0

    private fun TestScope.sync(failFirst: Boolean = false): GraphSync =
        GraphSync("main", backgroundScope, EmptyCoroutineContext).also { sync ->
            sync.bind {
                reloads++
                if (failFirst && reloads == 1) error("the store is busy")
            }
        }

    @Test
    fun `a change the store found reloads the graph, also one found before it opened`() = runTest {
        val sync = GraphSync("main", backgroundScope, EmptyCoroutineContext)
        sync.changed()
        runCurrent()
        sync.bind { reloads++ }
        runCurrent()
        assertEquals(1, reloads)

        sync.changed()
        sync.changed()
        runCurrent()
        assertEquals(2, reloads, "changes found together reload once")
    }

    @Test
    fun `a pull reloads only when other devices wrote`() = runTest {
        val answers = ArrayDeque(listOf(false, true))
        val sync = sync().also { it.attach { answers.removeFirst() } }

        sync.pull()
        assertEquals(0, reloads)
        sync.pull()
        assertEquals(1, reloads)
        assertTrue(sync.remote)
    }

    @Test
    fun `a failed reload is tried again at the next pull`() = runTest {
        val sync = sync(failFirst = true).also { it.attach { false } }
        sync.changed()
        runCurrent()
        assertEquals(1, reloads)

        sync.pull()
        assertEquals(2, reloads)
        sync.pull()
        assertEquals(2, reloads)
    }

    @Test
    fun `an open remote graph pulls on its timer and before each sleep, a local one never`() = runTest {
        val pulls = CopyOnWriteArrayList<String>()
        val opener = FakeOpener()
        val graphs = LatticeGraphs(
            FakeBackends(pull = { pulls += it; true }),
            opener,
            LatticeSettings(),
            backgroundScope,
            Clock.systemUTC(),
            EmptyCoroutineContext,
            pullInterval = 1.minutes,
        )
        graphs.onOpen()
        graphs.graph(AgentId.MAIN)
        advanceTimeBy(1.minutes + 1.seconds)
        runCurrent()
        assertEquals(listOf("main"), pulls)
        assertEquals(listOf("main"), opener.reloads.value)

        val timer = SleepTimer(graphs, false, java.time.LocalTime.NOON, java.time.ZoneOffset.UTC, backgroundScope, SystemSleepClock(Clock.systemUTC()))
        assertTrue(timer.start(AgentId.MAIN))
        runCurrent()
        assertEquals(listOf("main", "main"), pulls, "a sleep pulls first")
        assertEquals(1, opener.graph("main").sleeps.value)
        graphs.onDrain()

        val local = FakeOpener()
        val localGraphs = LatticeGraphs(FakeBackends(), local, LatticeSettings(), backgroundScope, Clock.systemUTC(), EmptyCoroutineContext)
        localGraphs.graph(AgentId.MAIN)
        localGraphs.pull(AgentId.MAIN)
        assertTrue(local.reloads.value.isEmpty())
    }
}
