package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [EpisodeNarrator.withinNarrationBand]: the narration length cap holds across scripts. */
class NarrationBandTest {

    @Test
    fun `a two-sentence English narration fits the cap sized on Chinese`() {
        // A real English hub narration the old character count refused (907 chars > 600).
        val english = "The user asked me to pick up the agenda table right where I had stopped, and I rebuilt it with the " +
            "discussion points, owners and time slots they wanted, then added a closing row for open questions. " +
            "Afterwards we went through who should present each item, and I suggested moving the budget review to the " +
            "start so the finance lead could leave early, which they agreed to; they also asked for a short summary " +
            "they could paste into the invitation, so I wrote three lines covering the goals, the agenda and the " +
            "materials to read beforehand, and reminded them to attach last quarter's numbers and the draft plan."
        assertTrue(english.length > 600)
        assertTrue(EpisodeNarrator.withinNarrationBand(english), "size ${EpisodeNarrator.narrationSize(english)}")
    }

    @Test
    fun `chinese is measured as before and a dump in any script is still refused`() {
        assertTrue(EpisodeNarrator.withinNarrationBand("我和 Ethan 在同里古镇划了船。"))
        assertFalse(EpisodeNarrator.withinNarrationBand("长".repeat(EpisodeNarrator.EPISODE_NARRATION_MAX + 1)))
        assertFalse(EpisodeNarrator.withinNarrationBand("，".repeat(EpisodeNarrator.EPISODE_NARRATION_MAX + 1)))
        assertFalse(EpisodeNarrator.withinNarrationBand("word ".repeat(700)))
        assertFalse(EpisodeNarrator.withinNarrationBand("abc"))
    }
}
