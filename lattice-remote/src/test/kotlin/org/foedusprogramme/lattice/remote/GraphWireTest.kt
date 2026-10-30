package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.foedusprogramme.lattice.core.Edge
import org.foedusprogramme.lattice.core.EdgeRecord
import org.foedusprogramme.lattice.core.InMemoryLatticeStore
import org.foedusprogramme.lattice.core.InMemoryLatticeStore.Change
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.SLOT_1023_ID
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.T1
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.assertEngram
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.fullEngram
import org.foedusprogramme.lattice.core.PeriodAliasRecord
import org.foedusprogramme.lattice.core.ThreadLinkRecord
import org.foedusprogramme.lattice.core.ThreadRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraphWireTest {
    @Test
    fun `an engram goes out with every field and a little-endian base64 embedding`() {
        val engram = fullEngram(SLOT_1023_ID)
        val op = GraphWire.encode(Change(InMemoryLatticeStore.Row.EngramRow(engram), removed = false))
        val wire = op["engram"]!!.jsonObject
        assertEquals("putEngram", (op["op"] as JsonPrimitive).content)
        assertEquals(SLOT_1023_ID.toString(), wire["id"].toString())
        assertEquals("AAAAPwAAAL/zBDU/AQAAAA==", (wire["embedding"] as JsonPrimitive).content)
        assertEquals("2026-07-23T08:00:00.123456789Z", (wire["saidTo"] as JsonPrimitive).content)
        assertEquals("true", wire["tainted"].toString())
        val back = (GraphWire.decode(JSON.parseToJsonElement(op.toString()).jsonObject) as WireOp.Mirror).change.row
        assertEngram(engram, (back as InMemoryLatticeStore.Row.EngramRow).engram)
    }

    @Test
    fun `an edge carries its direction as a and b`() {
        val edge = EdgeRecord(9, 2, Edge.Type.EPISODE_OF, 0.25, T1)
        val op = GraphWire.encode(Change(InMemoryLatticeStore.Row.EdgeRow(edge), removed = false))
        assertEquals("9", op["a"].toString())
        assertEquals("2", op["b"].toString())
        assertEquals(edge, ((GraphWire.decode(op) as WireOp.Mirror).change.row as InMemoryLatticeStore.Row.EdgeRow).edge)
    }

    @Test
    fun `a record key escapes its separator and a delete finds the row by it`() {
        val alias = PeriodAliasRecord("chat/with%slash", "夏天/秋天", 5)
        val put = GraphWire.encode(Change(InMemoryLatticeStore.Row.PeriodAliasRow(alias), removed = false))
        assertEquals("chat%2Fwith%25slash/夏天%2F秋天", (put["key"] as JsonPrimitive).content)
        val delete = GraphWire.encode(Change(InMemoryLatticeStore.Row.PeriodAliasRow(alias), removed = true))
        assertEquals(setOf("op", "type", "key"), delete.keys)
        val row = (GraphWire.decode(delete) as WireOp.Mirror).change.row
        assertEquals(InMemoryLatticeStore.Row.PeriodAliasRow(alias).key, row.key)

        val link = ThreadLinkRecord(1, 2, ThreadRole.RESOLVE)
        val linkDelete = GraphWire.encode(Change(InMemoryLatticeStore.Row.ThreadLinkRow(link), removed = true))
        assertEquals(link, ((GraphWire.decode(linkDelete) as WireOp.Mirror).change.row as InMemoryLatticeStore.Row.ThreadLinkRow).record)
    }

    @Test
    fun `an unknown op or record type is skipped, not fatal`() {
        assertIs<WireOp.Unknown>(GraphWire.decode(buildJsonObject { put("op", "putHologram") }))
        assertIs<WireOp.Unknown>(
            GraphWire.decode(
                buildJsonObject {
                    put("op", "putRecord")
                    put("type", "fromTheFuture")
                    put("key", "1")
                    put("data", JsonObject(emptyMap()))
                },
            ),
        )
        assertIs<WireOp.Unknown>(GraphWire.decode(buildJsonObject { put("op", "putEngram") }))
    }

    @Test
    fun `every row of a full store survives the wire`() {
        val source = InMemoryLatticeStore()
        val contract = object : org.foedusprogramme.lattice.core.LatticeStoreContract() {
            override fun newStore() = source
            override fun reopen(store: org.foedusprogramme.lattice.core.LatticeStore) = store
        }
        contract.`every field of every record survives a reopen`()
        val copy = InMemoryLatticeStore()
        copy.applyRemote(source.rows().map { (GraphWire.decode(GraphWire.encode(Change(it, false))) as WireOp.Mirror).change })
        val a = source.load()
        val b = copy.load()
        assertEquals(a.copy(engrams = emptyList()), b.copy(engrams = emptyList()))
        a.engrams.forEach { e -> assertEngram(e, b.engrams.single { it.id == e.id }) }
        assertTrue(a.periods.isNotEmpty())
    }
}
