package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.lattice.GraphBusyException
import org.foedusprogramme.lattice.Recall
import org.foedusprogramme.lattice.core.Reinforce
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryToolsTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")

    private suspend fun PluginHarness.Running.call(
        tool: String,
        vararg arguments: Pair<String, Any>,
        context: ToolContext = testToolContext(operatorTurn()),
    ): ToolResult {
        val json = JsonObject(
            arguments.associate { (name, value) ->
                name to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())
            },
        )
        return getAll<Tool>().single { it.definition.name == tool }.execute(json, context)
    }

    private val ToolResult.text: String get() = content.joinToString("") { (it as TextPart).text }

    @Test
    fun `the tools declare their risks`() {
        lateinit var risks: Map<String, ToolRisk>

        latticeHarness(opener = opener).execute {
            risks = getAll<Tool>().associate { it.definition.name to it.definition.risk }
        }

        assertEquals(
            mapOf(
                "lattice.search" to ToolRisk.READ_ONLY,
                "lattice.save" to ToolRisk.AGENT_STATE,
                "lattice.timeline" to ToolRisk.READ_ONLY,
            ),
            risks,
        )
    }

    @Test
    fun `a search is an explicit recall that reinforces what it finds`() {
        main.recalls = { _, _ -> Recall("<memories>tea</memories>", listOf(hit(1, "tea")), tainted = false) }
        val context = testToolContext(operatorTurn())

        latticeHarness(opener = opener).execute {
            val result = call("lattice.search", "query" to "drinks", "limit" to 3, context = context)

            assertEquals("<memories>tea</memories>", result.text)
        }

        val (cue, options) = main.cues.single()
        assertEquals("drinks", cue)
        assertEquals(listOf(3, true, Reinforce.FULL), listOf(options.limit, options.explicit, options.reinforce))
        assertTrue(context.untrustedReasons.isEmpty())
    }

    @Test
    fun `a search that recalls tainted memories reports untrusted content`() {
        main.recalls = { _, _ -> Recall("<memories>x</memories>", listOf(hit(1, "x")), tainted = true) }
        val context = testToolContext(operatorTurn())

        latticeHarness(opener = opener).execute { call("lattice.search", "query" to "x", context = context) }

        assertTrue(context.tainted)
        assertEquals(1, context.untrustedReasons.size)
    }

    @Test
    fun `a search says when nothing matches, when the graph is busy and when the arguments are wrong`() {
        latticeHarness(opener = opener).execute {
            assertEquals("Nothing in memory matches the query.", call("lattice.search", "query" to "x").text)
            main.recalls = { _, _ -> throw GraphBusyException("busy") }
            assertTrue(call("lattice.search", "query" to "x").isError)
            assertTrue(call("lattice.search", "query" to " ").isError)
            assertTrue(call("lattice.search", "query" to "x", "limit" to 99).isError)
        }
    }

    @Test
    fun `a save remembers the content, tainted when the turn is`() {
        val clean = testToolContext(operatorTurn())
        val tainted = testToolContext(operatorTurn()).apply { this.tainted = true }

        latticeHarness(opener = opener).execute {
            val first = call("lattice.save", "content" to " Alice moved to Berlin. ", context = clean)
            val second = call("lattice.save", "content" to "x", "importance" to 0.9, context = tainted)

            assertEquals(listOf("Saved memory #1.", "Saved memory #2."), listOf(first.text, second.text))
            assertTrue(call("lattice.save", "content" to "").isError)
            assertTrue(call("lattice.save", "content" to "x", "importance" to 2).isError)
        }

        val (first, second) = main.remembered.toList()
        assertEquals("Alice moved to Berlin.", first.first)
        assertFalse(first.second.tainted)
        assertEquals(0.5, first.second.salience)
        assertTrue(second.second.tainted)
        assertEquals(0.9, second.second.salience)
    }
}
