package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.PeriodLabels
import org.foedusprogramme.lattice.core.RecallFormatter
import org.foedusprogramme.lattice.core.RecallMarkers
import org.foedusprogramme.lattice.core.Reinforce
import org.foedusprogramme.lattice.core.TestLatticeStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recall through the service: the temporal dispatch (its switch off is plain recall), automatic versus explicit
 * recall, the block's language and dating, and the live writes.
 */
class DefaultLatticeRecallTest {

    private val fixed = Instant.parse("2026-08-01T00:00:00Z")

    /** "beta" texts share one axis, "alpha" texts another, everything else a third. */
    private class KwEmbedder : Embedder {
        override val model = "kw"
        override val dimension = 8
        override fun embed(text: String): FloatArray {
            val v = FloatArray(dimension)
            v[
                when {
                    "beta" in text -> 5
                    "alpha" in text -> 0
                    else -> 7
                },
            ] = 1f
            return v
        }
    }

    private val on = LatticeGraph.Config.bgeM3().copy(temporalLayer = true, temporalRetrieval = true)

    private fun lattice(config: LatticeGraph.Config) =
        LatticeTestKit.open(embedder = KwEmbedder(), graphConfig = config, now = fixed)

    private val read = RecallOptions(reinforce = Reinforce.NONE)

    // --- the temporal dispatch ---------------------------------------------------------------------------------------

    @Test
    fun withTemporalRetrievalOffRecallIsPlainGraphRecall() = runBlocking {
        val config = LatticeGraph.Config.bgeM3().copy(temporalLayer = true, temporalRetrieval = false)
        val lattice = lattice(config)
        val direct = LatticeGraph(KwEmbedder(), config, now = { fixed })
        for (g in listOf(direct)) {
            g.remember("beta 项目截止", salience = 0.7)
            g.remember("alpha 用户领养了猫", salience = 0.6)
        }
        lattice.withGraph { g -> g.remember("beta 项目截止", salience = 0.7); g.remember("alpha 用户领养了猫", salience = 0.6) }

        val viaService = lattice.recall("beta 项目", read)
        val hits = direct.recall("beta 项目", reinforce = Reinforce.NONE)
        assertEquals(hits.map { it.engram.content }, viaService.hits.map { it.content }, "a pass-through to recall")
        val block = RecallFormatter.format(hits, RecallMarkers.EN, temporalLayer = true)
        assertEquals(block, viaService.text, "and the block is byte-identical")
        lattice.close()
    }

    @Test
    fun aPeriodCueSurfacesItsPeriodEvenWhenNothingMatchesSemantically() = runBlocking {
        val lattice = lattice(on.copy(periodRecall = true))
        lattice.withGraph { g ->
            g.remember("beta 项目截止", salience = 0.7, eventDate = "2025-03")
            g.remember("alpha 用户领养了猫", salience = 0.6, eventDate = "2025-03")
        }
        val cue = "2025-03 那阵子还聊了什么"
        assertTrue(lattice.withGraph { it.recall(cue, reinforce = Reinforce.NONE) }.isEmpty(), "plain recall finds nothing")
        assertEquals(2, lattice.recall(cue, read).hits.size, "the period pre-seed surfaces the window")
        lattice.close()
    }

    @Test
    fun anOverDenseWindowAbstainsAndFallsThroughToSemanticRecall() = runBlocking {
        val lattice = lattice(on.copy(periodRecall = true, periodMaxNodes = 1))
        lattice.withGraph { g ->
            g.remember("beta 项目截止", salience = 0.7, eventDate = "2025-03")
            g.remember("alpha 用户领养了猫", salience = 0.6, eventDate = "2025-03")
        }
        assertTrue(lattice.recall("2025-03 那阵子还聊了什么", read).hits.isEmpty())
        lattice.close()
    }

    @Test
    fun aRelevanceCueWithACertainMonthReordersTowardIt() = runBlocking {
        val lattice = lattice(on.copy(temporalRerank = true, temporalRerankLambda = 5.0))
        lattice.withGraph { g ->
            g.remember("beta 项目", salience = 0.5, eventDate = "2025-01")
            g.remember("beta 项目", salience = 0.5, eventDate = "2025-03") // distinct dates: both kept
        }
        val hits = lattice.recall("beta 2025-03 进展", read).hits
        assertEquals("2025-03", hits.first().eventDate)
        lattice.close()
    }

    @Test
    fun aBackReferenceWithNothingInFocusFallsThroughToSemanticRecall() = runBlocking {
        val lattice = lattice(on.copy(periodRecall = true))
        lattice.withGraph { g -> g.remember("beta 项目截止", salience = 0.7, eventDate = "2025-03") }
        assertTrue(lattice.recall("那阵子还聊了什么", read).hits.isEmpty(), "empty focus: abstain, not a period fetch")
        lattice.close()
    }

    @Test
    fun whatARecallBringsBackPutsItsMonthInFocusForABackReference() = runBlocking {
        val lattice = lattice(on.copy(periodRecall = true))
        lattice.withGraph { g ->
            g.remember("beta 项目截止", salience = 0.7, eventDate = "2025-03")
            g.remember("alpha 用户领养了猫", salience = 0.6, eventDate = "2025-03")
        }
        assertEquals(1, lattice.recall("beta 项目", read).hits.size)
        assertEquals(2, lattice.recall("那阵子还聊了什么", read).hits.size, "the back-reference resolves to the month in focus")
        lattice.close()
    }

    // --- automatic and explicit recall -------------------------------------------------------------------------------

    @Test
    fun anAutomaticRecallLearnsSoftlyAndAnExplicitOneCountsAsReplay() = runBlocking {
        val lattice = LatticeTestKit.open(graphConfig = LatticeGraph.Config())
        val id = lattice.remember("部署 llama-server 报错 CUDA 显存不足", RememberOptions(salience = 0.3))
        lattice.recall("部署 显存 报错")
        val soft = lattice.withGraph { it.engram(id)!! }
        assertEquals(0, soft.activationCount, "SOFT: no replay")
        assertEquals(1, soft.softReplays)
        lattice.recall("部署 显存 报错", RecallOptions(explicit = true))
        assertEquals(1, lattice.withGraph { it.engram(id)!!.activationCount }, "explicit: a genuine replay")
        lattice.recall("部署 显存 报错", RecallOptions(explicit = true, reinforce = Reinforce.NONE))
        assertEquals(1, lattice.withGraph { it.engram(id)!!.activationCount }, "an explicit read-only search changes nothing")
        lattice.close()
    }

    @Test
    fun anExplicitSearchBypassesTheEmitGate() = runBlocking {
        val lattice = LatticeTestKit.open(graphConfig = LatticeGraph.Config(seedFloor = 0.1, emitFloor = 0.9))
        lattice.remember("部署失败排查记录")
        assertTrue(lattice.recall("部署文档", read).text.isEmpty(), "the per-turn recall stays silent on a weak match")
        val explicit = lattice.recall("部署文档", RecallOptions(explicit = true, reinforce = Reinforce.NONE))
        assertEquals("- 部署失败排查记录", explicit.text)
        assertEquals(Engram.Origin.MANUAL, explicit.hits.single().origin)
        lattice.close()
    }

    @Test
    fun theBlockFollowsTheCallsLanguageElseTheSettings() = runBlocking {
        val settings = LatticeTestKit.settings().copy(language = Language.ZH)
        val lattice = LatticeTestKit.open(settings = settings, graphConfig = LatticeGraph.Config())
        val id = lattice.saveEpisode("we planned the Kyoto trip together")
        assertNotNull(id)
        assertTrue(lattice.recall("Kyoto trip", RecallOptions(explicit = true)).text.startsWith("- " + RecallMarkers.ZH.episode))
        assertTrue(
            lattice.recall("Kyoto trip", RecallOptions(explicit = true, language = Language.EN)).text.startsWith("- " + RecallMarkers.EN.episode),
        )
        lattice.close()
    }

    @Test
    fun withSceneEpisodesOnTheDayShowsAndAPerTurnRecallSaysHowLongAgo() = runBlocking {
        val settings = LatticeTestKit.settings().copy(temporalLayer = true, scenes = SceneSettings(enabled = true, sceneEpisodes = true))
        val lattice = LatticeTestKit.open(settings = settings, graphConfig = LatticeGraph.Config(temporalLayer = true), now = fixed)
        lattice.withGraph { g -> g.remember("the user bought a new mouse", eventDate = "2026-07-25") }
        assertEquals("- [2026-07-25] the user bought a new mouse", lattice.recall("new mouse", RecallOptions(explicit = true)).text)
        val perTurn = lattice.recall("new mouse").text
        assertTrue(perTurn.startsWith("- [2026-07-25 · ${RecallMarkers.EN.relativeTime(7)}] "), perTurn)
        lattice.close()
    }

    @Test
    fun theNeutralPeriodLabelIsTheDateRange() {
        val period = org.foedusprogramme.lattice.core.PeriodRecord(1, "chat", "a name that may be untrusted", dateFrom = "2026-07-23", dateTo = "2026-07-29")
        val g = LatticeGraph(LocalHashingEmbedder())
        assertEquals("2026-07-23 to 2026-07-29", PeriodLabels.NEUTRAL.label(g, period, Language.EN))
        assertEquals("2026-07-23 至 2026-07-29", PeriodLabels.NEUTRAL.label(g, period, Language.ZH))
        assertEquals("2026-07-23", PeriodLabels.neutralDateName("2026-07-23", "2026-07-23", Language.EN))
        assertEquals("Undated", PeriodLabels.neutralDateName(null, null, Language.EN))
        assertEquals("日期不详", PeriodLabels.neutralDateName(null, " ", Language("zh-Hant")))
    }

    // --- live writes -------------------------------------------------------------------------------------------------

    @Test
    fun rememberWritesAManualMemorySaidNow() = runBlocking {
        val lattice = LatticeTestKit.open(now = fixed)
        val id = lattice.remember("the user prefers aisle seats", RememberOptions(salience = 0.8, layer = Engram.Layer.SEMANTIC, tainted = true))
        val e = lattice.withGraph { it.engram(id)!! }
        assertEquals(Engram.Origin.MANUAL, e.origin)
        assertEquals(Engram.Layer.SEMANTIC, e.layer)
        assertEquals(fixed, e.saidFrom)
        assertTrue(e.tainted)
        kotlin.test.assertFailsWith<IllegalArgumentException> { lattice.remember("  ") }
        lattice.close()
    }

    @Test
    fun anEpisodeIsKeptOnceAndOnlyWithEpisodeNodesOn() = runBlocking {
        val lattice = LatticeTestKit.open()
        val id = lattice.saveEpisode("会话回顾：讨论了记忆系统")
        assertNotNull(id)
        val e = lattice.withGraph { it.engram(id)!! }
        assertTrue(e.isEpisode)
        assertEquals(Engram.Layer.SEMANTIC, e.layer)
        assertNull(lattice.saveEpisode("会话回顾：讨论了记忆系统"), "a duplicate folds into the first")
        assertNull(lattice.saveEpisode("   "))
        lattice.close()

        val off = LatticeTestKit.open(settings = LatticeTestKit.settings().copy(episodicSessionNodes = false))
        assertNull(off.saveEpisode("会话回顾：讨论了记忆系统"))
        assertEquals(0, off.stats().engrams)
        off.close()
    }

    @Test
    fun statsCountTheGraph() = runBlocking {
        val store = TestLatticeStore()
        val lattice = LatticeTestKit.open(store = store)
        val a = lattice.remember("the user rides a blue bicycle to work")
        val b = lattice.remember("the user's blue bicycle has a basket")
        lattice.withGraph { g -> g.link(a, b, org.foedusprogramme.lattice.core.Edge.Type.CAUSAL, 0.9) }
        val stats = lattice.stats()
        assertEquals(2, stats.engrams)
        assertEquals(1, stats.edges)
        assertEquals(0, stats.entities)
        assertEquals(0, stats.scenes)
        assertEquals(0, stats.periods)
        lattice.close()
    }
}
