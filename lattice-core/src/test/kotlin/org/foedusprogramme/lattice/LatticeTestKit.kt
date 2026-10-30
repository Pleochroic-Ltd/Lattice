package org.foedusprogramme.lattice

import org.foedusprogramme.lattice.core.BundledTexts
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.MemoryDeadLetter
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.TestLatticeStore
import java.io.Flushable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Shared fakes for the service tests: a model answering per task, a store that counts flushes and closes. */
internal object LatticeTestKit {
    val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")

    fun settings(
        graphReadTimeout: kotlin.time.Duration = kotlin.time.Duration.parse("5s"),
        shutdownTimeout: kotlin.time.Duration = kotlin.time.Duration.parse("5s"),
    ) = LatticeSettings(zone = ZoneOffset.UTC, graphReadTimeout = graphReadTimeout, shutdownTimeout = shutdownTimeout)

    fun open(
        settings: LatticeSettings = settings(),
        store: LatticeStore = TestLatticeStore(),
        embedder: Embedder = LocalHashingEmbedder(),
        model: MemoryModel? = null,
        deadLetter: MemoryDeadLetter = MemoryDeadLetter.NoOp,
        graphConfig: LatticeGraph.Config? = null,
        now: Instant = NOW,
        name: String = "test",
        catchUp: SleepPass? = null,
        formPeriods: SleepPass? = null,
        conversations: ConversationSource? = null,
        sceneTrust: SceneTrustPolicy = SceneTrustPolicy.Conservative,
    ) = DefaultLattice(
        name = name,
        settings = settings,
        dependencies = LatticeDependencies(
            store = store,
            embedder = embedder,
            model = model,
            judgeModelId = "test-judge",
            texts = BundledTexts,
            deadLetter = deadLetter,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            conversations = conversations,
            sceneTrust = sceneTrust,
        ),
        graphConfig = graphConfig,
        catchUp = catchUp,
        formPeriods = formPeriods,
    )

    /** A model whose answer per task comes from [answers]; it records each call's task and thread. */
    class FakeModel(private val answers: (MemoryTask, String) -> String) : MemoryModel {
        val calls: MutableList<Pair<MemoryTask, String>> = Collections.synchronizedList(ArrayList())
        val users: MutableList<String> = Collections.synchronizedList(ArrayList())
        val systems: MutableList<String> = Collections.synchronizedList(ArrayList())
        private val counter = AtomicInteger()

        val count: Int get() = counter.get()

        fun calls(task: MemoryTask): Int = synchronized(calls) { calls.count { it.first == task } }

        fun threads(task: MemoryTask): List<String> = synchronized(calls) { calls.filter { it.first == task }.map { it.second } }

        override fun complete(task: MemoryTask, system: String, user: String): String {
            counter.incrementAndGet()
            calls += task to Thread.currentThread().name
            users += user
            systems += system
            return answers(task, user)
        }
    }

    /** A store that is [Flushable] and records flushes and the close. */
    open class FlushingStore : TestLatticeStore(), Flushable {
        val flushes = AtomicInteger()

        @Volatile
        var closed = false

        @Volatile
        var closedOn: String? = null

        override fun flush() {
            flushes.incrementAndGet()
        }

        override fun close() {
            closed = true
            closedOn = Thread.currentThread().name
        }
    }

    /** An extraction answer with one memory per content. */
    fun extraction(vararg contents: String, quote: String? = null): String = buildString {
        append("{\"memories\":[")
        contents.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"ref\":\"m${i + 1}\",\"content\":\"$c\",\"salience\":0.7,\"layer\":\"SEMANTIC\"")
            if (quote != null && i == 0) append(",\"quotes\":[{\"speaker\":\"user\",\"text\":\"$quote\"}]")
            append('}')
        }
        append("],\"relations\":[]}")
    }
}
