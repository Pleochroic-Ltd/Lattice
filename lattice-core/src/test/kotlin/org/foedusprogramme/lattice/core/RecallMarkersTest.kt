package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model-facing recall markers come from [MemoryTexts], per turn language. zh must stay BYTE-IDENTICAL to the
 * snapshots below; en is its own set.
 */
class RecallMarkersTest {

    private val zh = RecallMarkers.of(BundledTexts, Language.ZH)
    private val en = RecallMarkers.of(BundledTexts, Language.EN)

    // --- zh: byte-identical snapshots ---------------------------------------------------------------------------

    @Test
    fun `zh scene line is byte-identical`() {
        assertEquals("〔场景：隧道 · 2026-09-20～2026-09-23〕", zh.sceneTag("隧道", "2026-09-20", "2026-09-23", inProgress = false))
        assertEquals("〔场景：公园散步 · 2026-07-20〕", zh.sceneTag("公园散步", "2026-07-20", "2026-07-20", inProgress = false))
        assertEquals("〔场景 · 2026-07-20〕", zh.sceneTag("  ", "2026-07-20", null, inProgress = false))
        assertEquals("〔场景：t · 2026-07-21〕", zh.sceneTag(" t ", null, "2026-07-21", inProgress = false))
        assertEquals("〔场景：t〕", zh.sceneTag("t", null, null, inProgress = false))
    }

    @Test
    fun `zh in-progress scene is byte-identical`() {
        assertEquals("〔场景：公园散步 · 2026-07-20（进行中）〕", zh.sceneTag("公园散步", "2026-07-20", "2026-07-20", inProgress = true))
        assertEquals("〔场景 · 2026-07-20～2026-07-22（进行中）〕", zh.sceneTag("", "2026-07-20", "2026-07-22", inProgress = true))
    }

    @Test
    fun `zh settled and open thread annotations are byte-identical`() {
        assertEquals("（「周末的惊喜」后来已了结）", zh.threadNote(listOf("周末的惊喜"), emptyList()))
        assertEquals(
            "（「A」、「B」后来已了结）（「C」尚未了结）",
            zh.threadNote(listOf("A", "B"), listOf("C")),
        )
        assertEquals("（「A」、「B」、「C」等后来已了结）", zh.threadNote(listOf("A", "B", "C", "D"), emptyList()))
        assertEquals("（「A」后来已了结）", zh.threadNote(listOf("A", " A ", "A"), emptyList()), "names are distinct after normalizing")
        val long = "讲他小时候为什么会在外婆家住了整整三年的故事"
        assertEquals("（「${long.take(20)}…」后来已了结）", zh.threadNote(listOf(long), emptyList()))
        assertEquals("（「a b」后来已了结）", zh.threadNote(listOf(" a \n b "), emptyList()))
        // Cut by code point: an emoji straddling the limit is kept whole, never split into a lone surrogate.
        assertEquals(
            "（「${"x".repeat(19)}😀…」后来已了结）",
            zh.threadNote(listOf("x".repeat(19) + "😀tail"), emptyList()),
        )
        assertEquals("", zh.threadNote(emptyList(), listOf("C")), "only a memory with a closed thread is annotated")
        assertEquals("后来已了结）", zh.settledMark)
    }

    @Test
    fun `zh legend is the Chinese translation`() {
        assertEquals("（记忆说明：〔场景：…〕 一行是记忆系统自己对过去某个场景的总结，不是对话里任何人说过的话。）", zh.legendScene)
        assertEquals(
            "（记忆说明：（「…」后来已了结） / （「…」尚未了结） 是系统对某个承诺或悬念后来是否已了结的标注，不是对话里任何人说过的话。）",
            zh.legendThread,
        )
        assertEquals(
            "（记忆说明：〔场景：…〕 一行是记忆系统自己对过去某个场景的总结；（「…」后来已了结） / （「…」尚未了结） " +
                "是系统对某个承诺或悬念后来是否已了结的标注——两者都不是对话里任何人说过的话。）",
            zh.legendSceneAndThread,
        )
        assertEquals(zh.legendScene, zh.legend(scene = true, thread = false))
        assertEquals(zh.legendThread, zh.legend(scene = false, thread = true))
        assertEquals(zh.legendSceneAndThread, zh.legend(scene = true, thread = true))
        assertNull(zh.legend(scene = false, thread = false))
    }

    @Test
    fun `zh period tag is byte-identical`() {
        assertEquals("〔时期：苏州到同里古镇的一日〕", zh.periodTag("苏州到同里古镇的一日"))
        assertEquals("〔时期：2026-07-23 至 2026-07-29〕", zh.periodTag("2026-07-23 至 2026-07-29"))
    }

    @Test
    fun `zh relative time follows how people say it - Chinese numerals, 两 for two, 约 from a week on`() {
        val cases = listOf(
            0L to "今天", 1L to "昨天", 2L to "两天前", 6L to "六天前",
            7L to "约一周前", 10L to "约一周前", 11L to "约两周前", 20L to "约三周前",
            56L to "约八周前", // 2026-08-03 seen on 2026-09-28
            59L to "约八周前", 60L to "约两个月前", 76L to "约两个月前", 77L to "约三个月前", 335L to "约十一个月前",
            350L to "约十一个月前", 352L to "约一年前", 365L to "约一年前", 547L to "约一年前", 548L to "约两年前", 4383L to "约十二年前",
            8036L to "约二十二年前", 36525L to "约100年前",
            -1L to "明天", -2L to "两天后", -3L to "三天后", -7L to "约一周后", -20L to "约三周后",
            -90L to "约三个月后", -400L to "约一年后", -800L to "约两年后",
        )
        for ((days, phrase) in cases) assertEquals(phrase, zh.relativeTime(days), "$days days ago")
    }

    @Test
    fun `zh period legend is byte-identical and joins the scene and thread legend on one line`() {
        assertEquals(
            "（记忆说明：〔时期：…〕 是记忆系统给这条记忆所在的那段时期起的名字（或只写那段时期的日期），不是对话里任何人说过的话。）",
            zh.legendPeriod,
        )
        assertEquals("（〔时期：…〕 是记忆系统给记忆所在的那段时期起的名字（或只写日期），同样不是对话里任何人说过的话。）", zh.legendPeriodSuffix)
        assertEquals(zh.legendPeriod, zh.legend(scene = false, thread = false, period = true))
        assertEquals(zh.legendScene + zh.legendPeriodSuffix, zh.legend(scene = true, thread = false, period = true))
        assertEquals(zh.legendThread + zh.legendPeriodSuffix, zh.legend(scene = false, thread = true, period = true))
        assertEquals(zh.legendSceneAndThread + zh.legendPeriodSuffix, zh.legend(scene = true, thread = true, period = true))
        assertEquals(zh.legendScene, zh.legend(scene = true, thread = false, period = false), "no period tag: the legend is unchanged")
        assertNull(zh.legend(scene = false, thread = false, period = false))
        for (l in listOf(zh.legendPeriod, zh.legendPeriodSuffix)) assertTrue('\n' !in l, "one line: $l")
    }

    @Test
    fun `said and due tags and the dates legend`() {
        assertEquals("提到 2023-01-11", zh.said("2023-01-11", "2023-01-11"))
        assertEquals("提到 2023-01-11～2023-03-15", zh.said("2023-01-11", "2023-03-15"))
        assertEquals("目标 2023-12-31", zh.due("2023-12-31"))
        assertEquals("said 2023-01-11 to 2023-03-15", en.said("2023-01-11", "2023-03-15"))
        assertEquals("due 2023-12", en.due("2023-12"))
        assertEquals(en.legendDates, en.legend(scene = false, thread = false, period = false, dates = true))
        assertEquals(en.legendScene + en.legendDatesSuffix, en.legend(scene = true, thread = false, period = false, dates = true))
        assertEquals(
            en.legendScene + en.legendPeriodSuffix + en.legendDatesSuffix,
            en.legend(scene = true, thread = false, period = true, dates = true),
        )
    }

    @Test
    fun `quotes, the past-session lead and the default labels`() {
        assertEquals("「长风破浪会有时」 —— Ethan", zh.quote("长风破浪会有时", "Ethan"))
        assertEquals("「长风破浪会有时」", zh.quote("长风破浪会有时", null))
        assertEquals("我", zh.quoteSelf)
        assertEquals("用户", zh.quoteUser)
        assertEquals("〔往期会话〕", zh.episode)
        assertEquals("\"I want to hit level 100\" — Ethan", en.quote("I want to hit level 100", "Ethan"))
        assertEquals("\"words\"", en.quote("words", ""))
        assertEquals("me", en.quoteSelf)
        assertEquals("the user", en.quoteUser)
        assertEquals("[Past session] ", en.episode)
    }

    // --- en ---------------------------------------------------------------------------------------------------

    @Test
    fun `en period tag, relative time and legend`() {
        assertEquals(" [Period: Suzhou day trip]", en.periodTag("Suzhou day trip"))
        val cases = listOf(
            0L to "today", 1L to "yesterday", 2L to "2 days ago", 7L to "about a week ago", 56L to "about 8 weeks ago",
            60L to "about 2 months ago", 365L to "about a year ago", 730L to "about 2 years ago",
            -1L to "tomorrow", -3L to "in 3 days", -7L to "in about a week", -20L to "in about 3 weeks",
            -90L to "in about 3 months", -400L to "in about a year", -800L to "in about 2 years",
        )
        for ((days, phrase) in cases) assertEquals(phrase, en.relativeTime(days), "$days days ago")
        assertEquals(
            "(Memory note: [Period: …] names the stretch of time a memory belongs to — the memory system's own name for " +
                "it, or only its dates — not something the user said.)",
            en.legendPeriod,
        )
        assertEquals(
            " ([Period: …] is the memory system's own name for the stretch of time a memory belongs to, or only its " +
                "dates — not something the user said either.)",
            en.legendPeriodSuffix,
        )
        assertEquals(en.legendScene + en.legendPeriodSuffix, en.legend(scene = true, thread = false, period = true))
        assertEquals(en.legendPeriod, en.legend(scene = false, thread = false, period = true))
    }

    @Test
    fun `en scene line`() {
        assertEquals("[Scene: Tunnel · 2026-09-20～2026-09-23] ", en.sceneTag("Tunnel", "2026-09-20", "2026-09-23", inProgress = false))
        assertEquals("[Scene · 2026-07-20] ", en.sceneTag("", "2026-07-20", "2026-07-20", inProgress = false))
        assertEquals("[Scene: Dusk walk · 2026-07-20 (in progress)] ", en.sceneTag("Dusk walk", "2026-07-20", null, inProgress = true))
    }

    @Test
    fun `en thread annotations`() {
        assertEquals(" (later resolved: \"A\")", en.threadNote(listOf("A"), emptyList()))
        assertEquals(" (later resolved: \"A\", \"B\") (still open: \"C\")", en.threadNote(listOf("A", "B"), listOf("C")))
        assertEquals(" (later resolved: \"A\", \"B\", \"C\", etc.)", en.threadNote(listOf("A", "B", "C", "D"), emptyList()))
        assertEquals(" (later resolved: \"${"x".repeat(20)}…\")", en.threadNote(listOf("x".repeat(25)), emptyList()))
        assertEquals("(later resolved: ", en.settledMark)
        assertTrue(en.threadNote(listOf("A"), listOf("B")).contains(en.settledMark))
    }

    @Test
    fun `en legend keeps the English wording and names the English markers`() {
        assertEquals(
            "(Memory note: a [Scene: …] line is the memory system's own summary of a past scene, not something the user said.)",
            en.legendScene,
        )
        assertEquals(
            "(Memory note: (later resolved: \"…\") / (still open: \"…\") are system notes on whether a promise or " +
                "suspense was later resolved, not something the user said.)",
            en.legendThread,
        )
        assertEquals(
            "(Memory note: a [Scene: …] line is the memory system's own summary of a past scene; (later resolved: \"…\") / " +
                "(still open: \"…\") are system notes on whether a promise or suspense was later resolved — neither is " +
                "something the user said.)",
            en.legendSceneAndThread,
        )
    }

    @Test
    fun `per-language constants and regional tags`() {
        assertEquals(Language.ZH, RecallMarkers.ZH.language)
        assertEquals(zh.sceneTag("t", "d", null, false), RecallMarkers.ZH.sceneTag("t", "d", null, false))
        assertEquals(en.legendScene, RecallMarkers.EN.legendScene)
        val zhCn = RecallMarkers.of(BundledTexts, Language("zh-CN"))
        assertEquals("约两周前", zhCn.relativeTime(14), "a regional Chinese tag counts in Chinese numerals too")
        assertEquals("about 2 weeks ago", RecallMarkers.of(BundledTexts, Language("de")).relativeTime(14), "unknown: English")
    }
}
