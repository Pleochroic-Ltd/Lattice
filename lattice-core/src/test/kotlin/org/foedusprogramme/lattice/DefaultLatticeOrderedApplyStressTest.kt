package org.foedusprogramme.lattice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.IngestKit.Message
import org.foedusprogramme.lattice.IngestKit.SimModel
import org.foedusprogramme.lattice.IngestKit.SlowEmbedder
import org.foedusprogramme.lattice.core.BundledTexts
import org.foedusprogramme.lattice.core.DeadLetterEntry
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryDeadLetter
import org.foedusprogramme.lattice.core.MemoryModelUnavailableException
import org.foedusprogramme.lattice.core.SceneWorkState
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A search for a lost chunk under ordered apply ([LatticeSettings.extractParallelism]): randomized multi-session
 * conversations (sessions minutes or days apart, scenes of one to forty messages, tool results, live segmentation of an
 * OPEN scene), random model latencies, and chunks that fail — unparseable replies and outages — for one, two or three
 * passes (three gives the chunk up), driven as the benchmark drives a scope and caught up as its sleep does. For every
 * seed no chunk may be lost: every message of a chunk whose extraction answered ends up in exactly one memory, every
 * chunk whose extraction never answered is given up and counted, and every scene is extracted to its end.
 *
 * Narration and the host are held back during capture (gated): sixteen calls at a time must then leave what one at a
 * time leaves, id for id. Left to interleave as in the benchmark (ungated), each run is checked on its own.
 */
class DefaultLatticeOrderedApplyStressTest {

    private val opened = ArrayList<DefaultLattice>()

    @AfterTest
    fun closeAll() = runBlocking { opened.forEach { runCatching { it.close() } } }

    /** One generated scope: its sessions (with their start times) and its failure plan. */
    private class Scope(val sessions: List<Triple<String, Instant, List<Message>>>, val failures: Map<String, Pair<Boolean, Int>>)

    /**
     * [seed]'s scope: three to six sessions, each one to four topics of one to forty messages, sometimes a tainted tool
     * result, sessions minutes to days apart; about one chunk in six fails (parse or outage) until catch-up 1, 2 or 3.
     */
    private fun scope(seed: Long): Scope {
        val r = Random(seed)
        var at = Instant.parse("2023-03-01T09:00:00Z")
        val sessions = (1..3 + r.nextInt(4)).map { s ->
            at = at.plus(if (r.nextInt(3) == 0) Duration.ofMinutes(20L + r.nextInt(60)) else Duration.ofHours(6L + r.nextInt(60)))
            val messages = ArrayList<Message>()
            repeat(1 + r.nextInt(4)) { t ->
                messages += IngestKit.talk("s${s}t$t", 1 + r.nextInt(40))
                if (r.nextInt(5) == 0) messages += Message(Role.TOOL, "a fetched page for s${s}t$t", tainted = true)
            }
            Triple("conv$s", at, messages)
        }
        val failures = HashMap<String, Pair<Boolean, Int>>()
        for ((_, _, messages) in sessions) {
            for (m in messages) {
                val key = KEY.find(m.text)?.value ?: continue
                if (r.nextInt(6) == 0) failures[key] = (r.nextBoolean()) to (1 + r.nextInt(3))
            }
        }
        return Scope(sessions, failures)
    }

    private class Run(
        val lattice: DefaultLattice,
        val model: SimModel,
        val source: FakeConversationSource,
        val deadLetters: List<DeadLetterEntry>,
        val round: AtomicInteger,
        /** Chunk key -> the user messages (topic, n) its latest extraction prompt held, and whether that call answered. */
        val prompts: ConcurrentHashMap<String, Pair<Set<Pair<String, Int>>, Boolean>>,
        val gate: Boolean,
    ) {
        val driver = IngestKit.Driver(lattice, source, SlowEmbedder(), gateNarration = gate)

        suspend fun snapshot(): String = lattice.withGraph(IngestKit::snapshot)

        /** The sleep's catch-up, held behind narration like the sessions when [gate]. */
        suspend fun catchUp() = coroutineScope {
            round.incrementAndGet()
            if (!gate) {
                runInterruptible(Dispatchers.IO) { lattice.scenes!!.catchUp(); lattice.scenes!!.awaitIdle() }
                return@coroutineScope
            }
            val held = CountDownLatch(1)
            val narration = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { lattice.onNarration { held.await() } }
            runInterruptible(Dispatchers.IO) { lattice.scenes!!.catchUp() }
            lattice.onCapture { }
            held.countDown()
            narration.await()
            runInterruptible(Dispatchers.IO) { lattice.scenes!!.awaitIdle() }
        }
    }

    private fun open(parallelism: Int, scope: Scope, seed: Long, gate: Boolean): Run {
        val round = AtomicInteger()
        val prompts = ConcurrentHashMap<String, Pair<Set<Pair<String, Int>>, Boolean>>()
        val model = SimModel(jitter = 1L + seed % 7).apply {
            extract = { user ->
                val key = KEY.find(user)!!.value
                val asked = LINE.findAll(user).map { it.groupValues[1] to it.groupValues[2].toInt() }.filter { it.second % 2 == 1 }.toSet()
                val failure = scope.failures[key]
                val answers = failure == null || round.get() >= failure.second
                prompts[key] = asked to answers
                when {
                    answers -> IngestKit.extraction(user)
                    failure!!.first -> "not JSON"
                    else -> throw MemoryModelUnavailableException("the gateway answered 402 for $key")
                }
            }
        }
        val deadLetters = CopyOnWriteArrayList<DeadLetterEntry>()
        val source = FakeConversationSource(start = scope.sessions.first().second)
        val settings = LatticeTestKit.settings().copy(extractParallelism = parallelism, scenes = DefaultLatticeOrderedApplyTest.SCENES)
        val lattice = LatticeTestKit.open(
            settings = settings,
            embedder = SlowEmbedder(),
            model = model,
            deadLetter = MemoryDeadLetter { deadLetters += it },
            conversations = source,
            // No write-time fold: every extracted memory stays its own engram, so each one can be found.
            graphConfig = DefaultLattice.graphConfig(settings, BundledTexts).copy(dedupThreshold = 1.01),
        ).also { opened += it }
        lattice.scenes!!.awaitIdle()
        return Run(lattice, model, source, deadLetters, round, prompts, gate)
    }

    private suspend fun ingest(run: Run, scope: Scope) {
        for ((conversation, at, messages) in scope.sessions) {
            run.source.at(at)
            run.driver.session(conversation, messages)
        }
    }

    /** What a run kept, by content: the extracted memories and quotes, and every scene's extraction bookkeeping. */
    private suspend fun content(run: Run): Triple<List<String>, List<String>, List<String>> = run.lattice.withGraph { g ->
        val memories = g.engrams().filter { it.origin == Engram.Origin.EXTRACTED }.map { it.content }.sorted()
        val quotes = g.engrams().filter { it.origin == Engram.Origin.VERBATIM }.map { it.content }.sorted()
        val scenes = g.scenes().map { s ->
            val w = g.sceneWork(s.sceneId)!!
            "${s.conversationId} ${s.firstMessageId}..${s.lastMessageId} through=${w.extractedThrough} done=${w.chunksDone} failed=${w.chunksFailed} attempts=${w.extractAttempts}"
        }.sorted()
        Triple(memories, quotes, scenes)
    }

    /**
     * Nothing lost, nothing skipped, nothing twice, checked on one run against what its model answered. A chunk's last
     * extraction call settles it (every chunk after a stop is asked again, and by the last catch-up every failure is
     * over or has used its attempts): when that call answered, each of its user messages is in exactly one memory;
     * when it failed, the chunk was given up — none is, and its scene counts it failed. Every said user message reached
     * the extractor, every scene is extracted to its end and every conversation segmented to its end.
     */
    private suspend fun assertNothingLost(run: Run, scope: Scope, seed: Long) {
        val answered = run.prompts.values.filter { it.second }.flatMap { it.first }.toSet()
        val givenUp = run.prompts.values.filter { !it.second }.flatMap { it.first }.toSet()
        val (memories, _, _) = content(run)
        val kept = memories.map { MEMORY.find(it)!!.let { m -> m.groupValues[1] to m.groupValues[2].toInt() } }
        assertEquals(kept.size, kept.toSet().size, "seed $seed: a chunk was applied twice")
        assertEquals(answered, kept.toSet(), "seed $seed: every message of a chunk whose last call answered is in a memory, and no other")
        val said = scope.sessions.flatMap { (_, _, ms) -> ms.mapNotNull { m -> LINE_TEXT.find(m.text)?.let { it.groupValues[1] to it.groupValues[2].toInt() } } }
            .filter { it.second % 2 == 1 }.toSet()
        assertEquals(said, answered + givenUp, "seed $seed: every said user message reached the extractor")
        run.lattice.withGraph { g ->
            for (sc in g.scenes()) {
                val w = g.sceneWork(sc.sceneId)!!
                assertEquals(sc.lastMessageId, w.extractedThrough, "seed $seed: scene ${sc.sceneId} extracted to its end")
                assertTrue(w.state == SceneWorkState.MATERIALIZED || w.state == SceneWorkState.ABANDONED, "seed $seed: $w")
            }
            assertEquals(
                run.prompts.values.count { !it.second }, g.scenes().sumOf { g.sceneWork(it.sceneId)!!.chunksFailed },
                "seed $seed: a chunk whose last call failed was given up, and counted",
            )
            assertTrue(g.sceneCursors().all { it.finished }, "seed $seed: every conversation segmented to its end")
        }
    }

    @Test
    fun aGatedScopeEndsUpAsTheSerialOneForEverySeed() = runBlocking {
        var overlapped = 0
        for (seed in 1L..GATED_SEEDS) {
            val scope = scope(seed)
            val serial = open(parallelism = 1, scope = scope, seed = seed, gate = true)
            val ahead = open(parallelism = 16, scope = scope, seed = seed, gate = true)
            ingest(serial, scope)
            ingest(ahead, scope)
            assertEquals(serial.snapshot(), ahead.snapshot(), "seed $seed: after the sessions")
            repeat(4) { i ->
                serial.catchUp()
                ahead.catchUp()
                assertEquals(serial.snapshot(), ahead.snapshot(), "seed $seed: after catch-up ${i + 1}")
                assertEquals(serial.deadLetters.map { it.transcript }, ahead.deadLetters.map { it.transcript }, "seed $seed: dead letters")
            }
            assertNothingLost(serial, scope, seed)
            assertNothingLost(ahead, scope, seed)
            if (ahead.model.maxExtractInFlight.get() > 1) overlapped++
        }
        assertTrue(overlapped >= GATED_SEEDS / 2, "calls ran at once in most scopes ($overlapped of $GATED_SEEDS)")
    }

    @Test
    fun aScopeWhoseNarrationInterleavesLosesNoChunkForAnySeed() = runBlocking {
        for (seed in 1_001L..1_000L + INTERLEAVED_SEEDS) {
            val scope = scope(seed)
            val serial = open(parallelism = 1, scope = scope, seed = seed, gate = false)
            val ahead = open(parallelism = 16, scope = scope, seed = seed, gate = false)
            ingest(serial, scope)
            ingest(ahead, scope)
            repeat(4) {
                serial.catchUp()
                ahead.catchUp()
            }
            // Interleaved, the live steps (and so the chunks an OPEN scene is cut into, and the attempts a failure costs)
            // follow the host's timing, which no two runs share: each run is checked against its own answers.
            assertNothingLost(serial, scope, seed)
            assertNothingLost(ahead, scope, seed)
        }
    }

    companion object {
        /** LATTICE_STRESS_SEEDS=n widens the search to n seeds per test (default 8 gated, 16 interleaved). */
        private val SWEEP = System.getenv("LATTICE_STRESS_SEEDS")?.toLongOrNull()
        private val GATED_SEEDS = SWEEP ?: 8L
        private val INTERLEAVED_SEEDS = SWEEP ?: 16L

        /** A chunk's key: its first message (the chunk's prompt starts there). */
        private val KEY = Regex("topic=\\w+ message \\d+")
        private val LINE = Regex("\\[(?:user|assistant)] topic=(\\w+) message (\\d+)")
        private val LINE_TEXT = Regex("^topic=(\\w+) message (\\d+)$")
        private val MEMORY = Regex("^About (\\w+), message (\\d+) mentioned")
    }
}
