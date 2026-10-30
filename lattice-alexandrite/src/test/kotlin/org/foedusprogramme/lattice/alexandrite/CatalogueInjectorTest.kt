@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.awaitCancellation
import org.foedusprogramme.lattice.core.Language
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.turn.PromptSections
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testDelegatedTurn
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

class CatalogueInjectorTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")
    private val base = PromptSection("persona", "You are Mia.", stable = true)
    private val asked = CopyOnWriteArrayList<Pair<String, Language?>>()

    private suspend fun PluginHarness.Running.sections(turn: TurnInfo = testTurn()): HookDecision<PromptSections> {
        val injector = getAll<Hook>().filterIsInstance<CatalogueInjector>().single()
        return injector.intercept(PromptSections(turn, listOf(base)))
    }

    private fun HookDecision<PromptSections>.added(): PromptSection {
        val sections = assertIs<HookDecision.Replace<PromptSections>>(this).payload.sections
        assertEquals(base, sections.first(), "the host's sections stay first")
        return sections.drop(1).single()
    }

    private fun catalogue(text: String?) {
        main.catalogues = { chat, language -> asked += chat to language; text }
    }

    @Test
    fun `a turn's system prompt ends with the chat's catalogue as a stable section`() {
        catalogue("## Period catalogue\n- \"Kyoto\" 2026-07-22")

        latticeHarness(config(SCENES_ON), opener).execute {
            val section = sections().added()

            assertEquals("lattice.periods", section.id)
            assertEquals("## Period catalogue\n- \"Kyoto\" 2026-07-22", section.text)
            assertTrue(section.stable)
        }

        assertEquals(listOf("test:main:chat" to Language.EN), asked.toList())
    }

    @Test
    fun `every top-level turn of the chat gets it, a delegated one does not`() {
        catalogue("catalogue")

        latticeHarness(config(SCENES_ON), opener).execute {
            assertEquals("catalogue", sections(testTurn(TurnKind.HEARTBEAT)).added().text)
            assertEquals(HookDecision.Continue, sections(testDelegatedTurn()))
        }

        assertEquals(1, asked.size)
    }

    @Test
    fun `a chat without a catalogue gets no section`() {
        catalogue(null)

        latticeHarness(config(SCENES_ON), opener).execute {
            assertEquals(HookDecision.Continue, sections())
        }
    }

    @Test
    fun `a failing or slow read keeps the last catalogue, and one that finds none drops it`() {
        val reads = listOf<suspend () -> String?>(
            { "first" },
            { throw IllegalStateException("broken") },
            { awaitCancellation() },
            { null },
            { throw IllegalStateException("broken") },
        )
        var next = 0
        main.catalogues = { _, _ -> reads[next++]() }

        latticeHarness(config(SCENES_ON.dropLast(1) + """, "recall": {"timeoutMillis": 200}}"""), opener).execute {
            assertEquals("first", sections().added().text)
            assertEquals("first", sections().added().text)
            val took = measureTime { assertEquals("first", sections().added().text) }
            assertTrue(took.inWholeMilliseconds < 2000, "took $took")
            assertEquals(HookDecision.Continue, sections())
            assertEquals(HookDecision.Continue, sections())
        }
    }

    @Test
    fun `without scenes or with the catalogue off nothing is read`() {
        catalogue("catalogue")

        latticeHarness(opener = opener).execute { assertEquals(HookDecision.Continue, sections()) }
        latticeHarness(config(SCENES_ON.dropLast(1) + """, "recall": {"catalogue": false}}"""), opener).execute {
            assertEquals(HookDecision.Continue, sections())
        }

        assertTrue(asked.isEmpty())
    }
}
