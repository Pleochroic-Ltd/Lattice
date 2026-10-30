package org.foedusprogramme.lattice.core

/**
 * Cheap, NON-GATING zh↔en detection used ONLY to (a) tier judge-call ordering (cross-lingual pairs
 * judged first under the per-pass budget) and (b) annotate the judge prompt. It NEVER excludes a
 * pair from judging: measured on the live graph, ~6/11 gold Chinese sides fall below any script
 * threshold when code-mixed (a Chinese fact studded with @handles / Latin proper nouns), so any
 * script GATE would be recall-unsafe. A degraded classifier only reorders judging, never changes
 * correctness.
 *
 * The flag is ANY-Han (not a script ratio): after stripping @handles and quoted spans (embedded
 * catchphrases / proper nouns like "wuxia", "Starlight", "CEST"), a side is [zhBearing] iff it
 * contains ANY Han character and [enOnly] iff it has zero Han and at least one Latin letter. A ratio
 * classifier mis-tagged proper-noun-heavy Chinese sentences (e.g. "用户 Pixel42 … Stardew Valley …")
 * as English and lost them as cross-lingual; the any-Han flag keeps them zh-bearing.
 */
object CrossLingualDetector {

    private val HANDLE = Regex("@[A-Za-z0-9_]+")
    // Strip ASCII- and CJK-quoted spans (embedded proper nouns / catchphrases), so a Chinese
    // sentence quoting 'wuxia' is not mistaken for English, and an English sentence quoting 「武侠」
    // is not mistaken for Chinese.
    private val QUOTED = Regex("\"[^\"]*\"|'[^']*'|「[^」]*」|『[^』]*』|“[^”]*”|‘[^’]*’")
    private val HAN = Regex("\\p{IsHan}")
    private val LATIN = Regex("[A-Za-z]")

    private fun strip(s: String): String = s.replace(HANDLE, " ").replace(QUOTED, " ")

    /** True iff the note contains any Han character after stripping handles/quoted spans. */
    fun zhBearing(s: String): Boolean = HAN.containsMatchIn(strip(s))

    /** True iff the note has zero Han and at least one Latin letter (Latin-only). */
    fun enOnly(s: String): Boolean {
        val t = strip(s)
        return !HAN.containsMatchIn(t) && LATIN.containsMatchIn(t)
    }

    /** A pair is cross-lingual iff one side is zh-bearing and the other en-only. */
    fun isCrossLingual(a: String, b: String): Boolean =
        (zhBearing(a) && enOnly(b)) || (zhBearing(b) && enOnly(a))
}
