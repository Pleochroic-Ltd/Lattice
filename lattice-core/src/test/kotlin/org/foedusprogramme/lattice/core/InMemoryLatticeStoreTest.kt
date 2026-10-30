package org.foedusprogramme.lattice.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class InMemoryLatticeStoreContractTest : LatticeStoreContract() {
    override fun newStore(): LatticeStore = InMemoryLatticeStore()

    override fun reopen(store: LatticeStore): LatticeStore = store
}

class InMemoryLatticeStoreTest {
    private val changes = ArrayList<InMemoryLatticeStore.Change>()
    private val store = InMemoryLatticeStore(onChange = { changes += it })

    private fun engram(id: Long) = LatticeStoreContract.engram(id)

    @Test
    fun `a cascade reports the dependents before the row they depend on`() {
        store.putEngram(engram(1))
        store.putEngram(engram(2))
        store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0))
        store.putScene(SceneRecord(20, "s", "c", "chat", 1, 2, hubEngramId = 1))
        changes.clear()

        store.deleteEngram(1)

        assertEquals(
            listOf(
                InMemoryLatticeStore.Change(InMemoryLatticeStore.Row.EdgeRow(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0)), true),
                InMemoryLatticeStore.Change(InMemoryLatticeStore.Row.SceneRow(SceneRecord(20, "s", "c", "chat", 1, 2)), false),
            ),
            changes.dropLast(1),
        )
        val last = changes.last()
        assertTrue(last.removed && (last.row as InMemoryLatticeStore.Row.EngramRow).engram.id == 1L)
    }

    @Test
    fun `changes are told once the outermost batch succeeds, and never for a rolled-back one`() {
        store.batch { s ->
            s.putEngram(engram(5))
            assertTrue(changes.isEmpty())
        }
        assertEquals(1, changes.size)
        changes.clear()
        store.putEngram(engram(1))
        changes.clear()
        assertFails {
            store.batch { s ->
                s.putEngram(engram(2))
                error("fails")
            }
        }
        assertTrue(changes.isEmpty())
        assertEquals(setOf(1L, 5L), store.load().engrams.map { it.id }.toSet())
    }

    @Test
    fun `remote rows go in unchecked and repair drops what dangles`() {
        store.applyRemote(
            listOf(
                InMemoryLatticeStore.Change(InMemoryLatticeStore.Row.EdgeRow(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0)), false),
                InMemoryLatticeStore.Change(InMemoryLatticeStore.Row.EngramRow(engram(1)), false),
                InMemoryLatticeStore.Change(InMemoryLatticeStore.Row.PeriodRow(PeriodRecord(10, "chat", "甲", eraId = 5)), false),
            ),
        )
        assertTrue(changes.isEmpty())
        assertEquals(1, store.load().edges.size)

        store.repair()

        val loaded = store.load()
        assertTrue(loaded.edges.isEmpty())
        assertEquals(null, loaded.periods.single().eraId)
        assertEquals(2, changes.size)
    }

    @Test
    fun `period name keys fold width, case, spacing and quotes`() {
        assertEquals(periodNameKey("海边的夏天"), periodNameKey(" 「海边的 夏天」 "))
        assertEquals(periodNameKey("summer"), periodNameKey("ＳＵＭＭＥＲ"))
        assertEquals("", periodNameKey(" ​ "))
    }
}
