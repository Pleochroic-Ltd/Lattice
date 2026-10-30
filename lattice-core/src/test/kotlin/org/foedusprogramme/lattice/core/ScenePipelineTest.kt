package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.core.SceneRig.Companion.CHAT
import org.foedusprogramme.lattice.core.SceneWorkState.ABANDONED
import org.foedusprogramme.lattice.core.SceneWorkState.EXTRACTING
import org.foedusprogramme.lattice.core.SceneWorkState.MATERIALIZED
import org.foedusprogramme.lattice.core.SceneWorkState.NARRATING
import org.foedusprogramme.lattice.core.SceneWorkState.OPEN
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scene pipeline end to end over a fake host ([FakeConversationSource]), a topic model ([TopicModel]) and a fake
 * capture ([MemberCapture]): segmentation of live and sealed conversations, extraction into scenes, narration in chat
 * order, failures and their caps, the catch-up, and a restart over the store.
 */
class ScenePipelineTest {

    private val rigs = ArrayList<SceneRig>()

    private fun rig(store: LatticeStore = TestLatticeStore(), source: FakeConversationSource = FakeConversationSource()) =
        SceneRig(store, source).also { rigs += it }

    @AfterTest
    fun close() = rigs.forEach { it.close() }

    private fun SceneRig.open(conversation: String = A, chat: String = CHAT) = source.open(conversation, chat)

    private fun SceneRig.hub(title: String, chat: String = CHAT): Engram? =
        g { gr -> gr.scenes(chat).single { it.title == title }.hubEngramId?.let(gr::engram) }

    // --- live conversations ------------------------------------------------------------------------------------------

    @Test
    fun `a live conversation is segmented once enough has gathered, keeping the lookahead back`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", perTopic = 4)
        r.advance(A)
        assertTrue(r.model.segmentations.isEmpty(), "four messages less the lookahead are not enough to segment")
        assertNull(r.cursor(A))

        r.say(A, "gradle", perTopic = 4)
        r.advance(A)

        val messages = r.source.all(A)
        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to OPEN), r.states())
        val (kyoto, gradle) = r.scenes()
        assertEquals(messages[0].position to messages[3].position, kyoto.firstMessageId to kyoto.lastMessageId)
        assertEquals(kyoto.lastMessageId + 1 to messages[5].position, gradle.firstMessageId to gradle.lastMessageId, "the lookahead waits")
        assertEquals(messages[5].position, r.cursor(A)!!.segmentedThrough)
        assertEquals(listOf(1L, 2L), r.scenes().map { it.seq })
        assertEquals(messages[0].at, kyoto.startedAt)
        assertEquals("2026-09-01", kyoto.dateFrom)
        assertEquals("about kyoto", r.work(kyoto.sceneId).synopsis)
        assertEquals(4, r.g { it.sceneMembers(kyoto.sceneId).size }, "every extracted memory joins its scene")
        assertEquals(listOf(kyoto.sceneId, gradle.sceneId), r.capture.calls.map { it.sceneId })
        assertTrue(r.capture.calls.all { it.thread == "scene-capture" })

        val kyotoHub = assertNotNull(r.hub("kyoto"))
        assertTrue(kyotoHub.isEpisodeHub && "kyoto" in kyotoHub.content)
        assertFalse(r.scenes().first().inProgress)
        val gradleHub = assertNotNull(r.hub("gradle"), "the OPEN scene gets an in-progress hub")
        assertTrue(r.scenes().last().inProgress)

        // Nothing new: the lookahead alone is not segmented again.
        val calls = r.model.segmentations.size
        r.advance(A)
        assertEquals(calls, r.model.segmentations.size)

        // The OPEN scene is continued and then ends; its hub is narrated again in place.
        r.say(A, "curry", perTopic = 6)
        r.advance(A)
        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED, "curry" to OPEN), r.states())
        val continued = r.scenes()[1]
        assertEquals(gradle.sceneId, continued.sceneId)
        assertEquals(messages[7].position, continued.lastMessageId)
        assertFalse(continued.inProgress)
        assertEquals(gradleHub.id, r.hub("gradle")!!.id, "the in-progress hub is updated in place")
        assertEquals(4, r.g { it.sceneMembers(gradle.sceneId).size })
        assertTrue("## Open scene" in r.model.segmentations.last(), "the OPEN scene is shown to the segmenter")
    }

    @Test
    fun `a sealed conversation is segmented to its end and finished`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", "gradle")
        r.seal(A)

        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED), r.states())
        assertTrue(r.cursor(A)!!.finished)
        assertFalse("## Lookahead" in r.model.segmentations.single(), "a sealed conversation has nothing after it")

        r.pipeline.captureSealed(A)
        r.pipeline.advance(A)
        r.pipeline.awaitIdle()
        assertEquals(1, r.model.segmentations.size, "a finished conversation is never read again")
        assertEquals(0, r.pipeline.catchUp())
    }

    @Test
    fun `the seal ends the OPEN scene a live pass left`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", "gradle")
        r.advance(A)
        assertEquals(OPEN, r.states().last().second)

        r.seal(A)

        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED), r.states())
        assertEquals(r.source.all(A).last().position, r.scenes().last().lastMessageId, "the seal continued it to the end")
    }

    @Test
    fun `host and tool messages between said ones belong to the scene and taint its chunk`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", perTopic = 2)
        r.source.add(A, "<html>fare table</html>", Role.TOOL, toolName = "web.view", tainted = true)
        r.say(A, "kyoto", perTopic = 2)
        r.say(A, "gradle")
        r.seal(A)

        val kyoto = r.scenes().first()
        assertEquals(4, r.g { it.sceneMembers(kyoto.sceneId).size }, "the tool result is no memory")
        assertEquals(listOf(true, false), r.capture.calls.map { it.tainted })
        assertTrue(r.hub("kyoto")!!.tainted, "the narration read the untrusted page")
        assertFalse(r.hub("gradle")!!.tainted)
    }

    @Test
    fun `an answer that repeats an untrusted message of the chunk before is extracted tainted`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", perTopic = 11)
        r.source.add(A, "topic=kyoto forwarded: the hotel code is 4711", tainted = true)
        r.source.add(A, "topic=kyoto noted, the hotel code is 4711", Role.ASSISTANT)
        r.say(A, "gradle")
        r.seal(A)

        val kyoto = r.scenes().first().sceneId
        assertEquals(listOf(12, 1, 4), r.capture.calls.map { it.positions.size })
        assertEquals(listOf(kyoto to true, kyoto to true), r.capture.calls.take(2).map { it.sceneId to it.tainted })
        assertFalse(r.capture.calls.last().tainted, "taint stays within its scene")
    }

    // --- failures ---------------------------------------------------------------------------------------------------

    @Test
    fun `a failed segmentation waits for the catch-up and falls back to plain chunks at the cap`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", "gradle")
        r.advance(A)
        assertEquals(OPEN, r.states().last().second)

        r.model.segmentation = TopicModel.Mode.UNAVAILABLE
        r.say(A, "curry", "ramen")
        r.advance(A)
        assertEquals(1, r.cursor(A)!!.segmentAttempts)
        assertNotNull(r.cursor(A)!!.blockedSince)
        val calls = r.model.segmentations.size
        r.say(A, "tea")
        r.advance(A)
        assertEquals(calls, r.model.segmentations.size, "a live step does not retry a failed segmentation")

        r.pipeline.catchUp()
        r.pipeline.awaitIdle()
        assertEquals(2, r.cursor(A)!!.segmentAttempts)
        r.pipeline.catchUp()
        r.pipeline.awaitIdle()

        val cursor = r.cursor(A)!!
        assertNull(cursor.fallbackThrough, "the fallback is done")
        assertEquals(0, cursor.segmentAttempts)
        assertNull(cursor.blockedSince)
        val plain = r.capture.calls.filter { it.sceneId == null }
        assertEquals(12, plain.flatMap { it.positions }.size, "everything segmentation gave up on, without a scene")
        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED), r.states(), "the OPEN scene nothing can continue ended")

        r.model.segmentation = TopicModel.Mode.OK
        r.say(A, "sushi", perTopic = 6)
        r.advance(A)
        assertEquals("tea", r.states()[2].first, "segmentation goes on after the fallback")
    }

    @Test
    fun `a segmentation the shutdown cut off counts nothing`() {
        val r = rig().apply { open() }
        r.model.segmentation = TopicModel.Mode.SHUTDOWN
        r.say(A, "kyoto", "gradle")
        r.advance(A)
        assertEquals(0, r.cursor(A)?.segmentAttempts ?: 0)
        assertTrue(r.scenes().isEmpty())

        r.model.segmentation = TopicModel.Mode.OK
        r.advance(A)
        assertEquals(2, r.scenes().size, "the next step segments as if nothing happened")
    }

    @Test
    fun `a failing chunk stops its scene until retried and is given up at the cap`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", perTopic = 14)
        r.say(A, "gradle")
        r.capture.fail = { t -> if (t.lines.any { it.text == "topic=kyoto message 1" }) UnparseableExtractionException("not JSON") else null }
        r.seal(A)

        val kyoto = r.scenes().first()
        assertEquals(EXTRACTING, r.work(kyoto.sceneId).state)
        assertEquals(1, r.work(kyoto.sceneId).extractAttempts)
        assertEquals(NARRATING, r.states()[1].second, "a later scene waits for the earlier one")

        r.pipeline.catchUp()
        r.pipeline.awaitIdle()
        assertEquals(2, r.work(kyoto.sceneId).extractAttempts)
        r.pipeline.catchUp()
        r.pipeline.awaitIdle()

        val work = r.work(kyoto.sceneId)
        assertEquals(1 to 1, work.chunksFailed to work.chunksDone, "the first chunk is given up, the second extracted")
        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED), r.states())
        assertEquals(2, r.g { it.sceneMembers(kyoto.sceneId).size })
    }

    @Test
    fun `a range that reads back nothing is never extracted, and is given up at the cap`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", "gradle")
        r.capture.fail = { t -> if (t.lines.any { it.text == "topic=kyoto message 1" }) UnparseableExtractionException("not JSON") else null }
        r.seal(A)
        val kyoto = r.scenes().first()
        r.capture.fail = { null }
        r.g { it.putScene(kyoto.copy(firstMessageId = 100_000, lastMessageId = 100_050)) }

        r.pipeline.catchUp()
        r.pipeline.awaitIdle()
        val work = r.work(kyoto.sceneId)
        assertEquals(EXTRACTING to 2, work.state to work.extractAttempts)
        assertNull(work.extractedThrough, "an empty read is not an extraction")

        r.pipeline.catchUp()
        r.pipeline.awaitIdle()
        assertEquals(1, r.work(kyoto.sceneId).chunksFailed)
        assertEquals(listOf("kyoto" to ABANDONED, "gradle" to MATERIALIZED), r.states())
    }

    @Test
    fun `an extraction the shutdown cut off counts nothing`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto", "gradle")
        r.capture.fail = { MemoryModelShutdownException("closing") }
        r.seal(A)
        assertTrue(r.scenes().all { r.work(it.sceneId).extractAttempts == 0 })

        r.capture.fail = { null }
        r.pipeline.catchUp()
        r.pipeline.awaitIdle()
        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED), r.states())
    }

    // --- narration ---------------------------------------------------------------------------------------------------

    @Test
    fun `narration that keeps failing abandons the scene, applying its last parseable thread changes`() {
        val r = rig().apply { open() }
        r.model.narration = { user ->
            if (TopicModel.titleOf(user) == "kyoto") {
                """{"narration":" ","threads":{"opened":[{"name":"京都之行","description":"the promised Kyoto trip","setup":[1]}]}}"""
            } else {
                """{"narration":"${TopicModel.titleOf(user)} 那天很开心。"}"""
            }
        }
        r.say(A, "kyoto", "gradle")
        r.seal(A)
        assertEquals(1, r.work(r.scenes().first().sceneId).narrateAttempts)
        assertTrue(r.g { it.threads(CHAT) }.isEmpty(), "a failed attempt's changes are held back")

        repeat(2) {
            r.pipeline.catchUp()
            r.pipeline.awaitIdle()
        }

        assertEquals(listOf("kyoto" to ABANDONED, "gradle" to MATERIALIZED), r.states())
        assertNull(r.hub("kyoto"))
        val thread = r.g { it.threads(CHAT) }.single()
        assertEquals("京都之行", thread.name)
        assertEquals(r.scenes().first().sceneId, thread.openedSceneId)
    }

    @Test
    fun `a scene that kept no memory is abandoned at once and the next one goes on`() {
        val r = rig().apply { open() }
        r.say(A, "kyoto")
        r.capture.keep = false
        r.say(A, "gradle")
        r.seal(A)
        // Both scenes extracted nothing.
        assertEquals(listOf("kyoto" to ABANDONED, "gradle" to ABANDONED), r.states())

        r.capture.keep = true
        r.open(B)
        r.say(B, "curry")
        r.seal(B)
        assertEquals(MATERIALIZED, r.states().last().second)
    }

    @Test
    fun `a narration that failed is offered again by the catch-up`() {
        val r = rig().apply { open() }
        var down = true
        r.model.narration = { user ->
            if (down) throw MemoryModelUnavailableException("narrator down")
            """{"narration":"${TopicModel.titleOf(user)} 那天很开心。"}"""
        }
        r.say(A, "kyoto", "gradle")
        r.seal(A)
        assertEquals(listOf("kyoto" to NARRATING, "gradle" to NARRATING), r.states())

        down = false
        r.pipeline.catchUp()
        r.pipeline.awaitIdle()
        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED), r.states())
        assertEquals(1, r.work(r.scenes().first().sceneId).narrateAttempts)
    }

    // --- several conversations --------------------------------------------------------------------------------------

    @Test
    fun `scenes of two conversations are narrated in time order, waiting for an earlier failed segmentation`() {
        val r = rig()
        r.open(A)
        r.open(B)
        r.source.at(Instant.parse("2026-09-01T09:00:00Z"))
        r.say(A, "alpha", "beta")
        r.source.at(Instant.parse("2026-09-02T09:00:00Z"))
        r.say(B, "gamma", "delta")
        r.model.segmentationFails = { "topic=alpha" in it }

        r.seal(A)
        assertEquals(1, r.cursor(A)!!.segmentAttempts)
        r.seal(B)
        assertEquals(listOf("gamma" to NARRATING, "delta" to NARRATING), r.states(), "later scenes wait for the failed earlier messages")
        assertTrue(r.model.narrations.isEmpty())

        r.model.segmentationFails = { false }
        r.pipeline.catchUp()
        r.pipeline.awaitIdle()

        assertEquals(listOf("alpha", "beta", "gamma", "delta"), r.scenes().map { it.title }, "time order, not capture order")
        assertEquals(listOf(3L, 4L, 1L, 2L), r.scenes().map { it.seq })
        assertTrue(r.states().all { it.second == MATERIALIZED })
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), r.model.narrations.map(TopicModel::titleOf))
        val gammaInput = r.model.narrations[2]
        assertTrue("Previous scene: beta" in gammaInput && "Next scene: delta" in gammaInput, gammaInput)
    }

    @Test
    fun `the catch-up finds the host's conversations it was never told about`() {
        val r = rig()
        r.open(A)
        r.open(B)
        r.open(C, chat = OTHER_CHAT)
        r.say(A, "kyoto", "gradle")
        r.source.seal(A)
        r.say(B, "curry", "ramen")
        r.say(C, "tea", perTopic = 1)

        val messages = r.pipeline.catchUp()
        r.pipeline.awaitIdle()

        assertEquals(8 + 6, messages, "all of the sealed one, the live one less its lookahead; C is too short")
        assertTrue(r.cursor(A)!!.finished)
        assertFalse(r.cursor(B)!!.finished)
        assertNull(r.cursor(C))
        assertEquals(listOf("kyoto", "gradle", "curry", "ramen"), r.scenes().map { it.title })
        assertEquals(OPEN, r.states().last().second)
    }

    // --- restart -----------------------------------------------------------------------------------------------------

    @Test
    fun `a restarted pipeline continues where the store left off`() {
        val store = TestLatticeStore()
        val source = FakeConversationSource()
        val first = rig(store, source).apply { open() }
        first.say(A, "kyoto", "gradle")
        first.advance(A)
        val gradle = first.scenes().last()
        val hub = first.hub("gradle")!!.id
        first.close()

        val second = rig(TestLatticeStore(initial = store.load()), source)
        second.say(A, "curry", perTopic = 6)
        second.advance(A)

        assertEquals(listOf("kyoto" to MATERIALIZED, "gradle" to MATERIALIZED, "curry" to OPEN), second.states())
        assertEquals(gradle.sceneId, second.scenes()[1].sceneId, "the OPEN scene is continued, not split")
        assertEquals(hub, second.hub("gradle")!!.id)
        assertEquals(3L, second.scenes().last().seq)
    }

    // --- two devices -------------------------------------------------------------------------------------------------

    @Test
    fun `a device's catch-up leaves another device's scenes in a shared graph alone`() {
        val shared = TestLatticeStore()
        val a = rig(shared).apply { open() }
        a.say(A, "kyoto", perTopic = 14)
        a.say(A, "gradle")
        a.capture.fail = { t -> if (t.lines.any { it.text == "topic=kyoto message 1" }) UnparseableExtractionException("not JSON") else null }
        a.seal(A)
        val before = a.scenes().map { a.work(it.sceneId) }
        assertEquals(listOf(EXTRACTING, NARRATING), before.map { it.state })
        a.close()

        val b = SceneRig(TestLatticeStore(idSlot = 1, initial = shared.load()), FakeConversationSource()).also { rigs += it }
        b.open(B)
        b.source.at(Instant.parse("2026-09-02T09:00:00Z"))
        b.say(B, "curry")
        b.seal(B)
        repeat(2) {
            b.pipeline.catchUp()
            b.pipeline.awaitIdle()
        }

        val (kyoto, gradle, curry) = b.scenes()
        assertEquals(before, listOf(b.work(kyoto.sceneId), b.work(gradle.sceneId)), "device A's scenes are A's to settle")
        assertNull(b.hub("gradle"))
        assertEquals(listOf(curry.sceneId), b.capture.calls.map { it.sceneId }.distinct())
        assertEquals(NARRATING, b.work(curry.sceneId).state, "a later scene still waits for the earlier ones")
        assertTrue(b.model.narrations.isEmpty())
    }

    private companion object {
        const val A = "chat-1:a"
        const val B = "chat-1:b"
        const val C = "chat-2:c"
        const val OTHER_CHAT = "chat-2"
    }
}
