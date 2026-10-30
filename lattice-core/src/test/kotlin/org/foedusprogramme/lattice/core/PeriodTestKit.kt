package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.foedusprogramme.lattice.ConversationMessage.Role
import org.foedusprogramme.lattice.NamedSceneTrustPolicy
import org.foedusprogramme.lattice.SceneTrustPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/** The formation model: records every input (and the thread it ran on); answers with [reply]. */
class FormationModel(var reply: (String) -> String = { user -> periods(entry(1, newScenes(user), "一整段")) }) {
    val calls = CopyOnWriteArrayList<String>()
    val threads = CopyOnWriteArrayList<String>()

    @Volatile var before: () -> Unit = {}

    fun complete(user: String): String {
        calls += user
        threads += Thread.currentThread().name
        before()
        return reply(user)
    }

    companion object {
        /** How many new scenes the input lists (`[n] …` lines). */
        fun newScenes(user: String): Int = Regex("(?m)^\\[(\\d+)] ").findAll(user.substringAfter("## New scenes")).count()

        fun entry(first: Int, last: Int, name: String, continues: Boolean = false, gist: String = "$name 的日子。") =
            (if (continues) """{"continues_current": true, "last": $last""" else """{"first": $first, "last": $last""") +
                """, "name": "$name", "gist": "$gist"}"""

        fun periods(vararg entries: String) = """{"periods": [${entries.joinToString(", ")}]}"""

        /** One period over every new scene: [name] continued when a current period is shown, else a new one. */
        fun continuing(name: String): (String) -> String = { user ->
            periods(entry(1, newScenes(user), name, continues = "## Current period" in user))
        }
    }
}

/** The era model: records every input; answers with [reply] (which may throw). */
class EraModel(var reply: (String) -> String = { error("no era reply scripted") }) {
    val calls = CopyOnWriteArrayList<String>()

    fun complete(user: String): String {
        calls += user
        return reply(user)
    }

    companion object {
        fun reply(
            assign: List<String> = emptyList(),
            newest: Pair<String, String?>? = null,
            new: Pair<String, String?>? = null,
            merge: Pair<String, String?>? = null,
        ): String = buildJsonObject {
            if (assign.isNotEmpty()) put("assign", JsonArray(assign.map(::JsonPrimitive)))
            fun obj(key: String, v: Pair<String, String?>?) {
                if (v != null) put(key, buildJsonObject { put("name", v.first); v.second?.let { put("gist", it) } })
            }
            obj("newest", newest)
            obj("new", new)
            obj("merge", merge)
        }.toString()
    }
}

/**
 * A graph over [store], a fake host and the period layer's models, for the period tests. Every scene lives in its own
 * conversation of [CHAT] by default (positions restart in each, so only [SCENE_ORDER] can order them); its messages are
 * a line of the owner and a reply. [policy] trusts the owner, so a scene is untrusted only through an untrusted tool
 * result ([untrusted] / [taint]). [restart] reopens the graph over the same store.
 */
class PeriodRig(
    val store: LatticeStore = InMemoryLatticeStore(),
    val policy: SceneTrustPolicy = TRUSTING,
    val chat: String = CHAT,
) {
    val source = FakeConversationSource()
    val formation = FormationModel()
    val era = EraModel()
    val dead = CopyOnWriteArrayList<DeadLetterEntry>()
    val requeued = CopyOnWriteArrayList<String>()
    var post: PeriodPostFormation? = null
    var budget = PeriodCatalogue.BUDGET_TOKENS
    var densityValue = 1.0
    val densityAsked = CopyOnWriteArrayList<String>()

    @Volatile var graph: LatticeGraph = open()
        private set

    /** Always the current graph, serialized on a lock. */
    val access: GraphAccess = object : GraphAccess {
        override fun <T> withGraph(block: (LatticeGraph) -> T): T = synchronized(this) { block(graph) }
    }

    var publisher = PeriodPublisher(access)
        private set
    var trust = SceneTrust(access, source, policy)
        private set

    val model = MemoryModel { task, system, user ->
        assertEquals(MemoryTask.PERIODS, task)
        when (system) {
            PeriodFormer.SYSTEM_PROMPT -> formation.complete(user)
            PeriodCatalogue.SYSTEM_PROMPT -> era.complete(user)
            else -> error("an unexpected system prompt")
        }
    }

    private var day = LocalDate.parse("2026-07-22")
    private var conversations = 0

    private fun open() = LatticeGraph(
        LocalHashingEmbedder(), LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99), now = { NOW }, store = store,
    )

    /** A fresh graph, publisher and trust over the same store (a restart). */
    fun restart() {
        graph = open()
        publisher = PeriodPublisher(access)
        trust = SceneTrust(access, source, policy)
    }

    fun <T> g(block: (LatticeGraph) -> T): T = access.withGraph(block)

    fun catalogue(language: Language? = Language.ZH) = PeriodCatalogue(
        graph = access,
        publisher = publisher,
        model = model,
        density = { chat -> densityAsked += chat; densityValue },
        deadLetter = { dead += it },
        selfAliases = setOf("云舒"),
        userName = "Ethan",
        budgetTokens = budget,
        fallbackLanguage = language ?: Language.EN,
    )

    fun former(
        language: Language? = Language.ZH,
        inputCap: Int = PeriodFormer.FORMATION_MAX_SCENES,
        maxRounds: Int = PeriodFormer.MAX_ROUNDS_PER_TRIGGER,
        budgetMillis: Long = PeriodFormer.TRIGGER_BUDGET_MILLIS,
        now: () -> Long = System::currentTimeMillis,
        withCatalogue: Boolean = false,
        trust: SceneTrust = this.trust,
    ): PeriodFormer {
        val catalogue = if (withCatalogue) catalogue(language) else null
        return PeriodFormer(
            graph = access,
            source = source,
            publisher = publisher,
            trust = trust,
            model = model,
            language = { language },
            fallbackLanguage = language ?: Language.EN,
            deadLetter = { dead += it },
            postFormation = { post ?: catalogue ?: PeriodPostFormation.NoOp },
            selfAliases = setOf("云舒"),
            userName = "Ethan",
            requeue = { requeued += it },
            inputCap = inputCap,
            maxRoundsPerTrigger = maxRounds,
            triggerBudgetMillis = budgetMillis,
            nowMillis = now,
        )
    }

    fun at(date: LocalDate, hour: Int = 12): Instant = date.atTime(hour, 0).toInstant(ZoneOffset.UTC)

    /** Appends one message to [conversation] at [at]. */
    fun say(conversation: String, text: String, at: Instant, role: Role = Role.USER, toolName: String? = null, tainted: Boolean = false) =
        source.at(at).add(conversation, text, role, toolName = toolName, tainted = tainted)

    /**
     * A scene of [chat] in [state] with the local dates [date]..[to]: its messages ([rows], by default the owner's line
     * and a reply) in [conversation] (a new one by default), an untrusted tool result when [untrusted], and a hub
     * narration ([hub]) once materialized.
     */
    fun scene(
        title: String,
        date: LocalDate = day.also { day = day.plusDays(1) },
        to: LocalDate = date,
        /** Null: a scene the pipeline did not write (no work row). */
        state: SceneWorkState? = SceneWorkState.MATERIALIZED,
        untrusted: Boolean = false,
        hub: String? = "我和Ethan聊了$title。",
        conversation: String = "$chat:c${++conversations}",
        chatKey: String = chat,
        rows: (conversation: String) -> Unit = { c ->
            say(c, "我们聊聊$title", at(date))
            say(c, "好，说说$title。", at(to, 13), Role.ASSISTANT)
        },
    ): SceneRecord {
        source.open(conversation, chatKey)
        val before = source.all(conversation).size
        rows(conversation)
        if (untrusted) say(conversation, "<html>外部网页</html>", at(to, 14), Role.TOOL, toolName = "web.view")
        val messages = source.all(conversation).drop(before)
        return g { g ->
            val record = SceneRecord(
                sceneId = g.allocateSceneId(), title = title, conversationId = conversation, chatKey = chatKey,
                firstMessageId = messages.first().position, lastMessageId = messages.last().position,
                dateFrom = date.toString(), dateTo = to.toString(), inProgress = state == SceneWorkState.OPEN,
                startedAt = messages.first().at, seq = g.nextSceneSeq(chatKey),
            )
            g.putSceneState(record, state?.let { SceneWorkRecord(record.sceneId, it, "about $title") })
            if (hub != null && (state == null || state == SceneWorkState.MATERIALIZED)) {
                val member = g.remember("Ethan和我在「$title」里说过的话", layer = Engram.Layer.SEMANTIC)
                check(g.materializeScene(record, hub, listOf(member)) != LatticeGraph.REFUSED) { "hub refused: $hub" }
            }
            g.scene(record.sceneId)!!
        }
    }

    /** Later, [scene] turns out to hold external content: an untrusted tool result joins its range. */
    fun taint(scene: SceneRecord) {
        val current = g { it.scene(scene.sceneId)!! }
        val m = say(current.conversationId, "<html>外部网页</html>", at(LocalDate.parse(current.dateTo!!), 20), Role.TOOL, toolName = "web.view")
        g { it.putScene(it.scene(scene.sceneId)!!.copy(lastMessageId = m.position)) }
    }

    fun periods(chatKey: String = chat): List<Pair<PeriodRecord, List<Long>>> = g { g -> g.periods(chatKey).map { it to g.periodScenes(it.id) } }

    fun names(): List<String> = periods().map { it.first.name }

    fun eras(): List<Pair<EraRecord, List<String>>> = g { g -> g.eras(chat).map { e -> e to g.eraPeriods(e.id).map { it.name } } }

    fun failures(firstScene: Long) = g { it.periodFailureCount(chat, PeriodFailureKind.FORMATION, firstScene) }

    fun eraFailures(firstScene: Long) = g { it.periodFailureCount(chat, PeriodFailureKind.ERA, firstScene) }

    fun generation() = g { it.periodGeneration(chat) }

    fun published(language: Language = Language.ZH, chatKey: String = chat): String? = publisher.published(chatKey, language)

    /** Write periods directly (an earlier formation); the last one is provisional when [provisionalLast]. */
    fun seedPeriods(vararg groups: Pair<String, List<SceneRecord>>, provisionalLast: Boolean = true) {
        publisher.commit { g ->
            val records = groups.mapIndexed { i, (name, scenes) ->
                PeriodRecord(
                    g.allocatePeriodId(), chat, name,
                    state = if (provisionalLast && i == groups.lastIndex) PeriodState.PROVISIONAL else PeriodState.CLOSED,
                    namingSceneIds = scenes.map { it.sceneId }.toSet(),
                )
            }
            PeriodWrite(chat, periods = records, members = groups.flatMapIndexed { i, (_, scenes) -> scenes.map { PeriodMemberRecord(it.sceneId, records[i].id) } })
        }
    }

    data class SeedEra(val name: String, val gist: String? = "$name 那一阵。", val frozen: Boolean = true, val naming: Set<Long>? = null)

    data class SeedPeriod(val name: String, val scenes: List<SceneRecord>, val gist: String? = "$name 的日子。", val era: Int? = null, val naming: Set<Long>? = null)

    /** Write eras and periods directly (an earlier history); the last period is provisional. */
    fun seed(eras: List<SeedEra>, periods: List<SeedPeriod>) {
        publisher.commit { g ->
            val eraIds = eras.map { g.allocateEraId() }
            val periodIds = periods.map { g.allocatePeriodId() }
            PeriodWrite(
                chat,
                eras = eras.mapIndexed { i, e ->
                    EraRecord(
                        eraIds[i], chat, e.name, e.gist, frozen = e.frozen,
                        namingSceneIds = e.naming ?: periods.filter { it.era == i }.flatMap { p -> p.scenes.map { it.sceneId } }.toSet(),
                    )
                },
                periods = periods.mapIndexed { i, p ->
                    PeriodRecord(
                        periodIds[i], chat, p.name, p.gist,
                        state = if (i == periods.lastIndex) PeriodState.PROVISIONAL else PeriodState.CLOSED,
                        eraId = p.era?.let(eraIds::get),
                        namingSceneIds = p.naming ?: p.scenes.map { it.sceneId }.toSet(),
                    )
                },
                members = periods.flatMapIndexed { i, p -> p.scenes.map { PeriodMemberRecord(it.sceneId, periodIds[i]) } },
            )
        }
    }

    /** One formation round (with the catalogue) that makes each new scene a period of its own, named [names] in order. */
    fun formEach(vararg names: String): PeriodFormer.Outcome {
        formation.reply = { user ->
            val n = FormationModel.newScenes(user)
            check(n == names.size) { "expected ${names.size} new scenes, got $n" }
            FormationModel.periods(*names.mapIndexed { i, name -> FormationModel.entry(i + 1, i + 1, name) }.toTypedArray())
        }
        return former(withCatalogue = true).formChat(chat)
    }

    fun calibrate(): PeriodCatalogue.Calibration? = catalogue().calibrate(chat, trust.refresh(chat))

    /** A scene of only host text and the assistant (no person's message): excluded from periods. */
    fun announcementScene(date: LocalDate): SceneRecord = scene("闲聊提醒", date = date, rows = { c ->
        say(c, "[proactive] the user has been quiet for a while", at(date), Role.HOST)
        say(c, "在吗？", at(date, 13), Role.ASSISTANT)
    })

    companion object {
        const val CHAT = "telegram:1001"
        val NOW: Instant = Instant.parse("2026-09-28T00:00:00Z")

        /** Trusts the owner's messages; `web.view` results stay untrusted. */
        val TRUSTING: SceneTrustPolicy = NamedSceneTrustPolicy(trustedSenders = setOf(FakeConversationSource.OWNER))
    }
}
