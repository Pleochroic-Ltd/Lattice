package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.LatticeTestKit.FakeModel
import org.foedusprogramme.lattice.core.BgeBandFakeEmbedder
import org.foedusprogramme.lattice.core.Edge
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.EpisodeNarrator
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryTask
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The model-backed sleep passes through the service: judged dedup with its votes off the graph thread, synthesis with
 * its negative cache, same-day episodes, and entities.
 */
class DefaultLatticeSleepPassesTest {

    // --- judged dedup ------------------------------------------------------------------------------------------------

    private fun judgeConfig() = LatticeGraph.Config(
        autoLinkThreshold = 0.99,
        dedupThreshold = 0.86,
        judgeFloor = 0.68,
        judgeVotes = 3,
        judgeBudget = 200,
        neuromodulatorySalience = false,
        seedFloor = 0.0,
        emitFloor = 0.0,
    )

    /** Registers explicit unit vectors so a pair lands on an exact cosine; other texts are orthogonal. */
    private class ScriptedEmbedder(override val dimension: Int = 64) : Embedder {
        override val model = "scripted"
        private val map = HashMap<String, FloatArray>()
        private var nextBlock = 0

        fun pair(a: String, b: String, cos: Double) {
            val p = nextBlock
            nextBlock += 2
            val va = FloatArray(dimension).also { it[p] = 1f }
            val vb = FloatArray(dimension).also { it[p] = cos.toFloat(); it[p + 1] = sqrt(1.0 - cos * cos).toFloat() }
            map[a] = va
            map[b] = vb
        }

        @Synchronized
        override fun embed(text: String): FloatArray = map.getOrPut(text) {
            val p = nextBlock
            nextBlock += 2
            FloatArray(dimension).also { it[p] = 1f }
        }
    }

    private val zhText = "用户喜欢水彩画"
    private val enText = "the user likes watercolors"

    private suspend fun DefaultLattice.judged(): Int =
        sleep(setOf(SleepPhase.JUDGED_DEDUP)).phases.single().also { assertTrue(!it.skipped && it.error == null, "$it") }.count

    @Test
    fun theVotesRunOffTheGraphThreadAndStillSupersede() = runBlocking {
        val emb = ScriptedEmbedder().apply { pair(zhText, enText, 0.80) }
        val model = FakeModel { _, _ -> "SAME" }
        val lattice = LatticeTestKit.open(model = model, embedder = emb, graphConfig = judgeConfig())
        val (zh, en) = lattice.withGraph { g -> g.remember(zhText, salience = 0.9) to g.remember(enText, salience = 0.5) }

        assertEquals(1, lattice.judged())
        val threads = model.threads(MemoryTask.JUDGE)
        assertEquals(3, threads.size, "judgeVotes calls for one pair")
        assertTrue(threads.none { it == "lattice-test-graph" }, "votes never run on the graph thread: $threads")
        assertEquals(zh, lattice.withGraph { it.engram(en)!!.supersededBy }, "the narrower note is superseded")
        assertNotNull(lattice.withGraph { it.engram(en) }, "non-destructive")
        assertEquals(0, lattice.judged(), "the pair is guarded now: nothing is judged again")
        assertEquals(3, model.calls(MemoryTask.JUDGE))
        lattice.close()
    }

    @Test
    fun severalPairsAreVotedAtOnceAndKeepTheirOrder() = runBlocking {
        val emb = ScriptedEmbedder()
        val pairs = listOf("猫" to "cat", "狗" to "dog", "鱼" to "fish", "鸟" to "bird")
        pairs.forEach { (zh, en) -> emb.pair(zh, en, 0.80) }
        // Each vote waits until a second vote is in flight: a sequential pass would time out here.
        val together = CyclicBarrier(2)
        val model = FakeModel { _, _ ->
            together.await(5, TimeUnit.SECONDS)
            "SAME"
        }
        val settings = LatticeTestKit.settings().copy(judgeParallelism = 2)
        val lattice = LatticeTestKit.open(settings = settings, model = model, embedder = emb, graphConfig = judgeConfig())
        val ids = lattice.withGraph { g -> pairs.map { (zh, en) -> g.remember(zh, salience = 0.9) to g.remember(en, salience = 0.5) } }

        assertEquals(4, lattice.judged())
        ids.forEach { (zh, en) -> assertEquals(zh, lattice.withGraph { it.engram(en)!!.supersededBy }) }
        lattice.close()
    }

    @Test
    fun anAmbiguousVerdictIsRetriedNextSleep() = runBlocking {
        val emb = ScriptedEmbedder().apply { pair(zhText, enText, 0.80) }
        var answer = "MAYBE"
        val model = FakeModel { _, _ -> answer }
        val lattice = LatticeTestKit.open(model = model, embedder = emb, graphConfig = judgeConfig())
        val en = lattice.withGraph { g -> g.remember(zhText, salience = 0.9); g.remember(enText, salience = 0.5) }
        assertEquals(0, lattice.judged())
        assertEquals(null, lattice.withGraph { it.engram(en)!!.supersededBy })
        answer = "SAME"
        assertEquals(1, lattice.judged(), "not cached: judged again")
        lattice.close()
    }

    // --- synthesis ---------------------------------------------------------------------------------------------------

    /**
     * Two communities: X, whose abstraction is a verbatim copy of one child (a single-child spike the guard refuses),
     * and Y, whose abstraction is a genuine generalization. Only Y materializes; X is not sent to the model again until
     * its membership changes.
     */
    @Test
    fun aRefusedParaphraseIsCachedWhileAGenuineParentMaterializes() = runBlocking {
        val model = FakeModel { task, user ->
            check(task == MemoryTask.ABSTRACT)
            if ("TX" in user) "TX|s1|m1" else "TY|sG|mG"
        }
        val lattice = LatticeTestKit.open(model = model, embedder = BgeBandFakeEmbedder(), graphConfig = LatticeGraph.Config.bgeM3())
        // X: a tight core (x2, x3 share sub s2: 0.83) plus a bridging outlier x1 (0.64 to each) -> cohesion 0.70 passes
        // 0.63. A verbatim-of-x1 parent sits at [1.0, 0.64, 0.64]: max-mean 0.24 >= the 0.20 dominance margin.
        val x = lattice.withGraph { g -> listOf("TX|s1|m1", "TX|s2|m2", "TX|s2|m3").map { g.remember(it) } }
        // Y: uniformly tight (pairwise 0.83), a genuine diffuse gist materializes.
        val y = lattice.withGraph { g -> listOf("TY|sY|m1", "TY|sY|m2", "TY|sY|m3").map { g.remember(it) } }

        suspend fun synthesize() = lattice.sleep(setOf(SleepPhase.SYNTHESIZE)).phases.single().count
        assertEquals(1, synthesize(), "only Y materializes")
        x.forEach { c -> assertTrue(lattice.withGraph { g -> g.neighbors(c).none { it.type == Edge.Type.ABSTRACTS } }) }
        y.forEach { c -> assertTrue(lattice.withGraph { g -> g.neighbors(c).any { it.type == Edge.Type.ABSTRACTS } }) }
        assertTrue(model.threads(MemoryTask.ABSTRACT).all { it == "lattice-test-narrate" }, "the model runs on the narration thread")

        val callsAfterFirst = model.count
        assertEquals(0, synthesize(), "nothing new on the second sleep")
        assertEquals(callsAfterFirst, model.count, "the refused X is not sent again")

        // x4 joins the s2 core, so the enlarged set stays one community: a new key, sent again.
        lattice.withGraph { g ->
            val x4 = g.remember("TX|s2|m4")
            g.link(x.first(), x4, Edge.Type.SEMANTIC, 0.83)
        }
        synthesize()
        assertTrue(model.count > callsAfterFirst, "a membership change re-opens the cluster")
        lattice.close()
    }

    // --- same-day episodes -------------------------------------------------------------------------------------------

    private val day = "2025-03-10"

    private fun episodes(binding: Boolean, model: FakeModel?): DefaultLattice {
        val settings = LatticeTestKit.settings().copy(episodicBinding = binding, sameDayEpisodes = true, temporalLayer = true)
        val config = LatticeGraph.Config(episodicBinding = binding, temporalLayer = true, autoLinkThreshold = 0.99)
        return LatticeTestKit.open(settings = settings, model = model, embedder = BgeBandFakeEmbedder(), graphConfig = config)
    }

    /** Three same-day facts fully connected by co-retrieval edges: one episode candidate. */
    private suspend fun seedCandidate(lattice: DefaultLattice) = lattice.withGraph { g ->
        // Shared-topic members (0.64) clear the episode coherence gate.
        val ids = listOf("svc|a|m|u1", "svc|b|m|u2", "svc|c|m|u3").map { g.remember(it, eventDate = day) }
        for (i in ids.indices) for (j in i + 1 until ids.size) g.link(ids[i], ids[j], Edge.Type.CO_RETRIEVAL, 0.5)
    }

    private suspend fun DefaultLattice.formEpisodes(): SleepReport.PhaseReport = sleep(setOf(SleepPhase.EPISODES)).phases.single()

    private suspend fun DefaultLattice.hubs() = withGraph { g -> g.engrams().filter { it.isEpisodeHub } }

    @Test
    fun theEpisodePassMaterializesAHub() = runBlocking {
        val lattice = episodes(binding = true, model = FakeModel { _, _ -> "那天的一段经历" })
        seedCandidate(lattice)
        assertEquals(1, lattice.formEpisodes().count)
        assertEquals(1, lattice.hubs().size)
        lattice.close()
    }

    @Test
    fun theEpisodePassIsSkippedWithBindingOffOrWithoutAModel() = runBlocking {
        val off = episodes(binding = false, model = FakeModel { _, _ -> "那天的一段经历" })
        seedCandidate(off)
        assertTrue(off.formEpisodes().skipped)
        assertTrue(off.hubs().isEmpty())
        off.close()

        val noModel = episodes(binding = true, model = null)
        seedCandidate(noModel)
        assertEquals("There is no model.", noModel.formEpisodes().skipReason)
        noModel.close()
    }

    @Test
    fun aBlankNarrationIsRetriedOnTheNextSleep() = runBlocking {
        var reply = ""
        val lattice = episodes(binding = true, model = FakeModel { _, _ -> reply })
        seedCandidate(lattice)
        assertEquals(0, lattice.formEpisodes().count, "a blank narration makes no hub")
        reply = "那天团队完成了部署并一起庆祝。"
        assertEquals(1, lattice.formEpisodes().count, "the unchanged set is tried again")
        lattice.close()
    }

    @Test
    fun aFaithfulLongChineseNarrationMaterializesWhole() = runBlocking {
        val narration =
            "2025年3月10日这一天过得格外充实：上午我先去实验室把拖了很久的统计学作业最后一部分数据整理完毕并按时" +
                "提交了课程报告，随后还回复了导师发来的几封邮件并预约了下周的组会时间，中午和整个项目团队一起在食堂庆" +
                "祝我们的服务顺利上线并合影留念，下午回家的路上顺道去了一趟超市采购牛奶鸡蛋面包和一些日常清洁用品，又" +
                "在楼下的咖啡馆坐了一会儿整理思绪，傍晚到家后还抽空给远在老家的父母打了个电话聊了聊最近的工作与生活近" +
                "况，晚上则安静地读了几页书、整理好明天出差的行程并收拾好行李才踏实入睡。"
        assertTrue(narration.length in 200..EpisodeNarrator.EPISODE_NARRATION_MAX)
        val lattice = episodes(binding = true, model = FakeModel { _, _ -> narration })
        seedCandidate(lattice)
        assertEquals(1, lattice.formEpisodes().count)
        val hub = lattice.hubs().single()
        assertEquals(narration, hub.content, "the hub carries the whole narration")
        assertEquals(3, lattice.withGraph { it.episodeMembers(hub.id).size })
        lattice.close()
    }

    // --- entities ----------------------------------------------------------------------------------------------------

    @Test
    fun entitiesAreResolvedAndTheirAliasesMerged() = runBlocking {
        val model = FakeModel { task, user ->
            check(task == MemoryTask.ENTITIES)
            if (user.startsWith("(")) {
                // Resolution: each "(m<id>) content" line names the variant its content uses.
                val per = Regex("\\((m\\d+)\\)([^\\n]*)").findAll(user).joinToString(",") { m ->
                    val name = if ("Karim" in m.groupValues[2]) "Rafi Karim Ahmed" else "Rafi"
                    """{"ref":"${m.groupValues[1]}","entities":["$name"]}"""
                }
                """{"occasion":null,"key_entities":[],"per_memory":[$per]}"""
            } else {
                """{"groups":[["Rafi","Rafi Karim Ahmed"]]}"""
            }
        }
        val settings = LatticeTestKit.settings().copy(entityExtraction = true)
        val lattice = LatticeTestKit.open(settings = settings, model = model)
        lattice.withGraph { g ->
            g.remember("Rafi sent the design review notes")
            g.remember("Rafi Karim Ahmed fixed the build on friday")
        }
        val report = lattice.sleep(setOf(SleepPhase.ENTITIES, SleepPhase.CANONICALIZE_ENTITIES))
        assertEquals(listOf(2, 1), report.phases.map { it.count }, "$report")
        assertEquals(1, lattice.stats().entities, "the two variants are one entity now")
        assertTrue(model.threads(MemoryTask.ENTITIES).all { it == "lattice-test-narrate" })
        lattice.close()
    }
}
