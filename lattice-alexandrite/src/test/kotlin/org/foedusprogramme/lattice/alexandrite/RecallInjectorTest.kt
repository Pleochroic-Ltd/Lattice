@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.awaitCancellation
import org.foedusprogramme.lattice.GraphBusyException
import org.foedusprogramme.lattice.Recall
import org.foedusprogramme.lattice.core.Language
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextFallback
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.turn.TurnContext
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testDelegatedTurn
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

class RecallInjectorTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")

    private suspend fun PluginHarness.Running.inject(
        text: String,
        turn: TurnInfo = operatorTurn(),
    ): HookDecision<TurnContext> {
        val injector = getAll<Hook>().filterIsInstance<RecallInjector>().single()
        return injector.intercept(TurnContext(turn, text, emptyList()))
    }

    private fun HookDecision<TurnContext>.item(): TurnContextItem =
        assertIs<HookDecision.Replace<TurnContext>>(this).payload.items.single()

    @Test
    fun `a message turn gets what its input recalls, trusted when no memory is tainted`() {
        main.recalls = { _, _ -> Recall("<memories>tea</memories>", listOf(hit(1, "tea")), tainted = false) }

        latticeHarness(opener = opener).execute {
            val item = inject("  what do I drink?  ").item()

            assertEquals(listOf("lattice", "<memories>tea</memories>"), listOf(item.source, item.text))
            assertEquals(Trust.TRUSTED, item.trust)
            assertEquals(TurnContextFallback.DROP, item.fallback)
        }

        val (cue, options) = main.cues.single()
        assertEquals("what do I drink?", cue)
        assertEquals(
            listOf(6, Language.EN, "test:main:chat", false),
            listOf(options.limit, options.language, options.chatKey, options.explicit),
        )
    }

    @Test
    fun `memories of a tainted turn come as untrusted context`() {
        main.recalls = { _, _ -> Recall("<memories>x</memories>", listOf(hit(1, "x")), tainted = true) }

        latticeHarness(opener = opener).execute {
            assertEquals(Trust.UNTRUSTED, inject("hello").item().trust)
        }
    }

    @Test
    fun `the config can bake the block where an endpoint cannot keep it for one turn`() {
        main.recalls = { _, _ -> Recall("<memories>x</memories>", listOf(hit(1, "x")), tainted = false) }

        latticeHarness(config("""{"recall": {"fallback": "bake", "limit": 3}}"""), opener).execute {
            assertEquals(TurnContextFallback.BAKE, inject("hello").item().fallback)
        }

        assertEquals(3, main.cues.single().second.limit)
    }

    @Test
    fun `a busy graph, a failing one, a slow one and an empty recall add nothing`() {
        val recalls = listOf<suspend () -> Recall>(
            { throw GraphBusyException("busy") },
            { throw IllegalStateException("broken") },
            { awaitCancellation() },
            { Recall("", emptyList(), tainted = false) },
        )
        var next = 0
        main.recalls = { _, _ -> recalls[next++]() }

        latticeHarness(config("""{"recall": {"timeoutMillis": 200}}"""), opener).execute {
            repeat(recalls.size) {
                val took = measureTime { assertEquals(HookDecision.Continue, inject("hello")) }
                assertTrue(took.inWholeMilliseconds < 2000, "took $took")
            }
        }

        assertEquals(4, main.cues.size)
    }

    @Test
    fun `a graph that cannot open adds nothing and is not opened again at once`() {
        opener.failure = IllegalStateException("no embedder")

        latticeHarness(opener = opener).execute {
            assertEquals(HookDecision.Continue, inject("hello"))
            assertEquals(HookDecision.Continue, inject("hello again"))
        }

        assertTrue(main.cues.isEmpty())
        assertEquals(1, opener.attempts.get())
    }

    @Test
    fun `only top-level message turns with input recall, each in its agent's graph`() {
        val coder = opener.graph("coder")
        coder.recalls = { _, _ -> Recall("<memories>code</memories>", listOf(hit(1, "code")), tainted = false) }

        latticeHarness(opener = opener).execute {
            assertEquals(HookDecision.Continue, inject("beat", testTurn(TurnKind.HEARTBEAT)))
            assertEquals(HookDecision.Continue, inject("task", testDelegatedTurn()))
            assertEquals(HookDecision.Continue, inject("   "))
            assertEquals("<memories>code</memories>", inject("hi", operatorTurn { agent(AgentId("coder")) }).item().text)
        }

        assertTrue(main.cues.isEmpty())
        assertEquals(listOf("hi"), coder.cues.map { it.first })
    }

    @Test
    fun `recall can be switched off`() {
        latticeHarness(config("""{"recall": {"enabled": false}}"""), opener).execute {
            assertEquals(HookDecision.Continue, inject("hello"))
        }

        assertTrue(main.cues.isEmpty())
    }
}
