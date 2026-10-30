package org.foedusprogramme.lattice.remote

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.Lattice
import org.foedusprogramme.lattice.LatticeDependencies
import org.foedusprogramme.lattice.LatticeSettings
import org.foedusprogramme.lattice.core.BundledTexts
import org.foedusprogramme.lattice.core.Edge
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.openLattice
import org.foedusprogramme.lattice.reloadLattice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two devices on one Pleochroic graph: what one writes reaches the other's live graph once it reloads. */
class PleochroicReloadTest {
    @TempDir
    lateinit var dir: Path

    private val server = FakePleochroicServer()
    private val heard = CopyOnWriteArrayList<PleochroicGraphStore.RemoteChanges>()
    private val lattices = ArrayList<Lattice>()

    @AfterEach
    fun stop() = runBlocking {
        lattices.forEach { runCatching { it.close() } }
        server.close()
    }

    private fun store(device: String) = PleochroicGraphStore(
        server.client(), GRAPH, dir.resolve(device),
        PleochroicGraphStore.Options(minRetryDelay = Duration.ofMinutes(1)),
    ) { heard += it }

    private fun open(store: PleochroicGraphStore): Lattice = openLattice(
        "agent",
        LatticeSettings(zone = ZoneOffset.UTC),
        LatticeDependencies(store, LocalHashingEmbedder(DIMENSION), model = null, texts = BundledTexts),
    ).also { lattices += it }

    @Test
    fun `another device's write reaches the live graph after a pull and a reload`() = runBlocking {
        val storeA = store("a")
        val a = open(storeA)
        a.remember("the user drinks green tea every morning")
        val b = open(store("b"))
        val moved = b.remember("the user moved to Berlin last spring")

        assertNull(a.withGraph { it.engram(moved) })
        val pulled = storeA.pullRemoteChanges()
        assertTrue(moved in pulled.ids)
        assertTrue(reloadLattice(a))

        assertEquals("the user moved to Berlin last spring", a.withGraph { it.engram(moved)?.content })
        assertEquals(2, a.stats().engrams)
    }

    @Test
    fun `a write on a row another device deleted fails until the graph reloads, then succeeds`() = runBlocking {
        val storeA = store("a")
        val a = open(storeA)
        val gone = a.remember("the user drinks green tea every morning")
        val kept = a.remember("a violin lesson happens on Thursdays")
        val other = store("b").also { it.load() }
        other.deleteEngram(gone)

        val third = a.remember("quarterly taxes are due in October")
        assertTrue(heard.any { gone in it.ids }, "the post's conflict told the listener: $heard")
        assertFailsWith<IllegalArgumentException> { a.withGraph { it.link(kept, gone, Edge.Type.CAUSAL, 0.5) } }

        assertTrue(reloadLattice(a))

        assertNull(a.withGraph { it.engram(gone) })
        a.withGraph { it.link(kept, third, Edge.Type.CAUSAL, 0.5) }
        a.remember("the dentist appointment moved to Friday")
        assertFalse(gone in server.graph(GRAPH)!!.engramIds)
        assertEquals(a.withGraph { g -> g.size }, server.graph(GRAPH)!!.engramIds.size)
    }

    private companion object {
        const val GRAPH = "agent-main"
        const val DIMENSION = 512
    }
}
