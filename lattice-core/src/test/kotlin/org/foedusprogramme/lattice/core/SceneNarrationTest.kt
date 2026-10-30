package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.SceneSettings
import org.foedusprogramme.lattice.core.SceneRig.Companion.CHAT
import org.foedusprogramme.lattice.core.SceneWorkState.ABANDONED
import org.foedusprogramme.lattice.core.SceneWorkState.EXTRACTING
import org.foedusprogramme.lattice.core.SceneWorkState.MATERIALIZED
import org.foedusprogramme.lattice.core.SceneWorkState.NARRATING
import org.foedusprogramme.lattice.core.SceneWorkState.OPEN
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scene narration ([SceneHubNarration]) offered scenes directly ([NarrationRig]): hubs and their edges, the order a
 * chat's scenes are narrated in, attempt caps, the shutdown, the self-check mode, in-progress hubs and the thread
 * ledger hand-off. One case runs the whole pipeline to check the scene's local dates.
 */
class SceneNarrationTest {

    private fun selfCheck(model: ScriptedModel, verdict: () -> String) {
        model.reply = { _, system, user -> if (system == SceneNarrator.SELF_CHECK_PROMPT) verdict() else ScriptedModel.defaultReply(user) }
    }

    // --- the hub --------------------------------------------------------------------------------------------------

    @Test
    fun `three members - a hub with EPISODE_OF to each, the scene dated in the graph's zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        SceneRig(zone = tokyo, settings = SceneSettings(enabled = true, segmentLookaheadMessages = 2)).use { r ->
            // 14:58Z is 23:58 in Tokyo on the 20th; the third message (15:00Z) is midnight of the 21st there.
            r.source.open(A, CHAT)
            r.source.at(Instant.parse("2026-09-20T14:58:00Z"))
            r.say(A, "tongli", perTopic = 3)
            r.seal(A)

            val scene = r.scenes().single()
            assertEquals(MATERIALIZED, r.work(scene.sceneId).state)
            assertEquals("2026-09-20" to "2026-09-21", scene.dateFrom to scene.dateTo, "dates are local to the zone, not UTC")
            assertFalse(scene.inProgress)
            val hub = assertNotNull(r.g { g -> scene.hubEngramId?.let(g::engram) })
            assertTrue(hub.isEpisodeHub)
            assertEquals(Engram.Origin.HUB, hub.origin)
            assertEquals(Engram.Layer.SEMANTIC, hub.layer)
            assertEquals("tongli 那天的经历，大家都很开心。", hub.content)
            assertEquals("2026-09-20", hub.eventDate, "the hub's event date is the scene's local start date")
            assertEquals(scene.lastMessageId, hub.sourceMessageId)
            r.g { g ->
                val members = g.sceneMembers(scene.sceneId)
                assertEquals(3, members.size)
                assertEquals(members, g.episodeMembers(hub.id).toSet())
                for (m in members) {
                    val edge = g.neighbors(hub.id).single { it.to == m }
                    assertEquals(Edge.Type.EPISODE_OF, edge.type)
                    assertEquals(0.85, edge.weight, 1e-9)
                }
            }
        }
    }

    @Test
    fun `one member is enough, zero members abandons and the next scene still narrates`() {
        val r = NarrationRig()
        val empty = r.scene("空场景", emptyList())
        val one = r.scene("单成员", listOf("solo|x|y|1"))

        r.ready(empty)

        assertEquals(ABANDONED, r.state(empty))
        assertNull(r.hubOf(empty))
        assertEquals(MATERIALIZED, r.state(one), "the drain narrates the next scene right away")
        assertEquals(1, r.access.withGraph { it.episodeMembers(r.hubOf(one)!!.id).size })
    }

    @Test
    fun `an already materialized scene offered again is a no-op`() {
        val r = NarrationRig()
        val s = r.scene("重复", listOf("dup|a|a|1"))
        r.ready(s)
        r.ready(s)
        assertEquals(1, r.model.narrationCalls.size)
        assertEquals(1, r.hubs().size)
    }

    // --- order ----------------------------------------------------------------------------------------------------

    @Test
    fun `a scene waits while an earlier scene is not narrated, and runs once that scene is abandoned`() {
        val r = NarrationRig()
        val first = r.scene("第一幕", listOf("one|a|a|1"), state = EXTRACTING)
        val second = r.scene("第二幕", listOf("two|a|a|1"))

        r.ready(second)
        assertEquals(NARRATING, r.state(second), "held back by the earlier scene")
        assertTrue(r.model.calls.isEmpty())

        r.setState(first, NARRATING)
        r.model.reply = { _, _, user -> if ("Title: 第一幕" in user) "not json" else ScriptedModel.defaultReply(user) }
        repeat(3) { r.ready(first) }

        assertEquals(ABANDONED, r.state(first))
        assertEquals(MATERIALIZED, r.state(second), "abandoning the earlier scene drains the chat")
    }

    @Test
    fun `earlier means earlier in time even when the capture sequence says otherwise`() {
        val r = NarrationRig()
        val late = r.scene("后来", listOf("late|a|a|1"), at = Instant.parse("2026-09-02T00:00:00Z"))
        // Written second (a later capture sequence), but its messages are a day earlier, in another conversation.
        val early = r.scene("早先", listOf("early|a|a|1"), at = Instant.parse("2026-09-01T00:00:00Z"), conversation = "chat-1:s0")
        assertTrue(early.seq > late.seq, "precondition: the capture sequence is out of time order")

        r.ready(late)
        assertEquals(NARRATING, r.state(late), "the scene earlier in time goes first")

        r.ready(early)
        assertEquals(MATERIALIZED, r.state(early))
        assertEquals(MATERIALIZED, r.state(late), "drained right after the earlier one finished")
        assertEquals(listOf("早先", "后来"), r.model.narrationCalls.map(ScriptedModel::titleOf))
    }

    @Test
    fun `a scene waits for earlier messages of another conversation that failed to segment`() {
        val r = NarrationRig()
        val scene = r.scene("新会话", listOf("next|a|a|1"), at = Instant.parse("2026-09-02T00:00:00Z"), conversation = "chat-1:s2")
        val blocked = SceneCursorRecord("chat-1:s1", CHAT, segmentAttempts = 1, blockedSince = Instant.parse("2026-09-01T00:00:00Z"))
        r.access.withGraph { it.putSceneState(cursor = blocked) }
        // Another chat's blocked conversation, or one blocked after the scene started, holds nothing back.
        r.access.withGraph { it.putSceneState(cursor = blocked.copy(conversationId = "chat-9:s1", chatKey = "chat-9")) }

        r.ready(scene)
        assertEquals(NARRATING, r.state(scene), "held back by the earlier conversation's unsegmented messages")
        assertTrue(r.model.calls.isEmpty())

        r.access.withGraph { it.putSceneState(cursor = blocked.copy(blockedSince = Instant.parse("2026-09-03T00:00:00Z"))) }
        r.ready(scene)
        assertEquals(MATERIALIZED, r.state(scene))
    }

    @Test
    fun `finishing a scene drains every held-back scene of the chat in order`() {
        val r = NarrationRig()
        val a = r.scene("甲", listOf("a|a|a|1"))
        val b = r.scene("乙", listOf("b|b|b|1"))
        val c = r.scene("丙", listOf("c|c|c|1"))
        r.model.reply = { _, _, _ -> throw MemoryModelUnavailableException("down") }
        r.ready(b)
        r.ready(c)
        assertTrue(r.model.calls.isEmpty(), "both held back by 甲")

        r.model.reply = { _, _, user -> ScriptedModel.defaultReply(user) }
        r.ready(a)

        assertEquals(listOf(MATERIALIZED, MATERIALIZED, MATERIALIZED), listOf(a, b, c).map(r::state))
        assertEquals(listOf("甲", "乙", "丙"), r.model.narrationCalls.map(ScriptedModel::titleOf))
    }

    @Test
    fun `anchors mode shows the neighbouring scene titles in scene order`() {
        val r = NarrationRig()
        val a = r.scene("苏州早晨", listOf("a|a|a|1"))
        val b = r.scene("同里古镇", listOf("b|b|b|1"))
        r.scene("回上海", listOf("c|c|c|1"), state = EXTRACTING)
        r.ready(a)
        val user = r.model.narrationCalls.single { "Title: 同里古镇" in it }
        assertTrue("Previous scene: 苏州早晨" in user, user)
        assertTrue("Next scene: 回上海" in user, user)
        assertTrue("Never write their facts" in user, user)
        assertEquals(MATERIALIZED, r.state(b))
    }

    // --- failures ---------------------------------------------------------------------------------------------------

    @Test
    fun `blank, over-long and verbatim narrations count attempts and abandon at the third`() {
        val r = NarrationRig()
        val s = r.scene("失败", listOf("fail|a|a|1", "fail|b|b|2"))
        val next = r.scene("之后", listOf("next|a|a|1"))
        val replies = ArrayDeque(
            listOf(
                """{"narration":""}""",
                """{"narration":"${"长".repeat(EpisodeNarrator.EPISODE_NARRATION_MAX + 1)}"}""",
                """{"narration":"fail|a|a|1"}""",
            ),
        )
        r.model.reply = { _, _, user -> if ("Title: 失败" in user) replies.removeFirst() else ScriptedModel.defaultReply(user) }

        r.ready(s)
        assertEquals(NARRATING, r.state(s))
        assertEquals(1, r.work(s).narrateAttempts)
        assertNull(r.hubOf(s))
        r.ready(s)
        assertEquals(2, r.work(s).narrateAttempts)
        assertEquals(NARRATING, r.state(next), "held back while the earlier scene retries")

        r.ready(s)
        assertEquals(ABANDONED, r.state(s))
        assertNull(r.hubOf(s))
        assertEquals(MATERIALIZED, r.state(next))
    }

    @Test
    fun `a narration or self-check the shutdown cut off counts no attempt`() {
        val r = NarrationRig(mode = SceneNarrator.Mode.SELF_CHECK)
        val s = r.scene("关机", listOf("off|a|a|1", "off|b|b|2"))

        r.model.reply = { _, _, _ -> throw MemoryModelShutdownException("cut off by the shutdown") }
        r.ready(s)
        assertEquals(NARRATING, r.state(s))
        assertEquals(0, r.work(s).narrateAttempts, "a killed narration is not an attempt")

        r.model.reply = { _, system, user ->
            if (system == SceneNarrator.SELF_CHECK_PROMPT) throw MemoryModelShutdownException("not started: shutting down")
            ScriptedModel.defaultReply(user)
        }
        repeat(3) { r.ready(s) }
        assertEquals(NARRATING, r.state(s), "three killed self-checks never abandon the scene")
        assertEquals(0, r.work(s).narrateAttempts)
        assertNull(r.hubOf(s))

        selfCheck(r.model) { """{"faithful":true}""" }
        r.ready(s)
        assertEquals(MATERIALIZED, r.state(s))
    }

    @Test
    fun `self-check mode - a veto is a failure, a pass materializes`() {
        val r = NarrationRig(mode = SceneNarrator.Mode.SELF_CHECK)
        val s = r.scene("自检", listOf("check|a|a|1"))
        selfCheck(r.model) { """{"faithful":false,"issue":"wrong town"}""" }
        r.ready(s)
        assertEquals(NARRATING, r.state(s))
        assertEquals(1, r.work(s).narrateAttempts)
        assertNull(r.hubOf(s))

        selfCheck(r.model) { """{"faithful":true}""" }
        r.ready(s)
        assertEquals(MATERIALIZED, r.state(s))
        assertEquals(4, r.model.calls.size, "two narrations and two self-checks")
    }

    @Test
    fun `the self-check setting turns the self-check on in the pipeline`() {
        SceneRig(settings = SceneSettings(enabled = true, narrationCheck = "selfCheck", segmentLookaheadMessages = 2)).use { r ->
            r.model.selfCheck = { """{"faithful":false,"issue":"wrong town"}""" }
            r.source.open(A, CHAT)
            r.say(A, "kyoto")
            r.seal(A)
            val scene = r.scenes().single()
            assertEquals(NARRATING, r.work(scene.sceneId).state)
            assertEquals(1, r.work(scene.sceneId).narrateAttempts, "the veto counts")

            r.model.selfCheck = { """{"faithful":true}""" }
            r.pipeline.catchUp()
            r.pipeline.awaitIdle()
            assertEquals(MATERIALIZED, r.work(scene.sceneId).state)
            assertEquals(2, r.model.selfChecks.size)
        }
    }

    // --- in-progress hubs -------------------------------------------------------------------------------------------

    @Test
    fun `an OPEN scene gets an in-progress hub that its continuation updates in place`() {
        val r = NarrationRig()
        val open = r.scene("长途旅行", listOf("trip|a|a|1", "trip|b|b|2"), state = OPEN)

        r.ready(open)

        assertEquals(OPEN, r.state(open), "an in-progress hub does not end the scene")
        val first = assertNotNull(r.hubOf(open))
        assertTrue(r.access.withGraph { it.scene(open.sceneId)!!.inProgress })
        val firstEmbedding = first.embedding

        // The scene is continued by one more message and one more member, then ends.
        r.source.at(Instant.parse("2026-09-22T00:00:00Z"))
        val more = r.source.add(NarrationRig.CONV, "长途旅行 continues")
        r.access.withGraph { g ->
            g.putSceneState(
                g.scene(open.sceneId)!!.copy(lastMessageId = more.position, dateTo = "2026-09-22", inProgress = false),
                g.sceneWork(open.sceneId)!!.copy(state = NARRATING),
            )
            g.addSceneMembers(open.sceneId, listOf(g.remember("trip|c|c|3", layer = Engram.Layer.SEMANTIC)))
        }
        r.model.reply = { _, _, _ -> """{"narration":"长途旅行终于结束，所有人平安到家。"}""" }

        r.ready(open)

        assertEquals(MATERIALIZED, r.state(open))
        val hub = r.hubs().single()
        assertEquals(first.id, hub.id, "the same hub, updated in place")
        assertEquals("长途旅行终于结束，所有人平安到家。", hub.content)
        assertFalse(hub.embedding.contentEquals(firstEmbedding), "re-embedded")
        val record = r.access.withGraph { it.scene(open.sceneId)!! }
        assertFalse(record.inProgress, "in progress no more once the scene ended")
        assertEquals(more.position, record.lastMessageId)
        assertEquals("2026-09-22", record.dateTo)
        assertEquals(3, r.access.withGraph { it.episodeMembers(hub.id).size }, "the new member is bound too")
    }

    // --- the thread ledger ------------------------------------------------------------------------------------------

    @Test
    fun `ledger changes are applied with the hub`() {
        val r = NarrationRig()
        val s = r.scene("惊喜", listOf("gift|a|a|1", "gift|b|b|2"))
        r.model.reply = { _, _, _ ->
            """{"narration":"约定了周末的惊喜。","threads":{"opened":[{"name":"周末的惊喜","description":"周末的那份惊喜","setup":[2]}]}}"""
        }

        r.ready(s)

        assertEquals(MATERIALIZED, r.state(s))
        val thread = r.access.withGraph { it.threads(CHAT).single() }
        assertEquals(s.sceneId, thread.openedSceneId)
        assertEquals("周末的惊喜", thread.name)
        val setups = r.access.withGraph { g -> g.threadLinks(thread.id).map { g.engram(it.engramId)!!.content } }
        assertEquals(listOf("gift|b|b|2"), setups, "ref [2] maps back to the second member")
    }

    private companion object {
        const val A = "chat-1:a"
    }
}
