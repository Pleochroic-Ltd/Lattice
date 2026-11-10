package org.foedusprogramme.lattice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.IngestKit.Message
import org.foedusprogramme.lattice.IngestKit.SimModel
import org.foedusprogramme.lattice.IngestKit.SlowEmbedder
import org.foedusprogramme.lattice.IngestKit.talk
import org.foedusprogramme.lattice.core.DeadLetterEntry
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.MemoryDeadLetter
import org.foedusprogramme.lattice.core.MemoryModelInterruptedException
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.SceneWorkState
import org.foedusprogramme.lattice.core.TestLatticeStore
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Ordered apply ([LatticeSettings.extractParallelism]): the extraction calls of a conversation's chunks run ahead, many
 * at once, and the graph still ends up exactly as a serial capture leaves it — on success, when chunks fail, and when a
 * close cuts the calls off.
 */
class DefaultLatticeOrderedApplyTest {

    private val opened = ArrayList<DefaultLattice>()

    @AfterTest
    fun closeAll() = runBlocking { opened.forEach { runCatching { it.close() } } }

    private class Run(
        val lattice: DefaultLattice,
        val model: SimModel,
        val embedder: SlowEmbedder,
        val source: FakeConversationSource,
        val deadLetters: List<DeadLetterEntry>,
    ) {
        val driver = IngestKit.Driver(lattice, source, embedder, gateNarration = true)

        suspend fun snapshot(): String = lattice.withGraph(IngestKit::snapshot)

        /** [block], then the capture side's work, with narration held until both are done; then narration. */
        suspend fun gated(block: suspend () -> Unit) = kotlinx.coroutines.coroutineScope {
            val gate = CountDownLatch(1)
            val held = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { lattice.onNarration { gate.await() } }
            block()
            lattice.onCapture { }
            gate.countDown()
            held.await()
            runInterruptible(Dispatchers.IO) { lattice.scenes!!.awaitIdle() }
        }

        suspend fun catchUp() = gated { runInterruptible(Dispatchers.IO) { lattice.scenes!!.catchUp() } }
    }

    private fun open(
        parallelism: Int,
        model: SimModel = SimModel(jitter = 6),
        store: LatticeStore = TestLatticeStore(),
        shutdownTimeout: kotlin.time.Duration = 5.seconds,
        source: FakeConversationSource = FakeConversationSource(start = Instant.parse("2026-07-01T09:00:00Z")),
    ): Run {
        val deadLetters = CopyOnWriteArrayList<DeadLetterEntry>()
        val embedder = SlowEmbedder()
        val lattice = LatticeTestKit.open(
            settings = LatticeTestKit.settings(shutdownTimeout = shutdownTimeout).copy(extractParallelism = parallelism, scenes = SCENES),
            store = store,
            embedder = embedder,
            model = model,
            deadLetter = MemoryDeadLetter { deadLetters += it },
            conversations = source,
        ).also { opened += it }
        lattice.scenes!!.awaitIdle()
        return Run(lattice, model, embedder, source, deadLetters)
    }

    // --- equivalence ---------------------------------------------------------------------------------------------

    /** Four sessions: live batches that continue an open scene, scenes of several chunks, a tainted tool result, re-mentions. */
    private val sessions: List<Pair<String, List<Message>>> = listOf(
        "s1" to talk("kyoto", 30) + talk("gradle", 14),
        "s2" to talk("tea", 8) + talk("rain", 10) + Message(Role.TOOL, "a fetched page", tainted = true) +
            talk("rain", 20, from = 11) + talk("piano", 6),
        "s3" to talk("harbour", 40),
        "s4" to talk("kyoto", 12, from = 31) + talk("museum", 20),
    )

    private suspend fun ingestAll(run: Run) {
        for ((i, s) in sessions.withIndex()) {
            run.source.at(Instant.parse("2026-07-01T09:00:00Z").plusSeconds(86_400L * i))
            run.driver.session(s.first, s.second)
        }
    }

    @Test
    fun aConversationIngestedSixteenAtATimeEndsUpAsTheSerialOne() = runBlocking {
        val serial = open(parallelism = 1)
        ingestAll(serial)
        val ahead = open(parallelism = 16)
        ingestAll(ahead)

        val expected = serial.snapshot()
        assertEquals(expected, ahead.snapshot(), "the same engrams, edges, scenes, cursors, threads and periods, ids included")
        assertTrue(expected.lines().count { "origin=EXTRACTED" in it } > 50, "the conversation was extracted:\n$expected")
        assertTrue(ahead.lattice.withGraph { g -> g.engrams().any { it.tainted } && g.threads().isNotEmpty() })
        assertEquals(serial.model.calls(MemoryTask.EXTRACT), ahead.model.calls(MemoryTask.EXTRACT), "no call was made in vain")
        assertEquals(serial.model.callMix(), ahead.model.callMix())

        assertEquals(1, serial.model.maxExtractInFlight.get())
        assertTrue(ahead.model.maxExtractInFlight.get() in 2..16, "calls ran at once: ${ahead.model.maxExtractInFlight}")
        assertTrue(ahead.model.extractThreads.all { "-extract" in it }, "${ahead.model.extractThreads}")
        assertTrue(serial.model.extractThreads.all { it.endsWith("-capture") }, "${serial.model.extractThreads}")

        // The memories and quotes were embedded ahead, off the graph thread; serially the graph thread embedded them.
        fun graphEmbedded(run: Run) = run.embedder.embedded.filter { (thread, text) -> thread.endsWith("-graph") && extracted(text) }
        assertTrue(graphEmbedded(serial).isNotEmpty())
        assertEquals(emptyList(), graphEmbedded(ahead))
    }

    private fun extracted(text: String) = text.startsWith("About ") || text.startsWith("I said in message")

    // --- failures --------------------------------------------------------------------------------------------------

    @Test
    fun aFailingChunkStopsItsSceneAndNothingAfterItLandsOrIsDeadLettered() = runBlocking {
        // Chunks of twelve said messages: kyoto's second (messages 13-24) and third (25-36) do not parse.
        val broken = Regex("topic=kyoto message (13|25)\\b")
        fun failing(model: SimModel) = model.apply { extract = { user -> if (broken.containsMatchIn(user)) "not JSON" else IngestKit.extraction(user) } }
        val runs = listOf(open(parallelism = 1, model = failing(SimModel(jitter = 4))), open(parallelism = 16, model = failing(SimModel(jitter = 4))))
        for (run in runs) {
            run.gated {
                run.source.open("s1", IngestKit.CHAT)
                for (m in talk("kyoto", 48) + talk("gradle", 8)) run.source.add("s1", m.text, m.role)
                run.source.seal("s1")
                run.lattice.conversationSealed("s1")
            }
        }
        val (serial, ahead) = runs

        suspend fun same(step: String) {
            assertEquals(serial.snapshot(), ahead.snapshot(), "after $step")
            assertEquals(serial.deadLetters.map { it.transcript }, ahead.deadLetters.map { it.transcript }, "dead letters after $step")
        }
        same("the seal")
        val kyoto = ahead.lattice.withGraph { g -> g.scenes(IngestKit.CHAT).first().let { g.sceneWork(it.sceneId)!! } }
        assertEquals(Triple(1, 1, 0), Triple(kyoto.extractAttempts, kyoto.chunksDone, kyoto.chunksFailed))
        assertEquals(1, ahead.deadLetters.size, "only the chunk that stopped the scene is dead-lettered")
        assertTrue(ahead.model.calls(MemoryTask.EXTRACT) > serial.model.calls(MemoryTask.EXTRACT), "later chunks were extracted ahead, then dropped")
        assertEquals(
            listOf(SceneWorkState.EXTRACTING, SceneWorkState.NARRATING),
            ahead.lattice.withGraph { g -> g.scenes(IngestKit.CHAT).map { g.sceneWork(it.sceneId)!!.state } },
        )

        // Three attempts give the second chunk up; the third fails next and is given up after three more.
        repeat(4) { i ->
            serial.catchUp()
            ahead.catchUp()
            same("catch-up ${i + 1}")
        }
        val settled = ahead.lattice.withGraph { g -> g.scenes(IngestKit.CHAT).first().let { g.sceneWork(it.sceneId)!! } }
        assertEquals(Triple(0, 2, 2), Triple(settled.extractAttempts, settled.chunksDone, settled.chunksFailed))
        assertEquals(6, ahead.deadLetters.size)
        assertEquals(
            listOf(SceneWorkState.MATERIALIZED, SceneWorkState.MATERIALIZED),
            ahead.lattice.withGraph { g -> g.scenes(IngestKit.CHAT).map { g.sceneWork(it.sceneId)!!.state } },
        )
    }

    @Test
    fun aCloseCutsTheCallsInFlightOffAndCountsNothing() = runBlocking {
        val store = TestLatticeStore()
        val started = AtomicInteger()
        val cutOff = AtomicInteger()
        val model = SimModel().apply {
            extract = { user ->
                started.incrementAndGet()
                try {
                    IngestKit.pause(60_000)
                } catch (e: MemoryModelInterruptedException) {
                    cutOff.incrementAndGet()
                    throw e
                }
                IngestKit.extraction(user)
            }
        }
        val source = FakeConversationSource(start = Instant.parse("2026-07-01T09:00:00Z"))
        val run = open(parallelism = 4, model = model, store = store, shutdownTimeout = 300.milliseconds, source = source)
        source.open("s1", IngestKit.CHAT)
        for (m in talk("harbour", 60) + talk("kyoto", 8)) source.add("s1", m.text, m.role)
        source.seal("s1")
        run.lattice.conversationSealed("s1")
        withTimeout(5.seconds) { while (started.get() < 4) kotlinx.coroutines.delay(10) }

        withTimeout(10.seconds) { run.lattice.close() }

        assertEquals(4, started.get(), "four calls in flight, no more")
        withTimeout(5.seconds) { while (cutOff.get() < 4) kotlinx.coroutines.delay(10) }
        assertTrue(store.sceneWork.values.isNotEmpty())
        assertTrue(store.sceneWork.values.all { it.extractAttempts == 0 && it.extractedThrough == null && it.chunksDone == 0 }, "${store.sceneWork}")
        assertTrue(store.engrams.values.none { it.origin == Engram.Origin.EXTRACTED }, "nothing was applied")

        // Reopened, the catch-up extracts everything once.
        val again = open(parallelism = 4, store = store, source = source)
        again.catchUp()
        val work = again.lattice.withGraph { g -> g.scenes(IngestKit.CHAT).map { g.sceneWork(it.sceneId)!! } }
        assertEquals(listOf(5 to 0, 1 to 0), work.map { it.chunksDone to it.extractAttempts })
        assertTrue(work.all { it.state == SceneWorkState.MATERIALIZED })
    }

    companion object {
        val SCENES = SceneSettings(enabled = true, segmentBatchMessages = 6, segmentLookaheadMessages = 2, segmentWindowMessages = 40)
    }
}
