package org.foedusprogramme.lattice.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one-call scene narrator: gates, both faithfulness modes, and the typed ledger. */
class SceneNarratorTest {

    private class Llm(val replies: MutableList<String>) : MemoryModel {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override fun complete(task: MemoryTask, system: String, user: String): String {
            assertEquals(MemoryTask.NARRATE, task)
            calls += system to user
            return replies.removeFirst()
        }
    }

    private val members = listOf(
        SceneNarrator.Member(1, 101, "Ethan 说要在周末给妹妹一个惊喜"),
        SceneNarrator.Member(2, 102, "两人在同里古镇划了船"),
        SceneNarrator.Member(3, 103, "惊喜揭晓：Ethan 亲手烤了一个蛋糕作为礼物"),
    )

    private fun input(
        threads: List<SceneNarrator.CandidateThread> = emptyList(),
        previous: String? = "苏州早晨",
        next: String? = "回上海",
    ) = SceneNarrator.Input(
        title = "同里古镇",
        synopsis = "划船与惊喜",
        members = members,
        transcript = listOf("[user] 到了！", "[assistant] 这就是我说的惊喜"),
        dateFrom = "2026-07-22",
        dateTo = "2026-07-23",
        candidateThreads = threads,
        previousTitle = previous,
        nextTitle = next,
    )

    @Test
    fun `narration and every ledger field are parsed into the typed result`() {
        val llm = Llm(
            mutableListOf(
                """
                Here you go:
                ```json
                {"narration": "在同里古镇划船，晚上揭晓了惊喜。",
                 "threads": {
                   "opened": [{"description": "下次去看海", "setup": [2]}, {"description": "no refs", "setup": [9]}],
                   "advanced": [{"id": 5, "refs": ["#1"]}, {"id": 99, "refs": [1]}],
                   "closed": [{"id": 7, "resolve": [3, "m1"]}],
                   "dropped": [8, 99]
                 }}
                ```
                """.trimIndent(),
            ),
        )
        val threads = listOf(
            SceneNarrator.CandidateThread(5, "旅行计划"),
            SceneNarrator.CandidateThread(7, "周末的惊喜"),
            SceneNarrator.CandidateThread(8, "学做饭"),
        )

        val result = SceneNarrator(llm).narrate(input(threads))

        val ok = assertIs<SceneNarrator.Result.Success>(result)
        assertEquals("在同里古镇划船，晚上揭晓了惊喜。", ok.narration)
        assertEquals(listOf(SceneNarrator.OpenedThread("下次去看海", listOf(102L))), ok.ledger.opened, "an opened thread without valid refs is dropped")
        assertEquals(listOf(SceneNarrator.AdvancedThread(5, listOf(101L))), ok.ledger.advanced, "unknown thread ids are dropped")
        assertEquals(listOf(SceneNarrator.ClosedThread(7, listOf(103L, 101L))), ok.ledger.closed)
        assertEquals(listOf(8L), ok.ledger.dropped)
        // The candidate threads were shown with their ids.
        val user = llm.calls.single().second
        assertTrue("id=7 周末的惊喜" in user, user)
        assertTrue("[3] 惊喜揭晓" in user, user)
        assertTrue("这就是我说的惊喜" in user, "the transcript excerpt is included")
        assertTrue("Dates: 2026-07-22 – 2026-07-23" in user, user)
    }

    @Test
    fun `a narration without a ledger is fine`() {
        val result = SceneNarrator(Llm(mutableListOf("""{"narration":"划了船。"}"""))).narrate(input())
        val ok = assertIs<SceneNarrator.Result.Success>(result)
        assertTrue(ok.ledger.isEmpty)
    }

    @Test
    fun `gates - blank, too long, verbatim copy, unparseable, model failure, no members`() {
        fun reason(reply: String): SceneNarrator.Reason =
            assertIs<SceneNarrator.Result.Failure>(SceneNarrator(Llm(mutableListOf(reply))).narrate(input())).reason

        assertEquals(SceneNarrator.Reason.BLANK, reason("""{"narration":"  "}"""))
        assertEquals(SceneNarrator.Reason.BLANK, reason("""{"threads":{}}"""))
        assertEquals(SceneNarrator.Reason.TOO_LONG, reason("""{"narration":"${"长".repeat(EpisodeNarrator.EPISODE_NARRATION_MAX + 1)}"}"""))
        assertEquals(SceneNarrator.Reason.VERBATIM_COPY, reason("""{"narration":"两人在同里古镇划了船"}"""))
        assertEquals(SceneNarrator.Reason.UNPARSEABLE, reason("I cannot do that"))
        val down = SceneNarrator(MemoryModel { _, _, _ -> throw MemoryModelUnavailableException("down") }).narrate(input())
        assertEquals(SceneNarrator.Reason.UNAVAILABLE, assertIs<SceneNarrator.Result.Failure>(down).reason)
        val none = SceneNarrator(Llm(mutableListOf())).narrate(input().copy(members = emptyList()))
        assertEquals(SceneNarrator.Reason.NO_MEMBERS, assertIs<SceneNarrator.Result.Failure>(none).reason)
        // A narration exactly at the cap passes.
        val atCap = SceneNarrator(Llm(mutableListOf("""{"narration":"${"长".repeat(EpisodeNarrator.EPISODE_NARRATION_MAX)}"}"""))).narrate(input())
        assertIs<SceneNarrator.Result.Success>(atCap)
    }

    @Test
    fun `anchors mode shows neighbour titles with the no-facts rule, and makes one call`() {
        val llm = Llm(mutableListOf("""{"narration":"划了船。"}"""))
        assertIs<SceneNarrator.Result.Success>(SceneNarrator(llm, SceneNarrator.Mode.ANCHORS).narrate(input()))
        val user = llm.calls.single().second
        assertTrue("Previous scene: 苏州早晨" in user, user)
        assertTrue("Next scene: 回上海" in user, user)
        assertTrue("only mark where this scene begins and ends" in user, user)
        assertTrue("Never write their facts" in user, user)
    }

    // the narrator's rule cites the neighbour titles as evidence of an established imagined
    // world, so both modes send them — with the same boundaries-only / no-facts wording — and the self-check
    // sees them too.
    @Test
    fun `self-check mode also sends the neighbour titles, as boundaries that may show an established world`() {
        val llm = Llm(mutableListOf("""{"narration":"在同里古镇划了船。"}""", """{"faithful": true}"""))
        assertIs<SceneNarrator.Result.Success>(SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK).narrate(input()))
        for ((i, call) in llm.calls.withIndex()) {
            val user = call.second
            assertTrue("Previous scene: 苏州早晨" in user && "Next scene: 回上海" in user, "call $i:\n$user")
            assertTrue("only mark where this scene begins and ends" in user, "call $i:\n$user")
            assertTrue("may show an established shared imagined world" in user, "call $i:\n$user")
            assertTrue("Never write their facts" in user, "call $i:\n$user")
        }
        // No neighbours: no neighbour block in either call.
        val bare = Llm(mutableListOf("""{"narration":"在同里古镇划了船。"}""", """{"faithful": true}"""))
        SceneNarrator(bare, SceneNarrator.Mode.SELF_CHECK).narrate(input(previous = null, next = null))
        assertTrue(bare.calls.none { "Previous scene" in it.second })
    }

    @Test
    fun `self-check sees the scene's dates and title as given facts`() {
        // without them the checker vetoed every dated narration ("the memories contain no dates") and a
        // 53-member scene was abandoned after three vetoes. Narrations no longer carry dates, but a
        // relative time ("那天夜里") is still checked against them.
        val llm = Llm(mutableListOf("""{"narration":"在同里古镇划了船。"}""", """{"faithful": true}"""))
        SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK).narrate(input())
        val check = llm.calls[1].second
        assertTrue("2026-07-22" in check && "2026-07-23" in check, "self-check input carries the date range:\n$check")
        assertTrue("同里古镇" in check.substringBefore("## Memories"), "and the scene title")
    }

    @Test
    fun `self-check sees the same transcript excerpt the narration may draw on`() {
        // the narrator is told to use the memories AND the transcript, but the checker only saw the
        // memories, so a transcript-grounded detail (a detail only the scene's own messages gave) was vetoed three times
        // and 5 of 14 scenes were abandoned — losing their hubs and their thread-ledger changes.
        val llm = Llm(mutableListOf("""{"narration":"在同里古镇划了船。"}""", """{"faithful": true}"""))
        SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK).narrate(input())
        val check = llm.calls[1].second
        assertTrue("这就是我说的惊喜" in check, "self-check input carries the transcript excerpt:\n$check")
        assertTrue("transcript" in SceneNarrator.SELF_CHECK_PROMPT.lowercase(), "and the checker is told it is evidence")
    }

    @Test
    fun `self-check mode adds a check call, and a veto fails`() {
        val veto = Llm(mutableListOf("""{"narration":"在苏州划了船。"}""", """{"faithful": false, "issue": "苏州 is the previous scene"}"""))
        val vetoed = SceneNarrator(veto, SceneNarrator.Mode.SELF_CHECK).narrate(input())
        assertEquals(SceneNarrator.Reason.SELF_CHECK_VETO, assertIs<SceneNarrator.Result.Failure>(vetoed).reason)
        assertEquals(2, veto.calls.size)
        assertEquals(SceneNarrator.SELF_CHECK_PROMPT, veto.calls[1].first)
        assertTrue("在苏州划了船。" in veto.calls[1].second && "[2] 两人在同里古镇划了船" in veto.calls[1].second)

        val pass = Llm(mutableListOf("""{"narration":"在同里古镇划了船。"}""", """{"faithful": true}"""))
        assertIs<SceneNarrator.Result.Success>(SceneNarrator(pass, SceneNarrator.Mode.SELF_CHECK).narrate(input()))

        val garbled = Llm(mutableListOf("""{"narration":"在同里古镇划了船。"}""", "looks fine to me"))
        val g = SceneNarrator(garbled, SceneNarrator.Mode.SELF_CHECK).narrate(input())
        assertEquals(SceneNarrator.Reason.SELF_CHECK_VETO, assertIs<SceneNarrator.Result.Failure>(g).reason, "no verdict = veto")
    }

    // An unreachable self-check model is an outage, not a veto.
    @Test
    fun `a self-check call that throws is UNAVAILABLE, not a veto, and keeps the reply's ledger`() {
        val llm = object : MemoryModel {
            var calls = 0
            override fun complete(task: MemoryTask, system: String, user: String): String {
                if (++calls == 2) throw MemoryModelUnavailableException("self-check model down")
                return """{"narration":"在同里古镇划了船。","threads":{"opened":[{"name":"周末惊喜","description":"周末的惊喜","setup":[1]}]}}"""
            }
        }
        val r = SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK).narrate(input())
        val failure = assertIs<SceneNarrator.Result.Failure>(r)
        assertEquals(SceneNarrator.Reason.UNAVAILABLE, failure.reason, failure.detail)
        assertTrue("self-check" in failure.detail, failure.detail)
        assertEquals(1, failure.ledger?.opened?.size, "the narration reply's ledger is still carried")
    }

    @Test
    fun `a long transcript is cut in the middle, keeping head and tail`() {
        val llm = Llm(mutableListOf("""{"narration":"划了船。"}"""))
        val lines = (1..400).map { "[user] line %03d %s".format(it, "x".repeat(50)) }
        SceneNarrator(llm, maxTranscriptChars = 2_000).narrate(input().copy(transcript = lines))
        val user = llm.calls.single().second
        assertTrue("line 001" in user && "line 400" in user, "head and tail kept")
        assertFalse("line 200" in user, "middle elided")
        assertTrue("…" in user)
    }

    @Test
    fun `the prompt defines a thread as one concrete promise or suspense, never a topic`() {
        // a run opened nine "discussion topic" threads in scene 1 and later closed a 27-set-up catch-all,
        // marking unrelated memories settled.
        val p = SceneNarrator.SYSTEM_PROMPT
        assertTrue("ONE concrete, specific promise, plan, surprise, unanswered question or unresolved suspense" in p, p)
        assertTrue("expected concrete outcome" in p, p)
        assertTrue("Never a discussion topic, theme, mood or relationship state" in p, p)
        assertTrue("a surprise promised for the weekend" in p, p)
        assertTrue("short and specific" in p, p)
        assertTrue("At most ${SceneNarrator.MAX_THREAD_REFS} memories" in p, p)
        assertTrue("Never close a thread without the memories that resolve it" in p, p)
    }

    @Test
    fun `thread refs are capped per thread, and a close without resolve refs is dropped`() {
        val many = (1..9).map { SceneNarrator.Member(it, 200L + it, "记忆 $it 关于约定") }
        val refs = (1..9).joinToString(",")
        val llm = Llm(
            mutableListOf(
                """{"narration":"一个场景。","threads":{
                  "opened":[{"description":"周末的惊喜","setup":[$refs]}],
                  "advanced":[{"id":5,"refs":[$refs]}],
                  "closed":[{"id":7,"resolve":[]},{"id":8},{"id":9,"resolve":[99]}]}}""",
            ),
        )
        val threads = listOf(5L, 7L, 8L, 9L).map { SceneNarrator.CandidateThread(it, "t$it") }

        val ok = assertIs<SceneNarrator.Result.Success>(SceneNarrator(llm).narrate(input(threads).copy(members = many)))

        assertEquals((201L..206L).toList(), ok.ledger.opened.single().setupEngramIds, "the first ${SceneNarrator.MAX_THREAD_REFS} refs")
        assertEquals((201L..206L).toList(), ok.ledger.advanced.single().engramIds)
        assertTrue(ok.ledger.closed.isEmpty(), "a close must name a known resolve memory: ${ok.ledger.closed}")
    }

    @Test
    fun `the narration takes the language of the transcript, not of earlier output`() {
        // Same rule as NativeMemoryExtractor.buildPrompt, language-neutral: borrowed technical terms do not
        // tip the language, and the title / memories (model output that may have drifted) never decide it.
        val p = SceneNarrator.SYSTEM_PROMPT
        assertTrue("the language the transcript excerpt is written in" in p.replace(Regex("\\s+"), " "), p)
        assertTrue("words borrowed from another language" in p.replace(Regex("\\s+"), " "), "borrowed-term carve-out named:\n$p")
        assertTrue("keep such terms as they are" in p.replace(Regex("\\s+"), " "), "borrowed terms are kept as-is:\n$p")
        assertTrue("never decide the language" in p.replace(Regex("\\s+"), " "), "earlier output does not decide:\n$p")
        assertFalse("stays Chinese" in p || "dominant language" in p, "no language is the reference:\n$p")
        // 80ec1f4f: a hub said the person "admitted they may have fabricated" a gallery visit the assistant had doubted.
        assertTrue("never narrate it as false, made up or doubtful" in p.replace(Regex("\\s+"), " "), p)
        // The reply names the language before the narration (a rule alone still let DeepSeek drift).
        assertTrue("""{"language": "…", "narration"""" in p, p)
    }

    @Test
    fun `the narration speaks as the assistant in the first person and names the human counterpart`() {
        // hub narrations mixed "我", "助手", "assistant", "云舒" and "Aria" for the same speaker. The
        // extraction prompt already writes the assistant's memories in the first person (selfAliases); the
        // narration follows the same rule.
        val p = SceneNarrator.SYSTEM_PROMPT
        assertTrue("first person" in p, p)
        assertTrue("\"the assistant\"" in p && "\"AI\"" in p && "in any language, and never" in p.replace(Regex("\\s+"), " "), "the third-person labels are forbidden in any language:\n$p")

        val llm = Llm(mutableListOf("""{"narration":"我和 Ethan 在同里古镇划了船。"}"""))
        val narrator = SceneNarrator(llm, selfAliases = setOf("Aria", "云舒", "小舒"), userName = "Ethan")
        assertIs<SceneNarrator.Result.Success>(narrator.narrate(input()))
        val (system, user) = llm.calls.single()
        assertEquals(SceneNarrator.SYSTEM_PROMPT, system, "the system prompt stays byte-stable; the names ride in the input")
        val who = user.substringAfter("## Who is who\n").substringBefore("\n\n")
        assertTrue("Aria, 云舒, 小舒" in who, who)
        assertTrue("Ethan" in who, who)

        // Without names the rule still holds; no names are invented.
        val bare = Llm(mutableListOf("""{"narration":"我们在同里古镇划了船。"}"""))
        SceneNarrator(bare).narrate(input())
        val bareWho = bare.calls.single().second.substringAfter("## Who is who\n").substringBefore("\n\n")
        assertFalse("Aria" in bareWho || "Ethan" in bareWho, bareWho)
    }

    @Test
    fun `a scene between two named people is narrated by an observer, in the third person`() {
        fun msg(pos: Long, role: org.foedusprogramme.lattice.ConversationMessage.Role, name: String?, text: String = "hi there") =
            org.foedusprogramme.lattice.ConversationMessage(pos, role, text, java.time.Instant.EPOCH, speakerName = name)
        val user = org.foedusprogramme.lattice.ConversationMessage.Role.USER
        val assistant = org.foedusprogramme.lattice.ConversationMessage.Role.ASSISTANT
        assertEquals(listOf("Caroline", "Melanie"), SceneNarrator.observedSpeakers(listOf(msg(1, user, "Caroline"), msg(2, user, "Melanie"), msg(3, user, "Caroline"))))
        assertEquals(emptyList(), SceneNarrator.observedSpeakers(listOf(msg(1, user, "Caroline"), msg(2, assistant, null), msg(3, user, "Melanie"))), "the assistant speaks: its own memory")
        assertEquals(emptyList(), SceneNarrator.observedSpeakers(listOf(msg(1, user, null), msg(2, assistant, null))), "LongMemEval's user and assistant")
        assertEquals(emptyList(), SceneNarrator.observedSpeakers(listOf(msg(1, user, "Caroline"), msg(2, user, "Caroline"))), "one person is no conversation to observe")

        val llm = Llm(mutableListOf("""{"narration":"Caroline told Melanie about the support group."}""", """{"faithful": true}"""))
        val result = SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK).narrate(input().copy(observed = listOf("Caroline", "Melanie")))
        assertIs<SceneNarrator.Result.Success>(result)
        for ((_, user) in llm.calls) {
            val who = user.substringAfter("## Who is who\n").substringBefore("\n\n")
            assertEquals("You take no part in this chat: it is between Caroline and Melanie (each line names its speaker). Write about each of them in the third person, by name.", who.trim())
        }
        assertTrue("narrate as an observer" in SceneNarrator.SYSTEM_PROMPT)
        assertTrue("the narration is an observer's" in SceneNarrator.SELF_CHECK_PROMPT.replace(Regex("\\s+"), " "))
        assertEquals("A, B and C", SceneNarrator.observedList(listOf("A", "B", "C")))
    }

    @Test
    fun `self-check accepts first-person narration by the assistant and knows its names`() {
        val p = SceneNarrator.SELF_CHECK_PROMPT
        assertTrue("first person" in p, p)
        assertTrue("not a reason to veto" in p, p)
        val llm = Llm(mutableListOf("""{"narration":"我和 Ethan 在同里古镇划了船。"}""", """{"faithful": true}"""))
        SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK, selfAliases = setOf("Aria", "云舒"), userName = "Ethan").narrate(input())
        val check = llm.calls[1].second
        assertTrue("## Who is who" in check && "Aria, 云舒" in check && "Ethan" in check, check)
    }

    @Test
    fun `an imagined first-person situation is narrated as lived experience and the self-check does not demand the frame`() {
        // User decision (global): a scene the conversation spent inside an imagined situation ("闭上眼睛", "想象一下",
        // "假装我们在海边") is narrated directly, never as "角色扮演/扮演/role-play"; the frame is mentioned only when the
        // conversation itself discusses it ("跳出角色", "这不是扮演").
        val p = SceneNarrator.SYSTEM_PROMPT
        assertTrue("imagined situation" in p, p)
        assertTrue("lived first-person experience" in p, p)
        assertTrue("\"role-play\"" in p && "\"pretend\", in any language" in p, p)
        assertTrue("ONLY when the conversation itself talks about the frame" in p, p)
        val c = SceneNarrator.SELF_CHECK_PROMPT
        assertTrue("imagined situation" in c, c)
        assertTrue("without calling it imagined or role-play" in c, c)
        assertTrue("never a reason to veto" in c, c)
        // narrowed to a shared imagined scene; real-life what-ifs / plans stay what they are.
        SceneSegmenterTest.assertImaginedRuleIsNarrowed(p, "narrator")
        assertTrue("shared imagined scene" in c, "the no-veto clause covers only a shared imagined scene:\n$c")
        assertTrue("real-life" in c && "IS a reason to veto" in c, "a real-life claim presented as done is still vetoed:\n$c")
        // a world established earlier counts without an opener in view; idioms are not what-ifs.
        SceneSegmenterTest.assertImaginedRuleCoversEstablishedWorlds(p, "narrator")
        SceneSegmenterTest.assertImaginedRuleCoversEstablishedWorlds(c, "self-check")
        // the excerpt drops the middle of a long scene, so the memories are evidence of a
        // what-if too; and an established world needs visible evidence, not just in-world-sounding talk.
        assertTrue("when the memories or the transcript excerpt show it" in c, "either source shows the what-if:\n$c")
        assertFalse("only when the transcript excerpt itself shows" in c, c)
        val sources = p.substringAfter("already established earlier").substringBefore("counts as shared")
        assertTrue("synopsis" in sources && "neighbour" in sources && "opener" in sources, sources)
    }

    @Test
    fun `the self-check sees the synopsis the established-world rule cites`() {
        val llm = Llm(mutableListOf("""{"narration":"在同里古镇划了船。"}""", """{"faithful": true}"""))
        SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK).narrate(input())
        assertTrue("Synopsis: 划船与惊喜" in llm.calls[1].second.substringBefore("## Memories"), llm.calls[1].second)
    }

    @Test
    fun `the narration is told not to write dates, and the self-check does not require them`() {
        val p = SceneNarrator.SYSTEM_PROMPT
        assertFalse("anchored to its dates" in p, p)
        assertTrue("Do not write dates" in p, p)
        val c = SceneNarrator.SELF_CHECK_PROMPT
        assertTrue("dates are given facts" in c, c)
        assertTrue("need not mention the dates" in c, c)
    }

    @Test
    fun `a leading date clause the model still writes is stripped before the gates and the self-check`() {
        fun narrate(text: String): SceneNarrator.Result {
            val llm = Llm(mutableListOf("""{"narration":"$text"}""", """{"faithful": true}"""))
            val r = SceneNarrator(llm, SceneNarrator.Mode.SELF_CHECK).narrate(input())
            if (r is SceneNarrator.Result.Success) assertTrue(r.narration in llm.calls[1].second, "self-check sees the stripped text")
            return r
        }
        fun ok(text: String) = assertIs<SceneNarrator.Result.Success>(narrate(text)).narration

        assertEquals("我们在同里古镇划了船。", ok("2026-08-02，我们在同里古镇划了船。"))
        assertEquals("我们整理完了旧照片。", ok("2026年7月29日深夜至30日,我们整理完了旧照片。"))
        assertEquals("Ethan 请我帮忙改简历。", ok("2026年7月31日夜至8月2日晨，Ethan 请我帮忙改简历。"))
        assertEquals("我们去了苏州。", ok("7月31日～8月2日：我们去了苏州。"))
        assertEquals("Ethan and I painted the fence.", ok("On 2026-08-02, Ethan and I painted the fence."))
        assertEquals("Ethan and I painted the fence.", ok("2026-08-02 – 2026-08-03: Ethan and I painted the fence."))
        // Not a leading date clause: left alone.
        assertEquals("7月的旅行里我们划了船。", ok("7月的旅行里我们划了船。"))
        assertEquals("那天我们在同里古镇划了船。", ok("那天我们在同里古镇划了船。"))
        // Nothing left but the date: the blank gate.
        assertEquals(SceneNarrator.Reason.BLANK, assertIs<SceneNarrator.Result.Failure>(narrate("2026-08-02，")).reason)
    }

    @Test
    fun `mode follows the setting`() {
        assertEquals(SceneNarrator.Mode.ANCHORS, SceneNarrator.Mode.fromSetting("anchors"))
        assertEquals(SceneNarrator.Mode.SELF_CHECK, SceneNarrator.Mode.fromSetting("selfCheck"))
        assertEquals(SceneNarrator.Mode.ANCHORS, SceneNarrator.Mode.fromSetting("bogus"))
    }

    @Test
    fun `a narration the shutdown or an interrupt cut off is SHUTDOWN, never a counted failure`() {
        for (cause in listOf(MemoryModelShutdownException("closing"), MemoryModelInterruptedException("stopped"))) {
            val r = SceneNarrator(MemoryModel { _, _, _ -> throw cause }).narrate(input())
            assertEquals(SceneNarrator.Reason.SHUTDOWN, assertIs<SceneNarrator.Result.Failure>(r).reason)
        }
    }

    @Test
    fun `an opened thread carries a short noun-first name beside its fuller description`() {
        // A long closed thread's description "Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物" was cut at 20 chars in
        // the recall annotation to "Ethan答应周末搬完家后在新厨房里揭晓…", losing the noun (the gift).
        val long = "Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物"
        val tooLong = "很".repeat(RecallFormatter.THREAD_NAME_CHARS + 1)
        val llm = Llm(
            mutableListOf(
                """{"narration":"在同里古镇划了船。","threads":{"opened":[
                  {"name":"「周末的惊喜礼物」","description":"$long","setup":[1]},
                  {"description":"下次去看海","setup":[2]},
                  {"name":"   ","description":"冬天的滑雪之约","setup":[2]},
                  {"name":"$tooLong","description":"学做饭","setup":[2]},
                  {"name":"the promised winter ski trip next year","description":"a trip","setup":[2]},
                  {"name":"the weekend gift","description":"a gift","setup":[2]}
                ]}}""",
            ),
        )

        val opened = assertIs<SceneNarrator.Result.Success>(SceneNarrator(llm).narrate(input())).ledger.opened

        assertEquals(SceneNarrator.OpenedThread(long, listOf(101L), name = "周末的惊喜礼物"), opened[0], "quotes stripped")
        assertNull(opened[1].name, "a missing name stays missing")
        assertNull(opened[2].name, "a blank name is no name")
        assertEquals("冬天的滑雪之约", opened[2].description)
        assertNull(opened[3].name, "an over-long name is dropped: the description is the fallback")
        assertNull(opened[4].name, "an over-long name in another language too")
        assertEquals("the weekend gift", opened[5].name)
    }

    @Test
    fun `the prompt asks for a short noun-first name, and candidates show it`() {
        val p = SceneNarrator.SYSTEM_PROMPT
        assertTrue("\"name\"" in p, "the JSON contract carries a name:\n$p")
        assertTrue("noun-first" in p, p)
        assertTrue("at most 12" in p, p)
        assertTrue("\"the weekend gift\"" in p && "\"the winter ski trip\"" in p, p)

        val llm = Llm(mutableListOf("""{"narration":"划了船。"}"""))
        val threads = listOf(
            SceneNarrator.CandidateThread(7, "Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物", name = "周末的惊喜礼物"),
            SceneNarrator.CandidateThread(8, "学做饭"),
        )
        SceneNarrator(llm).narrate(input(threads))
        val user = llm.calls.single().second
        assertTrue("id=7 周末的惊喜礼物 — Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物\n" in user, user)
        assertTrue("id=8 学做饭\n" in user, user)
    }
}
