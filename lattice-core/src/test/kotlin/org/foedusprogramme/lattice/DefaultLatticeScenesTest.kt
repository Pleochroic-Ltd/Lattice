package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.SceneWorkState
import org.foedusprogramme.lattice.core.TopicModel
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The façade with scenes wired: a capture with a conversation id advances the pipeline, a sealed conversation is
 * finished, the sleep catches up and waits for narration before periods, recall dates hits for scene episodes, close
 * drains the scene work, and without a source, with scenes off or without a model everything works as before.
 */
class DefaultLatticeScenesTest {

    /** The topic model of the scene tests, extracting one memory per said line. */
    private class SceneModel : MemoryModel {
        val topic = TopicModel()
        val extractions = CopyOnWriteArrayList<Pair<String, String>>()

        override fun complete(task: MemoryTask, system: String, user: String): String {
            if (task != MemoryTask.EXTRACT) return topic.complete(task, system, user)
            extractions += user to Thread.currentThread().name
            val lines = LINE.findAll(user).map { "The user talked about ${it.groupValues[1]} (message ${it.groupValues[2]})" }
            return LatticeTestKit.extraction(*lines.toList().toTypedArray())
        }

        companion object {
            val LINE = Regex("\\[(?:user|assistant)] topic=(\\w+) message (\\d+)")
        }
    }

    private val source = FakeConversationSource(start = Instant.parse("2026-07-30T09:00:00Z"))
    private val model = SceneModel()
    private val opened = ArrayList<DefaultLattice>()

    private val sceneSettings = SceneSettings(
        enabled = true, segmentBatchMessages = 6, segmentLookaheadMessages = 2, segmentWindowMessages = 40,
    )

    private fun open(
        scenes: SceneSettings = sceneSettings,
        conversations: ConversationSource? = source,
        model: MemoryModel? = this.model,
        temporalLayer: Boolean = true,
        formPeriods: SleepPass? = null,
    ): DefaultLattice = LatticeTestKit.open(
        settings = LatticeTestKit.settings().copy(scenes = scenes, temporalLayer = temporalLayer),
        model = model,
        conversations = conversations,
        graphConfig = LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99, temporalLayer = temporalLayer),
        formPeriods = formPeriods,
    ).also { opened += it }

    @AfterTest
    fun closeAll() = runBlocking { opened.forEach { it.close() } }

    private fun say(vararg topics: String, perTopic: Int = 4) {
        for (topic in topics) source.talk(CONV, "topic=$topic", perTopic)
    }

    private fun live(): Transcript = Transcript(emptyList(), Transcript.Source(CHAT, CONV))

    private suspend fun DefaultLattice.states(): List<Pair<String?, SceneWorkState?>> =
        withGraph { g -> g.scenes(CHAT).map { it.title to g.sceneWork(it.sceneId)?.state } }

    // --- capture -----------------------------------------------------------------------------------------------------

    @Test
    fun aCaptureWithAConversationIdAdvancesThePipelineInsteadOfExtracting() = runBlocking {
        source.open(CONV, CHAT)
        say("kyoto", "gradle")
        val lattice = open()

        assertEquals(0, lattice.capture(live()), "the pipeline extracts in the background")
        assertNotNull(lattice.scenes).awaitIdle()

        assertEquals(listOf("kyoto" to SceneWorkState.MATERIALIZED, "gradle" to SceneWorkState.OPEN), lattice.states())
        val kyoto = lattice.withGraph { g -> g.scenes(CHAT).first().let { s -> g.sceneMembers(s.sceneId).mapNotNull { g.engram(it)?.content } } }
        assertTrue(kyoto.isNotEmpty() && kyoto.all { "kyoto" in it }, "the scene's memories joined it: $kyoto")
        assertTrue(model.extractions.all { it.second == "lattice-test-capture" }, "extraction runs on the capture thread")
        assertTrue(model.topic.narrations.any { "kyoto" in it }, "the ended scene was narrated")
    }

    @Test
    fun aSealedConversationIsFinished() = runBlocking {
        source.open(CONV, CHAT)
        say("kyoto", perTopic = 3)
        val lattice = open()
        lattice.capture(live())
        lattice.scenes!!.awaitIdle()
        assertTrue(lattice.states().isEmpty(), "three messages are too few to segment a live conversation")

        source.seal(CONV)
        lattice.conversationSealed(CONV)
        lattice.scenes!!.awaitIdle()

        assertEquals(listOf("kyoto" to SceneWorkState.MATERIALIZED), lattice.states())
        assertTrue(lattice.withGraph { it.sceneCursor(CONV)!!.finished })
    }

    @Test
    fun theTaintOfASceneMessageReachesItsMemories() = runBlocking {
        source.open(CONV, CHAT)
        source.add(CONV, "topic=kyoto message 1")
        source.add(CONV, "a fetched page", role = ConversationMessage.Role.TOOL, toolName = "web.fetch", tainted = true)
        source.add(CONV, "topic=kyoto message 2", role = ConversationMessage.Role.ASSISTANT)
        source.seal(CONV)
        val lattice = open()
        lattice.conversationSealed(CONV)
        lattice.scenes!!.awaitIdle()

        val memories = lattice.withGraph { g -> g.engrams().filter { !it.isEpisodeHub && it.origin == Engram.Origin.EXTRACTED } }
        assertTrue(memories.isNotEmpty())
        assertTrue(memories.all { it.tainted }, "the chunk read a tainted tool result")
        assertTrue(memories.all { it.saidFrom != null && it.sourceMessageId != null })
    }

    // --- sleep -------------------------------------------------------------------------------------------------------

    @Test
    fun theSleepCatchesUpAndWaitsForNarrationBeforePeriods() = runBlocking {
        source.open(CONV, CHAT)
        say("kyoto", "gradle", perTopic = 4)
        source.seal(CONV)
        val seen = ArrayList<List<Pair<String?, SceneWorkState?>>>()
        val lattice = open(formPeriods = { l -> seen += l.withGraph { g -> g.scenes(CHAT).map { it.title to g.sceneWork(it.sceneId)?.state } }; 0 })

        val report = lattice.sleep(setOf(SleepPhase.CATCH_UP, SleepPhase.PERIODS))

        assertEquals(8, report.phases.first { it.phase == SleepPhase.CATCH_UP }.count, "messages extracted")
        assertNull(report.phases.first { it.phase == SleepPhase.CATCH_UP }.error)
        assertEquals<List<Pair<String?, SceneWorkState?>>>(
            listOf("kyoto" to SceneWorkState.MATERIALIZED, "gradle" to SceneWorkState.MATERIALIZED),
            seen.single(),
            "periods see every scene narrated",
        )
    }

    // --- recall ------------------------------------------------------------------------------------------------------

    @Test
    fun withSceneEpisodesRecallShowsWhenAMemoryWasSaid() = runBlocking {
        source.open(CONV, CHAT)
        say("kyoto", perTopic = 3)
        source.seal(CONV)
        val lattice = open(scenes = sceneSettings.copy(sceneEpisodes = true))
        lattice.conversationSealed(CONV)
        lattice.scenes!!.awaitIdle()

        val explicit = lattice.recall("kyoto", RecallOptions(explicit = true, chatKey = CHAT)).text
        assertTrue("said 2026-07-30" in explicit, explicit)
        val perTurn = lattice.recall("kyoto", RecallOptions(chatKey = CHAT)).text
        assertTrue(perTurn.isEmpty() || "2026-07-30 · " in perTurn, "a per-turn block also says how long ago: $perTurn")

        val legacy = open()
        legacy.withGraph { g -> g.remember("The user talked about kyoto", saidFrom = Instant.parse("2026-07-30T09:00:00Z")); Unit }
        assertFalse("said" in legacy.recall("kyoto", RecallOptions(explicit = true)).text, "no said tags without scene episodes")
    }

    // --- close -------------------------------------------------------------------------------------------------------

    @Test
    fun closeDrainsTheSceneWorkFirst() = runBlocking {
        source.open(CONV, CHAT)
        say("kyoto", "gradle", "curry", perTopic = 4)
        source.seal(CONV)
        val store = LatticeTestKit.FlushingStore()
        val lattice = LatticeTestKit.open(
            settings = LatticeTestKit.settings().copy(scenes = sceneSettings),
            store = store,
            model = model,
            conversations = source,
            graphConfig = LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99),
        )
        lattice.conversationSealed(CONV)
        lattice.close()

        for (topic in listOf("kyoto", "gradle", "curry")) {
            assertTrue(model.topic.narrations.any { "Title: $topic" in it }, "$topic was narrated before the threads stopped")
        }
        assertTrue(store.closed)
    }

    // --- without scenes ----------------------------------------------------------------------------------------------

    @Test
    fun withoutASourceWithScenesOffOrWithoutAModelCaptureWorksAsBefore() = runBlocking {
        source.open(CONV, CHAT)
        val transcript = Transcript(
            listOf(Transcript.Line(Transcript.Role.USER, "topic=kyoto message 1", messageId = 10)),
            Transcript.Source(CHAT, CONV),
        )

        for (lattice in listOf(open(conversations = null), open(scenes = sceneSettings.copy(enabled = false)))) {
            assertNull(lattice.scenes)
            assertEquals(1, lattice.capture(transcript), "extracted at once")
            lattice.conversationSealed(CONV)
            val report = lattice.sleep(setOf(SleepPhase.CATCH_UP))
            assertTrue(report.phases.single().skipped)
        }
        assertEquals(2, model.extractions.size)
        assertTrue(model.topic.segmentations.isEmpty())

        val noModel = open(model = null)
        assertNull(noModel.scenes)
        assertEquals(0, noModel.capture(transcript))
        assertEquals("There is no model.", noModel.sleep(setOf(SleepPhase.CATCH_UP)).phases.single().skipReason)
        assertEquals(
            "The host provides no conversations to read scenes from.",
            open(conversations = null).sleep(setOf(SleepPhase.CATCH_UP)).phases.single().skipReason,
        )
        assertEquals("Scenes are off.", open(scenes = SceneSettings()).sleep(setOf(SleepPhase.CATCH_UP)).phases.single().skipReason)
    }

    @Test
    fun aTranscriptWithoutAConversationIdIsExtractedEvenWithScenes() = runBlocking {
        val lattice = open()
        val transcript = Transcript(listOf(Transcript.Line(Transcript.Role.USER, "topic=kyoto message 1")))
        assertEquals(1, lattice.capture(transcript))
        assertEquals(1, model.extractions.size)
    }

    private companion object {
        const val CHAT = "chat-1"
        const val CONV = "chat-1:c1"
    }
}
