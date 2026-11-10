package org.foedusprogramme.lattice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.foedusprogramme.lattice.IngestKit.Message
import org.foedusprogramme.lattice.IngestKit.SimModel
import org.foedusprogramme.lattice.IngestKit.SlowEmbedder
import org.foedusprogramme.lattice.IngestKit.talk
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import kotlin.test.Test

/**
 * The wall time of ingesting one benchmark haystack with fake latencies: no model or network, only the waits. Every chat
 * call takes 4 s and every embedding call 1.7 s (whatever its batch), both times `LATTICE_SIM_SCALE` (default 0.05: at
 * 0.01 a container's sleeps overshoot a 17 ms wait by about half again, and the overshoot swamps the differences).
 * Opt-in: `LATTICE_SIM=1 ./gradlew :lattice-core:cleanTest :lattice-core:test --tests '*IngestWallClockSimTest*'` (the
 * variable is no task input, so a clean forces the run); the results are printed to the test's standard output.
 *
 * Two shapes: a LongMemEval_S-sized haystack driven as the bench adapter drives it (50 sessions of 6-14 messages, about
 * 500 in all, a passive recall before every user turn, each session sealed and settled before the next), and the same
 * 500 messages as one sealed conversation. Each runs serially (extractParallelism 1, a cue embedded per recall: today's
 * bench) and with ordered apply at 16, with and without the adapter's per-session cue embedding in one call.
 */
class IngestWallClockSimTest {

    private val scale = System.getenv("LATTICE_SIM_SCALE")?.toDouble() ?: 0.05
    private val chatMillis = (4000 * scale).toLong()
    private val embedMillis = (1700 * scale).toLong()

    private val topics = listOf(
        "kyoto", "gradle", "tea", "rain", "piano", "harbour", "museum", "garden", "violin", "orchard", "bridge", "market",
        "lantern", "winter", "copper", "letter", "candle", "river", "school", "ticket",
    )

    /** 50 sessions of 6, 8, 10, 12 and 14 messages (500 in all); one topic below 10 messages, two from 10. */
    private val haystack: List<List<Message>> = (0 until 50).map { i ->
        val size = 6 + 2 * (i % 5)
        val a = topics[(2 * i) % topics.size]
        val b = topics[(2 * i + 1) % topics.size]
        if (size < 10) talk(a, size, from = 100 * i + 1) else talk(a, size / 2, from = 100 * i + 1) + talk(b, size - size / 2, from = 100 * i + 51)
    }

    private class Outcome(val label: String, val millis: Long, val sessions: Int, val model: SimModel, val embedder: SlowEmbedder, val engrams: Int)

    private suspend fun run(label: String, parallelism: Int, cues: Boolean, sessions: List<List<Message>>): Outcome {
        val source = FakeConversationSource(start = Instant.parse("2026-05-01T09:00:00Z"))
        val model = SimModel(millis = chatMillis)
        val embedder = SlowEmbedder(millis = embedMillis)
        val lattice = LatticeTestKit.open(
            settings = LatticeTestKit.settings(graphReadTimeout = kotlin.time.Duration.parse("10m"), shutdownTimeout = kotlin.time.Duration.parse("10m"))
                .copy(extractParallelism = parallelism, scenes = SceneSettings(enabled = true), userName = "User", assistantNames = setOf("Assistant")),
            embedder = embedder,
            model = model,
            conversations = source,
            sceneTrust = SceneTrustPolicy.Off,
        )
        try {
            runInterruptible(Dispatchers.IO) { lattice.scenes!!.awaitIdle() }
            val driver = IngestKit.Driver(lattice, source, embedder, gateNarration = false, precomputeCues = cues)
            val started = System.nanoTime()
            sessions.forEachIndexed { i, messages ->
                source.at(Instant.parse("2026-05-01T09:00:00Z").plusSeconds(86_400L * i))
                driver.session("s${i + 1}", messages)
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            return Outcome(label, millis, sessions.size, model, embedder, lattice.stats().engrams)
        } finally {
            lattice.close()
        }
    }

    private fun report(title: String, outcomes: List<Outcome>) {
        val base = outcomes.first().millis.toDouble()
        println("== $title (chat ${chatMillis} ms, embed ${embedMillis} ms per call; scale $scale) ==")
        for (o in outcomes) {
            println(
                "%-34s wall %7d ms  (%5.2fx)  per session %6d ms  unscaled %6.1f min  engrams %4d  chat calls %s (%d ms)  embed calls %d (%d ms)".format(
                    o.label, o.millis, base / o.millis, o.millis / o.sessions, o.millis / scale / 60_000.0, o.engrams,
                    o.model.callMix(), o.model.busyMillis.get(), o.embedder.calls.get(), o.embedder.busyMillis.get(),
                ),
            )
        }
    }

    @Test
    fun aLongMemEvalSizedHaystack() = runBlocking {
        assumeTrue(System.getenv("LATTICE_SIM") != null, "set LATTICE_SIM=1 to run the wall-clock simulation")
        val messages = haystack.sumOf { it.size }
        report(
            "LongMemEval_S-sized haystack, bench protocol: ${haystack.size} sessions, $messages messages",
            listOf(
                run("before: serial, a cue per recall", 1, false, haystack),
                run("ordered apply 16", 16, false, haystack),
                run("serial, cues in one call", 1, true, haystack),
                run("after: ordered apply 16 + cues", 16, true, haystack),
            ),
        )
        val one = listOf(haystack.flatten())
        report(
            "one sealed conversation of $messages messages",
            listOf(
                run("before: serial", 1, false, one),
                run("ordered apply 4", 4, false, one),
                run("ordered apply 16", 16, false, one),
                run("after: ordered apply 16 + cues", 16, true, one),
            ),
        )
    }
}
