package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.lattice.Recall
import org.foedusprogramme.lattice.core.Reinforce
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.TestCommandContext
import org.unitedsoftware.alexandrite.testkit.assertTextsComplete
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testCommandContext
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryCommandTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")

    private suspend fun PluginHarness.Running.memory(arguments: String, operator: Boolean = false): TestCommandContext {
        val handler = getAll<CommandHandler>().single { handler -> handler.commands.any { it.name == "memory" } }
        val invocation = testCommand("memory", arguments, issuer = testUser(isOperator = operator))
        val context = testCommandContext(invocation)
        handler.handle(invocation, context)
        return context
    }

    private suspend fun PluginHarness.Running.reply(arguments: String, operator: Boolean = false): String =
        memory(arguments, operator).replies.single().text

    @Test
    fun `status shows the size of the agent's memory and its sleeps`() {
        latticeHarness(opener = opener).execute {
            val status = reply("")

            assertEquals(status, reply("status"))
            assertContains(status, "Memory of main: 12 memories, 30 links, 2 entities, 0 scenes, 0 periods.")
            assertContains(status, "Embeddings: bge-m3, 1024 dimensions.")
            assertContains(status, "It has not slept since Alexandrite started.")
            assertContains(status, "Next sleep: ")
        }
    }

    @Test
    fun `search and sleep are for operators`() {
        latticeHarness(opener = opener).execute {
            assertEquals("Only an operator may run /memory search.", reply("search tea"))
            assertEquals("Only an operator may run /memory sleep.", reply("sleep"))
        }

        assertTrue(main.cues.isEmpty())
        assertEquals(0, main.sleeps.value)
    }

    @Test
    fun `an operator's search lists the memories without reinforcing them and keeps them from the model`() {
        main.recalls = { _, _ -> Recall("block", listOf(hit(7, "Alice drinks tea")), tainted = true) }

        latticeHarness(opener = opener).execute {
            val context = memory("search  what does Alice drink", operator = true)

            assertEquals(
                "Some of these memories come from turns that read untrusted content.\n#7 Alice drinks tea",
                context.replies.single().text,
            )
            assertEquals(listOf(false), context.repliesForModel)
        }

        val (cue, options) = main.cues.single()
        assertEquals("what does Alice drink", cue)
        assertEquals(Reinforce.NONE, options.reinforce)
    }

    @Test
    fun `an operator starts a sleep, and a second waits for the first to end`() {
        val gate = CompletableDeferred<Unit>()
        main.sleepGate = gate

        latticeHarness(opener = opener).execute {
            assertContains(reply("sleep", operator = true), "The memory of main is going to sleep.")
            assertContains(reply("sleep", operator = true), "A sleep is running already.")
            gate.complete(Unit)
            withTimeout(WAIT) { while (get<SleepTimer>().lastSleep(AgentId.MAIN) == null) delay(10) }

            assertContains(reply("status"), "from 10 memories to 8.")
        }
    }

    @Test
    fun `anything else gets the usage, and an unavailable memory says so`() {
        opener.failure = IllegalStateException("no store")

        latticeHarness(opener = opener).execute {
            assertContains(reply("forget everything"), "Send /memory or /memory status")
            assertContains(reply("status now"), "Send /memory or /memory status")
            assertEquals("The memory of main cannot be reached now. The log says why.", reply("status"))
        }
    }

    @Test
    fun `the command is described in the chat's language`() {
        latticeHarness(opener = opener).execute {
            val handler = getAll<CommandHandler>().single { handler -> handler.commands.any { it.name == "memory" } }

            assertContains(handler.description("memory", LanguageTag("zh")), "长期记忆")
        }
    }

    @Test
    fun `every language has every text`() {
        assertEquals(listOf("en", "zh"), assertTextsComplete("lattice").map { it.value }.sorted())
    }
}
