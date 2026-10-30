package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The period catalogue's wording; zh is pinned byte for byte. */
class CatalogueMarkersTest {

    private val zh = CatalogueMarkers.ZH
    private val en = CatalogueMarkers.EN

    @Test
    fun `zh lines are byte-identical`() {
        assertTrue(zh.header.startsWith("## 时期目录（记忆系统整理的档案"), zh.header)
        assertEquals("较早的阶段（由更早的时期归并而成）：", zh.erasLabel)
        assertEquals("时期：", zh.periodsLabel)
        assertEquals("之后的时期：", zh.periodsSinceLabel)
        assertEquals("2026-07-22～2026-07-29", zh.dates("2026-07-22", "2026-07-29"))
        assertEquals("2026-07-22", zh.dates("2026-07-22", "2026-07-22"))
        assertEquals("2026-07-29", zh.dates(null, "2026-07-29"))
        assertEquals("日期不详", zh.dates(null, null))
        assertEquals("- 「夏天」2026-07～2026-08：一起看海", zh.era("夏天", "2026-07～2026-08", "一起看海"))
        assertEquals("- 「夏天」2026-07～2026-08", zh.era("夏天", "2026-07～2026-08", null))
        assertEquals("- 一个阶段，2026-07～2026-08（名称不显示）", zh.era(null, "2026-07～2026-08", "ignored"))
        assertEquals("- 「苏州」2026-08-03（可能仍在延续）：一日游", zh.period("苏州", "2026-08-03", "一日游", provisional = true))
        assertEquals("- 一段时期，2026-08-03（名称不显示）", zh.period(null, "2026-08-03", null, provisional = false))
        assertEquals("尚未了结的线索：「A」、「B」，另有 3 条", zh.threads(listOf("A", "B"), more = 3))
        assertEquals("尚未了结的线索：「A」", zh.threads(listOf("A"), more = 0))
        assertEquals("覆盖范围：始于 2026-07-01；截至 2026-08-31。", zh.coverage("2026-07-01", "2026-08-31", pending = false, pendingTo = null))
        assertEquals(
            "覆盖范围：始于 2026-07-01；截至 2026-08-31；2026-08-31 之后至 2026-09-10 尚在整理。",
            zh.coverage("2026-07-01", "2026-08-31", pending = true, pendingTo = "2026-09-10"),
        )
        assertEquals(
            "覆盖范围：始于 2026-07-01；截至 2026-08-31；2026-08-31 之后的场景尚在整理。",
            zh.coverage("2026-07-01", "2026-08-31", pending = true, pendingTo = "2026-08-30"),
        )
    }

    @Test
    fun `en lines`() {
        assertEquals("- \"Summer\" 2026-07～2026-08: by the sea", en.era("Summer", "2026-07～2026-08", "by the sea"))
        assertEquals("- A period, 2026-08-03 (may still be going on) (name withheld)", en.period(null, "2026-08-03", null, provisional = true))
        assertEquals("Open threads: \"A\", \"B\", and 1 more", en.threads(listOf("A", "B"), more = 1))
        assertEquals("Covers: from undated to undated.", en.coverage(null, null, pending = false, pendingTo = null))
    }

    @Test
    fun `a name holding braces or quotes renders as it is`() {
        assertEquals("- 「{dates} 'x'」2026-08-03", zh.period("{dates} 'x'", "2026-08-03", null, provisional = false))
    }
}
