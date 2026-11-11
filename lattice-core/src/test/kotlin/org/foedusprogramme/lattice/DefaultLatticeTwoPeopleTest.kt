package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.core.FakeConversationSource
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.Speaker
import org.foedusprogramme.lattice.core.TopicModel
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A chat between two named people (LoCoMo's shape: both are persons, no assistant line, no configured names): the
 * scene is narrated by an observer in the third person, and each person's quote carries their name — even when the
 * model calls both of them "user". A user–assistant chat (LongMemEval's shape) keeps the first person and "the user".
 */
class DefaultLatticeTwoPeopleTest {

    private val caroline = "I went to a LGBTQ support group yesterday and it was so powerful."
    private val melanie = "Wow, that's cool, Caroline! What happened that was so awesome?"

    /** Scenes and narration from [TopicModel]; extraction: one memory quoting both lines, both as "user" (the bug). */
    private class Model : MemoryModel {
        val topic = TopicModel()

        override fun complete(task: MemoryTask, system: String, user: String): String {
            if (task != MemoryTask.EXTRACT) return topic.complete(task, system, user)
            val quotes = QUOTED.findAll(user).map { """{"speaker":"user","text":"${it.groupValues[1]}"}""" }.toList()
            return """{"memories":[{"ref":"m1","content":"They talked about the support group","salience":0.7,""" +
                """"layer":"SEMANTIC","quotes":[${quotes.joinToString(",")}]}],"relations":[]}"""
        }

        companion object {
            /** The body of each transcript line, after its (optional) Message Context wrapper. */
            val QUOTED = Regex("topic=pride (.+?)(?=\\n|$)")
        }
    }

    private val source = FakeConversationSource(start = Instant.parse("2023-05-08T13:56:00Z"))
    private val model = Model()
    private val opened = ArrayList<DefaultLattice>()

    @AfterTest
    fun closeAll() = runBlocking { opened.forEach { it.close() } }

    private fun open(): DefaultLattice = LatticeTestKit.open(
        // LoCoMo's adapter: no user name, no assistant names.
        settings = LatticeTestKit.settings().copy(scenes = SceneSettings(enabled = true), temporalLayer = true),
        model = model,
        conversations = source,
        graphConfig = LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99, temporalLayer = true, episodicBinding = true),
    ).also { opened += it }

    private suspend fun ingest(lattice: DefaultLattice, conversation: String, lines: List<Triple<Role, String?, String>>) {
        source.open(conversation, CHAT)
        for ((role, name, text) in lines) source.add(conversation, "topic=pride $text", role, speakerName = name)
        source.seal(conversation)
        lattice.conversationSealed(conversation)
        lattice.scenes!!.awaitIdle()
    }

    @Test
    fun twoNamedPeopleAreNarratedInTheThirdPersonAndQuotedByName() = runBlocking {
        val lattice = open()
        ingest(lattice, "s1", listOf(Triple(Role.USER, "Caroline", caroline), Triple(Role.USER, "Melanie", melanie)))

        val narration = model.topic.narrations.single()
        assertTrue("You take no part in this chat: it is between Caroline and Melanie" in narration, narration)
        assertFalse("You — the assistant, written in the first person" in narration, narration)

        val quotes = lattice.withGraph { g -> g.engrams().filter { it.speaker == Speaker.USER }.associate { it.content.removePrefix("topic=pride ") to it.speakerName } }
        assertEquals(mapOf(caroline to "Caroline", melanie to "Melanie"), quotes)
        val block = lattice.recall("the support group", RecallOptions(explicit = true, chatKey = CHAT)).text
        assertTrue("$caroline\" — Caroline" in block, block)
        assertTrue("$melanie\" — Melanie" in block, block)
        assertFalse("— the user" in block, block)
    }

    @Test
    fun aUserAndAnAssistantKeepTheFirstPersonAndTheUserLabel() = runBlocking {
        val lattice = open()
        ingest(lattice, "s1", listOf(Triple(Role.USER, null, caroline), Triple(Role.ASSISTANT, null, "That sounds powerful.")))

        val narration = model.topic.narrations.single()
        assertTrue("You — the assistant, written in the first person" in narration, narration)
        val quotes = lattice.withGraph { g -> g.engrams().filter { it.speaker == Speaker.USER }.map { it.speakerName } }
        assertTrue(quotes.isNotEmpty() && quotes.all { it == null }, "$quotes")
        assertTrue("— the user" in lattice.recall("the support group", RecallOptions(explicit = true, chatKey = CHAT)).text)
    }

    companion object {
        const val CHAT = "locomo:conv-26"
    }
}
