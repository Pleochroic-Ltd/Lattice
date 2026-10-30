package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graph writes that are one unit land as one store batch: a write failing half-way leaves nothing of the unit in
 * the store (a scene hub, a synthesized parent, an episode hub, a judged supersession).
 */
class LatticeAtomicWritesTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }

    /** An [InMemoryLatticeStore] whose writes throw once [failWhen] matches the row. */
    private class FailingStore(private val inner: InMemoryLatticeStore = InMemoryLatticeStore()) : LatticeStore by inner {
        var failWhen: (Any) -> Boolean = { false }

        private fun check(row: Any) {
            if (failWhen(row)) throw IllegalStateException("the disk is full")
        }

        override fun putEngram(engram: Engram) = check(engram).also { inner.putEngram(engram) }

        override fun putEdge(edge: EdgeRecord) = check(edge).also { inner.putEdge(edge) }

        override fun putScene(scene: SceneRecord) = check(scene).also { inner.putScene(scene) }

        override fun putVerdict(verdict: VerdictRecord) = check(verdict).also { inner.putVerdict(verdict) }

        override fun batch(block: (LatticeStore) -> Unit) = inner.batch { block(this) }

        fun stored(): LatticeStore.Loaded = inner.load()
    }

    private fun graph(store: LatticeStore, config: LatticeGraph.Config = LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99)) =
        LatticeGraph(LocalHashingEmbedder(), config, now = clock, store = store)

    private val kiteDay = listOf("the user walked to the harbour", "the user bought a red kite", "the user flew the kite at noon")

    @Test
    fun aSceneHubWhoseSceneRowFailsLeavesNoHubAndNoEdges() {
        val store = FailingStore()
        val g = graph(store)
        val members = kiteDay.map { g.remember(it) }
        g.putScene(SceneRecord(1, "kites", "chat-1:c1", "chat-1", 100, 109))
        g.addSceneMembers(1, members)

        store.failWhen = { it is SceneRecord }
        assertFailsWith<IllegalStateException> { g.materializeScene(g.scene(1)!!, "That day the user flew a red kite by the harbour.", members) }

        val stored = store.stored()
        assertTrue(stored.engrams.none { it.isEpisodeHub }, "no hub without its scene's pointer")
        assertTrue(stored.edges.none { it.type == Edge.Type.EPISODE_OF })
        assertNull(stored.scenes.single().hubEngramId)
    }

    @Test
    fun aParentWhoseEdgesFailIsNotStored() {
        val store = FailingStore()
        val g = graph(store)
        val child = g.remember(kiteDay[1])

        store.failWhen = { it is EdgeRecord && it.type == Edge.Type.ABSTRACTS }
        assertFailsWith<IllegalStateException> { g.synthesizeParent(listOf(child), "the user likes kites") }

        assertTrue(store.stored().engrams.none { it.origin == Engram.Origin.SYNTHESIZED }, "no parent without its children")
    }

    @Test
    fun anEpisodeHubWhoseEdgesFailIsNotStored() {
        val store = FailingStore()
        val g = graph(store, LatticeGraph.Config(episodicBinding = true, temporalLayer = true, dedupThreshold = 1.01, autoLinkThreshold = 0.99))
        val members = kiteDay.map { g.remember(it, eventDate = "2026-07-20") }

        store.failWhen = { it is EdgeRecord && it.type == Edge.Type.EPISODE_OF }
        assertFailsWith<IllegalStateException> {
            g.materializeEpisode(members, "That day the user flew a red kite by the harbour.", "2026-07-20", skipCohesion = true)
        }

        assertTrue(store.stored().engrams.none { it.isEpisodeHub }, "no hub without its members")
    }

    @Test
    fun aJudgedSupersessionWhoseTaintFailsLeavesNoVerdictAndNoPointer() {
        /** Every tennis text on one axis (cosine 1.0); anything else on its own. */
        val embedder = object : Embedder {
            override val model = "test"
            override val dimension = 4
            override fun embed(text: String) = FloatArray(dimension).also { it[if ("tennis" in text) 0 else 3] = 1f }
        }
        val judge = object : MergeJudge {
            override val modelId = "fake"
            override fun judge(noteA: String, noteB: String, crossLingualHint: Boolean) = MergeJudge.Verdict.SAME
        }
        val store = FailingStore()
        val g = LatticeGraph(embedder, LatticeGraph.Config(temporalLayer = true, judgedMergesOnly = true), now = clock, store = store, judge = judge)
        val canonical = g.remember("The user plays tennis on Sundays with their old friends")
        val narrower = g.remember("The user plays tennis on Sundays", tainted = true)

        store.failWhen = { it is Engram && it.id == canonical && it.tainted }
        assertFailsWith<IllegalStateException> { g.judgedSupersedePass() }

        val stored = store.stored()
        assertTrue(stored.verdicts.isEmpty(), "no verdict without its supersession")
        assertNull(stored.engrams.single { it.id == narrower }.supersededBy, "the retired note is not retired behind an untainted one")
        assertTrue(stored.edges.none { it.type == Edge.Type.SUPERSEDES })
    }
}
