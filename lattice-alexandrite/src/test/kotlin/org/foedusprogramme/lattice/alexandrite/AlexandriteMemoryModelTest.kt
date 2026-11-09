package org.foedusprogramme.lattice.alexandrite

import org.foedusprogramme.lattice.core.MemoryModelShutdownException
import org.foedusprogramme.lattice.core.MemoryModelUnavailableException
import org.foedusprogramme.lattice.core.MemoryTask
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AlexandriteMemoryModelTest {
    private val endpoint = ScriptedModel(
        models = listOf(ScriptedModel.modelInfo(), ScriptedModel.modelInfo("judge-model")),
    )
    private val judge = ModelRef(endpoint.id, "judge-model")

    private fun model(timeout: Duration = 5.seconds): AlexandriteMemoryModel = AlexandriteMemoryModel(
        { if (it == endpoint.id) endpoint else null },
        { task -> if (task == MemoryTask.JUDGE) judge else endpoint.ref },
        maxOutputTokens = 4000,
        timeout = timeout,
    )

    @Test
    fun `a completion is one request outside any turn whose text it returns`() {
        endpoint.reply { text("""{"memories": """, "[]}") }

        val answer = model().complete(MemoryTask.EXTRACT, "Extract memories.", "Alice: I like tea")

        assertEquals("""{"memories": []}""", answer)
        val request = endpoint.requests.single()
        assertEquals(endpoint.ref, request.model)
        assertEquals(listOf("lattice-extract" to "Extract memories."), request.instructions.map { it.id to it.text })
        val user = request.history.single() as UserEntry
        assertEquals("Alice: I like tea", (user.parts.single() as TextPart).text)
        assertEquals(4000, request.options.maxOutputTokens)
        assertEquals(ReasoningEffort.HIGH, request.options.reasoning, "extraction reasons hard")
        assertEquals(0, request.ids.round)
        assertEquals(emptyList(), request.tools)

        endpoint.reply { text("A quiet morning.") }
        model().complete(MemoryTask.NARRATE, "Narrate the scene.", "Alice: good morning")
        assertEquals(ReasoningEffort.NONE, endpoint.requests.last().options.reasoning, "the rest ask for the least")
    }

    @Test
    fun `each task runs on its own model`() {
        endpoint.reply { text("same") }

        assertEquals("same", model().complete(MemoryTask.JUDGE, "Judge.", "a | b"))
        assertEquals(judge, endpoint.requests.single().model)
    }

    @Test
    fun `an endpoint that is down or missing makes the model unavailable`() {
        endpoint.fail(ModelErrorKind.CONNECTION)
        val unknown = AlexandriteMemoryModel({ null }, { ModelRef(EndpointId("gone"), "m") }, 4000, 5.seconds)

        assertFailsWith<MemoryModelUnavailableException> { model().complete(MemoryTask.EXTRACT, "s", "u") }
        val missing = assertFailsWith<MemoryModelUnavailableException> {
            unknown.complete(MemoryTask.EXTRACT, "s", "u")
        }
        assertContains(missing.message!!, "No model provider contributes the endpoint 'gone'")
    }

    @Test
    fun `a model that does not answer in time is unavailable`() {
        endpoint.reply { hang() }

        assertFailsWith<MemoryModelUnavailableException> {
            model(timeout = 200.milliseconds).complete(MemoryTask.NARRATE, "s", "u")
        }
    }

    @Test
    fun `a refusal and a bad request count as bad answers`() {
        endpoint.reply { text("no").finish(FinishKind.REFUSAL) }
        endpoint.fail(ModelErrorKind.INVALID_REQUEST)

        assertFailsWith<IllegalStateException> { model().complete(MemoryTask.ABSTRACT, "s", "u") }
        assertFailsWith<ModelException> { model().complete(MemoryTask.ABSTRACT, "s", "u") }
    }

    @Test
    fun `a model shut down takes no more calls`() {
        val model = model()
        model.shutDown()

        assertFailsWith<MemoryModelShutdownException> { model.complete(MemoryTask.EXTRACT, "s", "u") }
        assertEquals(0, endpoint.requests.size)
    }
}
