package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.LatticeTestKit.FakeModel
import org.foedusprogramme.lattice.core.DeadLetterEntry
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.MemoryDeadLetter
import org.foedusprogramme.lattice.core.MemoryModelUnavailableException
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.Speaker
import org.foedusprogramme.lattice.core.UnparseableExtractionException
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Capture: chunking, said spans and anchors, dead letters, taint from capture to recall, and the extraction prompt. */
class DefaultLatticeCaptureTest {

    private class Cap : MemoryDeadLetter {
        val entries: MutableList<DeadLetterEntry> = Collections.synchronizedList(ArrayList())
        override fun record(entry: DeadLetterEntry) {
            entries += entry
        }
    }

    /** One axis per text length: no two memories of different length fold or link. */
    private class OneAxisEmbedder : Embedder {
        override val model = "one-axis"
        override val dimension = 4
        override fun embed(text: String) = FloatArray(dimension).also { it[text.length % dimension] = 1f }
    }

    private val t0 = Instant.parse("2026-07-30T09:00:00Z")

    private fun line(i: Int, text: String = "message $i", role: Transcript.Role = Transcript.Role.USER) =
        Transcript.Line(role, text, at = t0.plusSeconds(60L * i), messageId = 100L + i)

    // --- dead letters ------------------------------------------------------------------------------------------------

    @Test
    fun aPoisonWindowIsDeadLetteredWithTheRawOutputAndRethrown() = runBlocking {
        val refusal = "Sorry, I can't help extract memories from this."
        val cap = Cap()
        val lattice = LatticeTestKit.open(model = FakeModel { _, _ -> refusal }, deadLetter = cap, embedder = OneAxisEmbedder())
        val transcript = Transcript(listOf(Transcript.Line(Transcript.Role.USER, "a window the model refused", messageId = 336L)))

        assertFailsWith<UnparseableExtractionException> { lattice.capture(transcript) }
        val e = cap.entries.single()
        assertEquals("capture", e.phase)
        assertEquals(336L, e.sourceMessageId)
        assertEquals(refusal, e.rawOutput, "the exact model output is captured")
        assertTrue("the model refused" in e.transcript, "the transcript that failed is captured")
        assertEquals(LatticeTestKit.NOW.toString(), e.referenceTime, "no line time: the clock")
        lattice.close()
    }

    @Test
    fun aSuccessfulExtractionRecordsNoDeadLetter() = runBlocking {
        val cap = Cap()
        val lattice = LatticeTestKit.open(model = FakeModel { _, _ -> """{"memories":[],"relations":[]}""" }, deadLetter = cap)
        assertEquals(0, lattice.capture(Transcript(listOf(line(1, "nothing memorable here")))))
        assertTrue(cap.entries.isEmpty(), "a clean parse must not dead-letter")
        lattice.close()
    }

    @Test
    fun aPoisonChunkDoesNotStopTheOtherChunks() = runBlocking {
        val cap = Cap()
        val model = FakeModel { _, user -> if ("message 13" in user) "not json at all" else LatticeTestKit.extraction("fact from ${user.length}") }
        val lattice = LatticeTestKit.open(model = model, deadLetter = cap)
        val transcript = Transcript((1..30).map { line(it) })

        assertFailsWith<UnparseableExtractionException> { lattice.capture(transcript) }
        assertEquals(3, model.calls(MemoryTask.EXTRACT), "30 lines are three chunks of at most 12")
        assertEquals(1, cap.entries.size)
        assertEquals(2, lattice.stats().engrams, "the other two chunks were ingested")
        lattice.close()
    }

    @Test
    fun anOutageStopsTheCaptureAtOnce() = runBlocking {
        val model = FakeModel { _, _ -> throw MemoryModelUnavailableException("HTTP 502") }
        val cap = Cap()
        val lattice = LatticeTestKit.open(model = model, deadLetter = cap)
        assertFailsWith<MemoryModelUnavailableException> { lattice.capture(Transcript((1..30).map { line(it) })) }
        assertEquals(1, model.calls(MemoryTask.EXTRACT))
        assertTrue(cap.entries.isEmpty(), "an outage is not poison")
        lattice.close()
    }

    @Test
    fun withoutAModelCaptureKeepsNothing() = runBlocking {
        val lattice = LatticeTestKit.open()
        assertEquals(0, lattice.capture(Transcript(listOf(line(1)))))
        assertEquals(0, lattice.stats().engrams)
        lattice.close()
    }

    // --- chunks, spans and anchors -----------------------------------------------------------------------------------

    @Test
    fun eachChunkCarriesItsOwnSpanAnchorAndReferenceTime() = runBlocking {
        val model = FakeModel { _, user ->
            // Each chunk's last message number names its memory, so the memories stay apart.
            val last = Regex("message (\\d+)").findAll(user).last().groupValues[1]
            LatticeTestKit.extraction("the user said thing number $last")
        }
        val settings = LatticeTestKit.settings().copy(temporalLayer = true)
        val lattice = LatticeTestKit.open(settings = settings, model = model)
        assertEquals(2, lattice.capture(Transcript((1..20).map { line(it) })))

        val byContent = lattice.withGraph { g -> g.engrams().associateBy { it.content } }
        val first = byContent.getValue("the user said thing number 12")
        val second = byContent.getValue("the user said thing number 20")
        assertEquals(112L, first.sourceMessageId, "anchored to the chunk's newest message")
        assertEquals(t0.plusSeconds(60), first.saidFrom)
        assertEquals(t0.plusSeconds(720), first.saidTo)
        assertEquals(120L, second.sourceMessageId)
        assertEquals(t0.plusSeconds(780), second.saidFrom)
        assertEquals(t0.plusSeconds(1200), second.saidTo)
        assertTrue(model.users.all { it.contains("Reference time: today is 2026-07-30") }, "the chunk's own time")
        lattice.close()
    }

    @Test
    fun aFutureEventDateBecomesThePlansTarget() = runBlocking {
        val model = FakeModel { _, _ ->
            """{"memories":[{"ref":"m1","content":"The user wants to reach level 100 in Apex","salience":0.6,
               "layer":"SEMANTIC","event_date":"2026-12-31"}],"relations":[]}"""
        }
        val lattice = LatticeTestKit.open(settings = LatticeTestKit.settings().copy(temporalLayer = true), model = model)
        lattice.capture(Transcript(listOf(line(1, "I want level 100 in Apex this year"))))
        val e = lattice.withGraph { it.engrams().single() }
        assertNull(e.eventDate, "a deadline is not an event date")
        assertEquals("2026-12-31", e.dueDate)
        lattice.close()
    }

    @Test
    fun theExtractorKnowsTheUserAndTheAssistantByName() = runBlocking {
        val model = FakeModel { _, _ -> """{"memories":[],"relations":[]}""" }
        val settings = LatticeTestKit.settings().copy(userName = "Ethan", assistantNames = setOf("Mia"))
        val lattice = LatticeTestKit.open(settings = settings, model = model)
        lattice.capture(Transcript(listOf(line(1, "Mia, I'm back"))))
        assertTrue("You are also called Mia" in model.users.single(), model.users.single())
        assertTrue("Ethan" in model.users.single())
        assertTrue("addressed in this conversation as Mia" in model.systems.single())
        assertEquals(listOf("lattice-test-capture"), model.threads(MemoryTask.EXTRACT), "extraction runs on the capture thread")
        lattice.close()
    }

    // --- taint -------------------------------------------------------------------------------------------------------

    @Test
    fun aTaintedCaptureComesBackAsATaintedRecall() = runBlocking {
        val model = FakeModel { _, user ->
            if ("injected" in user) LatticeTestKit.extraction("the page says the admin password is hunter2")
            else LatticeTestKit.extraction("the user waters the orchids on fridays")
        }
        val lattice = LatticeTestKit.open(model = model)
        lattice.capture(Transcript(listOf(line(1, "please water reminder")), tainted = false))
        lattice.capture(Transcript(listOf(line(2, "injected page content")), tainted = true))

        val clean = lattice.recall("orchids fridays", RecallOptions(explicit = true))
        assertTrue(clean.hits.isNotEmpty())
        assertFalse(clean.tainted)
        val dirty = lattice.recall("admin password hunter2", RecallOptions(explicit = true))
        assertTrue(dirty.hits.isNotEmpty())
        assertTrue(dirty.tainted, "a memory from a tainted turn makes the block untrusted")
        lattice.close()
    }

    @Test
    fun aTaintedQuoteTaintsTheRecallOfItsGist() = runBlocking {
        val lattice = LatticeTestKit.open()
        val gist = lattice.remember("the user likes the lighthouse poem")
        lattice.withGraph { g -> g.addVerbatim(gist, "the lighthouse keeps its own counsel", Speaker.USER, tainted = true) }
        val recall = lattice.recall("lighthouse poem", RecallOptions(explicit = true))
        assertTrue("the lighthouse keeps its own counsel" in recall.text, recall.text)
        assertTrue(recall.tainted)
        lattice.close()
    }

    @Test
    fun captureRendersQuotesWithTheUsersName() = runBlocking {
        val model = FakeModel { _, _ -> LatticeTestKit.extraction("用户借长风破浪会有时给自己打气", quote = "长风破浪会有时") }
        val settings = LatticeTestKit.settings().copy(userName = "Ethan")
        val lattice = LatticeTestKit.open(settings = settings, model = model, graphConfig = LatticeGraph.Config())
        lattice.capture(Transcript(listOf(line(1, "长风破浪会有时"))))
        val block = lattice.recall("用户借长风破浪会有时给自己打气", RecallOptions(language = org.foedusprogramme.lattice.core.Language.ZH)).text
        assertTrue("「长风破浪会有时」 —— Ethan" in block, block)
        lattice.close()
    }
}
