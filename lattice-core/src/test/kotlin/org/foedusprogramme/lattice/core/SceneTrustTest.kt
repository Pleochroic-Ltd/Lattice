package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.NamedSceneTrustPolicy
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.foedusprogramme.lattice.core.FakeConversationSource.Companion.OWNER
import org.foedusprogramme.lattice.core.SceneTrust.Cause
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SceneTrust]: the per-message rules over the host's messages under a [SceneTrustPolicy], the thread rule over the
 * ledger, read failures and the verdict cache.
 */
class SceneTrustTest {

    private val source = FakeConversationSource()
    private val graph = LatticeGraph(LocalHashingEmbedder(), LatticeGraph.Config(dedupThreshold = 1.01), now = { NOW })
    private val access = GraphAccess.locked(graph)
    private val policy = NamedSceneTrustPolicy(
        trustedSenders = setOf(OWNER),
        trustedTools = setOf("clock.now", "memory.timeline"),
        rawTextTools = setOf("memory.transcript"),
        trustedHostSources = setOf("idle-note"),
    )
    private var nextScene = 1L
    private var building = 0L

    private fun trust(policy: SceneTrustPolicy = this.policy) = SceneTrust(access, source, policy)

    /** A scene of [chat] over the messages [build] adds to a conversation of its own; ended unless [open]. */
    private fun scene(chat: String = CHAT, open: Boolean = false, build: FakeConversationSource.() -> Unit): SceneRecord {
        val id = nextScene++
        val conversation = "$chat:s$id"
        source.open(conversation, chat)
        building = id
        source.build()
        val messages = source.all(conversation)
        val record = SceneRecord(
            id, "scene $id", conversation, chat, messages.first().position, messages.last().position,
            dateFrom = "2026-09-%02d".format(id), startedAt = messages.first().at, seq = id,
        )
        val work = SceneWorkRecord(id, if (open) SceneWorkState.OPEN else SceneWorkState.MATERIALIZED)
        graph.putSceneState(record, work)
        return record
    }

    private fun FakeConversationSource.chat(id: Long = building) {
        add("$CHAT:s$id", "周末去哪儿")
        add("$CHAT:s$id", "去海边吧", Role.ASSISTANT)
    }

    private fun members(sceneId: Long, vararg contents: String): List<Long> {
        val ids = contents.map { graph.remember(it, layer = Engram.Layer.SEMANTIC) }
        graph.addSceneMembers(sceneId, ids)
        return ids
    }

    private fun causes(s: SceneRecord, t: SceneTrust = trust()) = t.judgeChat(s.chatKey).verdicts.getValue(s.sceneId).causes

    // --- the per-message rules -----------------------------------------------------------------------------------

    @Test
    fun `the owner talking with the assistant is trusted`() {
        val s = scene { chat() }
        assertEquals(emptySet(), causes(s))
    }

    @Test
    fun `another sender or a message without a sender is untrusted`() {
        val other = scene { chat(); add("$CHAT:s1", "我是路人", senderId = "stranger-9") }
        val anonymous = scene { add("$CHAT:s2", "谁在说话", senderId = null) }
        assertEquals(setOf(Cause.UNTRUSTED_SENDER), causes(other))
        assertEquals(setOf(Cause.UNKNOWN_SENDER), causes(anonymous))
    }

    @Test
    fun `a tool result is trusted only from a trusted tool that replayed no stored text`() {
        val local = scene { chat(); add("$CHAT:s1", "10:00", Role.TOOL, toolName = "clock.now") }
        val web = scene { add("$CHAT:s2", "<html>page</html>", Role.TOOL, toolName = "web.view") }
        val unnamed = scene { add("$CHAT:s3", "something", Role.TOOL, toolName = null) }
        val replay = scene { add("$CHAT:s4", "[user] 原话", Role.TOOL, toolName = "memory.transcript") }
        assertEquals(emptySet(), causes(local))
        assertEquals(setOf(Cause.UNTRUSTED_TOOL), causes(web))
        assertEquals(setOf(Cause.UNKNOWN_TOOL), causes(unnamed))
        assertEquals(setOf(Cause.TRANSCRIPT_TOOL), causes(replay))
    }

    @Test
    fun `whether a call replayed stored text is told from its arguments`() {
        val pages = object : SceneTrustPolicy by policy {
            override fun opensRawText(toolName: String, arguments: String?) =
                toolName == "memory.timeline" && arguments?.contains("\"scene\"") == true
        }
        val listing = scene { add("$CHAT:s1", "periods: …", Role.TOOL, toolName = "memory.timeline", toolArguments = "{}") }
        val page = scene {
            add("$CHAT:s2", "[user] 原话", Role.TOOL, toolName = "memory.timeline", toolArguments = """{"scene": 4}""")
        }
        assertEquals(emptySet(), causes(listing, trust(pages)))
        assertEquals(setOf(Cause.TRANSCRIPT_TOOL), causes(page, trust(pages)))
    }

    @Test
    fun `host text is untrusted unless its source is trusted, and a tainted message always is`() {
        val idle = scene { chat(); add("$CHAT:s1", "（安静了一会儿）", Role.HOST, senderId = "idle-note") }
        val reminder = scene { add("$CHAT:s2", "提醒：给妈妈打电话", Role.HOST, senderId = "reminder") }
        val forwarded = scene { add("$CHAT:s3", "转发：今天全场五折", tainted = true) }
        assertEquals(emptySet(), causes(idle))
        assertEquals(setOf(Cause.HOST_TEXT), causes(reminder))
        assertEquals(setOf(Cause.TAINTED), causes(forwarded))
    }

    @Test
    fun `the conservative policy trusts no sender, tool or host text`() {
        val s = scene { chat(); add("$CHAT:s1", "10:00", Role.TOOL, toolName = "clock.now") }
        assertEquals(setOf(Cause.UNTRUSTED_SENDER, Cause.UNTRUSTED_TOOL), causes(s, trust(SceneTrustPolicy.Conservative)))
        val assistantOnly = scene { add("$CHAT:s2", "早上好", Role.ASSISTANT) }
        assertEquals(emptySet(), causes(assistantOnly, trust(SceneTrustPolicy.Conservative)))
    }

    @Test
    fun `judgeMessages applies the per-message rules to any message list`() {
        source.open("loose", CHAT)
        val messages = listOf(source.add("loose", "hi"), source.add("loose", "<html>", Role.TOOL, toolName = "web.view"))
        assertEquals(setOf(Cause.UNTRUSTED_TOOL), trust().judgeMessages(CHAT, messages))
        assertEquals(emptySet(), trust().judgeMessages(CHAT, messages.take(1)))
    }

    // --- the thread rule -------------------------------------------------------------------------------------------

    @Test
    fun `a trusted scene that advances or closes a thread an untrusted scene opened is untrusted, down the chain`() {
        val s1 = scene { chat() }
        val s2 = scene { add("$CHAT:s2", "查一下"); add("$CHAT:s2", "<html>page</html>", Role.TOOL, toolName = "web.view") }
        val s3 = scene { chat(3) }
        val s4 = scene { chat(4) }
        val s5 = scene { chat(5) }
        val m1 = members(s1.sceneId, "s1 周末计划 alpha")
        val m2 = members(s2.sceneId, "s2 网页上说要带伞 beta")
        val m3 = members(s3.sceneId, "s3 又提到带伞 gamma", "s3 新的约定 delta")
        val m4 = members(s4.sceneId, "s4 约定兑现 epsilon")
        members(s5.sceneId, "s5 无关的闲聊 zeta")
        val t1 = graph.putThread(ThreadRecord(0, CHAT, "带伞", openedSceneId = s2.sceneId))
        graph.linkThread(t1, m2[0], ThreadRole.SETUP)
        graph.linkThread(t1, m3[0], ThreadRole.SETUP) // s3 advances it
        graph.linkThread(t1, m1[0], ThreadRole.SETUP) // backfilled from an EARLIER scene: s1 unaffected
        val t2 = graph.putThread(ThreadRecord(0, CHAT, "新的约定", openedSceneId = s3.sceneId))
        graph.linkThread(t2, m3[1], ThreadRole.SETUP)
        graph.putThread(graph.thread(t2)!!.copy(state = ThreadState.CLOSED, closedSceneId = s4.sceneId)) // s4 closes it
        graph.linkThread(t2, m4[0], ThreadRole.RESOLVE)

        val result = trust().judgeChat(CHAT)

        assertTrue(result.verdicts.getValue(s1.sceneId).trusted)
        assertEquals(setOf(Cause.UNTRUSTED_TOOL), result.verdicts.getValue(s2.sceneId).causes)
        assertEquals(setOf(Cause.UNTRUSTED_THREAD), result.verdicts.getValue(s3.sceneId).causes)
        assertEquals(setOf(Cause.UNTRUSTED_THREAD), result.verdicts.getValue(s4.sceneId).causes)
        assertTrue(result.verdicts.getValue(s5.sceneId).trusted)
        assertFalse(result.failed)
    }

    @Test
    fun `a thread a trusted scene opened taints nobody, one of unknown origin taints every scene that touches it`() {
        val s1 = scene { chat() }
        val s2 = scene { chat(2) }
        val m1 = members(s1.sceneId, "s1 惊喜 alpha")
        val m2 = members(s2.sceneId, "s2 惊喜兑现 beta")
        val t = graph.putThread(ThreadRecord(0, CHAT, "惊喜", ThreadState.CLOSED, openedSceneId = s1.sceneId, closedSceneId = s2.sceneId))
        graph.linkThread(t, m1[0], ThreadRole.SETUP)
        graph.linkThread(t, m2[0], ThreadRole.RESOLVE)
        assertTrue(trust().judgeChat(CHAT).verdicts.values.all { it.trusted })

        val unknown = graph.putThread(ThreadRecord(0, CHAT, "来历不明", openedSceneId = 999))
        graph.linkThread(unknown, m1[0], ThreadRole.SETUP)
        graph.putThread(ThreadRecord(0, CHAT, "没有开场", ThreadState.CLOSED, openedSceneId = null, closedSceneId = s2.sceneId))
        val result = trust().judgeChat(CHAT)
        assertEquals(setOf(Cause.UNTRUSTED_THREAD), result.verdicts.getValue(s1.sceneId).causes)
        assertEquals(setOf(Cause.UNTRUSTED_THREAD), result.verdicts.getValue(s2.sceneId).causes)
    }

    // --- failures and the cache ------------------------------------------------------------------------------------

    @Test
    fun `a read failure is untrusted with a failure flag`() {
        val ok = scene { chat() }
        val broken = scene { chat(2) }
        source.failing += broken.conversationId

        val result = trust().judgeChat(CHAT)

        assertTrue(result.failed)
        assertTrue(result.verdicts.getValue(ok.sceneId).trusted)
        assertEquals(setOf(Cause.READ_FAILED), result.verdicts.getValue(broken.sceneId).causes)
        assertTrue(result.verdicts.getValue(broken.sceneId).readFailed)

        val noGraph = object : GraphAccess {
            override fun <T> withGraph(block: (LatticeGraph) -> T): T = throw IllegalStateException("the graph is closed")
        }
        val none = SceneTrust(noGraph, source, policy).judgeChat(CHAT)
        assertTrue(none.failed)
        assertTrue(none.verdicts.isEmpty())
    }

    @Test
    fun `a scene this host cannot read is untrusted, without failing the chat, and judged again later`() {
        val ok = scene { chat() }
        val foreign = scene { chat(2) }
        val messages = source.all(foreign.conversationId)
        source.forget(foreign.conversationId)
        val gone = scene { chat(3) }
        graph.putScene(gone.copy(firstMessageId = gone.lastMessageId + 1, lastMessageId = gone.lastMessageId + 50))
        val t = trust()

        val result = t.refresh(CHAT)

        assertFalse(result.failed, "another device's scene is not a failed read")
        assertTrue(result.verdicts.getValue(ok.sceneId).trusted)
        assertEquals(setOf(Cause.UNREADABLE), result.verdicts.getValue(foreign.sceneId).causes)
        assertEquals(setOf(Cause.UNREADABLE), result.verdicts.getValue(gone.sceneId).causes)
        assertEquals(false, graph.sceneTrust(foreign.sceneId))
        assertEquals(false, graph.sceneTrust(gone.sceneId))
        assertEquals(setOf(Cause.UNREADABLE), t.judgeMessages(CHAT, emptyList()))

        source.open(foreign.conversationId, CHAT)
        messages.forEach { source.add(foreign.conversationId, it.text, it.role) }
        assertTrue(t.refresh(CHAT).verdicts.getValue(foreign.sceneId).trusted, "an unreadable verdict is not kept")
        assertEquals(true, graph.sceneTrust(foreign.sceneId))
    }

    @Test
    fun `refresh caches the chat's ended scenes on the graph, not an open one`() {
        val trusted = scene { chat() }
        val untrusted = scene { add("$CHAT:s2", "<html>", Role.TOOL, toolName = "web.view") }
        val open = scene(open = true) { chat(3) }
        val other = scene(chat = OTHER_CHAT) { add("$OTHER_CHAT:s4", "hi") }

        val result = trust().refresh(CHAT)

        assertFalse(result.failed)
        assertEquals(true, graph.sceneTrust(trusted.sceneId))
        assertEquals(false, graph.sceneTrust(untrusted.sceneId))
        assertNull(graph.sceneTrust(open.sceneId), "an OPEN scene is still growing: not cached")
        assertNull(graph.sceneTrust(other.sceneId), "another chat's scene is not judged")
        assertTrue(result.verdicts.getValue(open.sceneId).trusted, "an open scene is still judged (it may open threads)")
    }

    @Test
    fun `an ended scene is read once, an open one every time, and a grown range reads again`() {
        val ended = scene { chat() }
        val open = scene(open = true) { chat(2) }
        val t = trust()
        val before = source.reads.get()
        t.judgeChat(CHAT)
        t.refresh(CHAT)
        assertEquals(3, source.reads.get() - before, "the ended scene once, the open one twice")

        graph.putScene(ended.copy(lastMessageId = ended.lastMessageId + 100))
        t.judgeChat(CHAT)
        assertEquals(5, source.reads.get() - before, "a scene whose range changed is read again")
        assertEquals(open.sceneId, graph.scenes(CHAT).last().sceneId)
    }

    private companion object {
        const val CHAT = "chat-1"
        const val OTHER_CHAT = "chat-2"
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
