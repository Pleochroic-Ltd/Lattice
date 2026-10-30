package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledTextsTest {

    /** Records every key asked for and fails on one no bundle has. */
    private class RecordingTexts : MemoryTexts {
        val keys = LinkedHashSet<String>()
        override fun text(key: String, language: Language, vararg args: Pair<String, Any?>): String {
            keys += key
            assertTrue(BundledTexts.template(key, language) != null, "missing text $key for ${language.tag}")
            return BundledTexts.text(key, language, *args)
        }
    }

    @Test
    fun `en and zh have the same keys`() {
        val en = BundledTexts.keys("en")
        val zh = BundledTexts.keys("zh")
        assertTrue(en.isNotEmpty())
        assertEquals(en.sorted(), zh.sorted())
    }

    @Test
    fun `every key the markers use exists in en and zh, and every bundled key is used`() {
        val used = HashSet<String>()
        for (language in listOf(Language.EN, Language.ZH)) {
            val texts = RecordingTexts()
            val recall = RecallMarkers.of(texts, language)
            recall.sceneTag("t", "2026-01-01", "2026-01-02", inProgress = true)
            recall.sceneTag("", "2026-01-01", null, inProgress = false)
            recall.quote("words", "Ethan")
            recall.quote("words", null)
            recall.threadNote(listOf("a", "b", "c", "d", "x".repeat(30)), listOf("e"))
            recall.periodTag("p")
            recall.said("2026-01-01", "2026-01-02")
            recall.due("2026-01-03")
            for (days in listOf(0L, 1L, 3L, 7L, 20L, 90L, 365L, 800L)) {
                recall.relativeTime(days)
                recall.relativeTime(-days)
            }
            recall.legend(scene = true, thread = true, period = true, dates = true)
            val catalogue = CatalogueMarkers.of(texts, language)
            catalogue.dates("2026-01-01", "2026-01-02")
            catalogue.era("e", "d", "g")
            catalogue.era("e", "d", null)
            catalogue.era(null, "d", null)
            catalogue.period("p", "d", "g", provisional = true)
            catalogue.period("p", "d", null, provisional = false)
            catalogue.period(null, "d", null, provisional = false)
            catalogue.threads(listOf("a", "b"), more = 2)
            catalogue.coverage("a", "b", pending = false, pendingTo = null)
            catalogue.coverage("a", "b", pending = true, pendingTo = "c")
            catalogue.coverage("a", "b", pending = true, pendingTo = null)
            used += texts.keys
        }
        assertEquals(BundledTexts.keys("en").sorted(), used.sorted(), "the bundles hold exactly the keys the markers use")
    }

    @Test
    fun `a longer tag falls back to shorter ones and then to en`() {
        assertEquals(listOf("zh-Hant-TW", "zh-Hant", "zh", "en"), BundledTexts.chain("zh-Hant-TW"))
        assertEquals(listOf("zh-CN", "zh", "en"), BundledTexts.chain("ZH_CN"))
        assertEquals(listOf("en-GB", "en"), BundledTexts.chain("en-GB"))
        assertEquals("场景", BundledTexts.text("lattice.recall.scene.untitled", Language("zh-CN")))
        assertEquals("Scene", BundledTexts.text("lattice.recall.scene.untitled", Language("fr")))
    }

    @Test
    fun `a missing key comes back as the key`() {
        assertEquals("lattice.no.such.key", BundledTexts.text("lattice.no.such.key", Language.ZH))
        assertEquals("lattice.no.such.key", BundledTexts.text("lattice.no.such.key", Language.ZH), "and again, warned once")
    }

    @Test
    fun `placeholders are filled literally in one pass`() {
        assertEquals("Scene: {body} {0}", BundledTexts.text("lattice.recall.scene.titled", Language.EN, "title" to "{body} {0}"))
        assertEquals("[Scene: x] ", BundledTexts.text("lattice.recall.scene.wrap", Language.EN, "body" to "Scene: x"))
        assertEquals("a {b} c", BundledTexts.fill("a {b} c", arrayOf("x" to 1)), "a placeholder without an argument stays")
        assertEquals("a { b } {} c", BundledTexts.fill("a { b } {} c", arrayOf(" b " to 1)), "only names are placeholders")
        assertEquals("1-2", BundledTexts.fill("{from}-{to}", arrayOf("from" to 1, "to" to 2)))
    }

    @Test
    fun `escaped spaces at either end survive`() {
        assertEquals(" to ", BundledTexts.text("lattice.recall.date.span_separator", Language.EN))
        assertEquals(", ", BundledTexts.text("lattice.recall.thread.separator", Language.EN))
        assertEquals("[Past session] ", BundledTexts.text("lattice.recall.episode", Language.EN))
    }
}
