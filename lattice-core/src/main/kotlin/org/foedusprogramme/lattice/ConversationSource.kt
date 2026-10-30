package org.foedusprogramme.lattice

import java.time.Instant

/**
 * What Lattice reads back from the host to cut conversations into scenes, narrate them and judge whether their content
 * can be trusted: the host's conversations and their messages, as stored. Scenes run only when a host provides one
 * ([LatticeDependencies.conversations]) and [SceneSettings.enabled] is on.
 *
 * Positions ([ConversationMessage.position]) are the host's own: unique and increasing within one conversation, with
 * gaps allowed, and never compared across conversations. They are the [Transcript.Line.messageId]s the host captures
 * with. Calls block and come from Lattice's own threads, never the caller's; a call that fails throws, and the work
 * that needed it is retried later (a live step at the next one, the rest at the sleep's catch-up).
 */
interface ConversationSource {
    /** The chats of this graph whose conversations may hold messages Lattice has not captured, for the catch-up. */
    fun chats(): List<String>

    /** The conversations of [chatKey], oldest first. */
    fun conversations(chatKey: String): List<ConversationInfo>

    /** Conversation [conversationId], or null when the host has none by that id. */
    fun conversation(conversationId: String): ConversationInfo?

    /**
     * The messages of [conversationId] whose positions are in [from]..[to], oldest first, at most [limit] of them;
     * empty for a conversation the host does not have.
     */
    fun messages(
        conversationId: String,
        from: Long,
        to: Long = Long.MAX_VALUE,
        limit: Int = Int.MAX_VALUE,
    ): List<ConversationMessage>
}

/** One conversation as the host keeps it. */
data class ConversationInfo(
    val id: String,
    /** The chat the conversation belongs to; scenes, threads and periods are per chat, across its conversations. */
    val chatKey: String,
    /** Takes no more messages (it was replaced by a new one): its last scene has ended. */
    val sealed: Boolean,
    /** The position of its last message, null when it has none; lets the catch-up skip a conversation it has read. */
    val lastPosition: Long? = null,
)

/** One stored message of a conversation. */
data class ConversationMessage(
    val position: Long,
    val role: Role,
    /** The text as the participants saw it, without the host's own framing (recall blocks, wrappers). */
    val text: String,
    val at: Instant,
    /** Who spoke, for a person in a group chat; shown to the models. */
    val speakerName: String? = null,
    /** The host's id of a [Role.USER] message's sender, or the source of a [Role.HOST] message (what wrote it). */
    val senderId: String? = null,
    /** The tool a [Role.TOOL] message is the result of. */
    val toolName: String? = null,
    /** The arguments of the call a [Role.TOOL] message answers, as the host recorded them. */
    val toolArguments: String? = null,
    /**
     * The message brought in content nobody in the chat vouched for — a forward, a quote the host could not verify, a
     * fetched page — or its turn read such content. Memories taken from it are tainted, and its scene is untrusted.
     */
    val tainted: Boolean = false,
) {
    enum class Role {
        /** A message a person in the chat wrote. */
        USER,

        ASSISTANT,

        /** The result of a tool call. */
        TOOL,

        /** Text the host wrote into the conversation itself: a reminder, a notice, a turn a plugin started. */
        HOST,
    }

    /** Words a participant said: what scenes are cut from and memories are taken from. */
    val said: Boolean get() = (role == Role.USER || role == Role.ASSISTANT) && text.isNotBlank()
}
