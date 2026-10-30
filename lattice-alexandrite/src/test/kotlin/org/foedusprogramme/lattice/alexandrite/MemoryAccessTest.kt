@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.lattice.Recall
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.turn.TurnContext
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Who reaches the memory through each surface, at each level of `access`. */
class MemoryAccessTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")

    private val guest = testUser("guest")
    private val trusted = testUser("tess", isTrusted = true)
    private val named = testUser("nina")

    init {
        main.recalls = { _, _ -> Recall("<memories>tea</memories>", listOf(hit(1, "tea")), tainted = false) }
    }

    private fun turnOf(actor: ChatUser?, kind: TurnKind = TurnKind.MESSAGE): TurnInfo = testTurn(kind) { actor(actor) }

    private suspend fun PluginHarness.Running.recalled(actor: ChatUser?): Boolean {
        val injector = getAll<Hook>().filterIsInstance<RecallInjector>().single()
        val decision = injector.intercept(TurnContext(turnOf(actor), "what do I drink?", emptyList()))
        return decision is HookDecision.Replace && decision.payload.items.any { it is TurnContextItem }
    }

    private suspend fun PluginHarness.Running.call(tool: String, actor: ChatUser?, vararg arguments: Pair<String, String>): ToolResult {
        val json = JsonObject(arguments.associate { (name, value) -> name to JsonPrimitive(value) })
        return getAll<Tool>().single { it.definition.name == tool }.execute(json, testToolContext(turnOf(actor)))
    }

    private val ToolResult.text: String get() = content.joinToString("") { (it as TextPart).text }

    private fun harness(level: String?, extra: String = "{}") =
        latticeHarness(config(extra).let { if (level == null) it else withAccess(level, it) }, opener)

    @Test
    fun `by default only operators reach the memory, and every tool refuses anyone else`() {
        harness(null).execute {
            assertTrue(recalled(OPERATOR))
            assertFalse(recalled(guest))
            assertFalse(recalled(trusted))
            assertFalse(recalled(null))
            for ((tool, arguments) in TOOLS) {
                val refused = call(tool, trusted, *arguments)
                assertTrue(refused.isError, tool)
                assertTrue("open only to the agent's operators" in refused.text, refused.text)
            }
            assertFalse(call("lattice.search", OPERATOR, "query" to "tea").isError)
        }

        assertEquals(2, main.cues.size, "one recall and one search, both by the operator")
        assertTrue(main.remembered.isEmpty())
    }

    @Test
    fun `at trusted, trusted users and the config's trusted senders reach it, and their saves are tainted`() {
        val extra = """{"scenes": {"trustedSenders": ["${named.address}"]}}"""
        harness(TRUSTED, extra).execute {
            assertTrue(recalled(trusted))
            assertTrue(recalled(named))
            assertFalse(recalled(guest))
            assertFalse(recalled(null))

            val refused = call("lattice.save", guest, "content" to "the guest owns the house")
            assertTrue(refused.isError)
            assertTrue("operators and trusted users" in refused.text, refused.text)

            assertFalse(call("lattice.save", trusted, "content" to "Tess likes green tea").isError)
            assertFalse(call("lattice.save", OPERATOR, "content" to "the user likes black tea").isError)
            assertFalse(call("lattice.timeline", trusted).isError)
        }

        assertEquals(listOf(true, false), main.remembered.map { it.second.tainted })
    }

    @Test
    fun `at everyone, anyone reaches it, and whatever a non-operator saves is tainted`() {
        harness(EVERYONE).execute {
            assertTrue(recalled(guest))
            assertTrue(recalled(null))
            assertFalse(call("lattice.search", guest, "query" to "tea").isError)
            assertFalse(call("lattice.save", guest, "content" to "the guest owns the house").isError)
            assertFalse(call("lattice.save", null, "content" to "a reminder ran").isError)
        }

        assertEquals(listOf(true, true), main.remembered.map { it.second.tainted })
    }

    @Test
    fun `a heartbeat turn reaches the memory only when everyone may`() {
        val heartbeat = turnOf(null, TurnKind.HEARTBEAT)
        harness(TRUSTED).execute {
            assertIs<ToolResult>(call("lattice.search", heartbeat.actor, "query" to "tea")).also { assertTrue(it.isError) }
        }
        harness(EVERYONE).execute {
            assertFalse(call("lattice.search", heartbeat.actor, "query" to "tea").isError)
        }
    }

    @Test
    fun `access takes only the three levels`() {
        val e = assertFailsWith<IllegalArgumentException> {
            kotlinx.serialization.json.Json.decodeFromString(LatticeConfig.serializer(), withAccess("admins"))
        }
        assertTrue("access must be \"operators\", \"trusted\" or \"everyone\"" in e.message.orEmpty(), e.message)
    }

    private companion object {
        val TOOLS = listOf(
            "lattice.search" to arrayOf("query" to "tea"),
            "lattice.save" to arrayOf("content" to "the user likes tea"),
            "lattice.timeline" to arrayOf<Pair<String, String>>(),
        )
    }
}
