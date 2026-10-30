package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.foedusprogramme.lattice.Transcript
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.ContextCompacted
import org.unitedsoftware.alexandrite.sdk.turn.ConversationSealed
import org.unitedsoftware.alexandrite.sdk.turn.TurnCommitted
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints

/**
 * Batches the words of committed message turns per conversation and captures each batch into its agent's graph, every
 * few turns and at each compaction and seal, on one worker that takes the events in the order they happened.
 */
@Singleton
internal class TurnCapture(
    config: LatticeConfig,
    private val graphs: LatticeGraphs,
    private val taints: TurnTaints,
    private val scope: PluginScope,
) : Lifecycle {
    private val config = config.capture
    private val events = Channel<Event>(CAPACITY)
    private var worker: Job? = null

    @Volatile
    private var dropping = false

    /** Confined to the worker. */
    private val pending = LinkedHashMap<ConversationId, Batch>()

    /** The last compaction summary of each conversation, confined to the worker. */
    private val summaries = HashMap<ConversationId, String>()

    override suspend fun onOpen() {
        worker = scope.launch(CoroutineName("lattice-capture")) {
            for (event in events) handle(event)
            for (conversation in pending.keys.toList()) flush(conversation)
        }
    }

    override suspend fun onDrain() {
        events.close()
        worker?.join()
    }

    suspend fun committed(turn: TurnInfo, entries: List<TranscriptEntry>) {
        if (!config.enabled || !turn.remembers()) return
        val lines = lines(entries)
        if (lines.isEmpty()) return
        offer(Event.Committed(turn, lines, taints.tainted(turn)))
    }

    fun compacted(turn: TurnInfo, summary: String) {
        if (config.enabled && turn.lineage == null) offer(Event.Compacted(turn, summary))
    }

    fun sealed(turn: TurnInfo, sealed: ConversationId) {
        if (config.enabled && turn.lineage == null) offer(Event.Sealed(turn, sealed))
    }

    private fun offer(event: Event) {
        if (events.trySend(event).isSuccess) {
            dropping = false
            return
        }
        if (!dropping) {
            logger.warn("The capture queue is full, so turns of {} and later ones go uncaptured", event.turn.key)
        }
        dropping = true
    }

    private suspend fun handle(event: Event) {
        val conversation = event.turn.conversation
        when (event) {
            is Event.Committed -> {
                val batch = pending.getOrPut(conversation) { Batch(event.turn.agent, source(event.turn, conversation)) }
                batch.lines += event.lines
                batch.turns++
                batch.tainted = batch.tainted || event.tainted
                if (batch.turns >= config.turnsPerBatch) flush(conversation)
            }

            is Event.Compacted -> {
                summaries[conversation] = event.summary
                if (config.onCompaction) flush(conversation)
            }

            is Event.Sealed -> {
                if (config.onSeal) flush(event.sealed)
                val summary = summaries.remove(event.sealed)
                if (summary != null && config.episodes) saveEpisode(event.turn, event.sealed, summary)
            }
        }
    }

    private suspend fun flush(conversation: ConversationId) {
        val batch = pending.remove(conversation) ?: return
        val transcript = Transcript(batch.lines.toList(), batch.source, tainted = batch.tainted)
        try {
            val written = graphs.graph(batch.agent).capture(transcript)
            logger.debug("Captured {} memories from conversation {} of agent '{}'", written, conversation, batch.agent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Capturing {} lines of conversation {} into the memory of agent '{}' failed",
                batch.lines.size,
                conversation,
                batch.agent,
                e,
            )
        }
    }

    private suspend fun saveEpisode(turn: TurnInfo, conversation: ConversationId, summary: String) {
        try {
            graphs.graph(turn.agent).saveEpisode(summary, source(turn, conversation))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Saving conversation {} of agent '{}' as an episode failed", conversation, turn.agent, e)
        }
    }

    private fun source(turn: TurnInfo, conversation: ConversationId): Transcript.Source =
        Transcript.Source(chatKey = turn.chatKey, conversationId = conversation.value)

    private class Batch(val agent: AgentId, val source: Transcript.Source) {
        val lines = mutableListOf<Transcript.Line>()
        var turns = 0
        var tainted = false
    }

    private sealed interface Event {
        val turn: TurnInfo

        class Committed(override val turn: TurnInfo, val lines: List<Transcript.Line>, val tainted: Boolean) : Event

        class Compacted(override val turn: TurnInfo, val summary: String) : Event

        class Sealed(override val turn: TurnInfo, val sealed: ConversationId) : Event
    }

    private companion object {
        const val CAPACITY = 1024
    }
}

/** Whether a turn's conversation took in untrusted content, as the conversation store marks it. */
@Singleton
internal class TurnTaints(private val conversations: ConversationStore?) {
    @Volatile
    private var warned = false

    /** True also when the store cannot tell. */
    suspend fun tainted(turn: TurnInfo): Boolean {
        val store = conversations
        if (store == null) {
            if (!warned) logger.warn("No conversation store is bound, so every captured turn counts as tainted")
            warned = true
            return true
        }
        return try {
            store.taint(turn.conversation) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot read the taint of conversation {}, so its turn counts as tainted", turn.conversation, e)
            true
        }
    }
}

/** Inline, so that the capture queue takes turns, compactions and seals in the order they happen. */
@Singleton
@Contribute(Hook::class)
internal class CommittedTurns(private val capture: TurnCapture) : ObserverHook<TurnCommitted> {
    override val point: ObserverPoint<TurnCommitted> = TurnPoints.TURN_COMMITTED

    override suspend fun observe(payload: TurnCommitted) {
        capture.committed(payload.turn, payload.entries)
    }
}

@Singleton
@Contribute(Hook::class)
internal class CompactedContexts(private val capture: TurnCapture) : ObserverHook<ContextCompacted> {
    override val point: ObserverPoint<ContextCompacted> = TurnPoints.CONTEXT_COMPACTED

    override suspend fun observe(payload: ContextCompacted) {
        capture.compacted(payload.turn, payload.summary.text)
    }
}

@Singleton
@Contribute(Hook::class)
internal class SealedConversations(private val capture: TurnCapture) : ObserverHook<ConversationSealed> {
    override val point: ObserverPoint<ConversationSealed> = TurnPoints.CONVERSATION_SEALED

    override suspend fun observe(payload: ConversationSealed) {
        capture.sealed(payload.turn, payload.sealed)
    }
}

/** The words that people and the model said in [entries], without tool calls, tool results or framing. */
private fun lines(entries: List<TranscriptEntry>): List<Transcript.Line> = entries.mapNotNull { entry ->
    when (entry) {
        is UserEntry -> {
            val origin = entry.origin as? UserOrigin.FromChat ?: return@mapNotNull null
            val text = entry.parts.text() ?: return@mapNotNull null
            Transcript.Line(
                Transcript.Role.USER,
                text,
                at = origin.receivedAt,
                messageId = entry.record?.id?.value,
                speakerName = origin.sender.displayName,
            )
        }

        is AssistantEntry -> {
            val text = entry.parts.text() ?: return@mapNotNull null
            Transcript.Line(
                Transcript.Role.ASSISTANT,
                text,
                at = entry.record?.createdAt,
                messageId = entry.record?.id?.value,
            )
        }

        else -> null
    }
}

private fun List<*>.text(): String? =
    filterIsInstance<TextPart>().joinToString("\n") { it.text }.trim().takeIf { it.isNotEmpty() }

private val logger = LoggerFactory.getLogger(TurnCapture::class.java)
