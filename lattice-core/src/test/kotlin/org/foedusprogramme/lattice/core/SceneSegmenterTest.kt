package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.ConversationMessage.Role
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SceneSegmenter]: the model's scene split over a transcript window. The model is canned; every assertion is about
 * what the segmenter sends and how strictly it validates the reply.
 */
class SceneSegmenterTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private class CannedLlm(private val reply: (String) -> String) : MemoryModel {
        val systems = ArrayList<String>()
        val users = ArrayList<String>()
        val tasks = ArrayList<MemoryTask>()
        override fun complete(task: MemoryTask, system: String, user: String): String {
            tasks += task
            systems += system
            users += user
            return reply(user)
        }
    }

    private fun msg(id: Long, text: String, at: Instant, role: Role = Role.USER, speaker: String? = null) =
        ConversationMessage(id, role, text, at, speakerName = speaker)

    /** Synthetic ~40-message transcript (ids 101..140) over two local days, four topics. No real user data. */
    private fun syntheticTranscript(): List<ConversationMessage> {
        val day1 = Instant.parse("2026-09-01T02:00:00Z") // 10:00 local
        val day2 = Instant.parse("2026-09-02T11:00:00Z") // 19:00 local
        val topics = listOf(
            (101L..112L) to "the Kyoto trip itinerary",
            (113L..124L) to "the failing Gradle KSP build",
            (125L..132L) to "a weeknight curry recipe",
            (133L..140L) to "which ryokan to book in Kyoto",
        )
        return topics.flatMap { (ids, topic) ->
            ids.map { id ->
                val base = if (id < 125) day1 else day2
                val role = if (id % 2 == 1L) Role.USER else Role.ASSISTANT
                msg(id, "message $id about $topic", base.plusSeconds((id - 100) * 60), role)
            }
        }
    }

    private fun recorded(): String =
        requireNotNull(javaClass.getResource("/lattice/scenes/scene-segmenter-recorded-v1.txt")).readText()

    private fun segmenter(llm: MemoryModel) = SceneSegmenter(llm, zone)

    private fun scenesJson(vararg ranges: Pair<Long, Long>, lastEnded: Boolean = true, continues: Boolean = false): String =
        ranges.mapIndexed { i, (s, e) ->
            val ended = if (i == ranges.lastIndex) lastEnded else true
            """{"start_id": $s, "end_id": $e, "title": "t$i", "synopsis": "s$i", "ended": $ended}"""
        }.joinToString(",", prefix = """{"continues_open_scene": $continues, "scenes": [""", postfix = "]}")

    private fun kindOf(block: () -> Unit): SceneSegmentationException.Kind =
        assertFailsWith<SceneSegmentationException> { block() }.kind

    // --- Normal output ------------------------------------------------------------------------------

    @Test
    fun replayingTheRecordedOutputYieldsFourContiguousScenes() {
        val messages = syntheticTranscript()
        val llm = CannedLlm { recorded() }

        val result = segmenter(llm).segment(messages)

        assertEquals(4, result.scenes.size)
        assertEquals(listOf(101L to 112L, 113L to 124L, 125L to 132L, 133L to 140L), result.scenes.map { it.startId to it.endId })
        assertEquals("Planning the Kyoto trip", result.scenes[0].title)
        assertTrue(result.scenes[1].synopsis!!.contains("KSP"))
        assertEquals(listOf(true, true, true, false), result.scenes.map { it.ended })
        assertFalse(result.continuesOpenScene)
        // Every to-segment message is in exactly one scene.
        val covered = result.scenes.flatMap { sc -> messages.map { it.position }.filter { it in sc.startId..sc.endId } }
        assertEquals(messages.map { it.position }, covered)
    }

    @Test
    fun eachLineCarriesItsIdAndLocalTime() {
        val llm = CannedLlm { recorded() }
        segmenter(llm).segment(syntheticTranscript())

        val user = llm.users.single()
        // 2026-09-01T02:01Z is 10:01 in Asia/Shanghai: rendered in the configured zone, not UTC.
        assertTrue(user.contains("id=101 2026-09-01 10:01 [user] message 101 about the Kyoto trip itinerary"), user)
        assertTrue(user.contains("id=140 2026-09-02 19:40 [assistant]"), user)
        val system = llm.systems.single()
        assertEquals(listOf(MemoryTask.SCENES), llm.tasks)
        assertTrue(system.contains("not by date", ignoreCase = true), system)
        assertTrue(system.contains("JSON only", ignoreCase = true), system)
    }

    @Test
    fun aGroupMembersLineNamesTheSpeaker() {
        val at = Instant.parse("2026-09-01T02:00:00Z")
        val messages = listOf(
            msg(101, "who is bringing the tent?", at, speaker = "Mia"),
            msg(102, "Mia is, I think", at.plusSeconds(60), Role.ASSISTANT),
        )
        val llm = CannedLlm { scenesJson(101L to 102L) }

        segmenter(llm).segment(messages)

        val line = llm.users.single().lines().single { it.startsWith("id=101 ") }
        assertTrue("Sender Display Name: Mia" in line && "who is bringing the tent?" in line, line)
    }

    @Test
    fun aQuoteRepairedReplyStillParses() {
        val messages = syntheticTranscript().take(10) // 101..110
        // Unescaped ASCII quotes inside a title — the NativeMemoryExtractor poison shape.
        val raw = """{"continues_open_scene": false, "scenes": [{"start_id": 101, "end_id": 110, "title": "我们的"京都"计划", "synopsis": "s", "ended": true}]}"""

        val result = segmenter(CannedLlm { raw }).segment(messages)

        assertEquals(1, result.scenes.size)
        assertEquals("我们的\"京都\"计划", result.scenes.single().title)
    }

    @Test
    fun aNonLastSceneIsAlwaysEnded() {
        val messages = syntheticTranscript().take(10)
        val raw = """{"scenes": [{"start_id": 101, "end_id": 105, "title": "a", "synopsis": "a", "ended": false},
            {"start_id": 106, "end_id": 110, "title": "b", "synopsis": "b"}]}"""

        val result = segmenter(CannedLlm { raw }).segment(messages)

        assertTrue(result.scenes[0].ended, "a scene followed by another scene has ended")
        assertFalse(result.scenes[1].ended, "a last scene without an ended flag stays open (kept in context)")
    }

    // --- Strict validation --------------------------------------------------------------------------

    @Test
    fun aGapBeyondTheSeamSlackIsInvalid() {
        val messages = syntheticTranscript().take(20)
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(101L to 104L, 109L to 120L) }).segment(messages) })
    }

    @Test
    fun anOverlapBeyondTheSeamSlackIsInvalid() {
        val messages = syntheticTranscript().take(20)
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(101L to 110L, 105L to 120L) }).segment(messages) })
    }

    @Test
    fun smallSeamSlipsAreRepaired() {
        val messages = syntheticTranscript().take(20) // 101..120
        // A one-message gap is folded into the previous scene.
        val gap = segmenter(CannedLlm { scenesJson(101L to 104L, 106L to 120L) }).segment(messages).scenes
        assertEquals(listOf(101L to 105L, 106L to 120L), gap.map { it.startId to it.endId })
        // A two-message overlap is clipped from the later scene.
        val overlap = segmenter(CannedLlm { scenesJson(101L to 106L, 105L to 120L) }).segment(messages).scenes
        assertEquals(listOf(101L to 106L, 107L to 120L), overlap.map { it.startId to it.endId })
        // A missing last message (seen live: "scenes end at 339, window ends at 340") extends the last scene.
        val tail = segmenter(CannedLlm { scenesJson(101L to 110L, 111L to 119L) }).segment(messages).scenes
        assertEquals(listOf(101L to 110L, 111L to 120L), tail.map { it.startId to it.endId })
        // A late first start is pulled back to the window's first id.
        val head = segmenter(CannedLlm { scenesJson(103L to 110L, 111L to 120L) }).segment(messages).scenes
        assertEquals(listOf(101L to 110L, 111L to 120L), head.map { it.startId to it.endId })
    }

    @Test
    fun anOutOfRangeIdIsInvalid() {
        val messages = syntheticTranscript().take(10)
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(99L to 105L, 106L to 110L) }).segment(messages) })
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(101L to 105L, 106L to 111L) }).segment(messages) })
    }

    /** Ids 101..106 and 111..120: the window has a gap (live: 806 → 811). */
    private fun gappedWindow(): List<ConversationMessage> = syntheticTranscript().take(20).filter { it.position !in 107L..110L }

    @Test
    fun anEndIdInsideAGapOfTheWindowSnapsToTheLastMessageBeforeIt() {
        // Live: "scene #3 end_id 807 is outside the window" with ids 806 -> 811.
        val scenes = segmenter(CannedLlm { scenesJson(101L to 108L, 111L to 120L) }).segment(gappedWindow()).scenes
        assertEquals(listOf(101L to 106L, 111L to 120L), scenes.map { it.startId to it.endId })
    }

    @Test
    fun aStartIdInsideAGapOfTheWindowSnapsToTheFirstMessageAfterIt() {
        val scenes = segmenter(CannedLlm { scenesJson(101L to 106L, 108L to 120L) }).segment(gappedWindow()).scenes
        assertEquals(listOf(101L to 106L, 111L to 120L), scenes.map { it.startId to it.endId })
    }

    @Test
    fun bothEdgesOfASeamInsideAGapSnapInward() {
        val messages = gappedWindow()
        val scenes = segmenter(CannedLlm { scenesJson(101L to 109L, 107L to 120L) }).segment(messages).scenes
        assertEquals(listOf(101L to 106L, 111L to 120L), scenes.map { it.startId to it.endId })
        val covered = scenes.flatMap { sc -> messages.map { it.position }.filter { it in sc.startId..sc.endId } }
        assertEquals(messages.map { it.position }, covered, "every window message in exactly one scene")
    }

    @Test
    fun aSnappedIdStillGetsTheSeamSlack() {
        // 103 is missing too: the second scene's start 108 snaps to 111, then the one-message seam gap (106) folds back.
        val messages = gappedWindow().filter { it.position != 103L } // 101 102 104 105 106 111..120
        val scenes = segmenter(CannedLlm { scenesJson(101L to 105L, 108L to 120L) }).segment(messages).scenes
        assertEquals(listOf(101L to 106L, 111L to 120L), scenes.map { it.startId to it.endId })
    }

    @Test
    fun anIdOutsideTheRangeOfAGappedWindowIsStillInvalid() {
        val messages = gappedWindow()
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(101L to 106L, 111L to 125L) }).segment(messages) })
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(95L to 106L, 111L to 120L) }).segment(messages) })
        // A scene that lies wholly inside the gap names no message at all.
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(101L to 106L, 107L to 110L, 111L to 120L) }).segment(messages) })
    }

    @Test
    fun reversedScenesOrAReversedRangeAreInvalid() {
        val messages = syntheticTranscript().take(10)
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(106L to 110L, 101L to 105L) }).segment(messages) })
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(110L to 101L) }).segment(messages) })
    }

    @Test
    fun incompleteCoverageAndNoScenesAreInvalid() {
        val messages = syntheticTranscript().take(10)
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { scenesJson(101L to 106L) }).segment(messages) })
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { """{"scenes": []}""" }).segment(messages) })
        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(CannedLlm { """{"scenes": [{"start_id": "x", "end_id": 110}]}""" }).segment(messages) })
    }

    // --- Read-only lookahead ------------------------------------------------------------------------

    @Test
    fun lookaheadIdsNeverEnterASceneRange() {
        val all = syntheticTranscript()
        val toSegment = all.take(24) // 101..124
        val lookahead = all.drop(24).take(4) // 125..128
        // The model wrongly stretches the last scene into the lookahead.
        val llm = CannedLlm { scenesJson(101L to 112L, 113L to 126L) }

        assertEquals(SceneSegmentationException.Kind.INVALID, kindOf { segmenter(llm).segment(toSegment, lookahead = lookahead) })
        val user = llm.users.single()
        val lookaheadSection = user.substringAfter("## Lookahead")
        assertTrue(lookaheadSection.contains("message 125 about a weeknight curry recipe"), user)
        assertFalse(lookaheadSection.contains("id=125"), "lookahead lines carry no selectable id:\n$user")
        assertFalse(user.substringBefore("## Lookahead").contains("message 125"), "lookahead is not in the to-segment section")
    }

    @Test
    fun lastSceneEndedFollowsTheModelsLookaheadJudgement() {
        val all = syntheticTranscript()
        val toSegment = all.take(24) // 101..124, the Gradle topic ends at 124
        val topicChanges = all.drop(24).take(4) // curry: the Gradle scene has ended
        val ended = segmenter(CannedLlm { scenesJson(101L to 112L, 113L to 124L, lastEnded = true) })
            .segment(toSegment, lookahead = topicChanges)
        assertTrue(ended.scenes.last().ended)

        val cutMidScene = all.take(20) // 101..120: the Gradle topic continues in 121..124
        val sameTopic = all.drop(20).take(4)
        val open = segmenter(CannedLlm { scenesJson(101L to 112L, 113L to 120L, lastEnded = false) })
            .segment(cutMidScene, lookahead = sameTopic)
        assertFalse(open.scenes.last().ended)
        assertEquals(120L, open.scenes.last().endId)
    }

    @Test
    fun lookaheadIsCappedToTheConfiguredSize() {
        val all = syntheticTranscript()
        val llm = CannedLlm { scenesJson(101L to 110L) }
        SceneSegmenter(llm, zone, lookaheadMessages = 2).segment(all.take(10), lookahead = all.drop(10))
        val section = llm.users.single().substringAfter("## Lookahead")
        assertTrue(section.contains("message 111") && section.contains("message 112"), section)
        assertFalse(section.contains("message 113"), section)
    }

    // --- Open scene continuation --------------------------------------------------------------------

    @Test
    fun anOpenSceneThatContinuesIsFlagged() {
        val all = syntheticTranscript()
        val open = SceneSegmenter.OpenScene(
            title = "Planning the Kyoto trip",
            synopsis = "Four days in Kyoto in November.",
            tail = all.take(3),
        )
        val llm = CannedLlm { scenesJson(104L to 112L, 113L to 124L, continues = true) }

        val result = segmenter(llm).segment(all.subList(3, 24), openScene = open)

        assertTrue(result.continuesOpenScene)
        val user = llm.users.single()
        assertTrue(user.contains("## Open scene"), user)
        assertTrue(user.contains("Planning the Kyoto trip") && user.contains("Four days in Kyoto"), user)
        assertTrue(user.substringAfter("## Open scene").substringBefore("## To segment").contains("message 103"), user)
        assertFalse(user.contains("id=101"), "open-scene tail lines carry no selectable id:\n$user")
    }

    @Test
    fun continuationIsIgnoredWithoutAnOpenScene() {
        val messages = syntheticTranscript().take(10)
        val llm = CannedLlm { scenesJson(101L to 110L, continues = true) }
        val result = segmenter(llm).segment(messages)
        assertFalse(result.continuesOpenScene, "nothing to continue")
        assertFalse(llm.users.single().contains("## Open scene"))
    }

    // --- Failures are distinguishable, never partial -------------------------------------------------

    @Test
    fun nonJsonAndEmptyOutputAreUnparseable() {
        val messages = syntheticTranscript().take(10)
        val nonJson = assertFailsWith<SceneSegmentationException> {
            segmenter(CannedLlm { "Sorry, I can't split this conversation." }).segment(messages)
        }
        assertEquals(SceneSegmentationException.Kind.UNPARSEABLE, nonJson.kind)
        assertEquals("Sorry, I can't split this conversation.", nonJson.rawOutput)
        assertEquals(SceneSegmentationException.Kind.UNPARSEABLE, kindOf { segmenter(CannedLlm { "" }).segment(messages) })
        assertEquals(SceneSegmentationException.Kind.UNPARSEABLE, kindOf { segmenter(CannedLlm { """{"scenes": 3}""" }).segment(messages) })
    }

    @Test
    fun aTimeoutOrOutageIsUnavailable() {
        val messages = syntheticTranscript().take(10)
        val e = assertFailsWith<SceneSegmentationException> {
            segmenter(CannedLlm { throw MemoryModelUnavailableException("the model timed out after 120s") }).segment(messages)
        }
        assertEquals(SceneSegmentationException.Kind.UNAVAILABLE, e.kind)
        assertTrue(e.cause is MemoryModelUnavailableException)
    }

    @Test
    fun unparseableAndInvalidRepliesAreDeadLettered() {
        // One "not parseable" segmentation once left no trace of what the model said.
        val messages = syntheticTranscript().take(10)
        val letters = ArrayList<DeadLetterEntry>()
        fun seg(reply: String) = SceneSegmenter(CannedLlm { reply }, zone, deadLetter = { letters += it })

        assertFailsWith<SceneSegmentationException> { seg("Sorry, I can't split this conversation.").segment(messages) }
        assertFailsWith<SceneSegmentationException> { seg(scenesJson(101L to 102L, 107L to 110L)).segment(messages) }
        assertFailsWith<SceneSegmentationException> {
            SceneSegmenter(CannedLlm { throw MemoryModelUnavailableException("timed out") }, zone, deadLetter = { letters += it }).segment(messages)
        }

        assertEquals(2, letters.size, "an outage has no reply to keep: $letters")
        val (unparseable, invalid) = letters
        assertEquals(SceneSegmenter.DEAD_LETTER_PHASE, unparseable.phase)
        assertEquals("Sorry, I can't split this conversation.", unparseable.rawOutput)
        assertTrue(unparseable.reason.startsWith("UNPARSEABLE"), unparseable.reason)
        assertEquals(101L, unparseable.sourceMessageId, "the window's first message")
        assertTrue(unparseable.transcript.contains("id=101 "), "the rendered window is kept")
        assertTrue(invalid.reason.startsWith("INVALID"), invalid.reason)
        assertTrue(invalid.rawOutput.contains("\"start_id\": 107"), invalid.rawOutput)
    }

    // --- Transcript hygiene -------------------------------------------------------------------------

    @Test
    fun longBodiesAreTruncated() {
        val long = "x".repeat(2000)
        val llm = CannedLlm { scenesJson(1L to 1L) }
        SceneSegmenter(llm, zone, maxBodyChars = 50).segment(listOf(msg(1, long, Instant.parse("2026-09-01T02:00:00Z"))))
        assertFalse(llm.users.single().contains("x".repeat(51)))
        assertTrue(llm.users.single().contains("x".repeat(50)))
    }

    // --- Imagined situations & naming (titles/synopses) ----------------------------------------------

    @Test
    fun `the system prompt forbids role-play framing for imagined situations and generic person labels`() {
        // A run produced the title "海边夏日角色扮演" and a synopsis with "用户"/"assistant": the rule the
        // extraction and narration prompts have must cover titles and synopses too.
        val p = SceneSegmenter.SYSTEM_PROMPT
        assertTrue("imagined situation" in p, p)
        assertTrue("\"role-play\"" in p && "\"pretend\", in any language" in p, "the framing words are forbidden in any language:\n$p")
        assertTrue("ONLY when the participants themselves talk about the frame" in p || "only when the participants themselves talk about the frame" in p, p)
        assertTrue("\"the user\"" in p && "\"the assistant\"" in p && "in any language, in a title" in p, "generic person labels are forbidden in any language:\n$p")
        // Narrowed to a shared imagined scene; real-life what-ifs and plans stay what they are.
        assertImaginedRuleIsNarrowed(p, "segmenter")
        assertImaginedRuleCoversEstablishedWorlds(p, "segmenter")
    }

    @Test
    fun `titles and synopses take the language of the lines being segmented`() {
        // The participants' own sentences decide, borrowed terms do not (a Chinese chat full of English jargon was
        // once summarized in English), and neither does the open scene's earlier title — on English chats one
        // Chinese title used to carry every later one into Chinese through it.
        val p = SceneSegmenter.SYSTEM_PROMPT
        assertTrue("the language of the participants' own" in p.replace(Regex("\\s+"), " "), p)
        assertTrue("words borrowed from another language" in p.replace(Regex("\\s+"), " "), "borrowed-term carve-out named:\n$p")
        assertTrue("keep such terms as they are" in p.replace(Regex("\\s+"), " "), "borrowed terms are kept as-is:\n$p")
        assertTrue("never decide the language" in p.replace(Regex("\\s+"), " "), "the open scene's own output does not decide:\n$p")
        assertFalse("stays Chinese" in p || "dominant language" in p, "no language is the reference:\n$p")
        // The reply names the language before any title (a rule alone still let one model drift).
        assertTrue("""{"language": "…", "continues_open_scene"""" in p, p)
    }

    @Test
    fun `no Who-is-who block is rendered when nothing is configured (byte-identical legacy)`() {
        val llm = CannedLlm { recorded() }
        segmenter(llm).segment(syntheticTranscript())
        assertFalse("## Who is who" in llm.users.single(), llm.users.single())
    }

    @Test
    fun `Who-is-who names the configured self aliases and human counterpart`() {
        val llm = CannedLlm { recorded() }
        SceneSegmenter(llm, zone, selfAliases = setOf("云舒", "小舒"), userName = "Ethan").segment(syntheticTranscript())
        val user = llm.users.single()
        assertTrue("## Who is who" in user, user)
        assertTrue("云舒" in user && "小舒" in user, user)
        assertTrue("Ethan" in user, user)
    }

    @Test
    fun `Who-is-who omits the unconfigured half without inventing it`() {
        val onlyName = CannedLlm { recorded() }
        SceneSegmenter(onlyName, zone, userName = "Ethan").segment(syntheticTranscript())
        val u1 = onlyName.users.single()
        assertTrue("## Who is who" in u1 && "Ethan" in u1, u1)
        assertFalse("云舒" in u1, u1)

        val onlyAliases = CannedLlm { recorded() }
        SceneSegmenter(onlyAliases, zone, selfAliases = setOf("云舒")).segment(syntheticTranscript())
        val u2 = onlyAliases.users.single()
        assertTrue("## Who is who" in u2 && "云舒" in u2, u2)
        assertFalse("Ethan" in u2, u2)
    }

    companion object {
        /** The imagined-world rule covers only a shared imagined scene; real-life what-ifs and plans stay what they are. */
        fun assertImaginedRuleIsNarrowed(p: String, where: String) {
            assertTrue("shared imagined scene" in p, "limited to a shared imagined scene ($where):\n$p")
            assertTrue("step into together" in p, "that the participants step into together ($where)")
            for (cue in listOf("\"suppose I", "\"if I", "\"someday we'll")) {
                assertTrue(cue in p, "the real-life carve-out names $cue ($where)")
            }
            assertTrue("never written as having happened" in p, "what-ifs are never written as having happened ($where)")
            assertTrue("hypotheticals" in p && "plans" in p, "they stay hypotheticals / plans ($where)")
        }

        /**
         * A short input often shows no opener: a shared imagined world established earlier still counts, but only on
         * visible evidence — talk about places is not enough, a real trip plan stays a plan — and idioms such as
         * "if I remember right" introduce real events.
         */
        fun assertImaginedRuleCoversEstablishedWorlds(p: String, where: String) {
            assertTrue("already established earlier" in p, "a world established earlier counts ($where):\n$p")
            assertTrue("even without an opener" in p, "no opener needed in this excerpt ($where)")
            assertTrue("only when something visible establishes it" in p, "visible evidence required ($where):\n$p")
            assertTrue("not evidence on its own" in p, "talk about places alone is not evidence ($where)")
            assertTrue("\"next year we'll go to Kyoto" in p && "stays a plan" in p, "the Kyoto trip-plan case ($where)")
            assertTrue("\"if I remember right\"" in p, "the idiom carve-out ($where)")
            assertTrue("introduce real events" in p, "idioms introduce real events ($where)")
        }
    }
}
