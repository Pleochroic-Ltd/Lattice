package org.foedusprogramme.lattice.core

import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-lingual JUDGED dedup (sleep-only phase 2, [LatticeGraph.judgedSupersedePass]) — the
 * mock-judge unit layer. bge-m3 scores a zh↔en restatement of the SAME fact ~0.63–0.79, below the
 * same-language dedupThreshold and geometrically indistinguishable from a cross-lingual RELATED
 * pair, so the SOLE decider is an LLM judge, voted unanimously, over a cosine candidate band. These
 * tests pin the geometry filter, the guards, the unanimity gate, the non-destructive/reversible
 * merge, the verdict cache, and the cross-lingual detector — all with a scripted judge and a
 * scripted embedder (no network).
 */
class LatticeCrossLingualDedupTest {

    private val clock = { Instant.parse("2026-08-03T00:00:00Z") }

    /** Judged phase-2 config: judge band [0.68, 0.86), 3-vote unanimity, auto-link OFF (0.99) so a
     *  markSuperseded SUPERSEDES edge is never masked by a SEMANTIC auto-link, salience raw. */
    private fun cfg(votes: Int = 3, budget: Int = 200) = LatticeGraph.Config(
        autoLinkThreshold = 0.99,
        dedupThreshold = 0.86,
        judgeFloor = 0.68,
        judgeVotes = votes,
        judgeBudget = budget,
        neuromodulatorySalience = false,
        seedFloor = 0.0,
        emitFloor = 0.0,
    )

    /** Deterministic embedder: registers explicit unit vectors so a pair lands on an EXACT cosine
     *  (2D block per pair); any two different blocks are orthogonal (cosine 0). Unregistered text
     *  gets its own fresh orthogonal block. */
    private class ScriptedEmbedder(override val dimension: Int = 512) : Embedder {
        override val model: String get() = "scripted"
        private val map = HashMap<String, FloatArray>()
        private var nextBlock = 0
        fun pair(textA: String, textB: String, cos: Double) {
            val p = nextBlock; nextBlock += 2
            val a = FloatArray(dimension); a[p] = 1f
            val b = FloatArray(dimension); b[p] = cos.toFloat(); b[p + 1] = sqrt(1.0 - cos * cos).toFloat()
            map[textA] = a; map[textB] = b
        }
        override fun embed(text: String): FloatArray = map.getOrPut(text) {
            val p = nextBlock; nextBlock += 2
            FloatArray(dimension).also { it[p] = 1f }
        }
    }

    private class ScriptedJudge(
        override val modelId: String = "test-judge",
        var script: (Int) -> MergeJudge.Verdict = { MergeJudge.Verdict.SAME },
    ) : MergeJudge {
        var calls = 0
        override fun judge(noteA: String, noteB: String, crossLingualHint: Boolean): MergeJudge.Verdict {
            calls++; return script(calls)
        }
    }

    /** A verdict written through to the store (a graph reads them back at load). */
    private fun TestLatticeStore.verdictOf(hashLo: String, hashHi: String, key: String): String? =
        verdicts[Triple(hashLo, hashHi, key)]?.verdict

    private fun graph(
        embedder: Embedder,
        judge: MergeJudge?,
        config: LatticeGraph.Config = cfg(),
        store: LatticeStore = NoOpLatticeStore,
    ) = LatticeGraph(embedder, config, clock, store, judge)

    private val ZH = "用户喜欢水彩画"
    private val EN = "the user likes watercolors"

    // --- merge decision -----------------------------------------------------

    @Test
    fun unanimousSameCrossLingualPairIsSupersededNonDestructively() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80)
        val judge = ScriptedJudge() // all SAME
        val g = graph(emb, judge)
        val zh = g.remember(ZH, salience = 0.9) // richer -> canonical
        val en = g.remember(EN, salience = 0.5)

        val superseded = g.judgedSupersedePass()

        assertEquals(1, superseded, "the unanimous-SAME cross-lingual pair should be superseded")
        assertEquals(3, judge.calls, "exactly judgeVotes (3) judge calls for one pair")
        // Non-destructive: BOTH nodes and both contents survive (the merge is reversible).
        assertNotNull(g.engram(zh)); assertNotNull(g.engram(en))
        assertEquals(EN, g.engram(en)!!.content, "narrower content preserved (non-destructive)")
        // Narrower redirected to the richer canonical; canonical is current.
        assertEquals(zh, g.engram(en)!!.supersededBy, "en (narrower) supersededBy zh (canonical)")
        assertNull(g.engram(zh)!!.supersededBy, "zh (canonical) is current")
        // A SUPERSEDES edge records the topology for an external undo.
        assertTrue(
            g.neighbors(zh).any { it.to == en && it.type == Edge.Type.SUPERSEDES },
            "a SUPERSEDES edge links canonical -> narrower",
        )
    }

    @Test
    fun splitVoteKeepsBothNodes() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80)
        // [SAME, DIFFERENT, SAME] — a split; a MAJORITY(>=2) rule would merge, unanimity must NOT.
        val judge = ScriptedJudge(script = { i -> if (i == 2) MergeJudge.Verdict.DIFFERENT else MergeJudge.Verdict.SAME })
        val g = graph(emb, judge)
        val zh = g.remember(ZH, salience = 0.9); val en = g.remember(EN, salience = 0.5)

        assertEquals(0, g.judgedSupersedePass(), "a split vote must fail-closed (no merge)")
        assertEquals(2, judge.calls, "the vote ends at the DIFFERENT")
        assertNull(g.engram(zh)!!.supersededBy); assertNull(g.engram(en)!!.supersededBy)
    }

    @Test
    fun unanimousDifferentKeepsBothNodes() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80)
        val judge = ScriptedJudge(script = { MergeJudge.Verdict.DIFFERENT })
        val g = graph(emb, judge)
        val zh = g.remember(ZH, salience = 0.9); val en = g.remember(EN, salience = 0.5)

        assertEquals(0, g.judgedSupersedePass())
        assertNull(g.engram(zh)!!.supersededBy); assertNull(g.engram(en)!!.supersededBy)
    }

    // --- guards (skip BEFORE any judge call) --------------------------------

    @Test
    fun speakerNullnessMismatchSkipsWithoutJudging() {
        val emb = ScriptedEmbedder()
        val gistText = "用户说过喜欢水彩画"
        val quoteText = "I like watercolor painting"
        emb.pair(gistText, quoteText, 0.80)
        val judge = ScriptedJudge()
        val g = graph(emb, judge)
        val gist = g.remember(gistText)
        val other = g.remember("锚点")
        val quote = g.addVerbatim(other, quoteText, Speaker.USER) // speaker != null

        assertEquals(0, g.judgedSupersedePass(), "gist<->verbatim must not merge")
        assertEquals(0, judge.calls, "speaker-nullness guard skips BEFORE the judge")
        assertNull(g.engram(gist)!!.supersededBy); assertNull(g.engram(quote)!!.supersededBy)
    }

    @Test
    fun episodeFactMismatchSkipsWithoutJudging() {
        val emb = ScriptedEmbedder()
        val factText = "用户喜欢水彩画"
        val episodeText = "a session where the user talked about watercolor painting"
        emb.pair(factText, episodeText, 0.80)
        val judge = ScriptedJudge()
        val g = graph(emb, judge)
        g.remember(factText)
        g.remember(episodeText, salience = 0.6, layer = Engram.Layer.SEMANTIC, isEpisode = true)

        assertEquals(0, g.judgedSupersedePass())
        assertEquals(0, judge.calls, "episode/fact guard skips BEFORE the judge")
    }

    // --- temporal anti-fold in the judged path (final sign-off regression) ---

    @Test
    fun distinctEventDatesSkipTheJudgedFoldEvenCrossLingually() {
        // Final sign-off review found the temporal anti-fold (present at encode + phase-1 mergeDuplicates)
        // was MISSING from the cross-lingual judged phase-2 (guardedCandidate). Content is dateless by
        // design, so a content-only judge votes SAME on two restatements carrying DIFFERENT event_dates
        // and hides one distinct-dated event at recall. The guard must skip the pair BEFORE the judge.
        val zh = "项目截止"; val en = "project deadline" // cross-lingual, dateless content
        val emb = ScriptedEmbedder(); emb.pair(zh, en, 0.80) // in the judge band [0.68, 0.86)
        val judge = ScriptedJudge() // would vote SAME

        val g = graph(emb, judge, config = cfg().copy(temporalLayer = true))
        val a = g.remember(zh, salience = 0.9, eventDate = "2025-03")
        val b = g.remember(en, salience = 0.5, eventDate = "2025-06") // rescheduled -> DIFFERENT date

        assertEquals(0, g.judgedSupersedePass(), "distinct-dated pair must NOT be folded by judged phase-2")
        assertEquals(0, judge.calls, "temporal guard skips BEFORE the judge (like speaker/episode guards)")
        assertNull(g.engram(a)!!.supersededBy)
        assertNull(g.engram(b)!!.supersededBy)
    }

    @Test
    fun sameDatedCrossLingualPairStillFoldsSoTheGuardDoesNotOverreach() {
        // Control: with the SAME event_date the guard is inert, so a genuine cross-lingual duplicate
        // still merges — the temporal guard only protects DISTINCT dates, never blocks real dedup.
        val zh = "项目截止"; val en = "project deadline"
        val emb = ScriptedEmbedder(); emb.pair(zh, en, 0.80)
        val judge = ScriptedJudge()

        val g = graph(emb, judge, config = cfg().copy(temporalLayer = true))
        val a = g.remember(zh, salience = 0.9, eventDate = "2025-03")
        val b = g.remember(en, salience = 0.5, eventDate = "2025-03") // SAME date -> real duplicate

        assertEquals(1, g.judgedSupersedePass(), "same-dated cross-lingual pair still folds (guard inert)")
        assertEquals(a, g.engram(b)!!.supersededBy)
    }

    // --- geometry band bounds -----------------------------------------------

    @Test
    fun pairsOutsideTheBandAreNeverJudged() {
        val emb = ScriptedEmbedder()
        emb.pair("用户喜欢猫", "the user likes cats", 0.90) // >= dedupThreshold (0.86): above band
        emb.pair("用户住在北京", "the user lives in Beijing", 0.60) // < judgeFloor (0.68): below band
        val judge = ScriptedJudge()
        val g = graph(emb, judge)
        g.remember("用户喜欢猫"); g.remember("the user likes cats")
        g.remember("用户住在北京"); g.remember("the user lives in Beijing")

        assertEquals(0, g.judgedSupersedePass(), "nothing in the [0.68,0.86) band")
        assertEquals(0, judge.calls)
    }

    @Test
    fun budgetExhaustionDefersJudging() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80)
        val judge = ScriptedJudge()
        val g = graph(emb, judge, config = cfg(budget = 0))
        g.remember(ZH, salience = 0.9); g.remember(EN, salience = 0.5)

        assertEquals(0, g.judgedSupersedePass(), "budget 0 -> defer, no merge")
        assertEquals(0, judge.calls)
    }

    // --- verdict cache ------------------------------------------------------

    @Test
    fun cachedKeepSuppressesReJudging() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80)
        val judge = ScriptedJudge(script = { MergeJudge.Verdict.DIFFERENT }) // KEEP, so pair stays judge-eligible
        val store = TestLatticeStore()
        val g = graph(emb, judge, store = store)
        g.remember(ZH, salience = 0.9); g.remember(EN, salience = 0.5)

        g.judgedSupersedePass()
        assertEquals(1, judge.calls, "cold cache -> voted (the first DIFFERENT ends the vote)")
        assertEquals(1, store.verdicts.size, "a stable KEEP was cached")

        judge.calls = 0
        assertEquals(0, g.judgedSupersedePass(), "cache hit still keeps")
        assertEquals(0, judge.calls, "cached KEEP suppresses re-judging")

        // A graph reopened over the same store reads the verdict back from the load: still no re-judging.
        val emb2 = ScriptedEmbedder(); emb2.pair(ZH, EN, 0.80)
        val reopened = graph(emb2, judge, store = store)
        assertEquals(2, reopened.size)
        assertEquals(0, reopened.judgedSupersedePass())
        assertEquals(0, judge.calls, "a loaded KEEP suppresses re-judging after a restart")
    }

    @Test
    fun ambiguousOutcomeIsNotCachedAndRetries() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80)
        // Backend unavailable => empty => AMBIGUOUS on every call.
        val judge = ScriptedJudge(script = { MergeJudge.Verdict.AMBIGUOUS })
        val store = TestLatticeStore()
        val g = graph(emb, judge, store = store)
        g.remember(ZH, salience = 0.9); g.remember(EN, salience = 0.5)

        assertEquals(0, g.judgedSupersedePass())
        assertTrue(store.verdicts.isEmpty(), "an unparseable/unavailable vote must NOT be cached")
    }

    // --- placement / phase interaction --------------------------------------

    @Test
    fun mergeDuplicatesTriggersJudgedPhaseTwoWhenJudgeWired() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80) // cross-lingual same-fact, in band
        val judge = ScriptedJudge()
        val g = graph(emb, judge)
        val zh = g.remember(ZH, salience = 0.9); val en = g.remember(EN, salience = 0.5)

        // Phase 2 is invoked from INSIDE mergeDuplicates (not a separate call): the cross-lingual
        // pair is superseded even though no direct judgedSupersedePass() was made.
        g.mergeDuplicates()

        assertEquals(3, judge.calls, "mergeDuplicates ran the judged phase-2")
        assertEquals(zh, g.engram(en)!!.supersededBy, "phase-2 superseded the cross-lingual pair")
        assertNotNull(g.engram(zh)); assertNotNull(g.engram(en)) // non-destructive
    }

    @Test
    fun sameLanguageWriteTimeDedupUnchangedAndHotPathIssuesNoJudgeCall() {
        val emb = ScriptedEmbedder()
        // A same-language near-restatement (>= dedupThreshold) is folded at WRITE time by remember's
        // pattern separation — unchanged legacy behavior — and the write hot path never calls the judge.
        emb.pair("用户偏好简体中文回复", "用户喜欢用简体中文回复", 0.90)
        val judge = ScriptedJudge()
        val g = graph(emb, judge)
        g.remember("用户偏好简体中文回复", salience = 0.8)
        g.remember("用户喜欢用简体中文回复", salience = 0.6)

        assertEquals(1, g.size, "write-time dedup still folds the same-language near-duplicate to one node")
        assertEquals(0, judge.calls, "remember() (hot path) issues zero judge calls")
    }

    @Test
    fun theNoteThatSaysMoreIsKeptEvenWhenTheThinnerOneIsMoreSalient() {
        val thin = "这本手账相当于我的备忘录"
        val full = "Ethan 在手账里加了每周回顾：只记不看的本子是仓库，不是备忘录"
        val emb = ScriptedEmbedder(); emb.pair(thin, full, 0.80)
        val g = graph(emb, ScriptedJudge())
        val a = g.remember(thin, salience = 0.95)
        val b = g.remember(full, salience = 0.40)

        assertEquals(1, g.judgedSupersedePass())
        assertEquals(b, g.engram(a)!!.supersededBy, "the thinner note is retired behind the fuller one")
        assertNull(g.engram(b)!!.supersededBy)
    }

    @Test
    fun aDuplicateOfARetiredNoteJoinsItsChainOnlyWhenItSaysNoMore() {
        val stale = "Ethan 和我约好每周跑步"
        val fuller = "Ethan 约我每周六一起跑步，我答应了：先沿河边跑五公里，跑完再一起去吃早餐"
        val thinner = "Ethan 和我约跑步"
        val emb = ScriptedEmbedder(); emb.pair(stale, fuller, 0.80)
        val g = graph(emb, ScriptedJudge())
        val current = g.remember("Ethan 和我定下了每周六晨跑，约好在河边集合，我爽快地答应了")
        val s = g.remember(stale)
        g.markSuperseded(current, s)
        val f = g.remember(fuller)

        assertEquals(0, g.judgedSupersedePass(), "a fuller note is not retired behind a thinner stale one")
        assertNull(g.engram(f)!!.supersededBy)

        val emb2 = ScriptedEmbedder(); emb2.pair(stale, thinner, 0.80)
        val g2 = graph(emb2, ScriptedJudge())
        val current2 = g2.remember("Ethan 和我定下了每周六晨跑，约好在河边集合，我爽快地答应了")
        val s2 = g2.remember(stale)
        g2.markSuperseded(current2, s2)
        val t = g2.remember(thinner)

        assertEquals(1, g2.judgedSupersedePass())
        assertEquals(s2, g2.engram(t)!!.supersededBy, "a thinner duplicate joins the stale note's chain")
    }

    @Test
    fun aSynthesizedSummaryAndAConcreteMemoryAreNeverJudgedOrFolded() {
        val summary = "我和 Ethan 的周末总围绕跑步与早餐展开"
        val concrete = "Ethan 跑完步第一件事就是去买豆浆油条"
        val emb = ScriptedEmbedder(); emb.pair(summary, concrete, 0.80)
        val judge = ScriptedJudge()
        val g = graph(emb, judge)
        g.remember(summary, salience = 0.9, origin = Engram.Origin.SYNTHESIZED)
        g.remember(concrete, salience = 0.5, origin = Engram.Origin.EXTRACTED)

        assertEquals(0, g.judgedSupersedePass())
        assertEquals(0, judge.calls, "the pair is guarded before any judge call")
        assertEquals(0, g.mergeDuplicates(threshold = 0.79, runJudgedPass = false), "nor folded by the geometric pass")
        assertEquals(2, g.size)
    }

    @Test
    fun twoVerbatimQuotesAreNeverJudged() {
        val quoteA = "慢一点也没关系"
        val quoteB = "慢慢来，反而比较快"
        val emb = ScriptedEmbedder(); emb.pair(quoteA, quoteB, 0.80)
        val judge = ScriptedJudge()
        val g = graph(emb, judge)
        val gistId = g.remember("我劝 Ethan 跑步别着急")
        g.addVerbatim(gistId, quoteA, Speaker.ASSISTANT)
        g.addVerbatim(gistId, quoteB, Speaker.ASSISTANT)

        assertEquals(0, g.judgedSupersedePass())
        assertEquals(0, judge.calls)
    }

    @Test
    fun aVerdictGivenUnderAnEarlierPromptIsNeverReapplied() {
        val emb = ScriptedEmbedder(); emb.pair(ZH, EN, 0.80)
        // A SAME cached under the bare model id (the earlier prompt's key) must not merge the pair.
        val hashes = listOf(ZH, EN).map { t ->
            java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted()
        val store = TestLatticeStore(
            initial = LatticeStore.Loaded(
                verdicts = listOf(VerdictRecord(hashes[0], hashes[1], "test-judge", "SAME", 5, clock())),
            ),
        )
        val judge = ModelMergeJudge(MemoryModel { _, _, _ -> "DIFFERENT" }, "test-judge")
        val g = graph(emb, judge, store = store)
        g.remember(ZH, salience = 0.9); g.remember(EN, salience = 0.5)

        assertEquals(0, g.judgedSupersedePass())
        assertEquals("test-judge#${ModelMergeJudge.PROMPT_VERSION}", judge.verdictKey)
        assertEquals("KEEP", store.verdictOf(hashes[0], hashes[1], judge.verdictKey), "re-judged under the current key")
    }

    @Test
    fun theJudgePromptAsksForARestatementNotASharedFact() {
        val prompt = ModelMergeJudge.SYSTEM_PROMPT
        assertTrue("most of what the less detailed note says" in prompt)
        assertTrue("only share a subject, theme" in prompt)
        assertFalse("asymmetric containment counts as SAME" in prompt)
    }

    // --- judge adapter parsing (verbose model output) -----------------------

    @Test
    fun judgeAdapterReadsLeadingVerdictDespiteVerboseProse() {
        fun judgeWith(reply: String) =
            ModelMergeJudge(MemoryModel { _, _, _ -> reply }, "test-judge").judge("a", "b", true)

        // Real model shape: verdict on line 1, explanation after — and the prose contains the OTHER word.
        assertEquals(
            MergeJudge.Verdict.SAME,
            judgeWith("SAME\n\nThe difference in language does not make them different facts."),
            "a leading SAME wins even when the explanation says 'different'",
        )
        assertEquals(
            MergeJudge.Verdict.DIFFERENT,
            judgeWith("DIFFERENT\n\nNote A and Note B are not the same fact."),
        )
        assertEquals(MergeJudge.Verdict.SAME, judgeWith("SAME"))
        assertEquals(MergeJudge.Verdict.DIFFERENT, judgeWith("different"))
        // Empty (backend unavailable) => AMBIGUOUS (fail-closed, not cached).
        assertEquals(MergeJudge.Verdict.AMBIGUOUS, judgeWith(""))
    }

    // --- cross-lingual detector (any-Han flag) ------------------------------

    @Test
    fun crossLingualDetectorAnyHanFlag() {
        assertTrue(CrossLingualDetector.isCrossLingual("用户喜欢水彩画", "the user likes watercolors"))
        assertFalse(CrossLingualDetector.isCrossLingual("用户喜欢A", "用户不喜欢B"), "zh<->zh is not cross-lingual")
        assertFalse(CrossLingualDetector.isCrossLingual("I like cats", "I like dogs"), "en<->en is not cross-lingual")
        // Proper-noun-heavy Chinese stays zh-bearing (a ratio classifier mis-tagged this as English).
        assertTrue(CrossLingualDetector.zhBearing("用户 Pixel42 最喜欢 Stardew Valley 里的角色"))
        assertTrue(
            CrossLingualDetector.isCrossLingual("用户 Pixel42 最喜欢 Stardew Valley", "the user's favorite is Stardew Valley"),
        )
        // A quoted CJK catchphrase inside an English sentence is stripped -> still en-only.
        assertTrue(CrossLingualDetector.enOnly("My favorite genre is 「武侠」 fiction"))
        assertTrue(CrossLingualDetector.isCrossLingual("My favorite genre is 「武侠」 fiction", "用户最喜欢武侠小说"))
    }
}
