package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryModelInterruptedException
import org.foedusprogramme.lattice.core.MemoryModelShutdownException
import org.foedusprogramme.lattice.core.MemoryModelUnavailableException
import org.foedusprogramme.lattice.core.MemoryTask
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * A [MemoryModel] over the host's model endpoints: each completion is one request outside any turn, whose response it
 * waits for on the calling thread.
 */
internal class AlexandriteMemoryModel(
    private val endpoint: (EndpointId) -> ModelEndpoint?,
    private val modelOf: (MemoryTask) -> ModelRef,
    private val maxOutputTokens: Int,
    private val timeout: Duration,
) : MemoryModel {
    private val infos = ConcurrentHashMap<ModelRef, ModelInfo>()
    private val calls = AtomicLong()

    @Volatile
    private var shutDown = false

    /** Refuses the calls from now on. */
    fun shutDown() {
        shutDown = true
    }

    override fun complete(task: MemoryTask, system: String, user: String): String {
        val model = modelOf(task)
        if (shutDown) throw MemoryModelShutdownException("Alexandrite is stopping, so model $model takes no calls.")
        return try {
            runBlocking { withTimeout(timeout) { ask(model, task, system, user) } }
        } catch (e: InterruptedException) {
            throw MemoryModelInterruptedException("The call of model $model for task ${task.wire} was interrupted.", e)
        } catch (e: TimeoutCancellationException) {
            throw MemoryModelUnavailableException("Model $model did not answer task ${task.wire} within $timeout.", e)
        } catch (e: ModelException) {
            if (e.error.kind !in UNAVAILABLE) throw e
            throw MemoryModelUnavailableException("Model $model cannot answer task ${task.wire} now: ${e.message}", e)
        }
    }

    private suspend fun ask(model: ModelRef, task: MemoryTask, system: String, user: String): String {
        val endpoint = endpoint(model.endpoint) ?: throw MemoryModelUnavailableException(
            "No model provider contributes the endpoint '${model.endpoint}' of model $model.",
        )
        val info = info(endpoint, model)
        val options = ModelOptions.builder()
            .maxOutputTokens(minOf(maxOutputTokens, info?.maxOutputTokens ?: maxOutputTokens))
            .reasoning(info?.let { EFFORTS.firstOrNull { effort -> effort in it.reasoningEfforts } })
            .build()
        val history = listOf(UserEntry(null, listOf(TextPart(user)), UserOrigin.SystemWritten(SOURCE)))
        val ids = RequestIds(CONVERSATION, TurnId("$SOURCE-${task.wire}-${calls.incrementAndGet()}"), 0)
        val request = ModelRequest.builder(model, history, 0, ids)
            .instructions(listOf(PromptSection("$SOURCE-${task.wire}", system, stable = true)))
            .options(options)
            .build()
        val response = endpoint.stream(request).filterIsInstance<ModelEvent.Completed>().lastOrNull()
            ?: throw MemoryModelUnavailableException("The response of model $model ended without its last event.")
        check(response.finish.kind != FinishKind.REFUSAL) { "Model $model refused task ${task.wire}." }
        return response.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
    }

    /** What [endpoint] lists about [model], null when it lists nothing about it. */
    private suspend fun info(endpoint: ModelEndpoint, model: ModelRef): ModelInfo? {
        infos[model]?.let { return it }
        val listed = try {
            endpoint.models()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Cannot list the models of endpoint '{}': {}", model.endpoint, e.toString())
            return null
        }
        return listed.firstOrNull { it.id == model.model }?.also { infos[model] = it }
    }

    private companion object {
        const val SOURCE = "lattice"

        val CONVERSATION = ConversationId(SOURCE)

        /** The efforts a request asks for, least first. */
        val EFFORTS = listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW)

        val UNAVAILABLE = setOf(
            ModelErrorKind.CONNECTION,
            ModelErrorKind.TIMEOUT,
            ModelErrorKind.SERVER_ERROR,
            ModelErrorKind.OVERLOADED,
            ModelErrorKind.RATE_LIMITED,
            ModelErrorKind.QUOTA_EXHAUSTED,
            ModelErrorKind.MODEL_NOT_FOUND,
            ModelErrorKind.AUTHENTICATION,
        )
    }
}

private val logger = LoggerFactory.getLogger(AlexandriteMemoryModel::class.java)
