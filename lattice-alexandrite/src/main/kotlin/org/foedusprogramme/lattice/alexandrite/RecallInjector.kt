package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.lattice.GraphBusyException
import org.foedusprogramme.lattice.Recall
import org.foedusprogramme.lattice.RecallOptions
import org.foedusprogramme.lattice.core.Language
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextFallback
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.turn.TurnContext
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import java.util.concurrent.ConcurrentHashMap

/** Recalls what the input of each message turn brings back and adds it to the turn's context. */
@Singleton
@Contribute(Hook::class)
internal class RecallInjector(
    config: LatticeConfig,
    private val graphs: LatticeGraphs,
    private val access: MemoryAccess,
) : InterceptorHook<TurnContext> {
    private val recall = config.recall
    private val language = Language(recall.language)
    private val fallback = if (recall.fallback == BAKE) TurnContextFallback.BAKE else TurnContextFallback.DROP

    /** The agents whose last recall failed, each logged once until one works again. */
    private val failing = ConcurrentHashMap.newKeySet<AgentId>()

    override val point: InterceptorPoint<TurnContext> = TurnPoints.CONTEXT_INJECT

    override suspend fun intercept(payload: TurnContext): HookDecision<TurnContext> {
        val turn = payload.turn
        val cue = payload.text.trim()
        if (!recall.enabled || !turn.remembers() || !access.admits(turn) || cue.isEmpty()) return HookDecision.Continue
        val found = recall(turn, cue) ?: return HookDecision.Continue
        if (found.text.isBlank()) return HookDecision.Continue
        val trust = if (found.tainted) Trust.UNTRUSTED else Trust.TRUSTED
        return HookDecision.Replace(payload + TurnContextItem(SOURCE, found.text, trust, fallback))
    }

    /** What [cue] recalls in [turn]'s graph within the recall timeout, null when nothing comes in time. */
    private suspend fun recall(turn: TurnInfo, cue: String): Recall? {
        val agent = turn.agent
        val options = RecallOptions(limit = recall.limit, language = language, chatKey = turn.chatKey)
        val found = try {
            withTimeoutOrNull(recall.timeout) { graphs.graph(agent).recall(cue, options) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GraphBusyException) {
            failed(turn, "its graph is busy")
            return null
        } catch (e: Exception) {
            failed(turn, e.toString())
            return null
        }
        if (found == null) {
            failed(turn, "it took longer than ${recall.timeout}")
            return null
        }
        if (failing.remove(agent)) logger.info("Recall works again for agent '{}'", agent)
        return found
    }

    private fun failed(turn: TurnInfo, why: String) {
        if (failing.add(turn.agent)) {
            logger.warn("Turn {} of {} goes on without memories, as recall failed: {}", turn.id, turn.key, why)
        } else {
            logger.debug("Turn {} of {} goes on without memories: {}", turn.id, turn.key, why)
        }
    }

    private companion object {
        const val SOURCE = "lattice"
    }
}

private val logger = LoggerFactory.getLogger(RecallInjector::class.java)
