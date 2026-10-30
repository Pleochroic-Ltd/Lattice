package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationInfo
import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.ConversationSource
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-memory [ConversationSource] for scene tests: conversations of chats, messages with positions that step by
 * [step] (so the positions have gaps, as a host's may), a minute apart from [start]. Thread-safe: the scene pipeline
 * reads it from its own threads while a test appends. [failing] makes every read of a conversation throw.
 */
class FakeConversationSource(
    private val start: Instant = Instant.parse("2026-09-01T02:00:00Z"),
    private val step: Long = 10,
) : ConversationSource {
    private val conversations = LinkedHashMap<String, ConversationInfo>()
    private val messages = HashMap<String, MutableList<ConversationMessage>>()
    private var clock = start
    val failing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    val reads = AtomicInteger()

    @Synchronized
    fun open(conversationId: String, chatKey: String): FakeConversationSource = apply {
        conversations.putIfAbsent(conversationId, ConversationInfo(conversationId, chatKey, sealed = false))
        messages.putIfAbsent(conversationId, ArrayList())
    }

    @Synchronized
    fun seal(conversationId: String) {
        conversations[conversationId] = conversations.getValue(conversationId).copy(sealed = true)
    }

    /** Drops [conversationId] and its messages, as a host that never had it (another device's conversation). */
    @Synchronized
    fun forget(conversationId: String) {
        conversations -= conversationId
        messages -= conversationId
    }

    /** Moves the clock messages are stamped with. */
    @Synchronized
    fun at(instant: Instant): FakeConversationSource = apply { clock = instant }

    /** Appends one message to [conversationId] and returns it. */
    @Synchronized
    fun add(
        conversationId: String,
        text: String,
        role: Role = Role.USER,
        senderId: String? = OWNER,
        toolName: String? = null,
        toolArguments: String? = null,
        tainted: Boolean = false,
        speakerName: String? = null,
    ): ConversationMessage {
        val list = messages.getValue(conversationId)
        val position = (list.lastOrNull()?.position ?: 0) + step
        val message = ConversationMessage(
            position, role, text, clock,
            speakerName = speakerName,
            senderId = if (role == Role.USER || role == Role.HOST) senderId else null,
            toolName = toolName,
            toolArguments = toolArguments,
            tainted = tainted,
        )
        clock = clock.plus(Duration.ofMinutes(1))
        list += message
        conversations[conversationId] = conversations.getValue(conversationId).copy(lastPosition = position)
        return message
    }

    /** [count] alternating user / assistant messages about [topic]; returns them. */
    fun talk(conversationId: String, topic: String, count: Int): List<ConversationMessage> =
        (1..count).map { i ->
            add(conversationId, "$topic message $i", if (i % 2 == 1) Role.USER else Role.ASSISTANT)
        }

    @Synchronized
    fun all(conversationId: String): List<ConversationMessage> = messages.getValue(conversationId).toList()

    @Synchronized
    override fun chats(): List<String> = conversations.values.map { it.chatKey }.distinct()

    @Synchronized
    override fun conversations(chatKey: String): List<ConversationInfo> = conversations.values.filter { it.chatKey == chatKey }

    @Synchronized
    override fun conversation(conversationId: String): ConversationInfo? = conversations[conversationId]

    @Synchronized
    override fun messages(conversationId: String, from: Long, to: Long, limit: Int): List<ConversationMessage> {
        reads.incrementAndGet()
        if (conversationId in failing) throw IllegalStateException("the host's store is unreadable")
        return messages[conversationId].orEmpty().filter { it.position in from..to }.take(limit)
    }

    companion object {
        const val OWNER = "owner-1"
    }
}
