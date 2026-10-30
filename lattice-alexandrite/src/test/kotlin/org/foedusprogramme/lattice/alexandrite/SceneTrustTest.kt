@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.json.Json
import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.core.GraphAccess
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.SceneTrust
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.TurnStart
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Whose words, which tool results and which host text a scene is trusted with in Alexandrite. */
class SceneTrustTest {
    private val dir = Files.createTempDirectory("lattice-trust")
    private val risks = ToolRisks(dir.resolve("tool-risks.json"))
    private val senders = SenderTrust()

    private fun policy(scenes: String = "{}") = AlexandriteSceneTrustPolicy(
        Json.decodeFromString(ScenesConfig.serializer(), scenes),
        risks,
        senders,
    )

    @Test
    fun `operators and trusted users of the chat and the configured senders are trusted`() {
        senders.saw(CHAT, "test:main@alice", trusted = true)
        senders.saw(CHAT, "test:main@guest", trusted = false)
        val policy = policy("""{"trustedSenders": ["telegram:main@42"]}""")

        assertTrue(policy.isTrustedSender(CHAT, "test:main@alice"))
        assertFalse(policy.isTrustedSender("test:main:other", "test:main@alice"), "trust is per chat")
        assertFalse(policy.isTrustedSender(CHAT, "test:main@guest"))
        assertFalse(policy.isTrustedSender(CHAT, "test:main@stranger"))
        assertTrue(policy.isTrustedSender("test:main:other", "telegram:main@42"))
    }

    @Test
    fun `a tool is trusted by its risk or the config, and a transcript search never`() {
        risks.learn(
            listOf(
                testToolDefinition("fs.read", ToolRisk.READ_ONLY),
                testToolDefinition("profile.edit", ToolRisk.AGENT_STATE),
                testToolDefinition("web.fetch", ToolRisk.NETWORK_READ),
                testToolDefinition("shell.run", ToolRisk.EXEC),
                testToolDefinition("chat.send", ToolRisk.EXTERNAL_ACTION),
                testToolDefinition("history.search", ToolRisk.READ_ONLY),
                testToolDefinition("notes.dump", ToolRisk.READ_ONLY),
            ),
        )
        val policy = policy("""{"trustedTools": ["shell.run"], "rawTextTools": ["notes.dump"]}""")

        assertEquals(
            mapOf(
                "fs.read" to true,
                "profile.edit" to true,
                "web.fetch" to false,
                "shell.run" to true,
                "chat.send" to false,
                "unknown.tool" to false,
            ),
            listOf("fs.read", "profile.edit", "web.fetch", "shell.run", "chat.send", "unknown.tool")
                .associateWith { policy.isTrustedTool(it, null) },
        )
        assertTrue(policy.opensRawText("history.search", "{}"))
        assertTrue(policy.opensRawText("notes.dump", null))
        assertFalse(policy.opensRawText("fs.read", null))
    }

    @Test
    fun `only the host's own framing is trusted host text`() {
        val policy = policy()

        assertTrue(policy.isTrustedHostText("alexandrite.limit"))
        assertTrue(policy.isTrustedHostText("alexandrite.summary"))
        assertFalse(policy.isTrustedHostText("plugin:reminders"))
        assertFalse(policy.isTrustedHostText("alexandrite.job"))
        assertFalse(policy.isTrustedHostText(null))
    }

    @Test
    fun `the risks learned from a turn's tools survive a restart`() {
        risks.learn(listOf(testToolDefinition("web.fetch", ToolRisk.EXEC)))
        risks.learn(listOf(testToolDefinition("web.fetch", ToolRisk.NETWORK_READ), testToolDefinition("fs.read", ToolRisk.READ_ONLY)))

        val restarted = ToolRisks(dir.resolve("tool-risks.json"))
        assertEquals(ToolRisk.NETWORK_READ, restarted.risk("web.fetch"))
        assertEquals(ToolRisk.READ_ONLY, restarted.risk("fs.read"))
        assertNull(restarted.risk("shell.run"))
        Files.writeString(dir.resolve("tool-risks.json"), "not json")
        assertNull(ToolRisks(dir.resolve("tool-risks.json")).risk("fs.read"), "an unreadable file counts as none")
    }

    @Test
    fun `each turn's offered tools teach the risks when scenes are on`() {
        val harness = latticeHarness(config(SCENES_ON)) { store(MemoryStore()) }
        harness.execute {
            val hook = getAll<Hook>().filterIsInstance<OfferedToolRisks>().single()
            val start = TurnStart(testTurn(), null, listOf(testToolDefinition("web.fetch", ToolRisk.NETWORK_READ)))

            assertEquals(HookDecision.Continue, hook.intercept(start))
            assertEquals(ToolRisk.NETWORK_READ, get<SceneHost>().toolRisks.risk("web.fetch"))
        }
    }

    @Test
    fun `scene trust over the source judges each message by the policy`() {
        senders.saw(CHAT, "test:main@alice", trusted = true)
        risks.learn(listOf(testToolDefinition("fs.read", ToolRisk.READ_ONLY), testToolDefinition("web.fetch", ToolRisk.NETWORK_READ)))
        val trust = SceneTrust(GraphAccess.locked(LatticeGraph(LocalHashingEmbedder())), EmptySource, policy())
        fun judge(vararg messages: ConversationMessage) = trust.judgeMessages(CHAT, messages.toList()).map { it.label }.toSet()
        val at = Instant.EPOCH

        assertEquals(emptySet(), judge(
            ConversationMessage(1, Role.USER, "hi", at, senderId = "test:main@alice"),
            ConversationMessage(2, Role.ASSISTANT, "hello", at),
            ConversationMessage(3, Role.TOOL, "a file", at, toolName = "fs.read"),
            ConversationMessage(4, Role.HOST, "Sum up.", at, senderId = "alexandrite.limit"),
        ))
        assertEquals(setOf("untrusted_sender"), judge(ConversationMessage(1, Role.USER, "hi", at, senderId = "test:main@guest")))
        assertEquals(setOf("untrusted_tool"), judge(ConversationMessage(1, Role.TOOL, "a page", at, toolName = "web.fetch")))
        assertEquals(setOf("transcript_tool"), judge(ConversationMessage(1, Role.TOOL, "old words", at, toolName = "history.search")))
        assertEquals(setOf("host_text"), judge(ConversationMessage(1, Role.HOST, "Stretch", at, senderId = "plugin:reminders")))
        assertEquals(setOf("tainted_content"), judge(ConversationMessage(1, Role.USER, "fwd", at, senderId = "test:main@alice", tainted = true)))
    }

    private object EmptySource : org.foedusprogramme.lattice.ConversationSource {
        override fun chats(): List<String> = emptyList()
        override fun conversations(chatKey: String) = emptyList<org.foedusprogramme.lattice.ConversationInfo>()
        override fun conversation(conversationId: String): org.foedusprogramme.lattice.ConversationInfo? = null
        override fun messages(conversationId: String, from: Long, to: Long, limit: Int) = emptyList<ConversationMessage>()
    }

    private companion object {
        const val CHAT = "test:main:chat"
    }
}

/** Scenes on, with a model they need. */
internal const val SCENES_ON: String =
    """{"model": {"kind": "openai", "baseUrl": "http://127.0.0.1:8080/v1", "default": "qwen3-8b"}, "scenes": {"enabled": true}}"""
