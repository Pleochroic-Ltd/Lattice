@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import org.foedusprogramme.lattice.Transcript
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.turn.ContextCompacted
import org.unitedsoftware.alexandrite.sdk.turn.ConversationSealed
import org.unitedsoftware.alexandrite.sdk.turn.TurnCommitted
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.TEST_MODEL
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testDelegatedTurn
import org.unitedsoftware.alexandrite.testkit.testRecord
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnCaptureTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")
    private val store = MemoryStore()
    private var entryIds = 0L

    private fun harness(extra: String = """{"capture": {"turnsPerBatch": 2}}""", bindStore: Boolean = true) =
        latticeHarness(config(extra), opener) { if (bindStore) store(store) }

    private fun turn(conversation: ConversationId = ConversationId("test"), kind: TurnKind = TurnKind.MESSAGE) =
        testTurn(kind, conversation = conversation, id = TurnId("turn-${entryIds + 1}"))

    private fun user(turn: TurnInfo, text: String): TranscriptEntry =
        testUserEntry(turn, text, testRecord(++entryIds, turn))

    private fun assistant(turn: TurnInfo, text: String): TranscriptEntry =
        AssistantEntry(
            testRecord(++entryIds, turn),
            listOf(ReasoningPart("thinking", null, null), TextPart(text)),
            TEST_MODEL,
        )

    private inline fun <reified H : Hook> PluginHarness.Running.hook(): H =
        getAll<Hook>().filterIsInstance<H>().single()

    private suspend fun PluginHarness.Running.commit(turn: TurnInfo, vararg entries: TranscriptEntry) {
        hook<CommittedTurns>().observe(TurnCommitted(turn, entries.toList(), TurnOutcome.Completed(null), null))
    }

    private suspend fun PluginHarness.Running.exchange(turn: TurnInfo, said: String, answered: String) {
        commit(turn, user(turn, said), assistant(turn, answered))
    }

    private suspend fun PluginHarness.Running.compact(turn: TurnInfo, summary: String) {
        val entry = SummaryEntry(testRecord(++entryIds, turn), summary, EntryId(entryIds - 1))
        hook<CompactedContexts>().observe(ContextCompacted(turn, entry, 900, 100))
    }

    private suspend fun PluginHarness.Running.seal(sealed: ConversationId) {
        val command = testTurn(TurnKind.COMMAND)
        hook<SealedConversations>().observe(ConversationSealed(command, sealed, ConversationId("next")))
    }

    // Batches.

    @Test
    fun `every few turns the words of a conversation are captured, without tool calls, results or reasoning`() {
        harness().execute {
            val first = turn()
            val call = ToolCallPart(ToolCallId("call-1"), "web.fetch", "{}")
            commit(
                first,
                user(first, "I switched to green tea"),
                AssistantEntry(testRecord(++entryIds, first), listOf(call), TEST_MODEL),
                ToolResultEntry(
                    testRecord(++entryIds, first),
                    call.id,
                    call.name,
                    listOf(TextPart("a page")),
                    ToolOutcome.Succeeded,
                ),
                assistant(first, "Noted."),
            )
            exchange(turn(), "And I stopped coffee", "Good.")

            val transcript = main.awaitCaptures(1).single()

            assertEquals(
                listOf(
                    Transcript.Role.USER to "I switched to green tea",
                    Transcript.Role.ASSISTANT to "Noted.",
                    Transcript.Role.USER to "And I stopped coffee",
                    Transcript.Role.ASSISTANT to "Good.",
                ),
                transcript.lines.map { it.role to it.text },
            )
            assertEquals(listOf(1L, 4L, 5L, 6L), transcript.lines.map { it.messageId })
            assertEquals(listOf("Guest", null), transcript.lines.take(2).map { it.speakerName })
            assertEquals(TEST_TIME, transcript.lines.first().at)
            assertEquals(Transcript.Source("test:main:chat", "test"), transcript.source)
            assertFalse(transcript.tainted)
        }

        assertEquals(1, main.captures.value.size)
    }

    @Test
    fun `a batch of a tainted conversation is tainted`() {
        harness().execute {
            val conversation = store.conversations.current(AgentChatKey(AgentId.MAIN, testChat())).id
            store.conversations.markTainted(conversation, "a web page", Instant.now())
            exchange(turn(conversation), "read this page", "It says hello.")
            exchange(turn(conversation), "thanks", "You're welcome.")

            assertTrue(main.awaitCaptures(1).single().tainted)
        }
    }

    @Test
    fun `without a conversation store every batch counts as tainted`() {
        harness(bindStore = false).execute {
            exchange(turn(), "hi", "hello")
            exchange(turn(), "bye", "goodbye")

            assertTrue(main.awaitCaptures(1).single().tainted)
        }
    }

    @Test
    fun `each conversation and each agent has its own batch`() {
        val coder = opener.graph("coder")

        harness().execute {
            exchange(turn(), "one", "a")
            exchange(turn(ConversationId("other")), "two", "b")
            val theirs = testTurn(id = TurnId("coder-turn"), conversation = ConversationId("coder")) {
                agent(AgentId("coder"))
            }
            exchange(theirs, "three", "c")
            exchange(turn(), "four", "d")

            assertEquals(listOf("one", "a", "four", "d"), main.awaitCaptures(1).single().lines.map { it.text })
        }

        assertEquals(listOf("test", "other"), main.captures.value.map { it.source.conversationId })
        assertEquals(listOf("three", "c"), coder.captures.value.single().lines.map { it.text })
    }

    // Compaction, seal and drain.

    @Test
    fun `a compaction captures what is pending, and a seal also saves the last summary as an episode`() {
        harness("""{"capture": {"turnsPerBatch": 10}}""").execute {
            exchange(turn(), "one", "a")
            compact(turn(), "We talked about one.")

            assertEquals(listOf("one", "a"), main.awaitCaptures(1).last().lines.map { it.text })

            exchange(turn(), "two", "b")
            compact(turn(), "We talked about one and two.")
            exchange(turn(), "three", "c")
            seal(ConversationId("test"))

            assertEquals(listOf("three", "c"), main.awaitCaptures(3).last().lines.map { it.text })
            val (summary, source) = main.awaitEpisodes(1).single()
            assertEquals("We talked about one and two.", summary)
            assertEquals(Transcript.Source("test:main:chat", "test"), source)
        }
    }

    @Test
    fun `compaction flushes and episodes can be switched off`() {
        harness("""{"capture": {"turnsPerBatch": 10, "onCompaction": false, "episodes": false}}""").execute {
            exchange(turn(), "one", "a")
            compact(turn(), "We talked about one.")
            exchange(turn(), "two", "b")
            seal(ConversationId("test"))

            assertEquals(listOf("one", "a", "two", "b"), main.awaitCaptures(1).single().lines.map { it.text })
        }

        assertTrue(main.episodes.value.isEmpty())
    }

    @Test
    fun `what is pending is captured when the plugin drains`() {
        harness().execute { exchange(turn(), "one", "a") }

        assertEquals(listOf("one", "a"), main.captures.value.single().lines.map { it.text })
        assertTrue(main.closed)
    }

    @Test
    fun `only top-level message turns are captured, and capture can be switched off`() {
        harness().execute {
            exchange(turn(kind = TurnKind.HEARTBEAT), "beat", "nothing to do")
            val delegated = testDelegatedTurn()
            exchange(delegated, "task", "done")
            commit(turn(), assistant(turn(), "  "))
        }
        harness("""{"capture": {"enabled": false}}""").execute { exchange(turn(), "one", "a") }

        assertTrue(main.captures.value.isEmpty())
    }
}
