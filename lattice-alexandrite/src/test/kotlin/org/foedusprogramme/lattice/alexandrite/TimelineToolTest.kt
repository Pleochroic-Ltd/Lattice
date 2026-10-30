package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.lattice.GraphBusyException
import org.foedusprogramme.lattice.core.ChatTimeline
import org.foedusprogramme.lattice.core.EraRecord
import org.foedusprogramme.lattice.core.PeriodRecord
import org.foedusprogramme.lattice.core.PeriodState
import org.foedusprogramme.lattice.core.SceneWorkState
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testToolContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineToolTest {
    private val opener = FakeOpener()
    private val main = opener.graph("main")

    private suspend fun PluginHarness.Running.call(
        vararg arguments: Pair<String, Any>,
        context: ToolContext = testToolContext(),
    ): ToolResult {
        val json = JsonObject(
            arguments.associate { (name, value) ->
                name to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())
            },
        )
        return getAll<Tool>().single { it.definition.name == "lattice.timeline" }.execute(json, context)
    }

    private val ToolResult.text: String get() = content.joinToString("") { (it as TextPart).text }

    private fun scene(id: Long, title: String, day: String, period: Long?, trusted: Boolean = true) = ChatTimeline.Scene(
        id, title, "c$id", 10 * id, 10 * id + 5, day, day, trusted, SceneWorkState.MATERIALIZED, period, excluded = false,
    )

    /** An era of two periods, a period outside it with an untrusted scene, a withheld one, and a scene still pending. */
    private val timeline: ChatTimeline = run {
        val scenes = listOf(
            scene(1, "Arriving in Kyoto", "2026-06-01", 10),
            scene(2, "Temples", "2026-06-03", 10),
            scene(3, "Gradle upgrade", "2026-06-10", 11),
            scene(4, "A fetched page", "2026-06-20", 12, trusted = false),
            scene(5, "Curry night", "2026-06-21", 12),
            scene(6, "Secret plans", "2026-07-01", 13, trusted = false),
            scene(7, "Later", "2026-07-05", null),
        )
        val era = ChatTimeline.Era(
            EraRecord(100, CHAT, "Early summer", "When it all began.", dateFrom = "2026-06-01", dateTo = "2026-06-10"),
            aliases = listOf("June"),
            periodIds = listOf(10, 11),
            shown = true,
        )
        fun period(id: Long, name: String, from: String, to: String, sceneIds: List<Long>, shown: Boolean = true, era: Long? = null, state: PeriodState = PeriodState.CLOSED) =
            ChatTimeline.Period(PeriodRecord(id, CHAT, name, "$name, in short.", state, era, dateFrom = from, dateTo = to), emptyList(), sceneIds, shown)
        ChatTimeline(
            CHAT,
            eras = listOf(era),
            periods = listOf(
                period(10, "Kyoto trip", "2026-06-01", "2026-06-03", listOf(1, 2), era = 100),
                period(11, "Build work", "2026-06-10", "2026-06-10", listOf(3), era = 100),
                period(12, "Curry", "2026-06-20", "2026-06-21", listOf(4, 5)),
                period(13, "Plotting", "2026-07-01", "2026-07-01", listOf(6), shown = false, state = PeriodState.PROVISIONAL),
            ),
            scenes = scenes,
        )
    }

    @Test
    fun `no arguments list the eras and periods, withholding untrusted names`() {
        val asked = mutableListOf<String>()
        main.timelines = { asked += it; timeline }
        val context = testToolContext()

        latticeHarness(config(SCENES_ON), opener).execute {
            val text = call(context = context).text

            assertEquals(
                """
                This chat's history, oldest first:
                - Era "Early summer" 2026-06-01 to 2026-06-10
                  - Period "Kyoto trip" 2026-06-01 to 2026-06-03
                  - Period "Build work" 2026-06-10
                - Period "Curry" 2026-06-20 to 2026-06-21
                - A period, 2026-07-01 (name withheld) (may still be going on)
                Covers 2026-06-01 to 2026-07-01. 1 later scene is still being organised.
                Ask for a period or an era by its name or by a date inside it to see its scenes.
                """.trimIndent(),
                text,
            )
        }

        assertEquals(listOf("test:main:chat"), asked)
        assertTrue(context.untrustedReasons.isEmpty())
    }

    @Test
    fun `a period found by name lists its scenes, an untrusted one by its date only`() {
        main.timelines = { timeline }
        val context = testToolContext()

        latticeHarness(config(SCENES_ON), opener).execute {
            val text = call("period" to "curry", context = context).text

            assertEquals(
                """
                Period "Curry" 2026-06-20 to 2026-06-21
                Summary: Curry, in short.
                Scenes:
                - 2026-06-20: a scene with untrusted content (title withheld)
                - 2026-06-21: Curry night
                """.trimIndent(),
                text,
            )
            assertContains(call("period" to "2026-06-02").text, "In Era \"Early summer\" 2026-06-01 to 2026-06-10")
        }

        assertTrue(context.untrustedReasons.isEmpty())
    }

    @Test
    fun `a withheld period is found by a date only, and its page is untrusted`() {
        main.timelines = { timeline }
        val byName = testToolContext()
        val byDate = testToolContext()

        latticeHarness(config(SCENES_ON), opener).execute {
            assertContains(call("period" to "Plotting", context = byName).text, "No period matches \"Plotting\"")
            val page = call("period" to "2026-07-01", context = byDate).text

            assertTrue(page.startsWith("Lists withhold this name"), page)
            assertContains(page, "Period \"Plotting\" 2026-07-01 (may still be going on)")
            assertFalse("Secret plans" in page, page)
        }

        assertTrue(byName.untrustedReasons.isEmpty())
        assertEquals(1, byDate.untrustedReasons.size)
    }

    @Test
    fun `an era lists its scenes under its periods, and several matches give candidates`() {
        main.timelines = { timeline }

        latticeHarness(config(SCENES_ON), opener).execute {
            assertEquals(
                """
                Era "Early summer" 2026-06-01 to 2026-06-10
                Also called: "June"
                Summary: When it all began.
                Scenes:
                - Period "Kyoto trip" 2026-06-01 to 2026-06-03
                  - 2026-06-01: Arriving in Kyoto
                  - 2026-06-03: Temples
                - Period "Build work" 2026-06-10
                  - 2026-06-10: Gradle upgrade
                """.trimIndent(),
                call("era" to "june").text,
            )
            assertEquals(
                """
                Several periods match "r"; ask again by one's name or date:
                - Period "Kyoto trip" 2026-06-01 to 2026-06-03
                - Period "Build work" 2026-06-10
                - Period "Curry" 2026-06-20 to 2026-06-21
                """.trimIndent(),
                call("period" to "r").text,
            )
        }
    }

    @Test
    fun `a chat without periods, scenes off, a busy graph and bad arguments are told`() {
        val empty = ChatTimeline(CHAT, emptyList(), emptyList(), listOf(scene(1, "Kyoto", "2026-06-01", null)))
        val answers = mutableListOf<suspend () -> ChatTimeline?>({ empty }, { null }, { throw GraphBusyException("busy") })
        main.timelines = { answers.removeFirst()() }

        latticeHarness(config(SCENES_ON), opener).execute {
            assertEquals(
                "This chat's history is not organised into periods yet. It has 1 scene, on 2026-06-01.",
                call().text,
            )
            assertEquals("This memory keeps no timeline, as scenes are off.", call().text)
            val busy = call()
            assertTrue(busy.isError)
            assertContains(busy.text, "busy")
            assertTrue(call("period" to "a", "era" to "b").isError)
            assertTrue(call("period" to 3).isError)
        }
    }

    private companion object {
        const val CHAT = "test:main:chat"
    }
}
