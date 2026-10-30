package org.foedusprogramme.lattice.remote

import org.foedusprogramme.lattice.core.Edge
import org.foedusprogramme.lattice.core.EdgeRecord
import org.foedusprogramme.lattice.core.EmbeddingSpec
import org.foedusprogramme.lattice.core.EntityRecord
import org.foedusprogramme.lattice.core.IdAllocator
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.SPEC
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.T0
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.engram
import org.foedusprogramme.lattice.core.SceneRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PleochroicGraphStoreTest {
    @TempDir
    lateinit var dir: Path

    private val server = FakePleochroicServer()
    private val stores = ArrayList<PleochroicGraphStore>()
    private val heard = ArrayList<PleochroicGraphStore.RemoteChanges>()

    @AfterEach
    fun stop() {
        stores.forEach { runCatching { it.close() } }
        server.close()
    }

    private fun store(
        device: String = "a",
        name: String = GRAPH,
        options: PleochroicGraphStore.Options = PleochroicGraphStore.Options(minRetryDelay = Duration.ofMinutes(1)),
        client: PleochroicClient = server.client(),
    ) = PleochroicGraphStore(client, name, dir.resolve(device), options) { heard += it }.also { stores += it }

    private fun posts() = server.requests.count { it == "POST /graphs/$GRAPH/ops" }

    @Test
    fun `a missing graph loads empty and declaring its embedding creates it`() {
        val store = store()
        assertEquals(0, store.load().engrams.size)
        assertNull(store.embedding)
        assertNull(server.graph(GRAPH))
        assertFailsWith<IllegalStateException> { store.putEngram(engram(1)) }

        store.declareEmbedding(SPEC)
        store.putEngram(engram(1))

        val graph = server.graph(GRAPH)!!
        assertEquals(setOf(1L), graph.engramIds)
        val claim = graph.records("device").single()
        assertEquals(store.idSlot.toLong(), claim["slot"].toString().toLong())
        assertTrue(store.idSlot in 1 until IdAllocator.SLOTS)
    }

    @Test
    fun `two devices take different slots and keep them`() {
        val a = store("a").also { it.declareEmbedding(SPEC) }
        val slotA = a.idSlot
        val b = store("b")
        val slotB = b.idSlot
        assertNotEquals(slotA, slotB)
        assertEquals(2, server.graph(GRAPH)!!.records("device").size)
        a.close()
        assertEquals(slotA, store("a").idSlot)
        assertEquals(2, server.graph(GRAPH)!!.records("device").size)
    }

    @Test
    fun `a write is one post and a batch is one post`() {
        val store = store().also { it.declareEmbedding(SPEC) }
        store.putEngram(engram(1))
        val before = posts()
        store.putEngram(engram(2))
        assertEquals(before + 1, posts())
        store.batch { s ->
            (3L..50L).forEach { s.putEngram(engram(it)) }
            s.putEdge(EdgeRecord(3, 4, Edge.Type.SEMANTIC, 0.5, T0))
        }
        assertEquals(before + 2, posts())
        assertEquals((1L..50L).toSet(), server.graph(GRAPH)!!.engramIds)
    }

    @Test
    fun `a batch past the per-post limit is split, and a failing batch posts nothing`() {
        val store = store(options = PleochroicGraphStore.Options(maxBatchOps = 10)).also { it.declareEmbedding(SPEC) }
        val before = posts()
        store.batch { s -> (1L..25L).forEach { s.putEngram(engram(it)) } }
        assertEquals(before + 3, posts())
        assertEquals(25, server.graph(GRAPH)!!.engramIds.size)

        runCatching {
            store.batch { s ->
                s.putEngram(engram(99))
                error("fails")
            }
        }
        assertEquals(before + 3, posts())
        assertFalse(99L in store.load().engrams.map { it.id })
    }

    @Test
    fun `a cascade goes out as explicit ops`() {
        val store = store().also { it.declareEmbedding(SPEC) }
        store.putEngram(engram(1))
        store.putEngram(engram(2))
        store.putEntity(EntityRecord(10, "Alice", "alice"))
        store.linkEngramEntity(1, 10)
        store.putScene(SceneRecord(20, "s", "c", "chat", 1, 2, hubEngramId = 1))
        store.putSceneMember(20, 1)
        store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.5, T0))

        store.deleteEngram(1)

        val graph = server.graph(GRAPH)!!
        assertEquals(setOf(2L), graph.engramIds)
        assertTrue(graph.records("entityLink").isEmpty())
        assertTrue(graph.records("sceneMember").isEmpty())
        assertNull(graph.records("scene").single()["hubEngramId"])
        assertEquals(0, graph.edgeCount)
    }

    @Test
    fun `a conflict pulls the other device's writes, keeps ours on top and tells the listener`() {
        val a = store("a").also { it.declareEmbedding(SPEC) }
        val b = store("b").also { it.load() }
        a.putEngram(engram(1))
        a.putEngram(engram(2).copy(content = "a's version"))

        b.putEngram(engram(2).copy(content = "b's version"))
        b.putEngram(engram(3))

        assertEquals(setOf(1L, 2L, 3L), b.load().engrams.map { it.id }.toSet())
        assertEquals("b's version", b.load().engrams.single { it.id == 2L }.content)
        assertTrue(heard.any { 1L in it.ids }, "b heard a's write: $heard")

        val pulled = a.pullRemoteChanges()
        assertTrue(3L in pulled.ids)
        assertEquals("b's version", a.load().engrams.single { it.id == 2L }.content)
        assertEquals(setOf(1L, 2L, 3L), server.graph(GRAPH)!!.engramIds)
    }

    @Test
    fun `a remote delete takes what our mirror hung on the engram, and the repair goes to the service`() {
        val a = store("a").also { it.declareEmbedding(SPEC) }
        a.putEngram(engram(1))
        a.putEngram(engram(2))
        val b = store("b").also { it.load() }
        a.putEntity(EntityRecord(10, "Alice", "alice"))
        a.linkEngramEntity(1, 10)

        b.deleteEngram(1)

        assertTrue(b.load().entityLinks.isEmpty())
        b.putEngram(engram(3))
        assertTrue(server.graph(GRAPH)!!.records("entityLink").isEmpty(), "b dropped the link a's write left dangling")
    }

    @Test
    fun `writes made while the service is down wait in the outbox and go out in order`() {
        val store = store().also { it.declareEmbedding(SPEC) }
        store.putEngram(engram(1))
        server.down = true

        store.putEngram(engram(2))
        val whileDown = server.requests.size
        store.putEngram(engram(3))
        store.deleteEngram(2)
        assertEquals(whileDown, server.requests.size, "writes back off while the service is down")
        assertEquals(3, store.pendingWrites)
        assertTrue(Files.size(dir.resolve("a/$GRAPH.outbox.jsonl")) > 0)
        assertEquals(setOf(1L, 3L), store.load().engrams.map { it.id }.toSet())

        server.down = false
        assertTrue(store.flush())
        assertEquals(0, store.pendingWrites)
        assertFalse(Files.exists(dir.resolve("a/$GRAPH.outbox.jsonl")))
        assertEquals(setOf(1L, 3L), server.graph(GRAPH)!!.engramIds)
    }

    @Test
    fun `the outbox survives a restart and a store opens offline from its copy`() {
        val first = store().also { it.declareEmbedding(SPEC) }
        first.putEngram(engram(1))
        server.down = true
        first.putEngram(engram(2))
        first.close()

        val second = store()
        assertEquals(setOf(1L, 2L), second.load().engrams.map { it.id }.toSet())
        assertEquals(1, second.pendingWrites)
        assertEquals(setOf(1L), server.graph(GRAPH)!!.engramIds)

        server.down = false
        second.putEngram(engram(3))
        assertEquals(2, second.pendingWrites, "a write during the back-off waits behind the outbox")
        assertTrue(second.flush())
        assertEquals(setOf(1L, 2L, 3L), server.graph(GRAPH)!!.engramIds)
    }

    @Test
    fun `an offline close saves the outbox and the copy without calling the service`() {
        val store = store().also { it.declareEmbedding(SPEC) }
        store.putEngram(engram(1))
        server.failNextOps = 1
        store.putEngram(engram(2))
        Files.deleteIfExists(dir.resolve("a/$GRAPH.snapshot.ndjson"))
        val before = server.requests.size

        store.closeOffline()

        assertEquals(before, server.requests.size)
        assertTrue(Files.readString(dir.resolve("a/$GRAPH.outbox.jsonl")).contains("\"putEngram\""))
        assertTrue(Files.exists(dir.resolve("a/$GRAPH.snapshot.ndjson")))
        assertFailsWith<IllegalStateException> { store.putEngram(engram(3)) }
        assertEquals(setOf(1L, 2L), store("a").load().engrams.map { it.id }.toSet())
    }

    @Test
    fun `a failed post is retried once the back-off has passed`() {
        val store = store(options = PleochroicGraphStore.Options(minRetryDelay = Duration.ZERO)).also { it.declareEmbedding(SPEC) }
        server.failNextOps = 1
        store.putEngram(engram(1))
        assertEquals(1, store.pendingWrites)
        store.putEngram(engram(2))
        assertEquals(0, store.pendingWrites)
        assertEquals(setOf(1L, 2L), server.graph(GRAPH)!!.engramIds)
    }

    @Test
    fun `a post refused as malformed is set aside and the rest goes on`() {
        val store = store().also { it.declareEmbedding(SPEC) }
        server.rejectNextOps = 1
        store.putEngram(engram(1))
        store.putEngram(engram(2))
        assertEquals(0, store.pendingWrites)
        assertEquals(setOf(2L), server.graph(GRAPH)!!.engramIds)
        assertTrue(Files.readString(dir.resolve("a/$GRAPH.rejected.jsonl")).contains("\"putEngram\""))
    }

    @Test
    fun `a device past the op log reloads the snapshot`() {
        val a = store("a").also { it.declareEmbedding(SPEC) }
        val b = store("b").also { it.load() }
        a.putEngram(engram(1))
        a.putEngram(engram(2))
        server.compact(GRAPH)

        val pulled = b.pullRemoteChanges()

        assertTrue(pulled.reloaded)
        assertEquals(setOf(1L, 2L), b.load().engrams.map { it.id }.toSet())
        b.putEngram(engram(3))
        assertEquals(setOf(1L, 2L, 3L), server.graph(GRAPH)!!.engramIds)
    }

    @Test
    fun `a graph deleted on the service is not created again, and its unsent writes are moved aside`() {
        val store = store().also { it.declareEmbedding(SPEC) }
        store.putEngram(engram(1))
        server.down = true
        store.putEngram(engram(2))
        server.down = false
        server.delete(GRAPH)
        store.putEngram(engram(3))

        assertFalse(store.flush())

        assertNull(server.graph(GRAPH), "the deleted graph is not created again")
        assertContains(store.failure.orEmpty(), "was deleted on Pleochroic")
        assertEquals(0, store.pendingWrites)
        assertFalse(Files.exists(dir.resolve("a/$GRAPH.outbox.jsonl")))
        val aside = Files.list(dir.resolve("a")).use { files -> files.toList() }
            .single { it.fileName.toString().startsWith("$GRAPH.outbox.gone-") }
        val kept = Files.readString(aside)
        assertTrue("\"id\":2" in kept && "\"id\":3" in kept, kept)

        val before = server.requests.size
        store.putEngram(engram(4))
        store.close()
        assertEquals(before, server.requests.size, "a store whose graph is gone writes no more")
        assertFalse(Files.exists(dir.resolve("a/$GRAPH.snapshot.ndjson")))
    }

    @Test
    fun `a pull that finds the graph deleted stops the store too`() {
        val store = store().also { it.declareEmbedding(SPEC) }
        store.putEngram(engram(1))
        server.delete(GRAPH)

        store.pullRemoteChanges()

        assertContains(store.failure.orEmpty(), "was deleted on Pleochroic")
        store.putEngram(engram(2))
        assertNull(server.graph(GRAPH))
    }

    @Test
    fun `a graph with another embedding is refused`() {
        store("a").declareEmbedding(SPEC)
        val b = store("b")
        assertFailsWith<IllegalStateException> { b.declareEmbedding(EmbeddingSpec("other", 4)) }
        b.declareEmbedding(SPEC)
    }

    @Test
    fun `a first open without the service fails clearly and loses nothing`() {
        server.down = true
        val store = store()
        val e = assertFailsWith<PleochroicUnreachableException> { store.load() }
        assertTrue("connect once" in e.message.orEmpty(), e.message)
        server.down = false
        assertEquals(0, store.load().engrams.size)
    }

    @Test
    fun `a bad graph name is refused before any call`() {
        assertFailsWith<IllegalArgumentException> { PleochroicGraphStore(server.client(), "Bad_Name", dir) }
        assertTrue(server.requests.isEmpty())
    }

    private companion object {
        const val GRAPH = "agent-main"
    }
}
