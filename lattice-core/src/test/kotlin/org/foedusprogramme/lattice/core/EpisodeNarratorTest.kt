package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [EpisodeNarrator] unit tests: the prompt carries the shared date (and the entity hook), and an
 * incoherent/abstaining LLM (empty line) yields "" — there is NO verbatim fallback.
 */
class EpisodeNarratorTest {

    /** Captures the last user prompt and returns a canned completion. */
    private class CapturingLlm(private val reply: String) : MemoryModel {
        var lastUser: String? = null
        var lastSystem: String? = null
        var lastTask: MemoryTask? = null
        override fun complete(task: MemoryTask, system: String, user: String): String {
            lastTask = task; lastSystem = system; lastUser = user; return reply
        }
    }

    @Test
    fun promptCarriesTheDate() {
        val llm = CapturingLlm("那天团队完成了部署并庆祝。")
        val out = EpisodeNarrator(llm).narrate(listOf("部署成功", "团队一起庆祝"), "2025-03-10")
        assertEquals("那天团队完成了部署并庆祝。", out)
        assertTrue(llm.lastUser!!.startsWith("Date: 2025-03-10"), "date is anchored into the prompt")
        assertTrue("1. 部署成功" in llm.lastUser!!, "statements are numbered")
        assertEquals(MemoryTask.NARRATE, llm.lastTask)
    }

    @Test
    fun entityHookIsThreadedWhenProvided() {
        val llm = CapturingLlm("x")
        EpisodeNarrator(llm).narrate(listOf("a", "b"), "2025-03-10", entities = listOf("Lattice", "llama-server"))
        assertTrue("Key entities: Lattice、llama-server" in llm.lastUser!!, "entities threaded")
    }

    @Test
    fun incoherentEmptyLineYieldsBlankNoVerbatimFallback() {
        val out = EpisodeNarrator(CapturingLlm("\n")).narrate(listOf("统计学作业", "买了牛奶"), "2025-03-10")
        assertEquals("", out, "an abstaining narrator returns \"\" — never a member paraphrase")
    }

    @Test
    fun fewerThanTwoStatementsReturnsBlank() {
        assertEquals("", EpisodeNarrator(CapturingLlm("ignored")).narrate(listOf("only one"), "2025-03-10"))
    }

    @Test
    fun decorationIsStrippedAndFirstSubstantialLineTaken() {
        val out = EpisodeNarrator(CapturingLlm("\"  那天发生的事  \"")).narrate(listOf("a", "b"), "2025-03-10")
        assertEquals("那天发生的事", out)
    }
}
