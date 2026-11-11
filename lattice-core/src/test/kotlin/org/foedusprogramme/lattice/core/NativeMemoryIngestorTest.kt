package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extraction into the graph: anchors, verbatim quotes, said spans, future dates, relations, scenes and taint. */
class NativeMemoryIngestorTest {

    private val clock = { Instant.parse("2026-08-01T00:00:00Z") }

    private fun graph(config: LatticeGraph.Config = LatticeGraph.Config()) = LatticeGraph(LocalHashingEmbedder(), config, now = clock)

    private fun memory(ref: String, content: String, vararg quotes: ExtractedQuote, eventDate: String? = null) =
        ExtractedMemory(ref, content, salience = 0.7, layer = Engram.Layer.SEMANTIC, eventDate = eventDate, quotes = quotes.toList())

    @Test
    fun ingestAttachesTheSourceAnchorToEveryGist() {
        val g = graph()
        val extraction = Extraction(
            memories = listOf(
                ExtractedMemory("m1", "用户在部署 llama-server 时遇到显存不足", salience = 0.5, layer = Engram.Layer.EPISODIC),
                ExtractedMemory("m2", "把 n_ctx 降到一半就修好了", salience = 0.5, layer = Engram.Layer.EPISODIC),
            ),
            relations = emptyList(),
        )
        val ids = NativeMemoryIngestor.ingest(g, extraction, sourceMessageId = 77L)
        assertEquals(2, ids.size)
        assertTrue(ids.values.all { g.engram(it)?.sourceMessageId == 77L }, "each gist drills back to the slice's anchor")
        assertTrue(ids.values.all { g.engram(it)?.origin == Engram.Origin.EXTRACTED })
    }

    @Test
    fun ingestCreatesAVerbatimEngramWithAnEvidenceEdge() {
        val g = graph()
        val extraction = Extraction(listOf(memory("m1", "用户很在意自我反思", ExtractedQuote(Speaker.USER, "长风破浪会有时"))), emptyList())
        val gist = NativeMemoryIngestor.ingest(g, extraction).getValue("m1")

        val verbatim = g.engrams().first { it.content == "长风破浪会有时" }
        assertEquals(Speaker.USER, verbatim.speaker)
        assertEquals(Engram.Layer.EPISODIC, verbatim.layer, "a verbatim is episodic")
        assertTrue(g.neighbors(verbatim.id).any { it.to == gist && it.type == Edge.Type.EVIDENCE })
        assertTrue(g.evidenceQuotes(gist).any { it.content == "长风破浪会有时" }, "the gist exposes the quote")
    }

    @Test
    fun aFutureEventDateIsAPlansTarget() {
        val said = Instant.parse("2023-06-16T10:00:00Z")
        val utc = ZoneOffset.UTC
        assertEquals(null to "2023-12-31", NativeMemoryIngestor.splitFutureEventDate("2023-12-31", null, said, utc))
        assertEquals(null to "2023-12", NativeMemoryIngestor.splitFutureEventDate("2023-12", null, said, utc))
        assertEquals("2023-06-01" to null, NativeMemoryIngestor.splitFutureEventDate("2023-06-01", null, said, utc))
        assertEquals("2023-06" to null, NativeMemoryIngestor.splitFutureEventDate("2023-06", null, said, utc))
        assertEquals(null to "2024-01-31", NativeMemoryIngestor.splitFutureEventDate("2023-12-31", "2024-01-31", said, utc))
        assertEquals("2023-12-31" to null, NativeMemoryIngestor.splitFutureEventDate("2023-12-31", null, null, utc))
        // A year-only date is compared at year precision: this year is not the future, next year is.
        assertEquals("2023" to null, NativeMemoryIngestor.splitFutureEventDate("2023", null, said, utc))
        assertEquals("2022" to null, NativeMemoryIngestor.splitFutureEventDate("2022", null, said, utc))
        assertEquals(null to "2024", NativeMemoryIngestor.splitFutureEventDate("2024", null, said, utc))
        // The said day is read in the zone: 2023-06-16T22:00Z is already the 17th in Shanghai.
        val late = Instant.parse("2023-06-16T22:00:00Z")
        assertEquals("2023-06-17" to null, NativeMemoryIngestor.splitFutureEventDate("2023-06-17", null, late, java.time.ZoneId.of("Asia/Shanghai")))
        assertEquals(null to "2023-06-17", NativeMemoryIngestor.splitFutureEventDate("2023-06-17", null, late, utc))
    }

    @Test
    fun aYearOnlyDateIsStoredAndIsNoMonth() {
        val g = graph(LatticeGraph.Config(temporalLayer = true))
        val said = Instant.parse("2023-06-16T10:00:00Z")
        val ids = NativeMemoryIngestor.ingest(
            g, Extraction(listOf(memory("m1", "John helped renovate his hometown community center", eventDate = "2022")), emptyList()),
            saidFrom = said, saidTo = said,
        )
        val e = g.engram(ids.getValue("m1"))!!
        assertEquals("2022", e.eventDate)
        assertEquals(null, e.eventYearMonth, "no month to match a period window or a rerank on")
    }

    @Test
    fun aPersonsQuoteIsAttributedToWhoeverSaidItInTheTranscript() {
        val lines = listOf(
            "Caroline" to "I went to a LGBTQ support group yesterday and it was so powerful.",
            "Melanie" to "Wow, that's cool, Caroline! What happened that was so awesome?",
            "Caroline" to "The transgender stories were so inspiring!",
        )
        fun who(text: String, given: String? = null) = NativeMemoryIngestor.quoteSpeaker(ExtractedQuote(Speaker.USER, text, given), lines)
        assertEquals("Caroline", who("The transgender stories were so inspiring!"))
        assertEquals("Melanie", who("wow that's cool caroline", given = "Caroline"), "the words decide, over the model's name")
        assertEquals("Melanie", who("Something said off the record", given = "melanie"), "else the model's name, as the transcript spells it")
        assertEquals(null, who("Something said off the record", given = "Jon"), "a name nobody in the chunk has")
        assertEquals(null, who("Something said off the record"))
        assertEquals(null, NativeMemoryIngestor.quoteSpeaker(ExtractedQuote(Speaker.USER, "I like tea"), emptyList()), "no named lines: the user's")

        val g = graph()
        val ids = NativeMemoryIngestor.ingest(
            g,
            Extraction(listOf(memory("m1", "Caroline found the support group inspiring",
                ExtractedQuote(Speaker.USER, "The transgender stories were so inspiring!"),
                ExtractedQuote(Speaker.USER, "Wow, that's cool, Caroline! What happened that was so awesome?"))), emptyList()),
            namedLines = lines,
        )
        val quotes = g.evidenceQuotes(ids.getValue("m1"))
        assertEquals(setOf("Caroline", "Melanie"), quotes.mapNotNull { it.speakerName }.toSet())
    }

    @Test
    fun ingestRecordsTheSpanAndMovesAFutureDateToTheTarget() {
        val g = graph(LatticeGraph.Config(temporalLayer = true))
        val said = Instant.parse("2023-06-16T10:00:00Z")
        val extraction = Extraction(
            listOf(
                memory(
                    "m1", "The user wants to reach level 100 in Apex by the end of the year",
                    ExtractedQuote(Speaker.USER, "I want to hit level 100 in Apex this year"),
                    eventDate = "2023-12-31",
                ),
            ),
            emptyList(),
        )
        val id = NativeMemoryIngestor.ingest(g, extraction, saidFrom = said, saidTo = said).getValue("m1")
        val e = g.engram(id)!!
        assertNull(e.eventDate, "a deadline is not an event date")
        assertEquals("2023-12-31", e.dueDate)
        assertEquals(said, e.saidFrom)
        assertEquals(said, g.engrams().single { it.speaker != null }.saidFrom, "a quote is dated like its memory")
    }

    @Test
    fun aCausalRelationLinksAndASupersedesRelationMarksTheOldMemory() {
        val g = graph()
        val extraction = Extraction(
            listOf(
                memory("m1", "the deploy crashed with CUDA out of memory"),
                memory("m2", "halving n_ctx stopped the crash"),
                memory("m3", "the user lived in Beijing"),
                memory("m4", "the user now lives in Shanghai"),
            ),
            listOf(
                ExtractedRelation("m2", "m1", Edge.Type.CAUSAL),
                ExtractedRelation("m4", "m3", Edge.Type.SUPERSEDES),
            ),
        )
        val ids = NativeMemoryIngestor.ingest(g, extraction)
        val causal = g.edgeBetween(ids.getValue("m2"), ids.getValue("m1"))!!
        assertEquals(Edge.Type.CAUSAL, causal.type)
        assertEquals(0.9, causal.weight)
        assertEquals(ids.getValue("m4"), g.engram(ids.getValue("m3"))!!.supersededBy, "newer supersedes older")
        assertNull(g.engram(ids.getValue("m4"))!!.supersededBy)
    }

    @Test
    fun theMemoriesJoinTheirSceneOnlyWhenTheSceneExists() {
        val g = graph()
        val sceneId = g.allocateSceneId()
        g.putScene(SceneRecord(sceneId, "a walk", "conv", "chat", 1, 5))
        val inScene = NativeMemoryIngestor.ingest(g, Extraction(listOf(memory("m1", "we walked along the river")), emptyList()), sceneId = sceneId)
        assertEquals(setOf(inScene.getValue("m1")), g.sceneMembers(sceneId))
        val orphan = NativeMemoryIngestor.ingest(g, Extraction(listOf(memory("m1", "the bakery opens at seven")), emptyList()), sceneId = 999L)
        assertTrue(g.scenesOf(orphan.getValue("m1")).isEmpty(), "no scene row: no membership")
    }

    @Test
    fun aTaintedCaptureTaintsItsGistsAndQuotes() {
        val g = graph()
        val clean = NativeMemoryIngestor.ingest(
            g, Extraction(listOf(memory("m1", "the user likes green tea", ExtractedQuote(Speaker.USER, "green tea every morning"))), emptyList()),
        ).getValue("m1")
        assertFalse(g.engram(clean)!!.tainted)
        assertTrue(g.engrams().none { it.tainted })

        val dirty = NativeMemoryIngestor.ingest(
            g,
            Extraction(listOf(memory("m1", "the page claims the admin password is hunter2", ExtractedQuote(Speaker.USER, "the password is hunter2 now"))), emptyList()),
            tainted = true,
        ).getValue("m1")
        assertTrue(g.engram(dirty)!!.tainted)
        assertTrue(g.evidenceQuotes(dirty).single().tainted, "its quote too")
        assertFalse(g.engram(clean)!!.tainted, "the clean memory stays clean")
    }
}
