package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import org.foedusprogramme.lattice.GraphBusyException
import org.foedusprogramme.lattice.RecallOptions
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.Reinforce
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `/memory`: the memory of the chat's agent, searched and put to sleep by operators. */
@Singleton
@Contribute(CommandHandler::class)
internal class MemoryCommand(
    config: LatticeConfig,
    private val graphs: LatticeGraphs,
    private val timer: SleepTimer,
    private val texts: Texts,
) : CommandHandler {
    private val language = Language(config.recall.language)

    override val commands: List<CommandSpec> =
        listOf(CommandSpec.builder(NAME, description(NAME, null)).runsAlongside(true).build())

    override fun description(command: String, language: LanguageTag?): String =
        texts.text(TextKeys.DESCRIPTION, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val turn = context.turn
        val replies = Replies(texts, turn.language)
        val words = invocation.arguments.trim()
        val action = words.substringBefore(' ').lowercase()
        val rest = words.substringAfter(' ', "").trim()
        if (action in OPERATOR_ACTIONS && !turn.actorIsOperator) {
            context.reply(replies.text(TextKeys.OPERATORS_ONLY, "action" to action))
            return
        }
        val agent = turn.agent
        try {
            when {
                (action.isEmpty() || action == "status") && rest.isEmpty() -> context.reply(status(replies, agent))
                action == "search" && rest.isNotEmpty() ->
                    context.reply(search(replies, agent, rest, turn.chatKey), forModel = false)
                action == "sleep" && rest.isEmpty() -> {
                    val key = if (timer.start(agent)) TextKeys.SLEEP_STARTED else TextKeys.SLEEP_RUNNING
                    context.reply(replies.text(key, "agent" to agent))
                }
                else -> context.reply(replies.text(TextKeys.USAGE))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GraphBusyException) {
            context.reply(replies.text(TextKeys.BUSY))
        } catch (e: Exception) {
            logger.warn("/memory {} found the memory of agent '{}' unavailable", action, agent, e)
            context.reply(replies.text(TextKeys.UNAVAILABLE, "agent" to agent))
        }
    }

    private suspend fun status(replies: Replies, agent: AgentId): String {
        val stats = graphs.graph(agent).stats()
        val lines = mutableListOf(
            replies.text(
                TextKeys.STATUS,
                "agent" to agent,
                "engrams" to stats.engrams,
                "edges" to stats.edges,
                "entities" to stats.entities,
                "scenes" to stats.scenes,
                "periods" to stats.periods,
            ),
            stats.embeddingModel?.let {
                replies.text(TextKeys.STATUS_EMBEDDING, "model" to it, "dimension" to stats.embeddingDimension)
            } ?: replies.text(TextKeys.STATUS_NO_EMBEDDING),
        )
        val slept = timer.lastSleep(agent)
        lines += if (slept == null) {
            replies.text(TextKeys.STATUS_NEVER_SLEPT)
        } else {
            replies.text(
                TextKeys.STATUS_LAST_SLEEP,
                "time" to replies.time(slept.at, timer.zone),
                "before" to slept.report.engramsBefore,
                "after" to slept.report.engramsAfter,
            )
        }
        val next = timer.nextSleep()
        lines += if (next == null) {
            replies.text(TextKeys.STATUS_SLEEP_OFF)
        } else {
            replies.text(TextKeys.STATUS_NEXT_SLEEP, "time" to replies.time(next, timer.zone))
        }
        return lines.joinToString("\n")
    }

    private suspend fun search(replies: Replies, agent: AgentId, query: String, chatKey: String): String {
        val options = RecallOptions(reinforce = Reinforce.NONE, explicit = true, language = language, chatKey = chatKey)
        val recall = graphs.graph(agent).recall(query, options)
        if (recall.hits.isEmpty()) return replies.text(TextKeys.SEARCH_EMPTY)
        val hits = recall.hits.joinToString("\n") { hit ->
            val date = hit.eventDate?.let { " ($it)" } ?: ""
            "#${hit.id}$date ${hit.content}"
        }
        return if (recall.tainted) replies.text(TextKeys.SEARCH_TAINTED) + "\n" + hits else hits
    }

    private companion object {
        const val NAME = "memory"
        val OPERATOR_ACTIONS = setOf("search", "sleep")
    }
}

/** The plugin's texts in [language]. */
private class Replies(private val texts: Texts, private val language: LanguageTag?) {
    fun text(key: String, vararg args: Pair<String, Any?>): String = texts.text(key, language, *args)

    fun time(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(DATE_TIME)
}

/** The keys of the plugin's texts for people. */
internal object TextKeys {
    const val DESCRIPTION: String = "command.memory.description"
    const val USAGE: String = "usage.memory"
    const val OPERATORS_ONLY: String = "memory.operators_only"
    const val BUSY: String = "memory.busy"
    const val UNAVAILABLE: String = "memory.unavailable"
    const val STATUS: String = "memory.status"
    const val STATUS_EMBEDDING: String = "memory.status_embedding"
    const val STATUS_NO_EMBEDDING: String = "memory.status_no_embedding"
    const val STATUS_LAST_SLEEP: String = "memory.status_last_sleep"
    const val STATUS_NEVER_SLEPT: String = "memory.status_never_slept"
    const val STATUS_NEXT_SLEEP: String = "memory.status_next_sleep"
    const val STATUS_SLEEP_OFF: String = "memory.status_sleep_off"
    const val SEARCH_EMPTY: String = "memory.search_empty"
    const val SEARCH_TAINTED: String = "memory.search_tainted"
    const val SLEEP_STARTED: String = "memory.sleep_started"
    const val SLEEP_RUNNING: String = "memory.sleep_running"
}

private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private val logger = LoggerFactory.getLogger(MemoryCommand::class.java)
