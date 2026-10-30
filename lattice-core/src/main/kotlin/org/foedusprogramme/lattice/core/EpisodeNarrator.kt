package org.foedusprogramme.lattice.core

/**
 * The narration seam for the episodic consolidation pass — the LLM half that writes ONE
 * self-contained sentence narrating a same-day episode. It REPLACES [MemoryAbstractor] for episodes
 * rather than reusing it: the semantic abstractor is blind to `event_date` and its "output memory #1
 * verbatim" incoherent-fallback yields exactly the single-child paraphrase the graph-side guard
 * rejects. This narrator instead receives the shared date (and, once a distilled entity field
 * lands, the key entities — pre-threaded, empty today) and, on incoherence, emits an EMPTY line so the
 * POSITIVE quality gate in [LatticeGraph.materializeEpisode] simply drops it — there is NO verbatim
 * fallback.
 *
 * Language policy tracks the same convention as [MemoryAbstractor] (write in the conversation's
 * language once that direction lands there too).
 */
class EpisodeNarrator(
    private val model: MemoryModel,
) {
    /**
     * Narrate the episode formed by [statements] (all on [eventDate]). [entities] is the entity cold-start
     * hook — empty until a distilled entity field exists on [Engram]. Returns the single narration
     * sentence, or "" when the LLM abstains / the statements do not form one coherent episode.
     */
    fun narrate(statements: List<String>, eventDate: String, entities: List<String> = emptyList()): String {
        if (statements.size < 2) return ""
        val ents = if (entities.isEmpty()) "" else "\nKey entities: ${entities.joinToString("、")}"
        val user = "Date: $eventDate$ents\n" +
            statements.mapIndexed { i, s -> "${i + 1}. ${s.trim()}" }.joinToString("\n")
        val raw = runCatching { model.complete(MemoryTask.NARRATE, SYSTEM_PROMPT, user) }.getOrDefault("").trim()
        // One self-contained sentence; take the first substantial line, strip decoration. No verbatim
        // fallback — an incoherent set yields no substantial line -> "" -> dropped by the positive gate.
        //
        // Length band 4..EPISODE_NARRATION_MAX (600) is an EPISODE-SUMMARY band, NOT a single-fact band:
        // a faithful narration of a normal 3-4 member same-day episode measures ~229-290 chars (CN, the
        // same-day gate's target locale), so the legacy 200 ceiling silently NULLED every real episode
        // (narrate -> "" -> refused AND membership-negative-cached => the episode was lost forever). The
        // ceiling must sit above a genuine multi-member narration with headroom, yet still cap a runaway
        // multi-paragraph dump. A condensed single-sentence narration scales SUB-linearly with member
        // count (a summary sentence does not grow 1:1), so even an episodeMaxSize=12 day lands well under
        // 600; 600 clears the observed 3-4 member band (+2x margin) while still rejecting a dump. See the
        // twin gate in [LatticeGraph.materializeEpisode] — the two ceilings MUST stay equal (§ below).
        return raw.lineSequence()
            .map { it.trim().trim('"', '「', '」', '-', '*', '·', ' ') }
            .firstOrNull { withinNarrationBand(it) }
            .orEmpty()
    }

    companion object {
        /**
         * Upper length bound (chars) for a materialized episode narration — an EPISODE-SUMMARY ceiling,
         * shared verbatim by [LatticeGraph.materializeEpisode]'s positive quality gate so the two never
         * disagree (a narrator ceiling above the gate's would let a too-long narration through only to be
         * refused-and-cached downstream — the exact loss this bound is sized to prevent). 600 sits ~2x
         * above the observed 229-290 char band of a faithful 3-4 member CN episode narration, clearing a
         * dense multi-member day while still capping a degenerate multi-paragraph dump.
         */
        const val EPISODE_NARRATION_MAX = 600

        /**
         * A narration's length as measured against [EPISODE_NARRATION_MAX], comparable across scripts: a CJK
         * character or CJK punctuation mark counts one, any other non-blank character a quarter. The cap was sized
         * on Chinese narrations; the same scene told in English runs about three times as many characters and was
         * refused as too long.
         */
        fun narrationSize(text: String): Double {
            var size = 0.0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                size += when {
                    Character.isWhitespace(cp) -> 0.0
                    isCjk(cp) -> 1.0
                    else -> 0.25
                }
                i += Character.charCount(cp)
            }
            return size
        }

        /** The narration band every narration gate shares: at least 4 characters, at most [EPISODE_NARRATION_MAX] by [narrationSize]. */
        fun withinNarrationBand(text: String): Boolean = text.length >= 4 && narrationSize(text) <= EPISODE_NARRATION_MAX

        private fun isCjk(cp: Int): Boolean = cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF ||
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
                else -> false
            }

        val SYSTEM_PROMPT = """
            These memories all occurred on the given date. Write ONE self-contained sentence narrating that
            episode — what happened that day — anchored to the date. Rules:
            - Output only the single sentence. No numbering, no quotes, no explanation.
            - Narrate the concrete episode; do NOT generalize into a timeless schema.
            - If they do not form one coherent episode, output an empty line.
        """.trimIndent()
    }
}
