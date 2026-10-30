package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * The period catalogue: what a chat's eras and recent periods look like as the archive every turn may be given, and the
 * era layer that keeps it short.
 *
 * As the formation's [PeriodPostFormation] ([extend], on the narration thread, before the round's write commits):
 *  1. ERAS. Every period outside an era that is no longer among the chat's last [RECENT_PERIODS] periods is placed in
 *     ONE model call ([MemoryTask.PERIODS]; a round that already made two skips it) into the newest era or into one new
 *     era that starts with it; only the newest era may be renamed (its old name becomes an alias), older eras are
 *     frozen. When the catalogue the round would publish is over [budgetTokens] — estimated BEFORE the call, with the
 *     waiting periods in a new era, as [estimateTokens] × the chat's density ([density]) — the oldest eras (never the
 *     newest) are merged into one, the fewest that bring it within the budget ([mergeCount]), under a name and a
 *     one-line gist the same call writes; their old names become aliases. A call that fails still merges, under a
 *     neutral date-range label. A failed placement counts on a persisted counter keyed (chat, ERA, first scene of the
 *     first waiting period); the periods keep their own lines meanwhile, and at [MAX_FAILURES] they join the newest era
 *     by time, keeping its name (a new neutrally labelled era when there is none). A call cut off by a shutdown or an
 *     interrupt changes and counts nothing.
 *     Input isolation: a period or era whose name its naming scenes no longer vouch for ([periodNameShown]) reaches the
 *     call as its dates and [UNTRUSTED_MARK] only. Each era records its NAMING scenes: the naming scenes of the periods
 *     and eras the call saw in full that it was named from.
 *  2. RENDERING ([render], every language of [languages] pre-rendered, [CatalogueMarkers]): the archive framing on the
 *     first line; the eras (name, dates, one line) oldest first; the periods outside an era (name, dates, gist; the
 *     newest may still be going on); one capped line of unresolved threads — never one an untrusted scene opened —
 *     computed only here, at a formation round; the coverage line (first period's first day, last period's last day,
 *     and how far the scenes that wait for a period reach; excluded scenes do not count). Absolute dates only, nothing
 *     numbered; a period or era whose name is not vouched for shows a neutral dated label; every model-written field is
 *     normalized ([PeriodFields]) and capped, so no field can open a line of its own.
 *  3. PUBLISHING: a language's snapshot joins the round's write only when its text differs from the published one (no
 *     period: none). The snapshot's `coverage` column records the render facts as JSON — the coverage line and the ids
 *     of the threads the line was computed from — so a startup re-render ([calibrate]) keeps the threads line as the
 *     last formation computed it. A scene trust that could not be read publishes nothing (the old text stays).
 */
class PeriodCatalogue(
    private val graph: GraphAccess,
    private val publisher: PeriodPublisher,
    private val model: MemoryModel,
    private val texts: MemoryTexts = BundledTexts,
    /** The languages every catalogue is rendered in; a turn reads the one it needs ([PeriodPublisher.published]). */
    private val languages: List<Language> = DEFAULT_LANGUAGES,
    /** The language of a neutral era label when the chat's is unknown. */
    private val fallbackLanguage: Language = Language.EN,
    /** How much denser than [estimateTokens] the chat's prompts tokenize (1.0 = the plain estimate). */
    private val density: (chatKey: String) -> Double = { 1.0 },
    private val deadLetter: MemoryDeadLetter = MemoryDeadLetter.NoOp,
    /** The assistant's names, shown in "Who is who" like the formation's. */
    private val selfAliases: Set<String> = emptySet(),
    /** The human counterpart's name. */
    private val userName: String? = null,
    private val budgetTokens: Int = BUDGET_TOKENS,
) : PeriodPostFormation {
    private val logger = LoggerFactory.getLogger(PeriodCatalogue::class.java)
    private val markerCache = ConcurrentHashMap<Language, CatalogueMarkers>()

    init {
        require(languages.isNotEmpty()) { "A period catalogue needs at least one language to render; pass Language.EN at least." }
    }

    private fun markers(language: Language): CatalogueMarkers = markerCache.getOrPut(language) { CatalogueMarkers.of(texts, language) }

    // --- the formation seam ----------------------------------------------------------------------------------------

    override fun extend(round: PeriodRound, write: PeriodWrite): PeriodWrite {
        val chatKey = round.chatKey
        if (round.trust.failed) {
            logger.warn("Period catalogue skipped chat={}: the scene trust could not be read (the published catalogue stays)", chatKey)
            return write
        }
        val trusted: (Long) -> Boolean = { round.trust.trusted(it) }
        val facts = graph.withGraph { g ->
            GraphFacts(
                threads = g.threads(chatKey),
                exclusions = g.periodExclusions(chatKey).mapTo(HashSet()) { it.sceneId },
                aliases = round.erasBefore.associate { e -> e.id to g.eraAliases(e.id).map { it.alias } },
            )
        }
        val work = EraWork(round, Layer(round.after(write), round.erasBefore.filter { it.id !in write.deleteEraIds } + write.eras), facts.aliases)
        work.dropEmptyEras()

        val scenes = round.scenes.mapValues { (_, v) -> SceneFacts(v.sceneId, v.order, v.dateFrom, v.dateTo) }
        val excluded = facts.exclusions + write.exclusions.map { it.sceneId }
        val threads = openThreads(facts.threads, work.layer, scenes, trusted)
        val coverage = coverageFacts(work.layer, scenes, excluded)
        val densityFactor = densityFactor(chatKey)

        placeAndMerge(round, work, threads, coverage, trusted, densityFactor)

        val source = CatalogueSource(chatKey, work.layer.periods.toList(), work.layer.orderedEras(), threads, coverage.pending, coverage.pendingTo, trusted)
        // A round followed by another of the same trigger publishes nothing: [publish] does, once the rounds end — one
        // prompt-cache miss per trigger instead of one per round.
        val catalogues = if (round.catalogueDeferred) emptyList() else snapshots(source, densityFactor, threads)
        return work.into(write, round.erasBefore, catalogues)
    }

    /**
     * After a trigger whose rounds deferred the catalogue: re-render it from the committed layer — the threads line
     * recomputed, as a formation round would — and publish the languages whose text changed. No model call; nothing
     * when [trust] could not be read. Call off the graph thread.
     */
    override fun publish(chatKey: String, trust: SceneTrust.ChatTrust) {
        val write = calibrate(chatKey, trust, recomputeThreads = true)?.write ?: return
        publisher.commit { write }
    }

    /**
     * Step 1 of [extend]: plan the merge from the projected estimate, then place the waiting periods and merge — in one
     * era call when one is needed and allowed, deterministically otherwise (see the class doc).
     */
    private fun placeAndMerge(
        round: PeriodRound,
        work: EraWork,
        threads: List<CatalogueThread>,
        coverage: Coverage,
        trusted: (Long) -> Boolean,
        densityFactor: Double,
    ) {
        val chatKey = round.chatKey
        val departing = work.layer.departing()
        val eras = work.layer.orderedEras()
        val mergeable = eras.dropLast(1) // the newest era never merges
        // The catalogue this round would publish with the n oldest eras merged (the waiting periods in a new era: the
        // longest outcome), in its longest language.
        val projected: (Int) -> Int = { n ->
            val p = work.layer.projection(departing, mergeable.take(n))
            val source = CatalogueSource(chatKey, p.periods, p.orderedEras(), threads, coverage.pending, coverage.pendingTo, trusted)
            languages.maxOf { lang -> estimate(render(source, markers(lang)).orEmpty(), densityFactor) }
        }
        val unmerged = projected(0)
        val g = mergeCount(mergeable.size, budgetTokens) { n -> if (n == 0) unmerged else projected(n) }
        val group = mergeable.take(g)
        when {
            unmerged <= budgetTokens -> Unit
            g == 0 -> logger.info("Period catalogue over budget chat={} (~{} of {} tokens) with no older era to merge", chatKey, unmerged, budgetTokens)
            projected(g) > budgetTokens -> logger.warn(
                "Period catalogue still over budget chat={} after merging all {} older eras (~{} of {} tokens)",
                chatKey, g, projected(g), budgetTokens,
            )
        }
        if (departing.isEmpty() && group.isEmpty()) return
        if (round.modelCalls >= MAX_MODEL_CALLS_PER_ROUND) {
            logger.info("Period era assignment deferred chat={}: the round already made {} model calls", chatKey, round.modelCalls)
            return
        }
        val key = departing.firstOrNull()?.sceneIds?.firstOrNull()
        val failuresBefore = key?.let { k -> graph.withGraph { it.periodFailureCount(chatKey, PeriodFailureKind.ERA, k) } } ?: 0
        if (key != null && failuresBefore >= MAX_FAILURES) {
            // A counter that already reached the cap (its deterministic write never landed): no call. The counter is
            // cleared only once the waiting periods are placed; a merge that finds no label leaves them unaffected.
            work.mergeNeutral(group)
            placedByTime(work, departing, key)
            return
        }
        val prompt = renderEraInput(round, work, departing, group, trusted)
        val reply = try {
            model.complete(MemoryTask.PERIODS, SYSTEM_PROMPT, prompt.text)
        } catch (e: Exception) {
            if (e.isMemoryModelShutdown() || e.isMemoryModelInterrupted()) {
                logger.info("Period era call cut off chat={} (not counted)", chatKey)
                return
            }
            eraFailed(round, work, departing, group, key, failuresBefore, "model unavailable: ${e.message}")
            return
        }
        val parsed = parseEraReply(reply)
        val problem = when (parsed) {
            is EraParsed.Unparseable -> parsed.reason
            is EraParsed.Ok -> work.validate(parsed.reply, departing, group)
        }
        if (problem != null) {
            recordDeadLetter(chatKey, key, problem, prompt.text, reply)
            eraFailed(round, work, departing, group, key, failuresBefore, problem)
            return
        }
        val ok = (parsed as EraParsed.Ok).reply
        work.apply(ok, departing, group, prompt)
        if (key != null && failuresBefore > 0) work.failures += PeriodFailureRecord(chatKey, PeriodFailureKind.ERA, key, 0)
        logger.info(
            "Period eras assigned chat={}: {}{}",
            chatKey,
            departing.zip(ok.assign).joinToString(", ") { (p, t) -> "「${p.period.name}」->${t.name.lowercase()}" }.ifEmpty { "(nothing to place)" },
            if (group.isEmpty()) "" else "; merged ${group.joinToString(" + ") { "「${it.name}」" }} into 「${ok.merge?.name}」",
        )
    }

    private fun eraFailed(
        round: PeriodRound,
        work: EraWork,
        departing: List<PeriodView>,
        group: List<EraRecord>,
        key: Long?,
        failuresBefore: Int,
        reason: String,
    ) {
        val chatKey = round.chatKey
        work.mergeNeutral(group)
        if (key == null) {
            logger.warn("Period era call failed chat={} (the merge used a neutral date label): {}", chatKey, reason)
            return
        }
        val count = failuresBefore + 1
        logger.warn("Period era call failed ({} of {}) chat={} waiting={} from scene={}: {}", count, MAX_FAILURES, chatKey, departing.size, key, reason)
        if (count >= MAX_FAILURES) {
            if (placedByTime(work, departing, key)) {
                logger.warn("Period eras placed by time after {} failures chat={}: {}", MAX_FAILURES, chatKey, departing.map { it.period.name })
            }
        } else {
            work.failures += PeriodFailureRecord(chatKey, PeriodFailureKind.ERA, key, count)
        }
    }

    /**
     * The by-time placement at the failure cap: [departing] joins the newest era (or a new neutrally labelled one). The
     * counter keyed [key] is cleared when they were placed; otherwise it is kept at the cap, so the next round makes no
     * call either and tries again.
     */
    private fun placedByTime(work: EraWork, departing: List<PeriodView>, key: Long): Boolean {
        val placed = work.placeDeterministically(departing)
        if (!placed) logger.warn("Period eras could not be placed by time chat={} (no free neutral label); kept at the failure cap", work.chatKey)
        work.failures += PeriodFailureRecord(work.chatKey, PeriodFailureKind.ERA, key, if (placed) 0 else MAX_FAILURES)
        return placed
    }

    /** The snapshots of every language whose text differs from the published one (none without a period). */
    private fun snapshots(source: CatalogueSource, densityFactor: Double, threads: List<CatalogueThread>): List<CatalogueSnapshot> {
        val rendered = languages.associateWith { render(source, markers(it)) }
        if (rendered.values.any { it == null }) return emptyList()
        val changed = rendered.filter { (lang, text) -> publisher.publishedExact(source.chatKey, lang) != text }
        if (changed.isEmpty()) return emptyList()
        val estimates = rendered.mapValues { (_, text) -> estimate(text!!, densityFactor) }
        logger.info(
            "Period catalogue re-rendered chat={} eras={} periods={} languages={} ~{} tokens (budget {})",
            source.chatKey, source.eras.size, source.periods.size, changed.keys.map { it.tag }, estimates.values.max(), budgetTokens,
        )
        return changed.map { (lang, text) -> CatalogueSnapshot(source.chatKey, lang.tag, text!!, coverage = facts(markers(lang), source, threads)) }
    }

    // --- startup check and calibration ------------------------------------------------------------------------------

    /**
     * The startup check and calibration, for every chat with a period-layer row. Call off the graph thread, before the
     * startup formation; no model call.
     *  1. CHECK: every member scene of the chat's periods must exist, belong to the chat and have ended (MATERIALIZED /
     *     ABANDONED, or never written by the pipeline). A chat that does not (a store merged from another device, a
     *     restored backup) is logged as an ERROR and its period layer reset ([PeriodWrite.reset]: snapshots included, so
     *     nothing more is published for it) — the formation that runs next rebuilds it. A reset that cannot be written
     *     withdraws the published text for this run instead ([PeriodPublisher.withdraw]).
     *  2. CALIBRATE: every other chat is re-rendered ([calibrate], with [trust] of the chat) and its snapshots
     *     republished only when a text differs from the published one. An unreadable trust publishes nothing.
     * Never throws for one chat; the result lists what happened to each.
     */
    fun startupCheck(trust: (chatKey: String) -> SceneTrust.ChatTrust): StartupCheck {
        val result = StartupCheck()
        for (chatKey in graph.withGraph { g -> g.periodChatKeys() }.sorted()) {
            val mismatches = try {
                memberMismatches(chatKey)
            } catch (e: Exception) {
                logger.warn("Period startup check: could not verify chat={}; its catalogue stays as published: {}", chatKey, e.toString())
                result.skipped += chatKey
                continue
            }
            if (mismatches.isNotEmpty()) {
                logger.error(
                    "Period layer of chat={} does not match its scenes ({} member scene(s): {}); its catalogue is withdrawn and its periods are formed again",
                    chatKey, mismatches.size, mismatches.take(MAX_REPORTED_MISMATCHES).joinToString("; "),
                )
                try {
                    publisher.commit { PeriodWrite(chatKey, reset = true) }
                } catch (e: Exception) {
                    logger.error("Period layer of chat={} could not be reset; its catalogue is withdrawn for this run", chatKey, e)
                    runCatching { publisher.withdraw(chatKey) }
                        .onFailure { logger.error("Period catalogue of chat={} could not be withdrawn either", chatKey, it) }
                }
                result.reset += chatKey
                continue
            }
            try {
                val calibration = calibrate(chatKey, trust(chatKey))
                val write = calibration?.write
                when {
                    calibration == null -> result.skipped += chatKey
                    write == null -> result.unchanged += chatKey
                    else -> {
                        publisher.commit { write }
                        result.published += chatKey
                    }
                }
            } catch (e: Exception) {
                logger.warn("Period startup calibration failed chat={}; its catalogue stays as published: {}", chatKey, e.toString(), e)
                result.skipped += chatKey
            }
        }
        logger.info(
            "Period startup check: reset={} republished={} unchanged={} skipped={}",
            result.reset, result.published, result.unchanged, result.skipped,
        )
        return result
    }

    /** Why [chatKey]'s period members disagree with its scenes (one line per scene; empty = consistent). */
    private fun memberMismatches(chatKey: String): List<String> = graph.withGraph { g ->
        g.periods(chatKey).flatMap { g.periodScenes(it.id) }.mapNotNull { id ->
            val scene = g.scene(id)
            val state = g.sceneWork(id)?.state
            when {
                scene == null -> "scene $id is missing"
                scene.chatKey != chatKey -> "scene $id belongs to ${scene.chatKey}"
                !PeriodFormer.ended(state) -> "scene $id is $state"
                else -> null
            }
        }
    }

    /** What [startupCheck] did, by chat key. */
    class StartupCheck {
        /** Inconsistent with its scenes: reset (or withdrawn), left to the formation. */
        val reset = ArrayList<String>()

        /** Re-rendered to a different text: republished. */
        val published = ArrayList<String>()

        /** Re-rendered to the published text: nothing written. */
        val unchanged = ArrayList<String>()

        /** Not verified or not rendered (a read failed, the trust is unreadable, no period): the published text stays. */
        val skipped = ArrayList<String>()
    }

    /**
     * Re-render [chatKey]'s catalogue from the committed period layer, without a model call: eras and periods as they
     * are, the threads line as the last formation computed it (the thread ids its snapshot recorded, filtered by today's
     * [trust]; recomputed when no snapshot recorded them, or with [recomputeThreads]), the coverage from the chat's
     * scenes. Returns every language's text, the estimate and the snapshots whose text differs from the published one
     * ([Calibration.write] commits them through [PeriodPublisher.commit]); null when [trust] could not be read (publish
     * nothing, keep the old text) or the chat has no period. Call off the graph thread.
     */
    fun calibrate(chatKey: String, trust: SceneTrust.ChatTrust, recomputeThreads: Boolean = false): Calibration? {
        if (trust.failed) return null
        val trusted: (Long) -> Boolean = { trust.trusted(it) }
        val read = graph.withGraph { g ->
            CalibrationRead(
                periods = g.periods(chatKey).map { PeriodView(it, g.periodScenes(it.id)) },
                eras = g.eras(chatKey),
                threads = g.threads(chatKey),
                exclusions = g.periodExclusions(chatKey).mapTo(HashSet()) { it.sceneId },
                scenes = g.scenes(chatKey).sortedWith(SCENE_ORDER),
                snapshots = g.catalogueSnapshots(chatKey),
            )
        }
        if (read.periods.isEmpty()) return null
        val scenes = read.scenes.withIndex().associate { (i, s) -> s.sceneId to SceneFacts(s.sceneId, i, s.dateFrom, s.dateTo ?: s.dateFrom) }
        val layer = Layer(read.periods, read.eras)
        val recorded = read.snapshots.firstNotNullOfOrNull { recordedThreadIds(it.coverage) }
        val threads = if (recorded == null || recomputeThreads) {
            openThreads(read.threads, layer, scenes, trusted)
        } else {
            val byId = read.threads.associateBy { it.id }
            recorded.mapNotNull { id ->
                val t = byId[id] ?: return@mapNotNull null
                val opened = t.openedSceneId ?: return@mapNotNull null
                if (!trusted(opened)) null else CatalogueThread(t.id, threadLabel(t))
            }
        }
        val coverage = coverageFacts(layer, scenes, read.exclusions)
        val source = CatalogueSource(chatKey, layer.periods.toList(), layer.orderedEras(), threads, coverage.pending, coverage.pendingTo, trusted)
        val rendered = languages.associateWith { render(source, markers(it)) ?: return null }
        val densityFactor = densityFactor(chatKey)
        val changed = rendered.filter { (lang, text) -> publisher.publishedExact(chatKey, lang) != text }
            .map { (lang, text) -> CatalogueSnapshot(chatKey, lang.tag, text, coverage = facts(markers(lang), source, threads)) }
        return Calibration(chatKey, rendered, changed, rendered.values.maxOf { estimate(it, densityFactor) })
    }

    /** What [calibrate] rendered: every language's text, the snapshots that differ from the published ones, the estimate. */
    class Calibration(
        val chatKey: String,
        val texts: Map<Language, String>,
        val changed: List<CatalogueSnapshot>,
        /** Tokens of the longest language's text ([estimateTokens] × density). */
        val estimate: Int,
    ) {
        /** A non-structural write publishing [changed] (null when nothing changed). */
        val write: PeriodWrite? get() = if (changed.isEmpty()) null else PeriodWrite(chatKey, catalogues = changed)
    }

    // --- budget ------------------------------------------------------------------------------------------------

    /** The density of [chatKey]'s prompts ([density]); 1.0 when it cannot be read or is not a positive number. */
    fun densityFactor(chatKey: String): Double =
        runCatching { density(chatKey) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0

    /** The catalogue [text]'s length as it is budgeted: [estimateTokens] × [densityFactor]. */
    fun estimateTokens(chatKey: String, text: String): Int = estimate(text, densityFactor(chatKey))

    private fun estimate(text: String, densityFactor: Double): Int = ceil(estimateTokens(text) * densityFactor).toInt()

    // --- the chat's facts -------------------------------------------------------------------------------------------

    private class GraphFacts(val threads: List<ThreadRecord>, val exclusions: Set<Long>, val aliases: Map<Long, List<String>>)

    private class CalibrationRead(
        val periods: List<PeriodView>,
        val eras: List<EraRecord>,
        val threads: List<ThreadRecord>,
        val exclusions: Set<Long>,
        /** In [SCENE_ORDER]. */
        val scenes: List<SceneRecord>,
        val snapshots: List<CatalogueSnapshot>,
    )

    /** One scene as the catalogue needs it: its place in [SCENE_ORDER] and its local dates. */
    private data class SceneFacts(val id: Long, val order: Int, val dateFrom: String?, val dateTo: String?)

    private data class Coverage(val pending: Boolean, val pendingTo: String?)

    /** The unresolved threads (OPEN / STALE) opened in a scene of a period by a TRUSTED scene, in the opening scenes' order. */
    private fun openThreads(threads: List<ThreadRecord>, layer: Layer, scenes: Map<Long, SceneFacts>, trusted: (Long) -> Boolean): List<CatalogueThread> {
        val periodized = layer.periods.flatMapTo(HashSet()) { it.sceneIds }
        return threads.filter { t ->
            (t.state == ThreadState.OPEN || t.state == ThreadState.STALE) &&
                t.openedSceneId != null && t.openedSceneId in periodized && trusted(t.openedSceneId)
        }.sortedWith(compareBy({ scenes[it.openedSceneId]?.order ?: Int.MAX_VALUE }, { it.id }))
            .map { CatalogueThread(it.id, threadLabel(it)) }
    }

    private fun threadLabel(t: ThreadRecord): String = t.name?.takeIf { it.isNotBlank() } ?: t.description

    /** Whether scenes wait for a period (neither in one nor excluded), and the last day they reach. */
    private fun coverageFacts(layer: Layer, scenes: Map<Long, SceneFacts>, excluded: Set<Long>): Coverage {
        val periodized = layer.periods.flatMapTo(HashSet()) { it.sceneIds }
        val waiting = scenes.values.filter { it.id !in periodized && it.id !in excluded }
        if (waiting.isEmpty()) return Coverage(false, null)
        return Coverage(true, waiting.mapNotNull { it.dateTo ?: it.dateFrom }.maxOrNull())
    }

    // --- the era layer being worked on ---------------------------------------------------------------------------

    /** Periods (in order, each with its era id) and eras of one chat, as a round leaves them so far. */
    private class Layer(periods: List<PeriodView>, eras: Collection<EraRecord>) {
        val eras = LinkedHashMap<Long, EraRecord>().apply { eras.forEach { put(it.id, it) } }
        val periods: MutableList<PeriodView> = periods.map { v ->
            val era = v.period.eraId
            if (era != null && era !in this.eras) v.copy(period = v.period.copy(eraId = null)) else v
        }.toMutableList()

        fun copy() = Layer(periods, eras.values)

        fun periodsOf(eraId: Long): List<PeriodView> = periods.filter { it.period.eraId == eraId }

        fun scenesOf(eraId: Long): List<Long> = periodsOf(eraId).flatMap { it.sceneIds }

        /** Eras by their first period (eras without one last), with dates derived from their periods. */
        fun orderedEras(): List<EraRecord> {
            val rank = HashMap<Long, Int>()
            periods.forEachIndexed { i, v -> v.period.eraId?.let { rank.putIfAbsent(it, i) } }
            return eras.values.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.id })).map { e ->
                val own = periodsOf(e.id)
                e.copy(dateFrom = own.mapNotNull { it.period.dateFrom }.minOrNull(), dateTo = own.mapNotNull { it.period.dateTo ?: it.period.dateFrom }.maxOrNull())
            }
        }

        /** Periods outside an era that are no longer among the last [RECENT_PERIODS], in order. */
        fun departing(): List<PeriodView> {
            val recent = periods.takeLast(RECENT_PERIODS).mapTo(HashSet()) { it.period.id }
            return periods.filter { it.period.eraId == null && it.period.id !in recent }
        }

        fun assign(periodIds: Collection<Long>, eraId: Long) {
            val ids = periodIds.toSet()
            for (i in periods.indices) {
                if (periods[i].period.id in ids) periods[i] = periods[i].copy(period = periods[i].period.copy(eraId = eraId))
            }
        }

        /** This layer with [departing] in a placeholder new era and [group] merged under placeholder text (the estimate). */
        fun projection(departing: List<PeriodView>, group: List<EraRecord>): Layer {
            val p = copy()
            if (departing.isNotEmpty()) {
                p.eras[PLACEHOLDER_ID] = EraRecord(PLACEHOLDER_ID, "", PLACEHOLDER_NAME, PLACEHOLDER_GIST)
                p.assign(departing.map { it.period.id }, PLACEHOLDER_ID)
            }
            if (group.size >= 2) {
                val keep = group.first().id
                group.drop(1).forEach { e -> p.assign(p.periodsOf(e.id).map { it.period.id }, keep); p.eras.remove(e.id) }
                p.eras[keep] = p.eras.getValue(keep).copy(name = PLACEHOLDER_NAME, gist = PLACEHOLDER_GIST, namingSceneIds = emptySet())
            }
            return p
        }
    }

    /** The era changes a round accumulates on top of the formation's write. */
    private inner class EraWork(val round: PeriodRound, val layer: Layer, val aliasesBefore: Map<Long, List<String>>) {
        val chatKey = round.chatKey
        val newAliases = ArrayList<EraAliasRecord>()
        val failures = ArrayList<PeriodFailureRecord>()

        private val nameLanguage: Language get() = round.language ?: fallbackLanguage

        /** Eras the formation left without a period (a re-cut of fallback periods) are deleted. */
        fun dropEmptyEras() {
            val used = layer.periods.mapNotNullTo(HashSet()) { it.period.eraId }
            layer.eras.keys.filter { it !in used }.forEach { layer.eras.remove(it) }
        }

        private fun aliasesOf(eraId: Long): List<String> =
            aliasesBefore[eraId].orEmpty() + newAliases.filter { it.eraId == eraId }.map { it.alias }

        /** Name keys of every era but [except] (names and aliases). */
        fun takenKeys(except: Set<Long>): Set<String> = layer.eras.values.filter { it.id !in except }
            .flatMap { e -> listOf(e.name) + aliasesOf(e.id) }.mapTo(HashSet(), ::periodNameKey)

        /** The newest era, when it may still take periods and be renamed (not frozen). */
        fun renamableNewest(): EraRecord? = layer.orderedEras().lastOrNull()?.takeIf { !it.frozen }?.let { layer.eras.getValue(it.id) }

        /** Null when [reply] is a valid answer for [departing] and [group]; else why not (a counted failure). */
        fun validate(reply: EraReply, departing: List<PeriodView>, group: List<EraRecord>): String? {
            if (departing.isNotEmpty() && reply.assign.size != departing.size) return "assign lists ${reply.assign.size} period(s), not ${departing.size}"
            val firstNew = if (departing.isEmpty()) -1 else reply.assign.indexOf(Target.NEW)
            if (firstNew >= 0 && reply.assign.drop(firstNew).any { it != Target.NEW }) return "a period after the new era's first one goes back to the newest era"
            val newest = renamableNewest()
            if (departing.isNotEmpty() && Target.NEWEST in reply.assign && newest == null) return "no newest era to join"
            val names = LinkedHashMap<String, String>() // key -> whose
            fun nameCheck(label: String, v: NameGist?, taken: Set<String>): String? {
                if (v == null) return null
                PeriodFields.nameProblem(v.name)?.let { return "$label: $it" }
                if (v.gist != null && v.gist.codePointCount(0, v.gist.length) > MAX_ERA_GIST_CODE_POINTS) {
                    return "$label: gist longer than $MAX_ERA_GIST_CODE_POINTS code points"
                }
                val key = periodNameKey(v.name)
                if (key in taken) return "$label: the name 「${v.name}」 is already used"
                names.put(key, label)?.let { return "$label: the name 「${v.name}」 is also given to the $it" }
                return null
            }
            if (newest != null && reply.newest != null) {
                if (periodNameKey(reply.newest.name) != periodNameKey(newest.name)) {
                    nameCheck("newest era", reply.newest, takenKeys(setOf(newest.id)))?.let { return it }
                } else {
                    // Its name kept: only the gist is new.
                    nameCheck("newest era", reply.newest.copy(name = newest.name), emptySet())?.let { return it }
                }
            }
            if (firstNew >= 0) {
                if (reply.new == null) return "periods start a new era but it has no name"
                nameCheck("new era", reply.new, takenKeys(emptySet()))?.let { return it }
            }
            if (group.isNotEmpty()) {
                if (reply.merge == null) return "a merge was asked for but not named"
                nameCheck("merged era", reply.merge, takenKeys(group.mapTo(HashSet()) { it.id }))?.let { return it }
            }
            return null
        }

        /** Apply a validated [reply]: the merge, then the placement (see the class doc for the naming scenes). */
        fun apply(reply: EraReply, departing: List<PeriodView>, group: List<EraRecord>, prompt: EraPrompt) {
            if (group.size >= 2) {
                val sources = group.filter { it.id in prompt.shownEras }.flatMapTo(LinkedHashSet()) { it.namingSceneIds }
                merge(group, reply.merge!!.name, reply.merge.gist, sources)
            }
            val toNewest = departing.filterIndexed { i, _ -> reply.assign[i] == Target.NEWEST }
            val toNew = departing.filterIndexed { i, _ -> reply.assign[i] == Target.NEW }
            val newest = renamableNewest()
            if (newest != null && (toNewest.isNotEmpty() || reply.newest != null)) {
                val renamed = reply.newest != null && periodNameKey(reply.newest.name) != periodNameKey(newest.name)
                val sources = LinkedHashSet<Long>()
                if (newest.id in prompt.shownEras) sources += newest.namingSceneIds
                prompt.shownNewestPeriods.forEach { sources += it }
                toNewest.filter { it.period.id in prompt.shownPeriods }.forEach { sources += it.period.namingSceneIds }
                val naming = if (renamed) sources else newest.namingSceneIds + sources
                layer.eras[newest.id] = newest.copy(
                    name = if (renamed) reply.newest.name else newest.name,
                    gist = reply.newest?.gist ?: newest.gist,
                    namingSceneIds = naming,
                )
                if (renamed) newAliases += EraAliasRecord(chatKey, newest.name, newest.id)
                layer.assign(toNewest.map { it.period.id }, newest.id)
            }
            if (toNew.isNotEmpty()) {
                val sources = toNew.filter { it.period.id in prompt.shownPeriods }.flatMapTo(LinkedHashSet()) { it.period.namingSceneIds }
                open(toNew, reply.new!!.name, reply.new.gist, sources)
            }
        }

        /** A new era for [periods] (the previous newest is frozen from now on). */
        private fun open(periods: List<PeriodView>, name: String, gist: String?, naming: Set<Long>) {
            layer.orderedEras().lastOrNull()?.let { last ->
                val record = layer.eras.getValue(last.id)
                if (!record.frozen) layer.eras[last.id] = record.copy(frozen = true)
            }
            val id = graph.withGraph { it.allocateEraId() }
            layer.eras[id] = EraRecord(id, chatKey, name, gist, frozen = false, namingSceneIds = naming)
            layer.assign(periods.map { it.period.id }, id)
        }

        /** Merge [group] (the oldest eras) into its first one under [name]; every old name becomes an alias. */
        private fun merge(group: List<EraRecord>, name: String, gist: String?, naming: Set<Long>) {
            val keep = layer.eras.getValue(group.first().id)
            for (e in group) {
                (listOf(e.name) + aliasesOf(e.id)).forEach { old ->
                    if (periodNameKey(old) != periodNameKey(name)) newAliases += EraAliasRecord(chatKey, old, keep.id)
                }
            }
            for (e in group.drop(1)) {
                layer.assign(layer.periodsOf(e.id).map { it.period.id }, keep.id)
                layer.eras.remove(e.id)
            }
            layer.eras[keep.id] = keep.copy(name = name, gist = gist, frozen = true, namingSceneIds = naming)
            val dropped = group.drop(1).map { it.id }
            newAliases.removeAll { a -> a.eraId in dropped }
        }

        /** The merge of [group] when no model named it: a neutral date-range label, no gist, no naming scenes. */
        fun mergeNeutral(group: List<EraRecord>) {
            if (group.size < 2) return
            val from = group.mapNotNull { it.dateFrom }.minOrNull()
            val to = group.mapNotNull { it.dateTo ?: it.dateFrom }.maxOrNull()
            val name = neutralName(from, to, takenKeys(group.mapTo(HashSet()) { it.id })) ?: run {
                logger.warn("Period era merge skipped chat={}: no free neutral label for {}..{}", chatKey, from, to)
                return
            }
            merge(group, name, null, emptySet())
            logger.info("Period eras merged under a neutral label chat={}: {} -> 「{}」", chatKey, group.map { it.name }, name)
        }

        /**
         * Place [departing] by time: into the newest era keeping its name, else into a new neutrally labelled era. False
         * when they could not be placed (no era and no free neutral label).
         */
        fun placeDeterministically(departing: List<PeriodView>): Boolean {
            if (departing.isEmpty()) return true
            val newest = layer.orderedEras().lastOrNull()
            if (newest != null) {
                layer.assign(departing.map { it.period.id }, newest.id)
                return true
            }
            val from = departing.mapNotNull { it.period.dateFrom }.minOrNull()
            val to = departing.mapNotNull { it.period.dateTo ?: it.period.dateFrom }.maxOrNull()
            val name = neutralName(from, to, takenKeys(emptySet())) ?: return false
            open(departing, name, null, emptySet())
            return true
        }

        private fun neutralName(from: String?, to: String?, taken: Set<String>): String? {
            val base = PeriodLabels.neutralDateName(from, to, nameLanguage)
            return (listOf("") + PeriodFormer.continuedSuffixes(nameLanguage)).map { base + it }.firstOrNull { periodNameKey(it) !in taken }
        }

        /** The formation's [write] with this round's era changes, counters and [catalogues] added. */
        fun into(write: PeriodWrite, erasBefore: List<EraRecord>, catalogues: List<CatalogueSnapshot>): PeriodWrite {
            val before = (erasBefore + write.eras).associateBy { it.id }
            fun bare(e: EraRecord) = e.copy(dateFrom = null, dateTo = null)
            val eras = layer.eras.values.filter { e -> before[e.id]?.let { bare(it) != bare(e) } ?: true }.map(::bare)
            val deleted = before.keys.filter { it !in layer.eras && it !in write.deleteEraIds && erasBefore.any { e -> e.id == it } }
            val originalEra = round.after(write).associate { it.period.id to it.period.eraId }
            val periods = LinkedHashMap<Long, PeriodRecord>()
            write.periods.forEach { periods[it.id] = it }
            for (v in layer.periods) {
                if (v.period.eraId == originalEra[v.period.id]) continue
                val record = periods[v.period.id] ?: v.period
                periods[v.period.id] = record.copy(eraId = v.period.eraId)
            }
            val newPeriods = periods.values.toList()
            if (eras.isEmpty() && deleted.isEmpty() && newAliases.isEmpty() && failures.isEmpty() && catalogues.isEmpty() && newPeriods == write.periods) {
                return write
            }
            return write.copy(
                deleteEraIds = write.deleteEraIds + deleted,
                eras = write.eras.filter { e -> eras.none { it.id == e.id } } + eras,
                periods = newPeriods,
                eraAliases = write.eraAliases + newAliases,
                failures = write.failures + failures,
                catalogues = write.catalogues + catalogues,
            )
        }
    }

    // --- the era call --------------------------------------------------------------------------------------------

    /** The rendered era input, and what it showed in full (its names and gists, not only dates). */
    private class EraPrompt(
        val text: String,
        val shownPeriods: Set<Long>,
        val shownEras: Set<Long>,
        /** Naming scenes of the newest era's periods listed by name. */
        val shownNewestPeriods: List<Set<Long>>,
    )

    private fun renderEraInput(round: PeriodRound, work: EraWork, departing: List<PeriodView>, group: List<EraRecord>, trusted: (Long) -> Boolean): EraPrompt {
        val layer = work.layer
        val shownPeriods = HashSet<Long>()
        val shownEras = HashSet<Long>()
        val shownNewestPeriods = ArrayList<Set<Long>>()
        fun periodShown(p: PeriodView) = periodNameShown(p.period.namingSceneIds, p.sceneIds, trusted)
        fun eraShown(e: EraRecord) = periodNameShown(e.namingSceneIds, layer.scenesOf(e.id), trusted)
        val eras = layer.orderedEras()
        val newest = work.renamableNewest()?.let { n -> eras.first { it.id == n.id } }

        val text = buildString {
            val used = eras.filter { it.id != newest?.id && eraShown(it) }.flatMap { e -> listOf(e.name) + work.aliasesBefore[e.id].orEmpty() }
            PeriodFields.appendInputHeader(this, round.language, selfAliases, userName, counterpartFallback = "as named in the periods", used)
            if (newest != null) {
                append('\n')
                append("## Newest era\n")
                if (eraShown(newest)) {
                    shownEras += newest.id
                    append("Name: 「").append(PeriodFields.normalize(newest.name)).append("」\n")
                    newest.gist?.let(PeriodFields::normalize)?.takeIf { it.isNotEmpty() }?.let { append("Summary: ").append(it).append('\n') }
                } else {
                    append("Name: ").append(UNTRUSTED_NAME_MARK).append('\n')
                }
                PeriodFields.dateRange(newest.dateFrom, newest.dateTo)?.let { append("Dates: ").append(it).append('\n') }
                val own = layer.periodsOf(newest.id).takeLast(NEWEST_PERIODS_SHOWN)
                append("Its latest periods: ")
                append(own.joinToString("; ") { p ->
                    val dates = PeriodFields.dateRange(p.period.dateFrom, p.period.dateTo) ?: "(undated)"
                    if (periodShown(p)) {
                        shownNewestPeriods += p.period.namingSceneIds
                        "「${PeriodFields.normalize(p.period.name)}」 $dates"
                    } else {
                        "$dates $UNTRUSTED_MARK"
                    }
                })
                append('\n')
            }
            if (departing.isNotEmpty()) {
                append('\n')
                append("## Periods to place\n")
                departing.forEachIndexed { i, p ->
                    append('[').append(i + 1).append("] ").append(PeriodFields.dateRange(p.period.dateFrom, p.period.dateTo) ?: "(undated)")
                    if (!periodShown(p)) {
                        append(' ').append(UNTRUSTED_MARK).append('\n')
                        return@forEachIndexed
                    }
                    shownPeriods += p.period.id
                    append('\n')
                    append("Name: 「").append(PeriodFields.normalize(p.period.name)).append("」\n")
                    append("Summary: ").append(p.period.gist?.let(PeriodFields::normalize)?.takeIf { it.isNotEmpty() } ?: "(none)").append('\n')
                }
            }
            if (group.isNotEmpty()) {
                append('\n')
                append("## Merge\n")
                append("The catalogue is too long: these oldest eras become ONE era (name it and give it a one-sentence gist):\n")
                for (e in group) {
                    val full = eras.first { it.id == e.id }
                    val dates = PeriodFields.dateRange(full.dateFrom, full.dateTo) ?: "(undated)"
                    if (eraShown(full)) {
                        shownEras += e.id
                        append("- 「").append(PeriodFields.normalize(e.name)).append("」 ").append(dates)
                        e.gist?.let(PeriodFields::normalize)?.takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
                        append('\n')
                    } else {
                        append("- ").append(dates).append(' ').append(UNTRUSTED_MARK).append('\n')
                    }
                }
            }
        }
        return EraPrompt(text.trimEnd('\n') + "\n", shownPeriods, shownEras, shownNewestPeriods)
    }

    internal enum class Target { NEWEST, NEW }

    internal data class NameGist(val name: String, val gist: String?)

    internal data class EraReply(val assign: List<Target>, val newest: NameGist?, val new: NameGist?, val merge: NameGist?)

    internal sealed interface EraParsed {
        data class Ok(val reply: EraReply) : EraParsed

        data class Unparseable(val reason: String) : EraParsed
    }

    /**
     * Read the reply: `{"assign": ["newest", "new"], "newest": {"name", "gist"}, "new": {…}, "merge": {…}}` (lenient on
     * spellings: `current` / `existing` for the newest era, `new_era` for the new one). Names and gists are normalized.
     */
    internal fun parseEraReply(raw: String): EraParsed {
        val root = NativeMemoryExtractor.parseLenientObject(raw) ?: return EraParsed.Unparseable("reply was not parseable JSON")
        val assign = ArrayList<Target>()
        when (val a = root["assign"] ?: root["assignments"]) {
            null -> Unit
            is JsonArray -> for (e in a) {
                val word = (e as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
                    ?: return EraParsed.Unparseable("an assignment is not a string")
                assign += when (word) {
                    "newest", "current", "existing", "latest", "newest_era" -> Target.NEWEST
                    "new", "new_era", "next" -> Target.NEW
                    else -> return EraParsed.Unparseable("unknown assignment \"$word\"")
                }
            }
            else -> return EraParsed.Unparseable("\"assign\" is not an array")
        }
        fun nameGist(e: JsonElement?): NameGist? {
            val o = e as? JsonObject ?: return null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.let(PeriodFields::normalize)?.takeIf { it.isNotEmpty() } ?: return null
            val gist = ((o["gist"] ?: o["summary"]) as? JsonPrimitive)?.contentOrNull?.let(PeriodFields::normalize)?.takeIf { it.isNotEmpty() }
            return NameGist(name, gist)
        }
        return EraParsed.Ok(
            EraReply(
                assign = assign,
                newest = nameGist(root["newest"] ?: root["newest_era"]),
                new = nameGist(root["new"] ?: root["new_era"]),
                merge = nameGist(root["merge"] ?: root["merged"]),
            ),
        )
    }

    private fun recordDeadLetter(chatKey: String, firstSceneId: Long?, reason: String, prompt: String, raw: String) {
        runCatching {
            deadLetter.record(
                DeadLetterEntry(
                    reason = "period era assignment chat=$chatKey: $reason",
                    phase = DEAD_LETTER_PHASE,
                    sourceMessageId = firstSceneId,
                    referenceTime = null,
                    transcript = prompt,
                    rawOutput = raw,
                ),
            )
        }
    }

    companion object {
        /** The catalogue's budget: about 1000 tokens, estimated as [estimateTokens] × the chat's density. */
        const val BUDGET_TOKENS = 1000

        /** Periods that keep a line of their own (the recent window); older ones go into eras. */
        const val RECENT_PERIODS = 5

        /** Consecutive failed era calls for one set of waiting periods before they are placed by time. */
        const val MAX_FAILURES = 3

        /** A round makes at most this many model calls (the formation and the era call). */
        const val MAX_MODEL_CALLS_PER_ROUND = 2

        /** Unresolved threads named on the threads line (the newest ones); the rest are counted. */
        const val MAX_OPEN_THREADS = 6

        /**
         * Hard cap on an era's gist; a longer one fails the era call. The prompt asks for one short sentence of at most
         * 60 CJK characters / 20 words, well within it: without a stated limit the newest era's gist grows with every
         * period that joins, and refused replies end in a placement by time.
         */
        const val MAX_ERA_GIST_CODE_POINTS = 160

        /** A thread name on the threads line is cut here (then `…`). */
        const val THREAD_NAME_CODE_POINTS = 20

        /** Periods of the newest era listed in the era input (its latest ones). */
        const val NEWEST_PERIODS_SHOWN = 8

        const val DEAD_LETTER_PHASE = "period-era"

        /** Inconsistent member scenes named in the startup check's ERROR line (the rest are counted). */
        const val MAX_REPORTED_MISMATCHES = 5

        /** How an untrusted period or era appears in the era input: its dates and this marker only. */
        const val UNTRUSTED_MARK = PeriodFormer.UNTRUSTED_MARK

        /** The newest era's name when a scene it was named from is untrusted now. */
        const val UNTRUSTED_NAME_MARK = "(withheld: named from a scene now untrusted — give it a new name if periods join it)"

        /** The languages a catalogue is rendered in by default: Lattice's bundled ones. */
        val DEFAULT_LANGUAGES: List<Language> = listOf(Language.EN, Language.ZH)

        private const val PLACEHOLDER_ID = -1L
        private const val PLACEHOLDER_NAME = "某某某某某某某某某某某某"
        private val PLACEHOLDER_GIST = "某".repeat(48) + "。"

        private const val CJK_CHARS_PER_TOKEN = 1.6
        private const val ASCII_CHARS_PER_TOKEN = 4.0
        private const val OTHER_CHARS_PER_TOKEN = 2.0

        private val JSON = Json { ignoreUnknownKeys = true }

        // English scaffolding on purpose (as the formation's): names follow the language asked for in the input, the
        // instructions stay byte-stable.
        val SYSTEM_PROMPT = """
            You keep the long view of one chat's shared past. Its PERIODS (named stretches, oldest first) are grouped
            into ERAS once they are no longer recent: an era is a broad stretch people name when they look back over a
            long time ("the summer of the house move", "the months of the night classes"). You are the assistant of this chat;
            these are your own memories of it.
            Placing periods:
            - Each period under "Periods to place" goes into the newest era (when it carries on what that era was
              about) or into ONE new era that starts with it. They are in order: the first ones may join the newest
              era; from the first one that starts the new era on, all the rest go into it.
            - Without a "Newest era" section, they all start the new era.
            - The newest era may keep its name or get one that fits all its periods; older eras are fixed.
            Untrusted periods and eras:
            - One shown as "$UNTRUSTED_MARK" is shown by its dates only. Place it by its dates and its neighbours;
              never guess what happened in it and never name an era after it.
            Merging (only when a "Merge" section is shown):
            - The catalogue is too long: the eras listed under "Merge" (the oldest ones) become ONE era. Give it a
              name and a one-sentence gist covering all of them.
            Names and gists:
            - name: what people would call that stretch — its theme or a landmark of it, broad but specific,
              noun-first. At most 16 characters in Chinese or Japanese; in other languages at most 6 words.
            - Never number eras or name them by their position ("Era 2", "Part II", "the first
              stretch", "1. …", in any language). Numbers that belong to the content (a date, a year) are fine.
            - Every name differs from the others and from every name under "Names already used".
            - gist: one short sentence on what that era was, written as your own memory — first person for you, the
              human counterpart by name (see "Who is who"). No dates. At most 60 characters in Chinese or Japanese;
              in other languages at most 20 words. Keep the newest era's gist that short too as periods join it:
              sum the whole stretch up, never list its periods.
            - Write names and gists in the language given under "Language". Do not use 「」, 『』, the dot · or
              dashes (—, –, ～) in them: they are shown inside such marks.
            Output JSON only:
            {"assign": ["newest", "new"], "newest": {"name": "…", "gist": "…"}, "new": {"name": "…", "gist": "…"}, "merge": {"name": "…", "gist": "…"}}
            "assign" has one entry per period to place, in order: "newest" or "new". Give "newest" when a period joins
            the newest era (its name may stay the same), "new" when a new era starts, "merge" only when a merge is
            asked for; leave out what does not apply.
        """.trimIndent()

        /**
         * A quick token estimate of [text]: about 1.6 CJK characters, 4 ASCII characters or 2 other characters per token
         * (rounded up per kind). Conservative for current tokenizers; the budget multiplies it by the chat's density.
         */
        fun estimateTokens(text: String): Int {
            if (text.isBlank()) return 0
            val cjk = text.count { Character.isIdeographic(it.code) }
            val nonAscii = text.count { it.code > 0x7F }
            val ascii = text.length - nonAscii
            val other = nonAscii - cjk
            return (ceil(ascii / ASCII_CHARS_PER_TOKEN) + ceil(cjk / CJK_CHARS_PER_TOKEN) + ceil(other / OTHER_CHARS_PER_TOKEN)).toInt().coerceAtLeast(1)
        }

        /**
         * How many of the oldest [mergeable] eras to merge into one (0 = none, else at least 2): the fewest that bring
         * [estimate] (the catalogue with that many merged; 0 = none) within [budget]; all of them when none does.
         */
        internal fun mergeCount(mergeable: Int, budget: Int, estimate: (Int) -> Int): Int {
            if (mergeable < 2 || estimate(0) <= budget) return 0
            for (g in 2..mergeable) if (estimate(g) <= budget) return g
            return mergeable
        }

        /**
         * The catalogue [source] renders to in [markers]' language, or null when it has no period (see the class doc for
         * the layout). Pure: no model, no store — the startup calibration re-renders with it.
         */
        fun render(source: CatalogueSource, markers: CatalogueMarkers): String? {
            if (source.periods.isEmpty()) return null
            val trusted = source.trusted
            val eraIds = source.eras.mapTo(HashSet()) { it.id }
            val byEra = source.periods.filter { it.period.eraId in eraIds }.groupBy { it.period.eraId!! }
            val lines = ArrayList<String>()
            lines += markers.header
            val eras = source.eras.filter { byEra[it.id].orEmpty().isNotEmpty() }
            if (eras.isNotEmpty()) {
                lines += markers.erasLabel
                for (e in eras) {
                    val members = byEra.getValue(e.id).flatMap { it.sceneIds }
                    val name = if (periodNameShown(e.namingSceneIds, members, trusted)) field(e.name, PeriodFields.MAX_NAME_CODE_POINTS) else null
                    val gist = if (name == null) null else e.gist?.let { field(it, MAX_ERA_GIST_CODE_POINTS) }
                    lines += markers.era(name, markers.dates(e.dateFrom, e.dateTo), gist)
                }
            }
            val loose = source.periods.filter { it.period.eraId == null || it.period.eraId !in byEra }
            if (loose.isNotEmpty()) {
                lines += if (eras.isEmpty()) markers.periodsLabel else markers.periodsSinceLabel
                for (p in loose) {
                    val name = if (periodNameShown(p.period.namingSceneIds, p.sceneIds, trusted)) field(p.period.name, PeriodFields.MAX_NAME_CODE_POINTS) else null
                    val gist = if (name == null) null else p.period.gist?.let { field(it, PeriodFields.MAX_GIST_CODE_POINTS) }
                    lines += markers.period(name, markers.dates(p.period.dateFrom, p.period.dateTo), gist, p.period.state == PeriodState.PROVISIONAL)
                }
            }
            val threadNames = source.threads.mapNotNull { t -> field(t.name, THREAD_NAME_CODE_POINTS) }
            if (threadNames.isNotEmpty()) {
                val shown = threadNames.takeLast(MAX_OPEN_THREADS)
                lines += markers.threads(shown, threadNames.size - shown.size)
            }
            val from = source.periods.mapNotNull { it.period.dateFrom }.minOrNull()
            val to = source.periods.mapNotNull { it.period.dateTo ?: it.period.dateFrom }.maxOrNull()
            lines += markers.coverage(from, to, source.pending, source.pendingTo)
            return lines.joinToString("\n")
        }

        /** A model-written field for the catalogue: [PeriodFields.normalize]d, cut at [max] code points (then `…`); null when empty. */
        private fun field(text: String, max: Int): String? {
            val s = PeriodFields.normalize(text)
            if (s.isEmpty()) return null
            if (s.codePointCount(0, s.length) <= max) return s
            return s.substring(0, s.offsetByCodePoints(0, max)) + "…"
        }

        /** The render facts a snapshot records in its `coverage` column (JSON): the coverage line and the thread ids. */
        private fun facts(markers: CatalogueMarkers, source: CatalogueSource, threads: List<CatalogueThread>): String {
            val from = source.periods.mapNotNull { it.period.dateFrom }.minOrNull()
            val to = source.periods.mapNotNull { it.period.dateTo ?: it.period.dateFrom }.maxOrNull()
            return buildJsonObject {
                put("coverage", markers.coverage(from, to, source.pending, source.pendingTo))
                put("threads", JsonArray(threads.map { JsonPrimitive(it.id) }))
            }.toString()
        }

        /** The thread ids a snapshot's render facts recorded, or null when it recorded none. */
        internal fun recordedThreadIds(coverage: String?): List<Long>? {
            if (coverage.isNullOrBlank()) return null
            val o = runCatching { JSON.parseToJsonElement(coverage) as? JsonObject }.getOrNull() ?: return null
            val ids = o["threads"] as? JsonArray ?: return null
            return ids.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        }
    }
}

/**
 * Everything a catalogue is rendered from ([PeriodCatalogue.render]) — pure data: the chat's periods (in order, with
 * their member scenes, derived dates and era ids), its eras (in order, dates derived from their periods), the
 * unresolved threads to list (oldest first; the newest [PeriodCatalogue.MAX_OPEN_THREADS] are named), whether scenes
 * still wait for a period and how far they reach, and the chat's scene verdicts ([trusted]: judged and trusted).
 */
data class CatalogueSource(
    val chatKey: String,
    val periods: List<PeriodView>,
    val eras: List<EraRecord>,
    val threads: List<CatalogueThread>,
    val pending: Boolean,
    val pendingTo: String?,
    val trusted: (Long) -> Boolean,
)

/** An unresolved thread the catalogue lists: its ledger id and its name (the narrator's, else its description). */
data class CatalogueThread(val id: Long, val name: String)
