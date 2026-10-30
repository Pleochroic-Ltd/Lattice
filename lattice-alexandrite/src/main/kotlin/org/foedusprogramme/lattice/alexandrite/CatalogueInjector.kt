package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.turn.PromptSections
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import java.util.concurrent.ConcurrentHashMap

/**
 * Adds the chat's period catalogue to the system prompt of each top-level turn, as one stable section: its text changes
 * only when the memory publishes a new catalogue, so the prompt cache holds between those. The catalogue names a period
 * only when every scene it was named from is trusted, else gives its dates, so it carries no untrusted content.
 */
@Singleton
@Contribute(Hook::class)
internal class CatalogueInjector(
    config: LatticeConfig,
    private val graphs: LatticeGraphs,
    private val access: MemoryAccess,
) : InterceptorHook<PromptSections> {
    private val enabled = config.scenes.enabled && config.recall.catalogue
    private val language = Language(config.recall.language)
    private val readTimeout = config.recall.timeout

    /** The text last read per agent and chat, which a turn whose read fails gets again so its prompt stays the same. */
    private val last = ConcurrentHashMap<Pair<AgentId, String>, String>()

    /** The agents whose last read failed, each logged once until one works again. */
    private val failing = ConcurrentHashMap.newKeySet<AgentId>()

    override val point: InterceptorPoint<PromptSections> = TurnPoints.PROMPT_SECTIONS

    override suspend fun intercept(payload: PromptSections): HookDecision<PromptSections> {
        val turn = payload.turn
        if (!enabled || turn.lineage != null || !access.admits(turn) || payload.sections.any { it.id == SECTION_ID }) {
            return HookDecision.Continue
        }
        val text = catalogue(turn) ?: return HookDecision.Continue
        return HookDecision.Replace(payload.withSections(payload.sections + PromptSection(SECTION_ID, text, stable = true)))
    }

    /** The catalogue of [turn]'s chat, the last one read when the read fails, null when there is none. */
    private suspend fun catalogue(turn: TurnInfo): String? {
        val key = turn.agent to turn.chatKey
        val read = try {
            withTimeoutOrNull(readTimeout) { Read(graphs.graph(turn.agent).catalogue(turn.chatKey, language)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(turn, e.toString())
            return last[key]
        }
        if (read == null) {
            failed(turn, "it took longer than $readTimeout")
            return last[key]
        }
        if (failing.remove(turn.agent)) logger.info("The period catalogue reads again for agent '{}'", turn.agent)
        val text = read.text?.takeIf { it.isNotBlank() }
        if (text == null) last.remove(key) else last[key] = text
        return text
    }

    private fun failed(turn: TurnInfo, why: String) {
        if (failing.add(turn.agent)) {
            logger.warn("Turn {} of {} keeps its last period catalogue, as reading it failed: {}", turn.id, turn.key, why)
        } else {
            logger.debug("Turn {} of {} keeps its last period catalogue: {}", turn.id, turn.key, why)
        }
    }

    private class Read(val text: String?)

    private companion object {
        const val SECTION_ID = "lattice.periods"
    }
}

private val logger = LoggerFactory.getLogger(CatalogueInjector::class.java)
