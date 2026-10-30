package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.core.SceneRig.Companion.CHAT
import org.foedusprogramme.lattice.core.SceneWorkState.MATERIALIZED
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scene pipeline's threads: capture and narration never hold each other up, the graph is only reached through
 * [GraphAccess] and never holds an embedding or model call, the catch-up queues behind live work, and concurrent
 * callers never split or duplicate a scene.
 */
class SceneConcurrencyTest {

    private val rigs = ArrayList<SceneRig>()

    @AfterTest
    fun close() = rigs.forEach { it.close() }

    private fun rig(
        embedder: Embedder = LocalHashingEmbedder(),
        access: ((LatticeGraph) -> GraphAccess)? = null,
    ) = SceneRig(embedder = embedder, access = access).also { rigs += it }

    @Test
    fun `narration runs on its own thread and a slow narration does not hold the capture thread`() {
        val r = rig()
        val release = CountDownLatch(1)
        val narrating = CountDownLatch(1)
        val threads = CopyOnWriteArrayList<String>()
        r.model.narration = { user ->
            threads += Thread.currentThread().name
            if (TopicModel.titleOf(user) == "kyoto") {
                narrating.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            """{"narration":"${TopicModel.titleOf(user)} 那天很开心。"}"""
        }
        r.source.open(A, CHAT)
        r.source.open(B, OTHER_CHAT)
        r.say(A, "kyoto", "gradle")
        r.source.seal(A)
        r.pipeline.captureSealed(A)
        assertTrue(narrating.await(10, TimeUnit.SECONDS), "the first scene is being narrated")

        r.say(B, "curry", "ramen")
        r.source.seal(B)
        r.pipeline.captureSealed(B)
        r.pipeline.catchUp() // returns once the capture side is through, narration still blocked

        assertEquals(4, r.capture.calls.size, "both conversations were extracted while a narration hung")
        assertEquals(listOf(SceneWorkState.NARRATING, SceneWorkState.NARRATING), r.states(OTHER_CHAT).map { it.second })
        release.countDown()
        r.pipeline.awaitIdle()

        assertTrue(r.states().all { it.second == MATERIALIZED } && r.states(OTHER_CHAT).all { it.second == MATERIALIZED })
        assertTrue(threads.all { it == "scene-narrate" }, "$threads")
        assertTrue(r.capture.calls.all { it.thread == "scene-capture" })
    }

    @Test
    fun `the graph never holds an embedding of a narration nor a model call`() {
        val inGraph = ThreadLocal.withInitial { false }
        val narrationEmbeddedInGraph = CopyOnWriteArrayList<Boolean>()
        val base = LocalHashingEmbedder()
        val embedder = object : Embedder by base {
            override fun embed(text: String): FloatArray {
                if ("那天的经历" in text) narrationEmbeddedInGraph += inGraph.get()
                return base.embed(text)
            }
        }
        val r = rig(embedder) { g ->
            object : GraphAccess {
                override fun <T> withGraph(block: (LatticeGraph) -> T): T = synchronized(g) {
                    inGraph.set(true)
                    try {
                        block(g)
                    } finally {
                        inGraph.set(false)
                    }
                }
            }
        }
        val modelInGraph = CopyOnWriteArrayList<Boolean>()
        val narration = r.model.narration
        r.model.narration = { user -> modelInGraph += inGraph.get(); narration(user) }
        r.source.open(A, CHAT)
        r.say(A, "kyoto", "gradle")
        r.seal(A)

        assertEquals(listOf(false, false), narrationEmbeddedInGraph, "each hub's narration is embedded before the graph call")
        assertEquals(listOf(false, false), modelInGraph)
        assertTrue(r.states().all { it.second == MATERIALIZED })
    }

    @Test
    fun `the catch-up waits behind live work already queued, and awaitIdle refuses a scene thread`() {
        val r = rig()
        val release = CountDownLatch(1)
        val extracting = CountDownLatch(1)
        r.capture.fail = {
            extracting.countDown()
            release.await(10, TimeUnit.SECONDS)
            null
        }
        r.source.open(A, CHAT)
        r.say(A, "kyoto", "gradle")
        r.source.seal(A)
        r.pipeline.captureSealed(A)
        assertTrue(extracting.await(10, TimeUnit.SECONDS))

        var caughtUp = false
        val sleeper = thread { r.pipeline.catchUp(); caughtUp = true }
        sleeper.join(300)
        assertFalse(caughtUp, "the catch-up runs behind the seal on the one capture thread")
        release.countDown()
        sleeper.join(10_000)
        assertTrue(caughtUp)

        val refused = CopyOnWriteArrayList<Throwable>()
        val narration = r.model.narration
        r.model.narration = { user ->
            runCatching { r.pipeline.awaitIdle() }.exceptionOrNull()?.let(refused::add)
            narration(user)
        }
        r.say(A, "curry") // a finished conversation: nothing happens
        r.source.open(B, CHAT)
        r.say(B, "curry")
        r.seal(B)
        assertTrue(refused.isNotEmpty() && refused.all { it is IllegalStateException }, "$refused")
    }

    @Test
    fun `concurrent advances while messages arrive split no scene and extract every message once`() {
        val r = rig()
        r.source.open(A, CHAT)
        val callers = Executors.newFixedThreadPool(4)
        try {
            repeat(12) { round ->
                r.say(A, "topic$round", perTopic = 3)
                repeat(4) { callers.execute { r.pipeline.advance(A) } }
            }
        } finally {
            callers.shutdown()
            callers.awaitTermination(10, TimeUnit.SECONDS)
        }
        r.seal(A)

        val said = r.source.all(A).map { it.position }
        val scenes = r.scenes()
        assertEquals((0 until 12).map { "topic$it" }, scenes.map { it.title }, "one scene per topic, in order")
        assertTrue(scenes.zipWithNext().all { (a, b) -> a.lastMessageId < b.firstMessageId }, "no overlap")
        assertEquals(said, r.capture.calls.flatMap { it.positions }, "every message extracted exactly once, in order")
        assertTrue(r.states().all { it.second == MATERIALIZED })
    }

    private companion object {
        const val A = "chat-1:a"
        const val B = "chat-1:b"
        const val OTHER_CHAT = "chat-2"
    }
}
