package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.foedusprogramme.lattice.ConversationInfo
import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.ConversationSource
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.store.ConversationKind
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readLines
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo as StoredConversation

/**
 * What scenes read from Alexandrite: the host's stores, the conversations the plugin has seen and the risks of the
 * tools it has seen offered. Scenes run when the config turns them on and the host binds a transcript and a
 * conversation store.
 */
@Singleton
internal class SceneHost(
    config: LatticeConfig,
    files: PluginFiles,
    private val transcripts: TranscriptStore?,
    private val conversations: ConversationStore?,
    private val access: MemoryAccess,
) : Lifecycle {
    private val scenes = config.scenes
    private val scenesOn = config.scenesOn
    val registry = ConversationRegistry(files.dataDir.resolve(SCENES_DIR).resolve("conversations.jsonl"))
    val toolRisks = ToolRisks(files.dataDir.resolve(SCENES_DIR).resolve("tool-risks.json"))

    /** Whether conversations are captured scene by scene. */
    val live: Boolean = scenesOn && transcripts != null && conversations != null

    override suspend fun onStart() {
        storesWarning()?.let { logger.warn(it) }
    }

    /**
     * What Lattice goes without when the host binds no conversation or transcript store: the SDK reserves them for the
     * agent, so a host may keep them from plugins. Null when both are bound.
     */
    fun storesWarning(): String? {
        val missing = listOfNotNull(
            "conversation store".takeIf { conversations == null },
            "transcript store".takeIf { transcripts == null },
        )
        if (missing.isEmpty()) return null
        val effects = listOfNotNull(
            "every captured turn and summary counts as tainted".takeIf { conversations == null },
            "conversations are captured in batches of turns instead of scenes".takeIf { scenesOn },
        ).ifEmpty { listOf("scenes cannot be turned on") }
        return "The host binds no ${missing.joinToString(" and ")} to plugins, so ${effects.joinToString(" and ")}"
    }

    /** The source and trust policy of the graph [graph], null when scenes do not run. */
    fun forGraph(graph: String): SceneDependencies? {
        if (!live) return null
        val agent = try {
            AgentId(graph)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val senders = SenderTrust()
        return SceneDependencies(
            AlexandriteConversationSource(agent, transcripts!!, conversations!!, registry, senders, access),
            AlexandriteSceneTrustPolicy(scenes, toolRisks, senders),
        )
    }

    /**
     * Records [conversation] as one of its chat's and returns it, null when it is not a user-lane conversation (a
     * heartbeat or a sub-agent run, which scenes leave out) or the store has no such conversation.
     */
    suspend fun register(conversation: ConversationId): RegisteredConversation? {
        registry.find(conversation.value)?.let { return it }
        val info = checkNotNull(conversations) { "Scenes need a conversation store." }.conversation(conversation)
        if (info == null || info.kind != ConversationKind.USER_LANE) return null
        return registry.add(RegisteredConversation(info.key.agent.value, info.key.chat.toString(), conversation.value))
    }
}

internal class SceneDependencies(val source: ConversationSource, val trust: SceneTrustPolicy)

/** A user-lane conversation of [agent] in [chat], the chat key it is captured under. */
@Serializable
internal class RegisteredConversation(val agent: String, val chat: String, val conversation: String)

/**
 * The conversations the plugin has seen, per agent and chat, in the order it saw them, kept in [file] across restarts:
 * the SDK has no list of a chat's past conversations, which the scene catch-up needs. It keeps the latest [maxEntries];
 * the oldest, long sealed and caught up, are forgotten.
 */
internal class ConversationRegistry(private val file: Path, private val maxEntries: Int = MAX_CONVERSATIONS) {
    private val lock = Any()
    private var loaded: MutableList<RegisteredConversation>? = null

    fun find(conversation: String): RegisteredConversation? = all().firstOrNull { it.conversation == conversation }

    /** The chats of [agent], each once. */
    fun chats(agent: AgentId): List<String> = all().filter { it.agent == agent.value }.map { it.chat }.distinct()

    /** The conversation ids of [agent] in [chat], oldest first. */
    fun conversations(agent: AgentId, chat: String): List<String> =
        all().filter { it.agent == agent.value && it.chat == chat }.map { it.conversation }

    /** Records [entry] unless its conversation is known, and returns the record kept. */
    fun add(entry: RegisteredConversation): RegisteredConversation = synchronized(lock) {
        val entries = entries()
        entries.firstOrNull { it.conversation == entry.conversation }?.let { return it }
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            Json.encodeToString(RegisteredConversation.serializer(), entry) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        entries += entry
        if (entries.size > maxEntries) {
            entries.subList(0, entries.size - maxEntries).clear()
            rewrite(entries)
        }
        entry
    }

    private fun rewrite(entries: List<RegisteredConversation>) {
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, entries.joinToString("") { Json.encodeToString(RegisteredConversation.serializer(), it) + "\n" })
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun all(): List<RegisteredConversation> = synchronized(lock) { entries().toList() }

    private fun entries(): MutableList<RegisteredConversation> = loaded ?: read().also { loaded = it }

    private fun read(): MutableList<RegisteredConversation> {
        if (!file.exists()) return ArrayList()
        val lines = try {
            file.readLines()
        } catch (e: IOException) {
            logger.warn("Cannot read the conversations of {}, so the scene catch-up starts from none", file, e)
            return ArrayList()
        }
        return lines.filter { it.isNotBlank() }.mapNotNullTo(ArrayList()) { line ->
            try {
                Json.decodeFromString(RegisteredConversation.serializer(), line)
            } catch (e: SerializationException) {
                logger.warn("Skipping a line of {} that does not read as a conversation", file)
                null
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping a line of {} that does not read as a conversation", file)
                null
            }
        }
    }
}

/** Whether the senders seen in each chat were operators or trusted users there, as their channel told. */
internal class SenderTrust {
    private val seen = ConcurrentHashMap<Pair<String, String>, Boolean>()

    fun saw(chatKey: String, senderId: String, trusted: Boolean) {
        seen[chatKey to senderId] = trusted
    }

    fun trusted(chatKey: String, senderId: String): Boolean = seen[chatKey to senderId] == true
}

/**
 * The conversations of [agent] as scenes read them: the user-lane conversations the plugin has seen
 * ([ConversationRegistry]), their entries from [transcripts] at positions that are their entry ids, and their seals and
 * taints from [conversations]. Lattice calls it from its own threads, so each call blocks on the stores.
 *
 * Only turns that [access] admits are read: a turn whose every chat sender it admits, and a turn with no chat sender (a
 * reminder, a turn a plugin started) only at [EVERYONE]. The rest of a turn goes with its sender's message, the
 * agent's answer included, so that what someone below the level said is neither cut into scenes, extracted nor
 * narrated, not even as the agent repeated it. The gap it leaves still counts: each such turn reads as one empty host
 * message from [WITHHELD_SOURCE], which no scene trusts and which is tainted when someone below the level wrote in it.
 */
internal class AlexandriteConversationSource(
    private val agent: AgentId,
    private val transcripts: TranscriptStore,
    private val conversations: ConversationStore,
    private val registry: ConversationRegistry,
    private val senders: SenderTrust,
    private val access: MemoryAccess,
) : ConversationSource {
    override fun chats(): List<String> = registry.chats(agent)

    override fun conversations(chatKey: String): List<ConversationInfo> =
        registry.conversations(agent, chatKey).mapNotNull(::conversation)

    override fun conversation(conversationId: String): ConversationInfo? = runBlocking {
        val id = ConversationId(conversationId)
        val info = lane(id) ?: return@runBlocking null
        ConversationInfo(
            id = conversationId,
            chatKey = info.key.chat.toString(),
            sealed = info.state == ConversationState.SEALED,
            lastPosition = transcripts.tail(id, 1).lastOrNull()?.record?.id?.value,
        )
    }

    override fun messages(conversationId: String, from: Long, to: Long, limit: Int): List<ConversationMessage> =
        runBlocking {
            val id = ConversationId(conversationId)
            val info = lane(id)
            if (info == null || limit <= 0 || from > to) return@runBlocking emptyList()
            val chatKey = info.key.chat.toString()
            val taintedFrom = conversations.taint(id)?.at
            val entries = if (from <= 1) transcripts.entries(id) else transcripts.entriesAfter(id, EntryId(from - 1))
            val calls = Calls()
            val admitted = Admitted(entries, straddles = from > 1)
            val withheld = HashSet<TurnId>()
            val messages = ArrayList<ConversationMessage>()
            for (entry in entries) {
                val record = entry.record ?: continue
                calls.add(entry)
                val position = record.id.value
                if (position < from) continue
                if (position > to || messages.size >= limit) break
                if (!admitted(record.turn)) {
                    if (withheld.add(record.turn)) messages += withheld(record, taintedFrom, admitted.spoken(record.turn))
                    continue
                }
                messages += message(entry, record, chatKey, taintedFrom, calls) ?: continue
            }
            messages
        }

    /** The conversation [id] when it is a user-lane conversation of [agent]. */
    private suspend fun lane(id: ConversationId): StoredConversation? =
        conversations.conversation(id)?.takeIf { it.kind == ConversationKind.USER_LANE && it.key.agent == agent }

    private suspend fun message(
        entry: TranscriptEntry,
        record: EntryRecord,
        chatKey: String,
        taintedFrom: Instant?,
        calls: Calls,
    ): ConversationMessage? {
        val position = record.id.value
        // A conversation stays tainted from the first untrusted content it took in.
        val tainted = taintedFrom != null && !record.createdAt.isBefore(taintedFrom)
        return when (entry) {
            is UserEntry -> when (val origin = entry.origin) {
                is UserOrigin.FromChat -> {
                    val sender = origin.sender
                    val address = sender.address.toString()
                    senders.saw(chatKey, address, sender.isOperator || sender.isTrusted)
                    val quoted = origin.quote?.trust?.content == false
                    // Whatever anyone but an operator says is kept tainted, so it never comes back as trusted context.
                    ConversationMessage(
                        position,
                        ConversationMessage.Role.USER,
                        entry.parts.text(),
                        origin.receivedAt,
                        speakerName = sender.displayName,
                        senderId = address,
                        tainted = tainted || origin.forwarded != null || quoted || taintsMemory(sender),
                    )
                }

                is UserOrigin.Initiated -> host(entry, record, "$PLUGIN_SOURCE${origin.plugin}", tainted)
                is UserOrigin.SystemWritten -> host(entry, record, origin.source, tainted)
                else -> host(entry, record, null, tainted)
            }

            is AssistantEntry -> ConversationMessage(
                position,
                ConversationMessage.Role.ASSISTANT,
                entry.parts.text(),
                record.createdAt,
                tainted = tainted,
            )

            is ToolResultEntry -> ConversationMessage(
                position,
                ConversationMessage.Role.TOOL,
                entry.content.text(),
                record.createdAt,
                toolName = entry.toolName,
                toolArguments = calls.of(entry.callId, record.turn)?.arguments,
                tainted = tainted,
            )

            else -> null
        }
    }

    /** A turn left out, as the empty host message that stands in for it; [spoken] = someone below the level wrote in it. */
    private fun withheld(record: EntryRecord, taintedFrom: Instant?, spoken: Boolean) = ConversationMessage(
        record.id.value,
        ConversationMessage.Role.HOST,
        "",
        record.createdAt,
        senderId = WITHHELD_SOURCE,
        tainted = spoken || (taintedFrom != null && !record.createdAt.isBefore(taintedFrom)),
    )

    private fun host(entry: UserEntry, record: EntryRecord, source: String?, tainted: Boolean) = ConversationMessage(
        record.id.value,
        ConversationMessage.Role.HOST,
        entry.parts.mapNotNull { (it as? TextPart)?.text ?: (it as? ContextPart)?.text }.joinToString("\n").trim(),
        record.createdAt,
        senderId = source,
        tainted = tainted,
    )

    /**
     * Whether [access] admits each turn of [entries], which run to the end of the conversation. Only the first turn can
     * have begun before them, when the read [straddles] a start; its own entries are read for its senders.
     */
    private inner class Admitted(entries: List<TranscriptEntry>, straddles: Boolean) {
        private val senders = HashMap<TurnId, MutableList<ChatUser>>()
        private val first = entries.firstOrNull()?.record?.turn?.takeIf { straddles }
        private val verdicts = HashMap<TurnId, Boolean>()

        init {
            if (access.level != EVERYONE) entries.forEach(::add)
        }

        private fun add(entry: TranscriptEntry) {
            val turn = entry.record?.turn ?: return
            val origin = (entry as? UserEntry)?.origin as? UserOrigin.FromChat ?: return
            senders.getOrPut(turn) { ArrayList() } += origin.sender
        }

        /** Whether a chat sender wrote in [turn], which must have been asked about. */
        fun spoken(turn: TurnId): Boolean = senders[turn].orEmpty().isNotEmpty()

        suspend operator fun invoke(turn: TurnId): Boolean {
            if (access.level == EVERYONE) return true
            verdicts[turn]?.let { return it }
            if (turn == first) {
                senders -= turn
                transcripts.turnEntries(turn).forEach(::add)
            }
            val users = senders[turn].orEmpty()
            return (users.isNotEmpty() && users.all { access.admits(it) }).also { verdicts[turn] = it }
        }
    }

    /** The tool calls seen so far, and those of a turn whose call came before the entries read. */
    private inner class Calls {
        private val calls = HashMap<ToolCallId, ToolCallPart>()
        private val turns = HashSet<TurnId>()

        fun add(entry: TranscriptEntry) {
            if (entry is AssistantEntry) entry.parts.filterIsInstance<ToolCallPart>().forEach { calls[it.id] = it }
        }

        suspend fun of(call: ToolCallId, turn: TurnId): ToolCallPart? {
            calls[call]?.let { return it }
            if (turns.add(turn)) transcripts.turnEntries(turn).forEach(::add)
            return calls[call]
        }
    }

    companion object {
        /** The source of a turn a plugin started: `plugin:<plugin id>`. */
        private const val PLUGIN_SOURCE = "plugin:"

        /** The source of the host message that stands in for a turn below the access level. */
        const val WITHHELD_SOURCE: String = "lattice.withheld"
    }
}

/** The text parts, one per line. */
private fun List<*>.text(): String = filterIsInstance<TextPart>().joinToString("\n") { it.text }.trim()

private const val SCENES_DIR = "scenes"
private const val MAX_CONVERSATIONS = 20_000

private val logger = LoggerFactory.getLogger(SceneHost::class.java)
