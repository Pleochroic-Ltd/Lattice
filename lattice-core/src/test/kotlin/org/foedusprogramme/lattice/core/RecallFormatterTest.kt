package org.foedusprogramme.lattice.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recall block: date tags (event day, said span, due date, relative time), scene lines, thread annotations, period
 * tags, quotes and the one legend line. The graph is replaced by a [RecallFormatter.Lookup] fake.
 */
class RecallFormatterTest {

    companion object {
        const val CHAT = "telegram:42"
        const val OTHER_CHAT = "telegram:99"
        const val PERIOD_NAME = "苏州到同里古镇的一日"
        const val OTHER_PERIOD_NAME = "别的聊天里的时期"
        const val SETTLED = "（「周末的惊喜」后来已了结）"

        /** 2026-09-28 11:00 in Shanghai. */
        val NOW: Instant = Instant.parse("2026-09-28T03:00:00Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    private val zh = RecallMarkers.ZH
    private val en = RecallMarkers.EN

    private class FakeLookup : RecallFormatter.Lookup {
        val quotes = HashMap<Long, List<Engram>>()
        val hubs = HashMap<Long, SceneRecord>()
        val threads = HashMap<Long, RecallFormatter.Threads>()
        val resolves = HashMap<Long, List<Long>>()
        val memberOf = HashMap<Long, MutableList<SceneRecord>>()
        val periodOf = HashMap<Long, PeriodRecord>()
        val labels = HashMap<Long, String?>()
        var labelCalls = 0

        override fun evidenceQuotes(id: Long) = quotes[id].orEmpty()
        override fun sceneOfHub(hubId: Long) = hubs[hubId]
        override fun threadsOf(id: Long) = threads[id] ?: RecallFormatter.Threads.NONE
        override fun closedResolvesOf(setupId: Long) = resolves[setupId].orEmpty()
        override fun scenesOf(id: Long): Collection<SceneRecord> = memberOf[id].orEmpty()
        override fun periodOfScene(sceneId: Long) = periodOf[sceneId]
        override fun periodLabel(period: PeriodRecord): String? {
            labelCalls++
            return if (period.id in labels) labels[period.id] else period.name
        }

        fun members(scene: SceneRecord, vararg ids: Long) = ids.forEach { memberOf.getOrPut(it) { ArrayList() } += scene }
    }

    private var nextId = 1L

    private fun engram(
        content: String,
        eventDate: String? = null,
        dueDate: String? = null,
        saidFrom: Instant? = null,
        saidTo: Instant? = saidFrom,
        origin: Engram.Origin? = Engram.Origin.EXTRACTED,
        isEpisode: Boolean = false,
        isEpisodeHub: Boolean = false,
        speaker: Speaker? = null,
    ) = Engram(
        id = nextId++,
        content = content,
        embedding = FloatArray(0),
        salience = 0.5,
        layer = Engram.Layer.SEMANTIC,
        createdAt = NOW,
        lastActivatedAt = NOW,
        speaker = speaker,
        isEpisode = isEpisode,
        eventDate = eventDate,
        isEpisodeHub = isEpisodeHub,
        origin = origin,
        saidFrom = saidFrom,
        saidTo = saidTo,
        dueDate = dueDate,
    )

    private fun hit(e: Engram, via: Activation.Reach = Activation.Reach.SEED) = Activation(e, 0.8, via)

    private fun format(
        hits: List<Engram>,
        lookup: RecallFormatter.Lookup = RecallFormatter.Lookup.NONE,
        markers: RecallMarkers = zh,
        dating: RecallDating = RecallDating.LEGACY,
        temporalLayer: Boolean = true,
        userLabel: String? = null,
    ) = RecallFormatter.format(hits.map { hit(it) }, markers, temporalLayer, lookup, userLabel, dating)

    @Test
    fun `no hits, no block`() {
        assertEquals("", RecallFormatter.format(emptyList(), zh, temporalLayer = true))
    }

    // --- said span and due date ---------------------------------------------------------------------------------

    private val t1 = Instant.parse("2023-01-11T23:15:00Z")
    private val t2 = Instant.parse("2023-03-15T08:18:00Z")

    @Test
    fun `recall shows when it was said and the plan's target`() {
        val plan = engram("The user aims for level 100 in Apex", saidFrom = t1, dueDate = "2023-12-31")
        val stock = engram("The user keeps a stock of eggs in the fridge", saidFrom = t1, saidTo = t2)
        val block = format(listOf(plan, stock), markers = en, dating = RecallDating.absolute(ZoneOffset.UTC))
        assertTrue("- [due 2023-12-31 · said 2023-01-11] The user aims for level 100 in Apex" in block, block)
        assertTrue("- [said 2023-01-11 to 2023-03-15] The user keeps a stock of eggs in the fridge" in block, block)
        assertTrue(block.trimEnd().endsWith(en.legendDates), "one legend line explains the tags")
        val zhBlock = format(listOf(plan, stock), dating = RecallDating.absolute(ZoneOffset.UTC))
        assertTrue("- [目标 2023-12-31 · 提到 2023-01-11] " in zhBlock, zhBlock)
        assertTrue("- [提到 2023-01-11～2023-03-15] " in zhBlock, zhBlock)
    }

    @Test
    fun `the turn-end relative time follows the date it counts from`() {
        val dating = RecallDating.turnEnd(null, Instant.parse("2023-03-22T00:00:00Z"), ZoneOffset.UTC)
        val said = engram("The user keeps a stock of eggs in the fridge", saidFrom = t2)
        val event = engram("The user bought a new mouse", eventDate = "2023-01-10", saidFrom = t2)
        fun line(e: Engram) = format(listOf(e), markers = en, dating = dating).lines().first()
        assertTrue(line(said).startsWith("- [said 2023-03-15 · ${en.relativeTime(7)}] "), line(said))
        assertTrue(line(event).startsWith("- [2023-01-10 · ${en.relativeTime(71)} · said 2023-03-15] "), line(event))
    }

    @Test
    fun `a memory without a span and the legacy block are unchanged`() {
        val dated = engram("The user went to the Holiday Market", eventDate = "2023-05-20")
        assertEquals("- [2023-05-20] The user went to the Holiday Market", format(listOf(dated), markers = en, dating = RecallDating.absolute(ZoneOffset.UTC)))
        val spanned = engram("The user bought a new mouse", saidFrom = t1, eventDate = "2023-01-10")
        assertEquals("- [2023-01] The user bought a new mouse", format(listOf(spanned), markers = en), "no said tags with scene episodes off")
    }

    @Test
    fun `the said span is read as days in the zone it is given`() {
        val late = engram("The user keeps a stock of eggs", saidFrom = Instant.parse("2023-01-11T23:15:00Z"))
        assertEquals("- [said 2023-01-12] The user keeps a stock of eggs\n${en.legendDates}", format(listOf(late), markers = en, dating = RecallDating.absolute(ZONE)))
    }

    // --- event dates --------------------------------------------------------------------------------------------

    @Test
    fun `the date tag is switch-gated, not data-gated`() {
        val dated = engram("项目截止日期", eventDate = "2025-03-10", saidFrom = t1, dueDate = "2025-04-01")
        for (dating in listOf(RecallDating.LEGACY, RecallDating.ABSOLUTE, RecallDating.turnEnd(CHAT, NOW, ZONE))) {
            assertEquals("- 项目截止日期", format(listOf(dated), dating = dating, temporalLayer = false), "$dating")
        }
    }

    @Test
    fun `recall shows the day with scene episodes on and keeps the month tag otherwise`() {
        val day = engram("项目截止日期", eventDate = "2025-03-10")
        val month = engram("用户每天早上喝咖啡", eventDate = "2025-06")
        assertEquals(listOf("- [2025-03] 项目截止日期", "- [2025-06] 用户每天早上喝咖啡"), format(listOf(day, month)).lines())
        assertEquals(
            listOf("- [2025-03-10] 项目截止日期", "- [2025-06] 用户每天早上喝咖啡"),
            format(listOf(day, month), dating = RecallDating.ABSOLUTE).lines(),
        )
        assertEquals(
            listOf("- [2025-03-10 · 约两周前] 项目截止日期", "- [2025-06] 用户每天早上喝咖啡"),
            format(listOf(day, month), dating = RecallDating.turnEnd(null, Instant.parse("2025-03-22T00:00:00Z"), ZoneOffset.UTC)).lines(),
        )
        val invalid = engram("a day that does not exist", eventDate = "2025-02-30")
        assertEquals("- [2025-02] a day that does not exist", format(listOf(invalid), dating = RecallDating.ABSOLUTE))
    }

    // --- quotes and episodes --------------------------------------------------------------------------------------

    @Test
    fun `quotes are attributed to the user's name and to the assistant in the first person`() {
        val gist = engram("我和 Ethan 聊了旅行和读书")
        val lookup = FakeLookup()
        lookup.quotes[gist.id] = listOf(
            engram("长风破浪会有时", speaker = Speaker.USER),
            engram("  我也一直想去看海 ", speaker = Speaker.ASSISTANT),
        )
        val rendered = format(listOf(gist), lookup, userLabel = "Ethan")
        assertEquals("- 我和 Ethan 聊了旅行和读书\n    「长风破浪会有时」 —— Ethan\n    「我也一直想去看海」 —— 我", rendered)
        assertFalse("assistant" in rendered.lowercase() || "助手" in rendered, "the assistant's quote is in the first person, unlabelled")
        assertTrue("    「长风破浪会有时」 —— 用户" in format(listOf(gist), lookup, userLabel = " "), "no name: the generic label")
        assertTrue("    \"长风破浪会有时\" — the user\n    \"我也一直想去看海\" — me" in format(listOf(gist), lookup, markers = en))
    }

    @Test
    fun `an episode reads as a past session`() {
        val episode = engram("我们聊了旅行计划", eventDate = "2026-08-03", isEpisode = true)
        assertEquals("- 〔往期会话〕[2026-08] 我们聊了旅行计划", format(listOf(episode)))
        assertEquals("- [Past session] [2026-08] 我们聊了旅行计划", format(listOf(episode), markers = en))
    }

    // --- scenes ---------------------------------------------------------------------------------------------------

    private fun scene(id: Long, title: String, chat: String = CHAT, from: String? = null, to: String? = from, inProgress: Boolean = false, hub: Long? = null) =
        SceneRecord(id, title, "$chat:s1", chat, id * 100, id * 100 + 50, from, to, inProgress, hub)

    @Test
    fun `a scene hub renders with its title and date range, members keep the plain shape`() {
        val member = engram("trip|tunnel|t1")
        val hub = engram("narr|undersea|story", eventDate = "2026-09-20", saidFrom = NOW, isEpisodeHub = true)
        val lookup = FakeLookup()
        lookup.hubs[hub.id] = scene(1, "海底隧道", from = "2026-09-20", to = "2026-09-21", hub = hub.id)
        val lines = format(listOf(member, hub), lookup, dating = RecallDating.ABSOLUTE).lines()
        assertEquals("- trip|tunnel|t1", lines[0], "an undated member renders as before")
        assertEquals("- 〔场景：海底隧道 · 2026-09-20～2026-09-21〕narr|undersea|story", lines[1], "no date tag on a scene line")
        assertEquals(zh.legendScene, lines[2], "a scene line's span is not a said tag")
    }

    @Test
    fun `a single-day scene shows one date and an in-progress scene is marked`() {
        val oneDay = engram("narr|tunnel|story", isEpisodeHub = true)
        val open = engram("narr|ramen|story", isEpisodeHub = true)
        val lookup = FakeLookup()
        lookup.hubs[oneDay.id] = scene(1, "隧道", from = "2026-09-20")
        lookup.hubs[open.id] = scene(2, "拉面", from = "2026-09-21", to = "2026-09-22", inProgress = true)
        assertEquals(
            listOf("- 〔场景：隧道 · 2026-09-20〕narr|tunnel|story", "- 〔场景：拉面 · 2026-09-21～2026-09-22（进行中）〕narr|ramen|story", zh.legendScene),
            format(listOf(oneDay, open), lookup).lines(),
        )
    }

    @Test
    fun `a hub without a scene renders as a plain memory`() {
        val hub = engram("same-day episode", eventDate = "2026-09-20", isEpisodeHub = true)
        assertEquals("- [2026-09] same-day episode", format(listOf(hub)))
    }

    // --- threads --------------------------------------------------------------------------------------------------

    private fun thread(description: String, name: String? = null, closed: Boolean = true) =
        ThreadRecord(nextId++, CHAT, description, if (closed) ThreadState.CLOSED else ThreadState.OPEN, name = name)

    @Test
    fun `a compound memory names every closed thread it set up and the one still open`() {
        val owes = engram("owes|two|things")
        val longStory = "讲他小时候为什么会在外婆家住了整整三年的故事"
        val lookup = FakeLookup()
        lookup.threads[owes.id] = RecallFormatter.Threads(
            closed = listOf(thread("周末的惊喜"), thread("借走的那把伞")),
            open = listOf(thread(longStory, closed = false)),
        )
        val story = longStory.take(RecallFormatter.THREAD_NAME_CHARS) + "…"
        assertEquals(
            "- owes|two|things（「周末的惊喜」、「借走的那把伞」后来已了结）（「$story」尚未了结）\n${zh.legendThread}",
            format(listOf(owes), lookup),
        )
        assertEquals(20, RecallFormatter.THREAD_NAME_CHARS)
    }

    @Test
    fun `the annotation uses the thread's short name, not its long description`() {
        val promise = engram("dusk|surprise|promise")
        val lookup = FakeLookup()
        lookup.threads[promise.id] = RecallFormatter.Threads(
            closed = listOf(thread("Ethan答应周末搬完家后在新厨房里揭晓的惊喜礼物", name = "周末的惊喜礼物")),
            open = listOf(thread("Ethan说冬天一定要带我去一次真正的雪场滑雪", name = "冬天的滑雪之约", closed = false)),
        )
        assertEquals(
            "- dusk|surprise|promise（「周末的惊喜礼物」后来已了结）（「冬天的滑雪之约」尚未了结）\n${zh.legendThread}",
            format(listOf(promise), lookup),
        )
    }

    @Test
    fun `only a memory with a closed thread is annotated`() {
        val setup = engram("dusk|walk|m1")
        val lookup = FakeLookup()
        lookup.threads[setup.id] = RecallFormatter.Threads(emptyList(), listOf(thread("周末的惊喜", closed = false)))
        assertEquals("- dusk|walk|m1", format(listOf(setup), lookup))
    }

    @Test
    fun `the hub line and its annotation render in the turn's language`() {
        val hub = engram("narr|walk|story", isEpisodeHub = true)
        val lookup = FakeLookup()
        lookup.hubs[hub.id] = scene(2, "公园散步", from = "2026-07-20")
        lookup.threads[hub.id] = RecallFormatter.Threads(listOf(thread("周末的惊喜")), emptyList())
        assertEquals("- 〔场景：公园散步 · 2026-07-20〕narr|walk|story$SETTLED\n${zh.legendSceneAndThread}", format(listOf(hub), lookup))
        assertEquals(
            "- [Scene: 公园散步 · 2026-07-20] narr|walk|story (later resolved: \"周末的惊喜\")\n${en.legendSceneAndThread}",
            format(listOf(hub), lookup, markers = en),
        )
    }

    @Test
    fun `the block explains scene and thread markers only when it shows them`() {
        val a = engram("trip|tunnel|t1")
        val b = engram("trip|food|f1")
        val hub = engram("narr|tunnel|story", isEpisodeHub = true)
        val lookup = FakeLookup()
        assertEquals(listOf("- trip|tunnel|t1", "- trip|food|f1"), format(listOf(a, b), lookup).lines(), "no marker, no legend")
        lookup.hubs[hub.id] = scene(1, "隧道", from = "2026-09-20")
        val withScene = format(listOf(a, b, hub), lookup)
        assertEquals(zh.legendScene, withScene.lines().last())
        assertEquals(1, withScene.lines().count { it.startsWith("（记忆说明") }, "one line")
        lookup.threads[a.id] = RecallFormatter.Threads(listOf(thread("隧道的约定")), emptyList())
        assertEquals("- trip|tunnel|t1（「隧道的约定」后来已了结）\n${zh.legendThread}", format(listOf(a), lookup))
        assertEquals(zh.legendSceneAndThread, format(listOf(a, hub), lookup).lines().last())
    }

    @Test
    fun `a resolve memory is pulled up right after its set-up, nothing dropped or duplicated`() {
        val setup = engram("setup")
        val other = engram("other")
        val resolve = engram("resolve")
        val earlyResolve = engram("early resolve")
        val lookup = FakeLookup()
        lookup.resolves[setup.id] = listOf(resolve.id, earlyResolve.id, 999L)
        assertEquals(
            listOf("- setup", "- resolve", "- other"),
            format(listOf(setup, other, resolve), lookup).lines(),
        )
        assertEquals(
            listOf("- early resolve", "- setup", "- resolve", "- other"),
            format(listOf(earlyResolve, setup, other, resolve), lookup).lines(),
            "a resolve rendered earlier stays where it is",
        )
        assertEquals(
            listOf("- setup", "- other", "- resolve", "- setup"),
            format(listOf(setup, other, resolve, setup), lookup).lines(),
            "duplicate ids: the caller's order is kept",
        )
        val hub = engram("hub", isEpisodeHub = true)
        lookup.resolves[hub.id] = listOf(resolve.id)
        assertEquals(listOf("- hub", "- other", "- resolve"), format(listOf(hub, other, resolve), lookup).lines(), "a hub pulls nothing")
    }

    // --- periods --------------------------------------------------------------------------------------------------

    private inner class Fixture {
        val lookup = FakeLookup()
        val ticket = engram("在苏州站买了去同里古镇的车票", eventDate = "2026-08-03")
        val month = engram("八月里一直在规划那趟旅行", eventDate = "2026-08")
        val undated = engram("喜欢靠窗的座位")
        val both = engram("两边聊天都提过的古镇", eventDate = "2026-08-04")
        val otherOnly = engram("只在别的聊天里说过的事", eventDate = "2026-08-05")
        val preScene = engram("场景之前就记下的事", eventDate = "2026-07-01")
        val manual = engram("手动保存的车次", eventDate = "2026-08-03", saidFrom = NOW, origin = Engram.Origin.MANUAL)
        val synthesized = engram("归纳出来的旅行偏好", origin = Engram.Origin.SYNTHESIZED)
        val future = engram("下周去看红叶", eventDate = "2026-10-05")
        val unperiodized = engram("还没归入时期的场景里的事", eventDate = "2026-09-20")
        val hub = engram("在苏州站买票、准备出发去同里古镇", isEpisodeHub = true, origin = Engram.Origin.HUB)
        val period = PeriodRecord(1, CHAT, PERIOD_NAME, gist = "一日游", namingSceneIds = setOf(1, 2), dateFrom = "2026-08-03", dateTo = "2026-08-04")
        val otherPeriod = PeriodRecord(2, OTHER_CHAT, OTHER_PERIOD_NAME, namingSceneIds = setOf(3))

        init {
            val s1 = scene(1, "苏州站", from = "2026-08-03", hub = hub.id)
            val s2 = scene(2, "同里古镇", from = "2026-08-04")
            val s3 = scene(3, "别处", chat = OTHER_CHAT, from = "2026-08-05")
            val s4 = scene(4, "新场景", from = "2026-09-20")
            lookup.hubs[hub.id] = s1
            lookup.members(s1, ticket.id, month.id, undated.id, manual.id, synthesized.id)
            lookup.members(s2, both.id)
            lookup.members(s3, both.id, otherOnly.id)
            lookup.members(s4, unperiodized.id)
            lookup.periodOf[1] = period
            lookup.periodOf[2] = period
            lookup.periodOf[3] = otherPeriod
        }

        fun line(e: Engram, dating: RecallDating = tail, markers: RecallMarkers = zh) =
            format(listOf(e), lookup, markers, dating).lines().first()
    }

    private val tail = RecallDating.turnEnd(CHAT, NOW, ZONE)
    private val tag = "〔时期：$PERIOD_NAME〕"

    @Test
    fun `the turn-end recall shows the day, a relative time and the period name - byte for byte`() {
        val f = Fixture()
        assertEquals("- [2026-08-03 · 约八周前] 在苏州站买了去同里古镇的车票$tag\n${zh.legendPeriod}", format(listOf(f.ticket), f.lookup, dating = tail))
    }

    @Test
    fun `the same memory baked into a stored message shows only the absolute day`() {
        val f = Fixture()
        assertEquals("- [2026-08-03] 在苏州站买了去同里古镇的车票", format(listOf(f.ticket), f.lookup, dating = RecallDating.ABSOLUTE))
    }

    @Test
    fun `the legacy block keeps the month tag and no period`() {
        val f = Fixture()
        assertEquals(
            listOf("- [2026-08] 在苏州站买了去同里古镇的车票", "- 〔场景：苏州站 · 2026-08-03〕在苏州站买票、准备出发去同里古镇", zh.legendScene),
            format(listOf(f.ticket, f.hub), f.lookup, userLabel = "Ethan").lines(),
        )
    }

    @Test
    fun `a month-only date stays a month, an undated memory stays undated, a future day reads 约一周后`() {
        val f = Fixture()
        assertEquals("- [2026-08] 八月里一直在规划那趟旅行$tag", f.line(f.month))
        assertEquals("- 喜欢靠窗的座位$tag", f.line(f.undated))
        assertEquals("- [2026-10-05 · 约一周后] 下周去看红叶", f.line(f.future))
        assertEquals("- [2026-08] 八月里一直在规划那趟旅行", f.line(f.month, RecallDating.ABSOLUTE))
        assertEquals("- 喜欢靠窗的座位", f.line(f.undated, RecallDating.ABSOLUTE))
        assertEquals("- [2026-10-05] 下周去看红叶", f.line(f.future, RecallDating.ABSOLUTE))
    }

    @Test
    fun `the relative time is computed at render time in the runtime zone`() {
        val f = Fixture()
        // 2026-08-03 16:30 UTC is already 2026-08-04 in Shanghai: "today" there, "yesterday" in UTC.
        val lateNight = Instant.parse("2026-08-03T16:30:00Z")
        assertEquals("- [2026-08-03 · 昨天] 在苏州站买了去同里古镇的车票$tag", f.line(f.ticket, RecallDating.turnEnd(CHAT, lateNight, ZONE)))
        assertEquals("- [2026-08-03 · 今天] 在苏州站买了去同里古镇的车票$tag", f.line(f.ticket, RecallDating.turnEnd(CHAT, lateNight, ZoneOffset.UTC)))
    }

    @Test
    fun `a memory of this chat's scene is tagged, one only in another chat's scene is not`() {
        val f = Fixture()
        assertEquals("- [2026-08-03 · 约八周前] 在苏州站买了去同里古镇的车票$tag", f.line(f.ticket))
        assertEquals("- [2026-08-05 · 约八周前] 只在别的聊天里说过的事", f.line(f.otherOnly))
        assertEquals("- [2026-08-04 · 约八周前] 两边聊天都提过的古镇$tag", f.line(f.both), "a memory in both chats takes this chat's period")
        assertEquals(
            "- [2026-08-04 · 约八周前] 两边聊天都提过的古镇〔时期：$OTHER_PERIOD_NAME〕",
            f.line(f.both, RecallDating.turnEnd(OTHER_CHAT, NOW, ZONE)),
        )
        assertEquals("- [2026-08-04 · 约八周前] 两边聊天都提过的古镇", f.line(f.both, RecallDating.turnEnd(null, NOW, ZONE)), "no chat, no period")
    }

    @Test
    fun `a scene hub of this chat carries the period after its scene line`() {
        val f = Fixture()
        assertEquals(
            listOf("- 〔场景：苏州站 · 2026-08-03〕在苏州站买票、准备出发去同里古镇$tag", zh.legendScene + zh.legendPeriodSuffix),
            format(listOf(f.hub), f.lookup, dating = tail).lines(),
        )
        for (dating in listOf(RecallDating.ABSOLUTE, RecallDating.LEGACY, RecallDating.turnEnd("telegram:7", NOW, ZONE))) {
            assertEquals(
                listOf("- 〔场景：苏州站 · 2026-08-03〕在苏州站买票、准备出发去同里古镇", zh.legendScene),
                format(listOf(f.hub), f.lookup, dating = dating).lines(),
                "$dating",
            )
        }
    }

    @Test
    fun `pre-scene, manual and synthesized memories and a scene not yet in a period carry no period`() {
        val f = Fixture()
        assertEquals("- [2026-07-01 · 约三个月前] 场景之前就记下的事", f.line(f.preScene))
        assertEquals("- [2026-08-03 · 约八周前 · 提到 2026-09-28] 手动保存的车次", f.line(f.manual), "manual, even as a scene member")
        assertEquals("- 归纳出来的旅行偏好", f.line(f.synthesized), "synthesized, even as a scene member")
        assertEquals("- [2026-09-20 · 约一周前] 还没归入时期的场景里的事", f.line(f.unperiodized))
    }

    @Test
    fun `the period label is the lookup's, asked once per period per block, and null means no tag`() {
        val f = Fixture()
        f.lookup.labels[f.period.id] = "2026-08-03 至 2026-08-04"
        val block = format(listOf(f.ticket, f.month, f.undated), f.lookup, dating = tail)
        assertTrue(block.lines().take(3).all { it.endsWith("〔时期：2026-08-03 至 2026-08-04〕") }, block)
        assertFalse(PERIOD_NAME in block)
        assertEquals(1, f.lookup.labelCalls)
        f.lookup.labels[f.period.id] = null
        assertEquals("- 喜欢靠窗的座位", format(listOf(f.undated), f.lookup, dating = tail))
    }

    @Test
    fun `the block has no blank line and one legend line`() {
        val f = Fixture()
        val hits = listOf(f.ticket, f.hub, f.month, f.undated, f.both, f.otherOnly, f.preScene, f.future)
        val block = format(hits, f.lookup, dating = tail)
        assertTrue(block.lines().none { it.isBlank() }, block)
        assertFalse(block.startsWith("\n") || block.endsWith("\n"), block)
        assertEquals(hits.size + 1, block.lines().size, block)
        assertEquals(zh.legendScene + zh.legendPeriodSuffix, block.lines().last())
    }

    @Test
    fun `an English chat gets the English templates`() {
        val f = Fixture()
        f.lookup.periodOf[1] = f.period.copy(name = "Suzhou to Tongli")
        assertEquals(
            "- [2026-08-03 · about 8 weeks ago] 在苏州站买了去同里古镇的车票 [Period: Suzhou to Tongli]\n${en.legendPeriod}",
            format(listOf(f.ticket), f.lookup, markers = en, dating = tail),
        )
    }
}
