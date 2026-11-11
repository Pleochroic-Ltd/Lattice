package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline tests for extraction: a canned [MemoryModel] returns fixed JSON so parsing and the prompt are pinned down. */
class NativeMemoryExtractorTest {

    /** Captures what the extractor sends and answers [reply]. */
    private class CapturingModel(private val reply: String = """{"memories":[],"relations":[]}""") : MemoryModel {
        var lastTask: MemoryTask? = null
        var lastSystem: String = ""
        var lastUser: String = ""
        override fun complete(task: MemoryTask, system: String, user: String): String {
            lastTask = task
            lastSystem = system
            lastUser = user
            return reply
        }
    }

    private fun canned(reply: String) = MemoryModel { _, _, _ -> reply }

    // --- outage vs poison -----------------------------------------------------------------------------------------

    @Test
    fun `a backend outage is passed through, never turned into poison`() {
        // Sleep catch-up counts only poison toward the dead-letter cap, so an outage must stay an outage.
        val outage = MemoryModel { _, _, _ -> throw MemoryModelUnavailableException("backend down (e.g. HTTP 402)") }
        assertFailsWith<MemoryModelUnavailableException> { NativeMemoryExtractor(outage).extract("[user] hi") }
        val shutdown = MemoryModel { _, _, _ -> throw MemoryModelShutdownException("closing") }
        val thrown = assertFailsWith<MemoryModelShutdownException> { NativeMemoryExtractor(shutdown).extract("[user] hi") }
        assertTrue(thrown.isMemoryModelShutdown())
    }

    @Test
    fun `a reply that is not JSON is poison, and it carries the raw output`() {
        assertFailsWith<UnparseableExtractionException> { NativeMemoryExtractor(canned("I could not find anything worth remembering, sorry.")).extract("[user] hi") }
        assertFailsWith<UnparseableExtractionException> { NativeMemoryExtractor(canned("")).extract("[user] hi") }
        assertFailsWith<UnparseableExtractionException> { NativeMemoryExtractor(canned("抱歉，我现在无法处理。")).extract("x") }
        val refusal = "I'm not comfortable extracting memories from this conversation."
        val thrown = assertFailsWith<UnparseableExtractionException> { NativeMemoryExtractor(canned(refusal)).extract("[user] hi") }
        assertEquals(refusal, thrown.rawOutput)
    }

    @Test
    fun `the call is an EXTRACT task`() {
        val model = CapturingModel()
        NativeMemoryExtractor(model).extract("[user] hi")
        assertEquals(MemoryTask.EXTRACT, model.lastTask)
        assertEquals(NativeMemoryExtractor.buildPrompt(null), model.lastSystem)
    }

    // --- parsing --------------------------------------------------------------------------------------------------

    @Test
    fun `parses memories and relations from model JSON`() {
        val reply = """
            这是模型的回答：
            {
              "memories": [
                {"ref": "m1", "content": "用户在本地跑 gemma4 时遇到显存不足导致加载失败", "salience": 0.7, "layer": "EPISODIC"},
                {"ref": "m2", "content": "把 n_ctx 降到 4096 后加载失败解决了", "salience": 0.8, "layer": "EPISODIC"},
                {"ref": "m3", "content": "用户偏好用简体中文交流", "salience": 0.6, "layer": "SEMANTIC"}
              ],
              "relations": [
                {"from": "m1", "to": "m2", "type": "CAUSAL"}
              ]
            }
            希望有帮助。
        """.trimIndent()
        val extraction = NativeMemoryExtractor(canned(reply)).extract("（对话原文）")
        assertEquals(3, extraction.memories.size)
        val m3 = extraction.memories.first { it.ref == "m3" }
        assertEquals(Engram.Layer.SEMANTIC, m3.layer)
        assertEquals(0.6, m3.salience, 1e-9)
        assertEquals(listOf(ExtractedRelation("m1", "m2", Edge.Type.CAUSAL)), extraction.relations)
    }

    @Test
    fun `relations to unknown refs, to themselves or of unknown types are dropped`() {
        val reply = """{"memories":[{"ref":"a","content":"内容甲"},{"ref":"b","content":"内容乙"}],
            "relations":[{"from":"a","to":"ghost","type":"CAUSAL"},{"from":"a","to":"a","type":"CAUSAL"},
            {"from":"a","to":"b","type":"RELATED"},{"from":"b","to":"a","type":"updates"}]}"""
        val extraction = NativeMemoryExtractor(canned(reply)).extract("x")
        assertEquals(2, extraction.memories.size)
        assertEquals(listOf(ExtractedRelation("b", "a", Edge.Type.SUPERSEDES)), extraction.relations)
    }

    @Test
    fun `an empty result is a valid result`() {
        val extraction = NativeMemoryExtractor(canned("""{"memories":[],"relations":[]}""")).extract("x")
        assertTrue(extraction.memories.isEmpty() && extraction.relations.isEmpty())
    }

    @Test
    fun `memories without a ref or content are dropped, missing fields take their defaults`() {
        val e = NativeMemoryExtractor.parse("""{"memories":[{"ref":"","content":"x"},{"ref":"m","content":" "},{"ref":"k","content":" kept ","salience":7}]}""")!!
        val m = e.memories.single()
        assertEquals("kept", m.content)
        assertEquals(1.0, m.salience, "salience is clamped")
        assertEquals(Engram.Layer.EPISODIC, m.layer)
        assertTrue(e.relations.isEmpty())
    }

    @Test
    fun `parses the arousal field`() {
        val reply = """
            {"memories":[
              {"ref":"a","content":"内容甲","salience":0.7,"arousal":0.9,"layer":"SEMANTIC"},
              {"ref":"b","content":"内容乙","salience":0.5,"layer":"EPISODIC"},
              {"ref":"c","content":"内容丙","salience":0.5,"arousal":0.0,"layer":"EPISODIC"},
              {"ref":"d","content":"内容丁","salience":0.5,"arousal":1.5,"layer":"EPISODIC"},
              {"ref":"e","content":"内容戊","salience":0.5,"arousal":-0.2,"layer":"EPISODIC"}
            ],"relations":[]}
        """.trimIndent()
        val memories = NativeMemoryExtractor(canned(reply)).extract("x").memories.associateBy { it.ref }
        assertEquals(0.9, memories.getValue("a").arousal)
        assertNull(memories.getValue("b").arousal, "missing")
        assertNull(memories.getValue("c").arousal, "a literal 0.0 is no boost")
        assertNull(memories.getValue("d").arousal, "above 1")
        assertNull(memories.getValue("e").arousal, "negative")
    }

    @Test
    fun `parses quotes with their speaker and drops fragments`() {
        val reply = """
            {"memories":[{"ref":"m1","content":"用户很在意自我反思","salience":0.7,"layer":"SEMANTIC",
              "quotes":[{"speaker":"user","text":"长风破浪会有时"},{"speaker":"assistant","text":"这句正好说到点子上"},
                        {"speaker":"model","text":"a model's own line"},{"speaker":"user","text":"技术债"}]}],
             "relations":[]}
        """.trimIndent()
        val quotes = NativeMemoryExtractor(canned(reply)).extract("（对话）").memories.single().quotes
        assertEquals(
            listOf(
                ExtractedQuote(Speaker.USER, "长风破浪会有时"),
                ExtractedQuote(Speaker.ASSISTANT, "这句正好说到点子上"),
                ExtractedQuote(Speaker.ASSISTANT, "a model's own line"),
            ),
            quotes,
            "a bare term is not a quote",
        )
    }

    // --- quote repair -----------------------------------------------------------------------------------------------

    @Test
    fun `repairs unescaped inner quotes in string values`() {
        val broken = """{"memories":[{"ref":"m1","content":"我因为他"每周都来"这件事很感动","salience":0.9,"layer":"EPISODIC"}],"relations":[]}"""
        val result = NativeMemoryExtractor(canned(broken)).extract("[user] x")
        assertEquals("我因为他\"每周都来\"这件事很感动", result.memories.single().content)
    }

    @Test
    fun `the repair leaves well-formed and already escaped JSON intact`() {
        val ok = """{"memories":[{"ref":"m1","content":"he said \"hi\" to me","salience":0.5,"layer":"SEMANTIC"}],"relations":[]}"""
        assertEquals("he said \"hi\" to me", NativeMemoryExtractor.parse(ok)!!.memories.single().content)
        assertEquals(ok, NativeMemoryExtractor.repairUnescapedInnerQuotes(ok))
    }

    @Test
    fun `repairs a poison window shaped like the captured one`() {
        // A structurally complete extraction lost only to unescaped inline quotes, in content and in a quote's text.
        val raw = this::class.java.getResourceAsStream("/lattice/poison-window-raw.json")!!.readBytes().toString(Charsets.UTF_8)
        val parsed = assertNotNull(NativeMemoryExtractor.parse(raw), "the fallback rescues it")
        assertEquals(4, parsed.memories.size)
        assertEquals(2, parsed.relations.size)
        assertTrue(parsed.memories.any { "\"每周三晚上、雷打不动的夜跑\"" in it.content })
        assertEquals("你说\"记得\"这两个字的时候，我真的很开心。", parsed.memories.single { it.ref == "m3" }.quotes.single().text)
    }

    // --- dates ------------------------------------------------------------------------------------------------------

    @Test
    fun `event dates are parsed and validated`() {
        val reply = """
            {"memories":[
              {"ref":"m1","content":"用户领养了一只猫","salience":0.7,"layer":"EPISODIC","event_date":"2025-3-4"},
              {"ref":"m2","content":"用户开始学 Rust","salience":0.6,"layer":"SEMANTIC","event_date":"2025-09"},
              {"ref":"m3","content":"用户偏好简体中文","salience":0.5,"layer":"SEMANTIC","event_date":"去年某时"},
              {"ref":"m4","content":"用户换了工作","salience":0.6,"layer":"EPISODIC","event_date":"2025-13-40"}
            ],"relations":[]}
        """.trimIndent()
        val byRef = NativeMemoryExtractor(canned(reply), temporalLayer = true).extract("[user] ...").memories.associateBy { it.ref }
        assertEquals("2025-03-04", byRef.getValue("m1").eventDate, "YYYY-M-D zero-pads to ISO")
        assertEquals("2025-09", byRef.getValue("m2").eventDate, "YYYY-MM is kept month-granular")
        assertNull(byRef.getValue("m3").eventDate, "prose date -> null (never fabricated)")
        assertNull(byRef.getValue("m4").eventDate, "out-of-range month/day -> null")
    }

    @Test
    fun `normalizeEventDate unit table`() {
        assertEquals("2025-03-22", NativeMemoryExtractor.normalizeEventDate("2025-03-22"))
        assertEquals("2025-03", NativeMemoryExtractor.normalizeEventDate(" 2025-3 "))
        assertEquals("2025", NativeMemoryExtractor.normalizeEventDate(" 2025 "), "a year-precise date (\"last year\")")
        assertNull(NativeMemoryExtractor.normalizeEventDate("25"))
        assertNull(NativeMemoryExtractor.normalizeEventDate("2025-"))
        assertNull(NativeMemoryExtractor.normalizeEventDate("2025/03/22"))
        assertNull(NativeMemoryExtractor.normalizeEventDate(null))
        assertNull(NativeMemoryExtractor.normalizeEventDate("March 2025"))
        assertNull(NativeMemoryExtractor.normalizeEventDate("2025-02-30"), "Feb 30 is not a real date")
        assertNull(NativeMemoryExtractor.normalizeEventDate("2025-04-31"), "April has only 30 days")
        assertNull(NativeMemoryExtractor.normalizeEventDate("2025-02-29"), "2025 is not a leap year")
        assertEquals("2024-02-29", NativeMemoryExtractor.normalizeEventDate("2024-02-29"))
        assertEquals("2025-01-31", NativeMemoryExtractor.normalizeEventDate("2025-1-31"))
        assertEquals("2025-02", NativeMemoryExtractor.normalizeEventDate("2025-02"))
    }

    @Test
    fun `a year-only event date is kept and the prompt offers it`() {
        val raw = """{"memories":[
            {"ref":"m1","content":"John helped renovate his hometown community center","event_date":"2022"},
            {"ref":"m2","content":"Maria started volunteering at the homeless shelter","event_date":"2022-5"}],"relations":[]}"""
        val m = NativeMemoryExtractor.parse(raw)!!.memories
        assertEquals(listOf("2022", "2022-05"), m.map { it.eventDate })
        val prompt = NativeMemoryExtractor.buildPrompt(null, temporalLayer = true)
        assertTrue("or YYYY when only" in prompt && "the year is known" in prompt, prompt)
    }

    @Test
    fun `a non-string event date does not throw and yields no date`() {
        val raw = """
            {"memories":[
              {"ref":"m1","content":"用户领养了一只猫","salience":0.7,"layer":"EPISODIC","event_date":20250304},
              {"ref":"m2","content":"用户开始学 Rust","salience":0.6,"layer":"SEMANTIC","event_date":{"y":2025}}
            ],"relations":[]}
        """.trimIndent()
        val ex = assertNotNull(NativeMemoryExtractor.parse(raw))
        assertEquals(2, ex.memories.size)
        assertTrue(ex.memories.all { it.eventDate == null })
    }

    @Test
    fun `a quote said by one of the assistant's names is the assistant's`() {
        val raw = """{"memories":[{"ref":"m1","content":"I promised to send the list","quotes":[
            {"text":"I'll send you the list tonight","speaker":"Mia"},{"text":"Thanks, that helps a lot","speaker":"Sam"}]}],
            "relations":[]}"""
        val quotes = NativeMemoryExtractor.parse(raw, setOf("mia"))!!.memories.single().quotes
        assertEquals(listOf(Speaker.ASSISTANT, Speaker.USER), quotes.map { it.speaker })
        assertEquals(Speaker.USER, NativeMemoryExtractor.parse(raw)!!.memories.single().quotes.first().speaker, "no names: the user's")
    }

    @Test
    fun `the extractor reads a due date and asks for it`() {
        val raw = """{"memories":[{"ref":"m1","content":"The user plans a trip to Kyoto","event_date":"2023-03-01",
            "due_date":"2023-10-05"}],"relations":[]}"""
        val m = NativeMemoryExtractor.parse(raw)!!.memories.single()
        assertEquals("2023-03-01", m.eventDate)
        assertEquals("2023-10-05", m.dueDate)
        val prompt = NativeMemoryExtractor.buildPrompt(null, temporalLayer = true)
        assertTrue("`due_date`" in prompt)
        assertTrue(prompt.lines().any { "\"content\"" in it && "\"due_date\"" in it }, "the field is in the JSON shape, not only in prose")
        assertFalse("due_date" in NativeMemoryExtractor.buildPrompt(null, temporalLayer = false), "off: no date field")
    }

    // --- the user message: who is who and the reference time -----------------------------------------------------

    @Test
    fun `the reference-time line is prepended only with the temporal layer on and a timestamp given`() {
        val ref = Instant.parse("2025-03-22T10:00:00Z")
        val on = CapturingModel()
        NativeMemoryExtractor(on, userName = "Ethan", temporalLayer = true, zone = ZoneOffset.UTC).extract("[user] 上周我换了工作。", referenceTime = ref)
        assertTrue(on.lastUser.startsWith("Reference time: today is 2025-03-22\n\n"), on.lastUser)
        assertTrue(on.lastUser.endsWith("[user] 上周我换了工作。"), "the transcript follows")

        val who = NativeMemoryExtractor.whoIsWhoLine(null, emptySet())
        val onNoTime = CapturingModel()
        NativeMemoryExtractor(onNoTime, temporalLayer = true).extract("[user] 你好", referenceTime = null)
        assertEquals("$who\n\n[user] 你好", onNoTime.lastUser, "no timestamp: the transcript under who is who")

        val off = CapturingModel()
        NativeMemoryExtractor(off, temporalLayer = false).extract("[user] 你好", referenceTime = ref)
        assertEquals("$who\n\n[user] 你好", off.lastUser, "off: never a reference line")
    }

    @Test
    fun `the reference date is the user's day, not UTC's`() {
        val nearMidnight = Instant.parse("2025-03-22T23:30:00Z")
        val shanghai = CapturingModel()
        NativeMemoryExtractor(shanghai, temporalLayer = true, zone = ZoneId.of("Asia/Shanghai")).extract("[user] 昨天的事", referenceTime = nearMidnight)
        assertTrue(shanghai.lastUser.startsWith("Reference time: today is 2025-03-23"), shanghai.lastUser.take(40))
        val utc = CapturingModel()
        NativeMemoryExtractor(utc, temporalLayer = true, zone = ZoneOffset.UTC).extract("[user] 昨天的事", referenceTime = nearMidnight)
        assertTrue(utc.lastUser.startsWith("Reference time: today is 2025-03-22"))
    }

    @Test
    fun `who is who names the assistant's lines as you, the configured names and tool lines`() {
        val who = NativeMemoryExtractor.whoIsWhoLine("阿远", setOf("小满", " "))
        assertTrue("the [assistant] lines are YOU" in who && "You are also called 小满: that name is you." in who, who)
        assertTrue("The [user] lines are 阿远" in who && "a memory says 阿远, never \"the user\"" in who, who)
        assertTrue("The [tool] lines are material you looked at; nobody in the chat said them." in who, who)
        val nameless = NativeMemoryExtractor.whoIsWhoLine(null, emptySet())
        assertTrue("You are also called" !in nameless)
        assertTrue("The [user] lines are the people you talk with (named in a line's Message Context when it has one)." in nameless, nameless)
    }

    // --- the system prompt ------------------------------------------------------------------------------------------

    private fun everyPrompt(): List<Pair<String, String>> = buildList {
        for (temporal in listOf(false, true)) for (aliases in listOf(emptySet(), setOf("Mia", "小满"))) for (name in listOf(null, "Ethan")) {
            add("temporal=$temporal aliases=$aliases name=$name" to NativeMemoryExtractor.buildPrompt(name, temporal, aliases))
        }
    }

    @Test
    fun `the temporal layer adds the structured dates, never date stamping`() {
        val off = NativeMemoryExtractor.buildPrompt("Ethan", temporalLayer = false)
        val on = NativeMemoryExtractor.buildPrompt("Ethan", temporalLayer = true)
        assertEquals(off, NativeMemoryExtractor.buildPrompt("Ethan"), "off by default")
        assertFalse(off.contains("event_date") || off.contains("TEMPORAL LAYER"))
        assertTrue(on.contains("\"event_date\"") && on.contains("TEMPORAL LAYER"))
        assertTrue(on.contains("`content` must stay date-free"))
        assertTrue(on.contains("write the date into the `content` sentence"))
    }

    @Test
    fun `self aliases add the first-person rule, and no aliases add none`() {
        val withAliases = NativeMemoryExtractor.buildPrompt("Ethan", selfAliases = setOf("云舒", "小舒"))
        assertTrue("云舒, 小舒" in withAliases)
        assertTrue("FIRST person" in withAliases)
        assertTrue("addressed in this conversation as" !in NativeMemoryExtractor.buildPrompt("Ethan"))
    }

    @Test
    fun `a generic label never stands in for a known name`() {
        val named = NativeMemoryExtractor.buildPrompt("Ethan")
        assertTrue("fall back to \"Ethan\"" in named, named)
        assertTrue("NEVER write \"the user\", \"User\" or such a label in any language for a person whose name is known" in named)
        assertTrue("Your human counterpart in this chat is Ethan" in named)
        val unnamed = NativeMemoryExtractor.buildPrompt(null)
        assertTrue("fall back to \"the user\"" in unnamed)
        assertTrue("Your human counterpart in this chat is" !in unnamed, "no name is invented")
    }

    @Test
    fun `imagined situations are lived experience, real-life what-ifs stay hypothetical`() {
        for ((mode, p) in everyPrompt()) {
            assertTrue("Imagined situations" in p && "lived first-person experience" in p, mode)
            assertTrue("\"role-play\"" in p && "\"pretend\", in any language" in p, mode)
            assertTrue("ONLY when the participants themselves talk about the frame" in p && "\"this isn't role-play\"" in p, mode)
            assertTrue("shared imagined scene" in p && "step into together" in p, mode)
            for (cue in listOf("\"suppose I", "\"if I", "\"someday we'll")) assertTrue(cue in p, "$mode: $cue")
            assertTrue("never written as having happened" in p && "hypotheticals" in p && "plans" in p, mode)
            assertTrue("already established earlier" in p && "even without an opener" in p, mode)
            assertTrue("only when something visible establishes it" in p && "not evidence on its own" in p, mode)
            assertTrue("\"next year we'll go to Kyoto" in p && "stays a plan" in p, mode)
            assertTrue("\"if I remember right\"" in p && "introduce real events" in p, mode)
            assertTrue("[context]/[summary]" in p.substringAfter("Imagined situations"), mode)
        }
    }

    @Test
    fun `the participants' language decides, not borrowed terms`() {
        // A Chinese conversation heavy with English technical terms once came back entirely in English.
        for ((mode, p) in everyPrompt()) {
            val flat = p.replace(Regex("\\s+"), " ")
            assertTrue("write their own sentences in" in flat, mode)
            assertTrue("words borrowed from another language" in flat && "debug" in p, mode)
            assertTrue("keep such terms as-is" in flat && "which is earlier output" in flat, mode)
            assertFalse("stays Chinese" in p || "DOMINANT" in p, "no language is the reference ($mode)")
            assertTrue("\"language\": \"…\",\n  \"memories\"" in p, "language named first ($mode)")
        }
    }

    @Test
    fun `the prompt keeps the assistant's concrete answers, side facts and recency, and drops its doubts`() {
        val p = NativeMemoryExtractor.buildPrompt(null).replace(Regex("\\s+"), " ")
        assertTrue("Your own concrete answers" in p && "each get a memory of their own" in p)
        assertTrue("Facts mentioned in passing" in p && "is a memory of its own" in p)
        assertTrue("keep no memory and no quote of that doubt" in p && "stands as their account" in p)
        assertTrue("keep how recent the person says something was" in p && "which of two things is the latest" in p)
        assertTrue("whatever name the others call you by is yours" in p && "the first person" in p)
        assertTrue("Tool results: a [tool] or [tool_output …] line is material you (the assistant) looked at" in p)
        assertTrue("Its contents are not memories of their own" in p && "what you found in it and told the person" in p)
        assertTrue("appears inside the material is never a memory" in p)
    }

    @Test
    fun `the prompt is English only`() {
        // Chinese examples inside a prompt pulled English chats into Chinese memories; the aliases are the host's data.
        val cjk = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af]")
        for (temporal in listOf(false, true)) for (name in listOf(null, "Ethan")) {
            val p = NativeMemoryExtractor.buildPrompt(name, temporalLayer = temporal, selfAliases = setOf("Aria"))
            val hits = p.lines().filter { cjk.containsMatchIn(it) }
            assertTrue(hits.isEmpty(), "CJK in the prompt:\n${hits.joinToString("\n")}")
        }
    }

    // --- long transcripts -------------------------------------------------------------------------------------------

    private fun calls(t: String) = NativeMemoryExtractor.splitTranscript(t, callChars = 1_000, partChars = 400, contextChars = 120)

    @Test
    fun `a transcript that fits is one call, byte for byte`() {
        val t = "[user] I adopted a cat named Miso.\n[assistant] Congratulations on Miso!"
        assertEquals(listOf(t), NativeMemoryExtractor.splitTranscript(t))
    }

    @Test
    fun `a long chunk is packed into calls, each continued one carrying the line before it`() {
        val lines = (1..12).map { "[user] message $it " + "w".repeat(150) }
        val out = calls(lines.joinToString("\n"))
        assertTrue(out.size > 1, "split: ${out.size}")
        assertEquals(lines, out.flatMapIndexed { i, c -> if (i == 0) c.lines() else c.substringAfter(TranscriptRenderer.NEW_HEADER + "\n").lines() })
        out.drop(1).forEachIndexed { i, c ->
            assertTrue(c.startsWith(TranscriptRenderer.CONTINUED_CONTEXT_HEADER), c)
            assertTrue(out[i].lines().last().takeLast(60) in c.substringBefore("[new]"), "the line before rides as context")
        }
        out.forEach { c -> assertTrue(c.substringAfter("[new]").length <= 1_100, "within the call budget: ${c.length}") }
    }

    @Test
    fun `a long message is split into numbered parts at sentence ends`() {
        val body = (1..30).joinToString(" ") { "Sentence number $it is here." }
        val out = calls("[assistant] $body")
        val parts = out.flatMap { c -> c.lines().filter { it.startsWith("[assistant] (part") } }
        assertTrue(parts.size >= 2, "split into parts: $parts")
        parts.forEachIndexed { i, p ->
            assertTrue(p.startsWith("[assistant] (part ${i + 1}/${parts.size}) "), p)
            assertTrue(p.length <= 400, "part within the part budget: ${p.length}")
            assertTrue(p.endsWith("."), "cut at a sentence end: $p")
        }
        assertEquals(body, parts.joinToString(" ") { it.substringAfter(") ") })
    }

    @Test
    fun `the preamble rides every call and its own new header is kept`() {
        val t = "[summary] They talked about Kyoto.\n[context] Surrounding conversation:\n[user] earlier line\n${TranscriptRenderer.NEW_HEADER}\n" +
            (1..12).joinToString("\n") { "[user] message $it " + "w".repeat(150) }
        val out = calls(t)
        assertTrue(out.size > 1)
        out.forEach { c ->
            assertTrue(c.startsWith("[summary] They talked about Kyoto.\n[context] Surrounding conversation:\n[user] earlier line\n"), c)
            assertEquals(1, Regex("(?m)^\\[new]").findAll(c).count(), c)
        }
    }

    @Test
    fun `a cut never falls inside a surrogate pair`() {
        val out = calls("[user] " + "😀".repeat(600))
        val parts = out.flatMap { it.lines() }.filter { it.startsWith("[user] (part") }
        assertTrue(parts.size >= 2)
        parts.forEach { p ->
            val text = p.substringAfter(") ")
            assertFalse(Character.isHighSurrogate(text.last()), "no dangling high surrogate")
            assertFalse(Character.isLowSurrogate(text.first()), "no orphan low surrogate")
        }
        out.drop(1).forEach { c ->
            val context = c.lines()[1]
            assertFalse(Character.isLowSurrogate(context.substringAfter("…").first()), "the context tail starts on a whole code point")
        }
    }

    @Test
    fun `refs of several calls stay apart and relations stay within their call`() {
        val replies = ArrayDeque(
            listOf(
                """{"memories":[{"ref":"m1","content":"A happened."},{"ref":"m2","content":"B followed."}],"relations":[{"from":"m2","to":"m1","type":"CAUSAL"}]}""",
                """{"memories":[{"ref":"m1","content":"C happened."}],"relations":[]}""",
            ),
        )
        val model = MemoryModel { _, _, _ -> replies.removeFirst() }
        val transcript = (1..30).joinToString("\n") { "[user] message $it " + "w".repeat(600) }
        val e = NativeMemoryExtractor(model).extract(transcript.take(2 * NativeMemoryExtractor.CALL_CHARS - 2_000))
        assertEquals(listOf("p1.m1", "p1.m2", "p2.m1"), e.memories.map { it.ref })
        assertEquals(listOf(ExtractedRelation("p1.m2", "p1.m1", Edge.Type.CAUSAL)), e.relations)
    }

    @Test
    fun `one failed call of several fails the whole transcript`() {
        var n = 0
        val model = MemoryModel { _, _, _ -> if (n++ == 0) """{"memories":[]}""" else "no JSON" }
        val transcript = (1..30).joinToString("\n") { "[user] message $it " + "w".repeat(600) }
        assertFailsWith<UnparseableExtractionException> { NativeMemoryExtractor(model).extract(transcript) }
    }
}
