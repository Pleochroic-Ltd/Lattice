@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.ConversationMessage.Role
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ForwardKind
import org.unitedsoftware.alexandrite.sdk.chat.ForwardOrigin
import org.unitedsoftware.alexandrite.sdk.chat.Quote
import org.unitedsoftware.alexandrite.sdk.chat.QuoteTrust
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.TEST_MODEL
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Alexandrite's transcripts and conversations as scenes read them, and the registry of a chat's conversations. */
class SceneSourceTest {
    private class SteppedClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = SteppedClock(TEST_TIME)
    private val store = MemoryStore(clock)
    private val dir = Files.createTempDirectory("lattice-scenes")
    private val registry = ConversationRegistry(dir.resolve("conversations.jsonl"))
    private val senders = SenderTrust()
    private val source = AlexandriteConversationSource(
        AgentId.MAIN, store.transcripts, store.conversations, registry, senders,
    )
    private val key = AgentChatKey(AgentId.MAIN, testChat())
    private val chatKey = testChat().toString()
    private var turns = 0

    private fun current(): ConversationId = runBlocking { store.conversations.current(key).id }

    /** Stores [entries] in one new turn of [key]'s current conversation, a minute after the last. */
    private fun append(vararg entries: TranscriptEntry): List<TranscriptEntry> = runBlocking {
        clock.now = clock.now.plusSeconds(60)
        val turn = testTurn(conversation = store.conversations.current(key).id, id = TurnId("turn-${++turns}"))
        store.conversations.startTurn(turn)
        store.transcripts.append(turn.id, entries.toList())
    }

    private fun said(text: String, sender: ChatUser = testUser(), forwarded: ForwardOrigin? = null, quote: Quote? = null) =
        UserEntry(
            null,
            listOf(ContextPart("alexandrite.message", "Sender: ${sender.displayName}"), TextPart(text)),
            UserOrigin.FromChat(sender, ChannelMessageRef(testChat(), "m-$text"), clock.now, forwarded, quote),
        )

    private fun answer(text: String, vararg calls: ToolCallPart) =
        AssistantEntry(null, listOfNotNull(TextPart(text).takeIf { text.isNotEmpty() }) + calls, TEST_MODEL)

    private fun result(call: ToolCallPart, text: String) =
        ToolResultEntry(null, call.id, call.name, listOf(TextPart(text)), ToolOutcome.Succeeded)

    private fun messages(from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, limit: Int = Int.MAX_VALUE) =
        source.messages(current().value, from, to, limit)

    private val List<TranscriptEntry>.positions: List<Long> get() = map { it.record!!.id.value }

    // Mapping.

    @Test
    fun `each entry kind becomes a message at its entry id, and summaries and notices are left out`() {
        val alice = testUser("alice", isOperator = true)
        val fetch = ToolCallPart(ToolCallId("call-1"), "web.fetch", """{"url": "https://example.com"}""")
        val stored = append(
            said("I moved to Berlin", alice),
            answer("Let me look that up.", fetch),
            result(fetch, "a page about Berlin"),
            answer("Welcome to Berlin."),
            UserEntry(null, listOf(TextPart("Time to stretch")), UserOrigin.Initiated("reminders", TurnKind.REMINDER)),
            UserEntry(null, listOf(ContextPart("alexandrite.limit", "Sum up.")), UserOrigin.SystemWritten("alexandrite.limit")),
            NoticeEntry(null, "The reply was too long.", NoticeKind.TOO_LONG),
        )
        val summary = append(SummaryEntry(null, "We talked about Berlin.", EntryId(stored.positions.last())))

        val messages = messages()

        assertEquals(stored.positions.dropLast(1), messages.map { it.position })
        assertFalse(summary.positions.single() in messages.map { it.position })
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.HOST, Role.HOST), messages.map { it.role })
        val user = messages[0]
        assertEquals("I moved to Berlin", user.text, "the host's framing is left out")
        assertEquals("Alice", user.speakerName)
        assertEquals(alice.address.toString(), user.senderId)
        assertTrue(user.said)
        assertEquals("Let me look that up.", messages[1].text)
        val tool = messages[2]
        assertEquals("web.fetch", tool.toolName)
        assertEquals("""{"url": "https://example.com"}""", tool.toolArguments)
        assertEquals("a page about Berlin", tool.text)
        assertFalse(tool.said)
        assertEquals(listOf("plugin:reminders", "alexandrite.limit"), messages.drop(4).map { it.senderId })
        assertEquals(listOf("Time to stretch", "Sum up."), messages.drop(4).map { it.text })
        assertTrue(messages.none { it.tainted })
        assertTrue(senders.trusted(chatKey, alice.address.toString()), "an operator is a trusted sender of the chat")
    }

    @Test
    fun `a forward, an untrusted quote and everything after the conversation's taint are tainted`() {
        val forward = ForwardOrigin(ForwardKind.USER, "Bob", null, null)
        val vouched = Quote.builder("my earlier note").trust(QuoteTrust(live = true, content = true)).build()
        append(
            said("plain"),
            said("forwarded", forwarded = forward),
            said("quoting", quote = Quote.builder("something Bob wrote").build()),
            said("quoting myself", quote = vouched),
        )
        runBlocking { store.conversations.markTainted(current(), "a web page", clock.now.plusSeconds(30)) }
        append(said("after the page"), answer("It said hello."))

        assertEquals(
            listOf("plain" to false, "forwarded" to true, "quoting" to true, "quoting myself" to false, "after the page" to true, "It said hello." to true),
            messages().map { it.text to it.tainted },
        )
    }

    @Test
    fun `a read takes the range and the limit, and finds a tool call made before it`() {
        val search = ToolCallPart(ToolCallId("call-2"), "history.search", """{"query": "Berlin"}""")
        val first = append(said("one"), answer("", search), result(search, "found Berlin"))
        val second = append(answer("two"), said("three"))
        val all = (first + second).positions

        assertEquals(all, messages().map { it.position })
        assertEquals(all.subList(2, 4), messages(from = all[2], to = all[3]).map { it.position })
        assertEquals(all.subList(1, 3), messages(from = all[1], limit = 2).map { it.position })
        assertEquals("""{"query": "Berlin"}""", messages(from = all[2]).first().toolArguments, "from the call's turn")
        assertEquals(emptyList(), messages(from = all.last() + 1))
        assertEquals(emptyList(), source.messages("no-such-conversation", Long.MIN_VALUE))
    }

    @Test
    fun `a conversation tells its chat, its seal and its last position, and only user lanes of the agent count`() {
        val first = current()
        assertEquals(org.foedusprogramme.lattice.ConversationInfo(first.value, chatKey, sealed = false), source.conversation(first.value))
        val stored = append(said("hello"))
        runBlocking { store.conversations.newConversation(key) }

        val info = source.conversation(first.value)!!
        assertTrue(info.sealed)
        assertEquals(stored.positions.single(), info.lastPosition)
        assertNull(source.conversation("no-such-conversation"))

        val heartbeat = runBlocking { store.conversations.heartbeatBase(key).id }
        assertNull(source.conversation(heartbeat.value), "heartbeats stay out of the chat's scenes")
        assertEquals(emptyList(), source.messages(heartbeat.value, Long.MIN_VALUE))
        val coder = runBlocking { store.conversations.current(AgentChatKey(AgentId("coder"), testChat())).id }
        assertNull(source.conversation(coder.value), "another agent's conversation belongs to another graph")
    }

    // Registry.

    @Test
    fun `the registry lists each agent's chats and conversations in the order seen, also after a restart`() {
        val first = current()
        append(said("hello"))
        val second = runBlocking { store.conversations.newConversation(key).successor.id }
        val other = testChat("other").toString()
        registry.add(RegisteredConversation("main", chatKey, first.value))
        registry.add(RegisteredConversation("main", other, "elsewhere"))
        registry.add(RegisteredConversation("main", chatKey, second.value))
        registry.add(RegisteredConversation("main", chatKey, first.value))
        registry.add(RegisteredConversation("coder", "test:main:coder", "theirs"))

        assertEquals(listOf(chatKey, other), source.chats())
        assertEquals(listOf(first.value, second.value), source.conversations(chatKey).map { it.id })
        assertEquals(listOf(true, false), source.conversations(chatKey).map { it.sealed })
        assertEquals(emptyList(), source.conversations(other), "a conversation the store does not have is left out")

        Files.writeString(dir.resolve("conversations.jsonl"), Files.readString(dir.resolve("conversations.jsonl")) + "not json\n")
        val restarted = ConversationRegistry(dir.resolve("conversations.jsonl"))
        assertEquals(listOf(first.value, second.value), restarted.conversations(AgentId.MAIN, chatKey))
        assertEquals(listOf("test:main:coder"), restarted.chats(AgentId("coder")))
        assertEquals("main", restarted.find(second.value)?.agent)
    }
}
