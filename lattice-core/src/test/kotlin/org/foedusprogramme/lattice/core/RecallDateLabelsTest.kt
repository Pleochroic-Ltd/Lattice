package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Labelled recall dates ([RecallDating.labels]): an event date and a said date can't be confused, a partial event date
 * is visibly partial, a settled plan shows when it was settled, a quote's relative time has its day beside it — and
 * with labels off the block is the one Lattice rendered before, byte for byte. The cases are the misreadings in the
 * LoCoMo and LongMemEval error analyses: `[2021 · said Wed 20 April 2022]` answered "April 2022", a plan of 1 February
 * read as decided that day though it was done on the 25th, "yesterday" in a quote under a said range.
 */
class RecallDateLabelsTest {

    private val en = RecallMarkers.EN
    private val zh = RecallMarkers.ZH
    private val words = RecallDating.absolute(ZoneOffset.UTC, inWords = true, labels = true)
    private val wordsLegacy = RecallDating.absolute(ZoneOffset.UTC, inWords = true)

    private class FakeLookup : RecallFormatter.Lookup {
        val quotes = HashMap<Long, List<Engram>>()
        val hubs = HashMap<Long, SceneRecord>()
        val threads = HashMap<Long, RecallFormatter.Threads>()
        val resolves = HashMap<Long, List<Long>>()
        val engrams = HashMap<Long, Engram>()

        override fun evidenceQuotes(id: Long) = quotes[id].orEmpty()
        override fun sceneOfHub(hubId: Long) = hubs[hubId]
        override fun threadsOf(id: Long) = threads[id] ?: RecallFormatter.Threads.NONE
        override fun closedResolvesOf(setupId: Long) = resolves[setupId].orEmpty()
        override fun engram(id: Long) = engrams[id]
    }

    private var nextId = 1L

    private fun at(iso: String): Instant = Instant.parse("${iso}T10:00:00Z")

    private fun engram(
        content: String,
        eventDate: String? = null,
        dueDate: String? = null,
        said: String? = null,
        saidTo: String? = said,
        isEpisodeHub: Boolean = false,
        speaker: Speaker? = null,
        speakerName: String? = null,
        origin: Engram.Origin = Engram.Origin.EXTRACTED,
    ) = Engram(
        id = nextId++,
        content = content,
        embedding = FloatArray(0),
        salience = 0.5,
        layer = Engram.Layer.SEMANTIC,
        createdAt = Instant.EPOCH,
        lastActivatedAt = Instant.EPOCH,
        speaker = speaker,
        eventDate = eventDate,
        isEpisodeHub = isEpisodeHub,
        origin = origin,
        saidFrom = said?.let(::at),
        saidTo = saidTo?.let(::at),
        dueDate = dueDate,
        speakerName = speakerName,
    )

    private fun format(
        hits: List<Engram>,
        lookup: RecallFormatter.Lookup = RecallFormatter.Lookup.NONE,
        markers: RecallMarkers = en,
        dating: RecallDating = words,
        temporalLayer: Boolean = true,
    ) = RecallFormatter.format(hits.map { Activation(it, 0.8, Activation.Reach.SEED) }, markers, temporalLayer, lookup, null, dating)

    /** The analyses' cases in one block: partial and full event dates, plans settled and not, a quote, a scene. */
    private inner class Fixture {
        val lookup = FakeLookup()
        val italy = engram("Ethan visited Italy", eventDate = "2021", said = "2022-04-20")
        val bogota = engram("Jolene took a photo on vacation in Bogota", eventDate = "2022", said = "2023-02-04")
        val drums = engram("John resumed playing drums", eventDate = "2022-02", said = "2022-03-27")
        val park = engram("Melanie took her kids to a park", eventDate = "2023-08-27", said = "2023-08-28", saidTo = "2023-09-13")
        val parkQuote = engram("I took my kids to a park yesterday", said = "2023-08-28", speaker = Speaker.USER, speakerName = "Melanie", origin = Engram.Origin.VERBATIM)
        val plan = engram(
            "User plans to take their Taylor GS Mini to Joe by the end of the month to get it serviced",
            dueDate = "2023-02-28", said = "2023-02-01",
        )
        val service = engram("User took their Taylor GS Mini to Joe's shop for servicing", eventDate = "2023-02-25", said = "2023-02-25")
        val reportedPlan = engram("User planned to ask Joe about new strings", said = "2023-02-01")
        val report = engram("User asked Joe about strings", said = "2023-02-10")
        val openPlan = engram("User plans to repaint the guitar case", dueDate = "2023-03", said = "2023-02-01")
        val party = engram("John had a party with veterans last Friday", said = "2023-05-20")
        val partyQuote = engram("We had a party last Friday", said = "2023-05-20", speaker = Speaker.USER, speakerName = "John", origin = Engram.Origin.VERBATIM)
        val hub = engram("Melanie told about her family's camping trip", isEpisodeHub = true, origin = Engram.Origin.HUB)

        val hits = listOf(italy, bogota, drums, park, plan, reportedPlan, openPlan, party, hub, service)

        init {
            for (e in listOf(italy, bogota, drums, park, parkQuote, plan, service, reportedPlan, report, openPlan, party, partyQuote, hub)) {
                lookup.engrams[e.id] = e
            }
            lookup.quotes[park.id] = listOf(parkQuote)
            lookup.quotes[party.id] = listOf(partyQuote)
            val gsMini = ThreadRecord(100, "chat", "GS Mini service", ThreadState.CLOSED)
            val strings = ThreadRecord(101, "chat", "new strings", ThreadState.CLOSED)
            lookup.threads[plan.id] = RecallFormatter.Threads(closed = listOf(gsMini), open = emptyList())
            lookup.threads[reportedPlan.id] = RecallFormatter.Threads(closed = listOf(strings), open = emptyList())
            lookup.resolves[plan.id] = listOf(service.id)
            lookup.resolves[reportedPlan.id] = listOf(report.id)
            lookup.hubs[hub.id] = SceneRecord(7, "Melanie's family camping trip", "c", "chat", 1, 2, "2023-06-27", "2023-06-27", hubEngramId = hub.id)
        }
    }

    // --- labelled -----------------------------------------------------------------------------------------------------

    @Test
    fun `a year-only, a month-only and a full event date each say they are when it happened, next to the mention`() {
        val f = Fixture()
        fun line(e: Engram) = format(listOf(e)).lines().first()
        assertEquals("- [happened: 2021 (year only) · mentioned: Wed 20 April 2022] Ethan visited Italy", line(f.italy))
        assertEquals("- [happened: 2022 (year only) · mentioned: Sat 4 February 2023] Jolene took a photo on vacation in Bogota", line(f.bogota))
        assertEquals("- [happened: February 2022 (month only) · mentioned: Sun 27 March 2022] John resumed playing drums", line(f.drums))
        assertEquals(
            "- [happened: 27 August 2023 · mentioned: Mon 28 August 2023 to Wed 13 September 2023] Melanie took her kids to a park",
            line(f.park),
        )
        assertEquals("- [mentioned: Sat 20 May 2023] John had a party with veterans last Friday", line(f.party))
        // ISO dates keep the labels and the partial marks.
        val iso = RecallDating.absolute(ZoneOffset.UTC, labels = true)
        assertEquals("- [happened: 2021 (year only) · mentioned: 2022-04-20] Ethan visited Italy", format(listOf(f.italy), dating = iso).lines().first())
        assertEquals("- [happened: 2022-02 (month only) · mentioned: 2022-03-27] John resumed playing drums", format(listOf(f.drums), dating = iso).lines().first())
        // An event date alone is labelled too, and so in the legend.
        val alone = engram("the user bought a new mouse", eventDate = "2026-07-25")
        assertEquals("- [happened: 25 July 2026] the user bought a new mouse\n" + en.legendDatesLabelled + en.legendRelativeLabelledSuffix, format(listOf(alone)))
        assertEquals("- [happened: 2023 (year only)] y", format(listOf(engram("y", eventDate = "2023"))).lines().first())
    }

    @Test
    fun `in Chinese the labels and the partial marks are Chinese`() {
        val f = Fixture()
        assertEquals("- [发生：2021年（仅知年份） · 提到：2022年4月20日（周三）] Ethan visited Italy", format(listOf(f.italy), markers = zh).lines().first())
        assertEquals("- [发生：2022年2月（仅知月份） · 提到：2022年3月27日（周日）] John resumed playing drums", format(listOf(f.drums), markers = zh).lines().first())
        val block = format(listOf(f.plan, f.service), f.lookup, markers = zh)
        assertTrue(block.startsWith("- [目标：2023年2月28日 · 了结：2023年2月25日 · 提到：2023年2月1日（周三）] "), block)
        assertTrue(block.endsWith(zh.legendThread + zh.legendDatesLabelledSuffix + zh.legendRelativeLabelledSuffix), block)
    }

    @Test
    fun `a settled plan shows when it was settled, an unsettled one only its target`() {
        val f = Fixture()
        val lines = format(listOf(f.plan, f.openPlan, f.service, f.reportedPlan), f.lookup).lines()
        assertEquals(
            "- [due: 28 February 2023 · resolved: 25 February 2023 · mentioned: Wed 1 February 2023] User plans to take their " +
                "Taylor GS Mini to Joe by the end of the month to get it serviced (later resolved: \"GS Mini service\")",
            lines[0],
        )
        assertEquals(
            "- [happened: 25 February 2023 · mentioned: Sat 25 February 2023] User took their Taylor GS Mini to Joe's shop for servicing",
            lines[1],
            "the outcome still follows its plan",
        )
        assertEquals("- [due: March 2023 · mentioned: Wed 1 February 2023] User plans to repaint the guitar case", lines[2])
        assertEquals(
            "- [resolved: by Fri 10 February 2023 · mentioned: Wed 1 February 2023] User planned to ask Joe about new strings (later resolved: \"new strings\")",
            lines[3],
            "an outcome dated only by its report is settled by that day",
        )
        // The outcome's date needs the resolve itself, not its hit: it is read through the lookup.
        val alone = format(listOf(f.plan), f.lookup).lines().first()
        assertTrue(alone.startsWith("- [due: 28 February 2023 · resolved: 25 February 2023 · mentioned: Wed 1 February 2023] "), alone)
        // A resolve the lookup can't return (deleted meanwhile) or with no date gives no outcome.
        val bare = FakeLookup().apply { resolves[f.plan.id] = listOf(999L) }
        assertEquals("- [due: 28 February 2023 · mentioned: Wed 1 February 2023] ", format(listOf(f.plan), bare).lines().first().substringBefore("User"))
    }

    @Test
    fun `a quote's day shows when the line above doesn't show that one day`() {
        val f = Fixture()
        val park = format(listOf(f.park), f.lookup).lines()
        assertEquals("    \"I took my kids to a park yesterday\" — Melanie (said: Mon 28 August 2023)", park[1], "under a range of days")
        val party = format(listOf(f.party), f.lookup).lines()
        assertEquals("    \"We had a party last Friday\" — John", party[1], "the line above already shows that day")
        // Under a scene line (dated by the scene, not the quote) and under an undated memory the quote shows its day.
        val undated = engram("John likes parties")
        f.lookup.quotes[undated.id] = listOf(f.partyQuote)
        assertEquals("    \"We had a party last Friday\" — John (said: Sat 20 May 2023)", format(listOf(undated), f.lookup).lines()[1])
    }

    @Test
    fun `a scene's dates are when it was talked through`() {
        val f = Fixture()
        val block = format(listOf(f.hub), f.lookup)
        assertEquals(
            "- [Scene: Melanie's family camping trip · mentioned: 27 June 2023] Melanie told about her family's camping trip\n" +
                en.legendScene + en.legendDatesLabelledSuffix + en.legendRelativeLabelledSuffix,
            block,
        )
    }

    @Test
    fun `the legend explains every label`() {
        val f = Fixture()
        val legend = format(f.hits, f.lookup).lines().last()
        assertEquals(en.legendSceneAndThread + en.legendDatesLabelledSuffix + en.legendRelativeLabelledSuffix, legend)
        for (label in listOf("\"happened: …\"", "(year only)", "(month only)", "\"mentioned: …\"", "\"due: …\"", "\"resolved: …\"", "\"said: …\"")) {
            assertTrue(label in en.legendDatesLabelled && label in en.legendDatesLabelledSuffix, label)
        }
        assertTrue("not the date of the event" in en.legendDatesLabelled)
        val alone = format(listOf(f.italy)).lines().last()
        assertEquals(en.legendDatesLabelled + en.legendRelativeLabelledSuffix, alone)
        assertFalse(format(listOf(f.italy), dating = RecallDating.absolute(ZoneOffset.UTC, labels = true)).endsWith(en.legendRelativeLabelledSuffix), "in ISO, as before, no relative note")
    }

    @Test
    fun `the turn-end relative time follows the date it counts from`() {
        val f = Fixture()
        val tail = RecallDating.turnEnd(null, Instant.parse("2022-04-27T10:00:00Z"), ZoneOffset.UTC, inWords = true, labels = true)
        assertEquals("- [happened: 2021 (year only) · mentioned: Wed 20 April 2022 · ${en.relativeTime(7)}] Ethan visited Italy", format(listOf(f.italy), dating = tail).lines().first())
        val day = engram("x", eventDate = "2022-04-24", said = "2022-04-25")
        assertEquals("- [happened: 24 April 2022 · ${en.relativeTime(3)} · mentioned: Mon 25 April 2022] x", format(listOf(day), dating = tail).lines().first())
    }

    @Test
    fun `labels need the temporal layer and day precision`() {
        val f = Fixture()
        assertEquals("- Ethan visited Italy", format(listOf(f.italy), temporalLayer = false))
        assertEquals("- [Scene: Melanie's family camping trip · 27 June 2023] Melanie told about her family's camping trip\n${en.legendScene}",
            format(listOf(f.hub), f.lookup, temporalLayer = false))
        kotlin.test.assertFailsWith<IllegalArgumentException> { RecallDating(labels = true) }
    }

    // --- off: the block as before -------------------------------------------------------------------------------------

    /**
     * With labels off, the fixture's block is the one Lattice 5cb729c (before labels) rendered for it, byte for byte,
     * in words and in ISO, at a turn end: these strings were taken from that commit's [RecallFormatter].
     */
    @Test
    fun `with labels off the block is the one before labels, byte for byte`() {
        val f = Fixture()
        assertEquals(LEGACY_WORDS, format(f.hits, f.lookup, dating = wordsLegacy))
        assertEquals(LEGACY_ISO_ZH, format(f.hits, f.lookup, markers = zh, dating = RecallDating.absolute(ZoneOffset.UTC)))
        val tail = RecallDating.turnEnd("chat", Instant.parse("2023-09-20T10:00:00Z"), ZoneOffset.UTC, inWords = true)
        assertEquals(LEGACY_TURN_END, format(f.hits, f.lookup, dating = tail))
        assertEquals(LEGACY_ISO, format(f.hits, f.lookup, dating = RecallDating.LEGACY))
    }

    private companion object {
        val LEGACY_WORDS = """
- [2021 · said Wed 20 April 2022] Ethan visited Italy
- [2022 · said Sat 4 February 2023] Jolene took a photo on vacation in Bogota
- [February 2022 · said Sun 27 March 2022] John resumed playing drums
- [27 August 2023 · said Mon 28 August 2023 to Wed 13 September 2023] Melanie took her kids to a park
    "I took my kids to a park yesterday" — Melanie
- [due 28 February 2023 · said Wed 1 February 2023] User plans to take their Taylor GS Mini to Joe by the end of the month to get it serviced (later resolved: "GS Mini service")
- [25 February 2023 · said Sat 25 February 2023] User took their Taylor GS Mini to Joe's shop for servicing
- [said Wed 1 February 2023] User planned to ask Joe about new strings (later resolved: "new strings")
- [due March 2023 · said Wed 1 February 2023] User plans to repaint the guitar case
- [said Sat 20 May 2023] John had a party with veterans last Friday
    "We had a party last Friday" — John
- [Scene: Melanie's family camping trip · 27 June 2023] Melanie told about her family's camping trip
(Memory note: a [Scene: …] line is the memory system's own summary of a past scene; (later resolved: "…") / (still open: "…") are system notes on whether a promise or suspense was later resolved — neither is something the user said.) ("said …" is when a memory came up in the conversation, first to latest mention; "due …" is a plan's target date, not something that has happened; when statements of the same thing disagree, the latest mention is the current one.) (A relative time inside a memory, such as "yesterday", "three days ago" or "last Friday", counts from the day the memory was said, not from today.)
""".removePrefix("\n").removeSuffix("\n")
        val LEGACY_ISO_ZH = """
- [2021 · 提到 2022-04-20] Ethan visited Italy
- [2022 · 提到 2023-02-04] Jolene took a photo on vacation in Bogota
- [2022-02 · 提到 2022-03-27] John resumed playing drums
- [2023-08-27 · 提到 2023-08-28～2023-09-13] Melanie took her kids to a park
    「I took my kids to a park yesterday」 —— Melanie
- [目标 2023-02-28 · 提到 2023-02-01] User plans to take their Taylor GS Mini to Joe by the end of the month to get it serviced（「GS Mini service」后来已了结）
- [2023-02-25 · 提到 2023-02-25] User took their Taylor GS Mini to Joe's shop for servicing
- [提到 2023-02-01] User planned to ask Joe about new strings（「new strings」后来已了结）
- [目标 2023-03 · 提到 2023-02-01] User plans to repaint the guitar case
- [提到 2023-05-20] John had a party with veterans last Friday
    「We had a party last Friday」 —— John
- 〔场景：Melanie's family camping trip · 2023-06-27〕Melanie told about her family's camping trip
（记忆说明：〔场景：…〕 一行是记忆系统自己对过去某个场景的总结；（「…」后来已了结） / （「…」尚未了结） 是系统对某个承诺或悬念后来是否已了结的标注——两者都不是对话里任何人说过的话。）（方括号里的“提到 …”是记忆被说起的日期，跨几天的写成“首次～最近一次”；“目标 …”是计划或截止日期，不是已经发生的事；新旧说法不一致时以最近一次提到的为准。）
""".removePrefix("\n").removeSuffix("\n")
        val LEGACY_TURN_END = """
- [2021 · said Wed 20 April 2022 · about a year ago] Ethan visited Italy
- [2022 · said Sat 4 February 2023 · about 7 months ago] Jolene took a photo on vacation in Bogota
- [February 2022 · said Sun 27 March 2022 · about a year ago] John resumed playing drums
- [27 August 2023 · about 3 weeks ago · said Mon 28 August 2023 to Wed 13 September 2023] Melanie took her kids to a park
    "I took my kids to a park yesterday" — Melanie
- [due 28 February 2023 · said Wed 1 February 2023 · about 8 months ago] User plans to take their Taylor GS Mini to Joe by the end of the month to get it serviced (later resolved: "GS Mini service")
- [25 February 2023 · about 7 months ago · said Sat 25 February 2023] User took their Taylor GS Mini to Joe's shop for servicing
- [said Wed 1 February 2023 · about 8 months ago] User planned to ask Joe about new strings (later resolved: "new strings")
- [due March 2023 · said Wed 1 February 2023 · about 8 months ago] User plans to repaint the guitar case
- [said Sat 20 May 2023 · about 4 months ago] John had a party with veterans last Friday
    "We had a party last Friday" — John
- [Scene: Melanie's family camping trip · 27 June 2023] Melanie told about her family's camping trip
(Memory note: a [Scene: …] line is the memory system's own summary of a past scene; (later resolved: "…") / (still open: "…") are system notes on whether a promise or suspense was later resolved — neither is something the user said.) ("said …" is when a memory came up in the conversation, first to latest mention; "due …" is a plan's target date, not something that has happened; when statements of the same thing disagree, the latest mention is the current one.) (A relative time inside a memory, such as "yesterday", "three days ago" or "last Friday", counts from the day the memory was said, not from today.)
""".removePrefix("\n").removeSuffix("\n")
        val LEGACY_ISO = """
- [2021] Ethan visited Italy
- [2022] Jolene took a photo on vacation in Bogota
- [2022-02] John resumed playing drums
- [2023-08] Melanie took her kids to a park
    "I took my kids to a park yesterday" — Melanie
- User plans to take their Taylor GS Mini to Joe by the end of the month to get it serviced (later resolved: "GS Mini service")
- [2023-02] User took their Taylor GS Mini to Joe's shop for servicing
- User planned to ask Joe about new strings (later resolved: "new strings")
- User plans to repaint the guitar case
- John had a party with veterans last Friday
    "We had a party last Friday" — John
- [Scene: Melanie's family camping trip · 2023-06-27] Melanie told about her family's camping trip
(Memory note: a [Scene: …] line is the memory system's own summary of a past scene; (later resolved: "…") / (still open: "…") are system notes on whether a promise or suspense was later resolved — neither is something the user said.)
""".removePrefix("\n").removeSuffix("\n")
    }
}
