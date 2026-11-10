package org.foedusprogramme.lattice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.EmbedderUnavailableException
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryModelInterruptedException
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.SceneNarrator
import org.foedusprogramme.lattice.core.TopicModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whole-conversation ingestion as the benchmark drives it, with fakes: a deterministic model whose calls take a set time
 * plus random jitter, an embedder that does the same, sessions of `topic=<word>` messages, and a snapshot of everything
 * the graph holds, to compare two runs.
 */
internal object IngestKit {
    const val CHAT = "chat-ingest"

    /**
     * Answers as a function of its input only. SCENES, JUDGE and self-checks as [TopicModel]; EXTRACT one memory per
     * odd-numbered message (a quote on every other one, a CAUSAL relation between the first two); NARRATE a sentence
     * naming the title that opens a thread about it. Each call sleeps [millis] plus up to [jitter] ms, interruptibly.
     */
    class SimModel(@Volatile var millis: Long = 0, @Volatile var jitter: Long = 0) : MemoryModel {
        private val topic = TopicModel()
        private val counts = ConcurrentHashMap<MemoryTask, AtomicInteger>()
        private val inFlight = AtomicInteger()
        val maxExtractInFlight = AtomicInteger()
        val extractThreads: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Milliseconds spent in calls, summed over every call (overlapping calls each count). */
        val busyMillis = java.util.concurrent.atomic.AtomicLong()

        /** The EXTRACT answer for a prompt; replace it to fail chunks. */
        @Volatile var extract: (user: String) -> String = ::extraction

        fun calls(task: MemoryTask): Int = counts[task]?.get() ?: 0

        fun callMix(): Map<MemoryTask, Int> = counts.mapValues { it.value.get() }.toSortedMap()

        override fun complete(task: MemoryTask, system: String, user: String): String {
            counts.computeIfAbsent(task) { AtomicInteger() }.incrementAndGet()
            if (task == MemoryTask.EXTRACT) {
                maxExtractInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                extractThreads += Thread.currentThread().name
            }
            val t0 = System.nanoTime()
            try {
                pause(millis + if (jitter > 0) ThreadLocalRandom.current().nextLong(jitter + 1) else 0)
                busyMillis.addAndGet((System.nanoTime() - t0) / 1_000_000)
                return when {
                    task == MemoryTask.EXTRACT -> extract(user)
                    task == MemoryTask.NARRATE && system == SceneNarrator.SYSTEM_PROMPT -> narration(user)
                    else -> topic.complete(task, system, user)
                }
            } finally {
                if (task == MemoryTask.EXTRACT) inFlight.decrementAndGet()
            }
        }
    }

    /** [LocalHashingEmbedder] behind a call that takes [millis]; it records which thread embedded which text. */
    class SlowEmbedder(@Volatile var millis: Long = 0, private val delegate: Embedder = LocalHashingEmbedder()) : Embedder {
        val calls = AtomicInteger()
        val busyMillis = java.util.concurrent.atomic.AtomicLong()
        val embedded = ConcurrentLinkedQueue<Pair<String, String>>()

        /** The adapter's per-session cue vectors (see [Driver.precomputeCues]). */
        private val cues = ConcurrentHashMap<String, FloatArray>()

        override val model: String get() = delegate.model
        override val dimension: Int get() = delegate.dimension

        override fun embed(text: String): FloatArray {
            cues[text]?.let { return it.copyOf() }
            return embedAll(listOf(text)).single()
        }

        override fun embedAll(texts: List<String>): List<FloatArray> {
            calls.incrementAndGet()
            val thread = Thread.currentThread().name
            texts.forEach { embedded += thread to it }
            val t0 = System.nanoTime()
            try {
                pause(millis)
                busyMillis.addAndGet((System.nanoTime() - t0) / 1_000_000)
            } catch (e: MemoryModelInterruptedException) {
                throw EmbedderUnavailableException("the fake embedding call was interrupted", e)
            }
            return texts.map(delegate::embed)
        }

        fun precompute(texts: Collection<String>) {
            val distinct = texts.distinct()
            if (distinct.isNotEmpty()) cues.putAll(distinct.zip(embedAll(distinct)))
        }

        fun clearCues() = cues.clear()
    }

    /** One message of a session: its role, its text, and whether the host marked it untrusted. */
    class Message(val role: ConversationMessage.Role, val text: String, val tainted: Boolean = false)

    /** [count] alternating user / assistant messages about [topic], numbered from [from]. */
    fun talk(topic: String, count: Int, from: Int = 1): List<Message> = (from until from + count).map { n ->
        Message(if (n % 2 == 1) ConversationMessage.Role.USER else ConversationMessage.Role.ASSISTANT, "topic=$topic message $n")
    }

    /**
     * Drives [lattice] as the benchmark adapter does: per session, a passive recall before every user turn, a capture
     * (a pipeline advance) after every reply, then the seal and a wait for the scene work. [gateNarration] holds the
     * narration thread until the session's capture side is done, so narration never interleaves with capture and two
     * runs are comparable id for id. [precomputeCues] embeds a session's cues in one call before its turns.
     */
    class Driver(
        val lattice: DefaultLattice,
        val source: FakeConversationSource,
        val embedder: SlowEmbedder,
        private val gateNarration: Boolean,
        private val precomputeCues: Boolean = false,
        private val passiveRecall: Boolean = true,
    ) {
        suspend fun session(conversationId: String, messages: List<Message>) = coroutineScope {
            source.open(conversationId, CHAT)
            val gate = CountDownLatch(1)
            val held = if (gateNarration) async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { lattice.onNarration { gate.await() } } else null
            if (precomputeCues && passiveRecall) embedder.precompute(messages.filter { it.role == ConversationMessage.Role.USER }.map { it.text })
            val live = Transcript(emptyList(), Transcript.Source(CHAT, conversationId))
            for (m in messages) {
                if (m.role == ConversationMessage.Role.USER && passiveRecall) lattice.recall(m.text, RecallOptions(chatKey = CHAT))
                if (m.role == ConversationMessage.Role.TOOL) {
                    source.add(conversationId, m.text, m.role, toolName = "web.fetch", tainted = m.tainted)
                } else {
                    source.add(conversationId, m.text, m.role, tainted = m.tainted)
                }
                if (m.role == ConversationMessage.Role.ASSISTANT) {
                    lattice.capture(live)
                    if (gateNarration) lattice.onCapture { }
                }
            }
            source.seal(conversationId)
            lattice.conversationSealed(conversationId)
            if (held != null) {
                lattice.onCapture { }
                gate.countDown()
                held.await()
            }
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) { lattice.scenes!!.awaitIdle() }
            embedder.clearCues()
        }
    }

    /** Everything the graph holds, ids included: engrams, edges, scenes with their work and members, cursors, threads, periods. */
    fun snapshot(g: LatticeGraph): String = buildString {
        for (e in g.engrams().sortedBy { it.id }) appendLine(engram(e))
        g.edges().map { it.toString() }.sorted().forEach(::appendLine)
        for (s in g.scenes().sortedBy { it.sceneId }) {
            appendLine(s)
            appendLine(g.sceneWork(s.sceneId))
            appendLine("members ${g.sceneMembers(s.sceneId).sorted()}")
        }
        g.sceneCursors().sortedBy { it.conversationId }.forEach(::appendLine)
        for (t in g.threads().sortedBy { it.id }) {
            appendLine(t)
            appendLine(g.threadLinks(t.id).map { it.toString() }.sorted())
        }
        g.periodChatKeys().sorted().forEach { chat -> g.periods(chat).forEach(::appendLine) }
    }

    private fun engram(e: Engram): String =
        e.toString().replace(Regex("embedding=\\[F@[0-9a-f]+"), "embedding=${e.embedding.contentHashCode()}")

    private val LINE = Regex("\\[(?:user|assistant)] topic=(\\w+) message (\\d+)")
    private val WORDS = listOf(
        "tea", "rain", "harbour", "violin", "ledger", "garden", "train", "lantern", "winter", "copper", "orchard", "bridge",
        "letter", "market", "candle", "river", "piano", "school", "fever", "ticket", "island", "mirror", "kitchen", "festival",
        "forest", "museum", "parcel", "clinic", "silver", "meadow", "office", "theatre", "village", "compass", "glacier", "bakery",
    )

    private fun words(topic: String, n: Int, count: Int): List<String> {
        var h = (topic.hashCode().toLong() and 0x7fffffff) * 31 + n
        return (0 until count).map {
            h = (h * 1103515245 + 12345) and 0x7fffffff
            WORDS[(h % WORDS.size).toInt()]
        }
    }

    fun extraction(user: String): String {
        val lines = LINE.findAll(user).map { it.groupValues[1] to it.groupValues[2].toInt() }.filter { it.second % 2 == 1 }.toList()
        val memories = lines.mapIndexed { i, (topic, n) ->
            val w = words(topic, n, 5)
            buildString {
                append("{\"ref\":\"m${i + 1}\",\"content\":\"About $topic, message $n mentioned ${w.joinToString(" ")}\",")
                append("\"salience\":0.${5 + n % 4},\"layer\":\"${if (n % 3 == 0) "EPISODIC" else "SEMANTIC"}\"")
                if (i % 2 == 0) append(",\"quotes\":[{\"speaker\":\"user\",\"text\":\"I said in message $n that $topic is about ${w[0]} and ${w[1]}\"}]")
                append('}')
            }
        }
        val relations = if (memories.size >= 2) """[{"from":"m1","to":"m2","type":"CAUSAL"}]""" else "[]"
        return """{"memories":[${memories.joinToString(",")}],"relations":$relations}"""
    }

    private fun narration(user: String): String {
        val title = TopicModel.titleOf(user)
        return """{"narration":"$title 那天的经历，大家都很开心。","threads":{"opened":[{"name":"$title plan","description":"the plan about $title","setup":[1]}]}}"""
    }

    /** Sleeps [millis] as a model or embedder call would wait: an interrupt cuts it off as one. */
    fun pause(millis: Long) {
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MemoryModelInterruptedException("the fake call was interrupted", e)
        }
    }
}
