package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryAbstractorTest {

    @Test
    fun `the parent is written in the memories' own language`() {
        // A prompt that names one language makes every parent of a chat in another language come out wrong.
        val p = MemoryAbstractor.SYSTEM_PROMPT.replace(Regex("\\s+"), " ")
        assertTrue("in the language the memories are written in" in p, p)
        assertFalse("Chinese" in p, p)

        var sent: Pair<String, String>? = null
        var task: MemoryTask? = null
        val llm = MemoryModel { t, system, user ->
            task = t
            sent = system to user
            "They are planning a trip to Tokyo."
        }
        assertEquals("They are planning a trip to Tokyo.", MemoryAbstractor(llm).synthesize(listOf("A tonkatsu place.", "A snake plant.")))
        assertEquals(MemoryAbstractor.SYSTEM_PROMPT to "1. A tonkatsu place.\n2. A snake plant.", sent)
        assertEquals(MemoryTask.ABSTRACT, task)
    }

    @Test
    fun `a reply carrying a bare markup tag synthesizes no parent`() {
        // A model once answered with nothing but `<thinking_mode>`, and the sleep wrote that as a parent.
        fun reply(text: String) = MemoryAbstractor(MemoryModel { _, _, _ -> text }).synthesize(listOf("A tonkatsu place.", "A snake plant."))

        assertEquals("", reply("<thinking_mode>"))
        assertEquals("", reply("<thinking_mode>\ninterleaved\n</thinking_mode>\nThey are planning a trip to Tokyo."))
        assertEquals("", reply("They are planning a trip.\n<reasoning_effort level=\"high\"/>"))
        assertEquals("They like <b>bold</b> plans.", reply("They like <b>bold</b> plans."))
    }
}
