package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.ConversationSource
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Period formation: when a chat is QUIET, the run of its ended scenes (MATERIALIZED / ABANDONED) that belong to no
 * period yet is grouped into named periods by ONE model call ([MemoryTask.PERIODS]) that judges where the conversation
 * turned; the newest period stays provisional. Runs on the narration thread (never on the graph thread): it reads the
 * graph through [graph] and the host's messages through [source], calls the model, and commits through
 * [PeriodPublisher.commit] after re-checking on the graph thread what the result was computed from.
 *
 * Every ordering of scenes is [SCENE_ORDER] over the chat's scenes (of all its conversations): message positions are
 * per conversation and ids per device slot, so neither orders anything here.
 *
 * One ROUND of a chat ([formChat]):
 *  1. Quiet: no EXTRACTING / NARRATING scene, no conversation whose segmentation failed and waits for a retry, and no
 *     sealed conversation holding said messages the scene pipeline has not segmented yet; otherwise nothing happens (the
 *     next narration drain asks again).
 *  2. Pending scenes: ended, in no period, not excluded. A pending scene without a person's message (only the host's
 *     own text, tool results and the assistant) is excluded for good, the first time it is seen.
 *  3. Late scenes: a pending scene that sorts at or before the last scene of the chat's periods is placed without a
 *     model call — into the period whose span contains it, else the one before it; earlier than the first period, into
 *     the first one (even the provisional one: its start moves). Names do not change.
 *  4. The formation input: the rest (the pending RUN) in scene order, stopping at an OPEN scene, capped at [inputCap]
 *     (the rest waits for the next round of the same trigger, see [formChat]), plus the provisional period (shown as
 *     context; the model may continue it — extend and rename — but never move its start or split it). Trailing
 *     fallback periods (needs-rename) are re-opened into the input when there are new scenes: they are the only periods
 *     a later formation may re-cut — whole ones, newest first, as many as fit the cap beside all the new scenes.
 *  5. Trust ([SceneTrust.refresh]): an untrusted scene is shown by its dates and a marker only, and every thread an
 *     untrusted scene opened is left out entirely; a period name its naming scenes no longer vouch for
 *     ([periodNameShown]) is not shown either. A trust read that fails skips the round (nothing written).
 *  6. The model call; the reply is parsed leniently and validated (contiguous runs covering the input once; only the
 *     first may continue the provisional period; unique, non-ordinal names after [PeriodFields.normalize], within the
 *     code point caps). An unparseable or invalid reply goes to the dead letter. Each period records its NAMING scenes:
 *     the trusted scenes the call saw in full; a continued period that keeps its name adds them to its old ones, a
 *     renamed one takes this call's alone.
 *  7. A failure (unreachable model, unreadable or invalid reply, a write the index refuses) counts on a persisted
 *     counter keyed (chat, FORMATION, first input scene); at [MAX_FAILURES] the input is grouped deterministically by
 *     date gaps instead ([fallbackGroups]), each group named after its main TRUSTED scene (a neutral date name when it
 *     has none) and flagged needs-rename. The fallback groups the model's input, else the whole pending run; a group
 *     with no free name joins its neighbour. When even that finds none (or the index refuses the write), the counter is
 *     held at the cap ([hold]): no model call until the input's scenes change or [HELD_RETRY_INTERVAL_MILLIS] passed
 *     since the model was last asked. A call cut off by a shutdown or an interrupt counts nothing and writes nothing.
 *  8. Before the commit [postFormation] may add era changes and catalogue snapshots to the SAME write (at most one more
 *     model call); a round followed by another of the same trigger adds no snapshot — the catalogue is published once
 *     the trigger's rounds end. The commit re-checks on the graph thread that the provisional period and the chat's
 *     generation are unchanged, the placed scenes are still where they were, the chat is still quiet and the scenes
 *     between the first and last placed scene are the same. On a conflict the result is dropped uncounted and the round
 *     is requeued ([requeue]) — at most [MAX_REQUEUES] times per input set, then it waits for the next trigger.
 * Everything a round changes lands in ONE [PeriodWrite]; a round without pending scenes writes nothing.
 */
class PeriodFormer(
    private val graph: GraphAccess,
    private val source: ConversationSource,
    private val publisher: PeriodPublisher,
    private val trust: SceneTrust,
    private val model: MemoryModel,
    /** The chat's language (names and gists are written in it); null = the conversation's own. */
    private val language: (chatKey: String) -> Language? = { null },
    /** The language of a neutral date name when the chat's is unknown. */
    private val fallbackLanguage: Language = Language.EN,
    private val deadLetter: MemoryDeadLetter = MemoryDeadLetter.NoOp,
    /** Era assignment and the catalogue, added to the same write (nothing by default). */
    private val postFormation: () -> PeriodPostFormation = { PeriodPostFormation.NoOp },
    /** The assistant's names, shown in "Who is who". */
    private val selfAliases: Set<String> = emptySet(),
    /** The human counterpart's name. */
    private val userName: String? = null,
    /** Queue another round of a chat after a conflict (on the narration thread). */
    private val requeue: (chatKey: String) -> Unit = {},
    /** New scenes one formation call is offered at most. */
    private val inputCap: Int = FORMATION_MAX_SCENES,
    /** Rounds one bounded trigger runs for a chat at most. */
    private val maxRoundsPerTrigger: Int = MAX_ROUNDS_PER_TRIGGER,
    /** No further round of a bounded trigger starts after this long. */
    private val triggerBudgetMillis: Long = TRIGGER_BUDGET_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(PeriodFormer::class.java)

    /** Conflict requeues per input set (chat + input scene ids); in memory — a restart starts over. */
    private val requeues = HashMap<String, Int>()

    /**
     * Inputs (chat + first input scene) whose counter is held at the cap because no fallback landed: the input's scene
     * ids (as a set) when last held. In memory: after a restart the first held round records them again.
     */
    private val held = HashMap<String, List<Long>>()

    /** When a held input was last warned about (the stall stays visible: again every [STALL_WARN_INTERVAL_MILLIS]). */
    private val heldWarnedAt = HashMap<String, Long>()

    /** Input scenes the last round left for a later round (beyond the input cap); read by [formChat]. */
    private var deferredScenes = 0

    /** A committed round of this trigger deferred the catalogue to a later round. */
    private var publishPending = false

    /** The last scene trust a round of this trigger could read (for the deferred publish). */
    private var lastTrust: SceneTrust.ChatTrust? = null

    /** What one round did. */
    enum class Outcome {
        /** A scene is still being extracted / narrated, or messages still await segmentation. */
        NOT_QUIET,

        /** No pending scene (or only ones after an OPEN scene). */
        NOTHING_TO_DO,

        /** The trust check could not read something: nothing was written, the next trigger retries. */
        TRUST_UNREADABLE,

        /** Only exclusions and / or late scenes were written (no model call was needed). */
        PLACED,

        /** New periods (or a continued provisional one) were written from the model's reply. */
        FORMED,

        /** The model call failed; the failure counter moved (anything placed deterministically was written). */
        FAILED,

        /** The input was grouped deterministically (needs-rename periods). */
        FALLBACK,

        /** The state moved under the round; its result was dropped uncounted (and the round requeued if allowed). */
        CONFLICT,

        /** The model call was cut off by a shutdown or an interrupt: nothing counted, nothing written. */
        SHUTDOWN,

        /** An unexpected failure (a read or a write threw); logged, nothing counted. */
        ERROR;

        /** The round changed the chat's period layer. */
        val changed: Boolean get() = this == PLACED || this == FORMED || this == FALLBACK
    }

    /** The chats that have at least one ended scene, sorted. */
    fun chatsWithEndedScenes(): List<String> = graph.withGraph { g ->
        g.scenes().filter { ended(g.sceneWork(it.sceneId)?.state) }.mapTo(sortedSetOf()) { it.chatKey }.toList()
    }

    /** One trigger for every chat that has an ended scene (startup, the sleep). Call on the narration thread; never throws. */
    fun formAll(complete: Boolean = false): Map<String, Outcome> {
        val chats = try {
            chatsWithEndedScenes()
        } catch (e: Exception) {
            logger.warn("Period formation: could not list the chats with ended scenes: {}", e.toString())
            return emptyMap()
        }
        return chats.associateWith { formChat(it, complete) }
    }

    /**
     * The rounds of one trigger for [chatKey]: one round, and another while the last one FORMED (or PLACED) and left
     * input scenes beyond the input cap; any other outcome stops it, so a failing model is not asked again for the rest.
     * A bounded trigger (the default) also stops after [maxRoundsPerTrigger] rounds or once it has held the narration
     * thread [triggerBudgetMillis]; a [complete] one runs until nothing is left. A round followed by another publishes no
     * catalogue: the catalogue is published once, when the trigger's rounds end — also when a later round was cut off,
     * conflicted or failed, so what was committed is what is shown. Returns the last round's outcome. Call on the
     * narration thread; never throws.
     */
    @Synchronized
    fun formChat(chatKey: String, complete: Boolean = false): Outcome {
        val started = nowMillis()
        var rounds = 0
        publishPending = false
        lastTrust = null
        try {
            while (true) {
                deferredScenes = 0
                val outcome = try {
                    round(chatKey)
                } catch (e: Exception) {
                    logger.warn("Period formation failed chat={}: {}", chatKey, e.toString(), e)
                    return Outcome.ERROR
                }
                rounds++
                val deferred = deferredScenes
                if (deferred == 0 || (outcome != Outcome.FORMED && outcome != Outcome.PLACED)) return outcome
                if (!complete && (rounds >= maxRoundsPerTrigger || nowMillis() - started >= triggerBudgetMillis)) {
                    logger.info(
                        "Period formation of chat={} stops after {} round(s) in {} s; {} scene(s) wait for the next trigger",
                        chatKey, rounds, (nowMillis() - started) / 1000, deferred,
                    )
                    return outcome
                }
                logger.info("Period formation continues chat={}: {} scene(s) beyond the {}-scene input cap", chatKey, deferred, inputCap)
            }
        } finally {
            if (publishPending) publishCatalogue(chatKey)
        }
    }

    /** The catalogue the trigger's rounds deferred, published as committed now (no model call); failures only log. */
    private fun publishCatalogue(chatKey: String) {
        publishPending = false
        val trust = lastTrust ?: return
        runCatching { postFormation().publish(chatKey, trust) }
            .onFailure { logger.warn("Period catalogue publish after the rounds failed chat={}: {}", chatKey, it.toString(), it) }
    }

    // --- the round ------------------------------------------------------------------------------------------

    /** One scene of the chat: its row, its work state and its index in [SCENE_ORDER]. */
    private class ChatScene(val record: SceneRecord, val state: SceneWorkState?, val order: Int) {
        val id: Long get() = record.sceneId
        val ended: Boolean get() = ended(state)
        val open: Boolean get() = state == SceneWorkState.OPEN
    }

    /** The chat's scenes and period layer as read on the graph thread at the start of a round. */
    private class Snapshot(
        /** In [SCENE_ORDER]. */
        val scenes: List<ChatScene>,
        val periods: List<PeriodRecord>,
        val scenesOf: Map<Long, List<Long>>,
        val eras: List<EraRecord>,
        val aliases: Map<Long, List<String>>,
        val periodOfScene: Map<Long, Long>,
        val excluded: Set<Long>,
        val generation: Long,
        val provisionalId: Long?,
        /** scene id -> (hub narration, whether the hub covers only part of the scene). */
        val hubs: Map<Long, Pair<String, Boolean>>,
        val threads: List<ThreadRecord>,
        /** The scene pipeline's cursors of the chat's conversations, by conversation id. */
        val cursors: Map<String, SceneCursorRecord>,
        /** Some scene is EXTRACTING / NARRATING, or some conversation's segmentation failed and waits for a retry. */
        val busy: Boolean,
    ) {
        val byId: Map<Long, ChatScene> = scenes.associateBy { it.id }

        fun periodViews(): List<PeriodView> = periods.map { PeriodView(it, scenesOf[it.id].orEmpty()) }
    }

    /** Everything one round has read and decided before its model call; the write helpers build on it. */
    private inner class Round(
        val chatKey: String,
        val language: Language?,
        val snap: Snapshot,
        val plan: Plan,
        val views: Map<Long, PeriodSceneView>,
        val trust: SceneTrust.ChatTrust,
        /** Scenes excluded by this round (first seen without a person's message). */
        val excluded: List<Long>,
    ) {
        val exclusions = excluded.map { PeriodExclusionRecord(it, chatKey, EXCLUSION_REASON) }
        val lateMembers = plan.late.map { (scene, period) -> PeriodMemberRecord(scene.id, period) }

        /** The late placements a write that keeps the re-opened fallback periods may carry (a failure's counter write). */
        val lateMembersOutsideReopened = lateMembers.filter { it.sceneId !in plan.reopenedMembers }

        /** The language of the round's neutral names. */
        val nameLanguage: Language get() = language ?: fallbackLanguage

        fun view(modelCalls: Int) = PeriodRound(
            chatKey, language, snap.periodViews(), snap.eras, snap.generation, views, trust, modelCalls,
            catalogueDeferred = plan.deferred > 0,
        )
    }

    private fun round(chatKey: String): Outcome {
        val snap = graph.withGraph { g -> snapshot(g, chatKey) }
        if (snap.busy || !conversationsSettled(chatKey, snap)) return Outcome.NOT_QUIET
        if (snap.scenes.none { it.ended }) return Outcome.NOTHING_TO_DO
        val pending = snap.scenes.filter { it.ended && it.id !in snap.periodOfScene && it.id !in snap.excluded }
        if (pending.isEmpty()) return Outcome.NOTHING_TO_DO

        // A scene without a person's message never joins a period (marked the first time it is seen).
        val newExclusions = pending.filter { !hasPersonMessage(it.record) }
        if (newExclusions.isNotEmpty()) {
            logger.info("Period formation: scene(s) {} of chat={} hold no message of a person; excluded from periods", newExclusions.map { it.id }, chatKey)
        }
        val candidates = pending - newExclusions.toSet()

        val plan = classify(snap, candidates)
        deferredScenes = plan.deferred
        val chatTrust = trust.refresh(chatKey)
        if (chatTrust.failed) {
            logger.warn("Period formation skipped chat={}: the scene trust could not be read (retried at the next trigger)", chatKey)
            return Outcome.TRUST_UNREADABLE
        }
        val r = Round(
            chatKey = chatKey,
            language = runCatching { language(chatKey) }.getOrNull(),
            snap = snap,
            plan = plan,
            views = snap.scenes.associate { it.id to view(it, snap) },
            trust = chatTrust,
            excluded = newExclusions.map { it.id },
        )
        lastTrust = chatTrust // the last trust that could be read (a failed one would make the deferred publish skip)

        if (plan.input.isEmpty()) {
            if (r.exclusions.isEmpty() && r.lateMembers.isEmpty()) return Outcome.NOTHING_TO_DO
            val write = PeriodWrite(chatKey, members = r.lateMembers, exclusions = r.exclusions)
            return commit(r, write, r.view(modelCalls = 0), Outcome.PLACED).also { if (it == Outcome.PLACED) forgetHolds(chatKey) }
        }

        val firstInput = plan.input.first().id
        val failure = graph.withGraph { g -> g.periodFailure(chatKey, PeriodFailureKind.FORMATION, firstInput) }
        var failures = failure?.count ?: 0
        if (failures >= MAX_FAILURES) {
            // A counter already at the cap: its fallback never landed. The fallback is tried again without a call, over
            // the whole pending run (the cap is for model calls only). Failing that, the model gets one more call when the
            // input's scenes changed since it was held (a new prompt), or when HELD_RETRY_INTERVAL_MILLIS passed since it
            // was last asked (the failure row's time, which a restart keeps): the model's own variation is then the only
            // way out of a name collision that new scenes never resolve.
            fallback(r, modelCalls = 0)?.let { return it }
            val heldInput = held[holdKey(r)]
            val input = holdIds(r)
            val changed = heldInput != null && heldInput != input
            val lastCall = failure?.updatedAt?.toEpochMilli()
            val due = lastCall != null && nowMillis() - lastCall >= HELD_RETRY_INTERVAL_MILLIS
            if (!changed && !due) return hold(r, modelCalls = 0)
            logger.info(
                "Period formation input of chat={} from scene={} was held at the failure cap; {}: one more model call",
                chatKey, firstInput,
                if (changed) "its scenes changed (${heldInput.size} -> ${input.size})" else "the model was last asked ${(nowMillis() - lastCall!!) / 3_600_000} h ago",
            )
            failures = MAX_FAILURES - 1
        }
        val prompt = renderInput(r)
        val reply = try {
            model.complete(MemoryTask.PERIODS, SYSTEM_PROMPT, prompt.text)
        } catch (e: Exception) {
            if (e.isMemoryModelShutdown() || e.isMemoryModelInterrupted()) {
                logger.info("Period formation cut off chat={} (not counted)", chatKey)
                return Outcome.SHUTDOWN
            }
            return failed(r, failures, "model unavailable: ${e.message}")
        }
        val entries = when (val parsed = parse(reply, plan.input.size, plan.current != null)) {
            is Parsed.Unparseable -> {
                recordDeadLetter(chatKey, firstInput, parsed.reason, prompt.text, reply)
                return failed(r, failures, parsed.reason)
            }
            is Parsed.Ok -> parsed.entries
        }
        validate(entries, plan, snap)?.let { reason ->
            recordDeadLetter(chatKey, firstInput, reason, prompt.text, reply)
            return failed(r, failures, reason)
        }

        val ids = graph.withGraph { g -> entries.map { e -> if (e.continues) null else g.allocatePeriodId() } }
        val write = formationWrite(r, entries, ids, prompt)
        return try {
            commit(r, write, r.view(modelCalls = 1), Outcome.FORMED).also {
                if (it == Outcome.FORMED) {
                    logFormed(chatKey, write, plan)
                    forgetHolds(chatKey)
                }
            }
        } catch (e: IllegalArgumentException) {
            // The index refused what validation let through: the reply was not usable after all.
            val reason = "refused by the period index: ${e.message}"
            recordDeadLetter(chatKey, firstInput, reason, prompt.text, reply)
            failed(r, failures, reason)
        }
    }

    private fun snapshot(g: LatticeGraph, chatKey: String): Snapshot {
        val records = g.scenes(chatKey).sortedWith(SCENE_ORDER)
        val scenes = records.mapIndexed { i, s -> ChatScene(s, g.sceneWork(s.sceneId)?.state, i) }
        val periods = g.periods(chatKey)
        val scenesOf = periods.associate { it.id to g.periodScenes(it.id) }
        val cursors = g.sceneCursors().filter { it.chatKey == chatKey }.associateBy { it.conversationId }
        return Snapshot(
            scenes = scenes,
            periods = periods,
            scenesOf = scenesOf,
            eras = g.eras(chatKey),
            aliases = periods.associate { p -> p.id to g.periodAliases(p.id).map { it.alias } },
            periodOfScene = scenesOf.flatMap { (p, ss) -> ss.map { it to p } }.toMap(),
            excluded = g.periodExclusions(chatKey).mapTo(HashSet()) { it.sceneId },
            generation = g.periodGeneration(chatKey),
            provisionalId = g.provisionalPeriod(chatKey)?.id,
            hubs = records.mapNotNull { s ->
                val hub = s.hubEngramId?.let(g::engram) ?: return@mapNotNull null
                s.sceneId to (hub.content to s.inProgress)
            }.toMap(),
            threads = g.threads(chatKey),
            cursors = cursors,
            busy = busy(g, chatKey),
        )
    }

    /**
     * Every sealed conversation of the chat has been segmented to its end by the scene pipeline: one that has not still
     * holds said messages that will become scenes, possibly before the ones a round would form now. Judged by the
     * messages past the conversation's cursor, not by its `finished` flag, which the pipeline sets only after
     * extraction — after narration may already have drained. A source that cannot be read counts as not settled (the
     * next trigger asks again).
     */
    private fun conversationsSettled(chatKey: String, snap: Snapshot): Boolean = try {
        source.conversations(chatKey).none { info ->
            val cursor = snap.cursors[info.id]
            val from = cursor?.segmentedThrough?.plus(1) ?: Long.MIN_VALUE
            info.sealed && cursor?.finished != true && (info.lastPosition == null || from <= info.lastPosition) &&
                source.messages(info.id, from).any { it.said }
        }
    } catch (e: Exception) {
        logger.warn("Period formation: the conversations of chat={} are unreadable; waiting: {}", chatKey, e.toString())
        false
    }

    private fun view(scene: ChatScene, snap: Snapshot): PeriodSceneView {
        val record = scene.record
        val hub = snap.hubs[scene.id]
        return PeriodSceneView(
            sceneId = scene.id,
            conversationId = record.conversationId,
            firstMessageId = record.firstMessageId,
            lastMessageId = record.lastMessageId,
            order = scene.order,
            state = scene.state,
            title = record.title,
            gist = hub?.first,
            gistPartial = hub?.second == true,
            dateFrom = record.dateFrom,
            dateTo = record.dateTo ?: record.dateFrom,
        )
    }

    /** A message a person wrote: a USER message with text — not the host's own text, not a tool result. */
    private fun hasPersonMessage(scene: SceneRecord): Boolean =
        source.messages(scene.conversationId, scene.firstMessageId, scene.lastMessageId)
            .any { it.role == ConversationMessage.Role.USER && it.text.isNotBlank() }

    /** Where the pending scenes go: deterministic placements, the formation input, re-opened fallback periods. */
    private class Plan(
        /** late scene -> the period it joins. */
        val late: List<Pair<ChatScene, Long>>,
        /** Every pending scene a formation would take, in scene order (up to an OPEN scene); the no-call fallback's pool. */
        val run: List<ChatScene>,
        /** The formation input: the first [run] scenes up to the input cap. */
        val input: List<ChatScene>,
        /** The provisional period the model may continue (null when there is none or it is re-opened). */
        val current: PeriodRecord?,
        /** Its member scene ids (in scene order). */
        val currentScenes: List<Long>,
        /** Trailing fallback periods re-opened into the input (deleted by the write). */
        val reopened: List<PeriodRecord>,
        /** Every other period of the chat that stays, in order. */
        val kept: List<PeriodRecord>,
    ) {
        /** Scenes of the run left out of the input by the cap, for a later round. */
        val deferred: Int get() = run.size - input.size

        /** Scene -> the period it is in now (the re-opened ones), for the commit re-check. */
        val reopenedMembers = HashMap<Long, Long>()
    }

    private fun classify(snap: Snapshot, candidates: List<ChatScene>): Plan {
        fun order(sceneId: Long): Int? = snap.byId[sceneId]?.order

        /** [pool] scenes after scene order [after], in order, stopping before the chat's first OPEN scene past it. */
        fun inputRun(pool: List<ChatScene>, after: Int): List<ChatScene> {
            val open = snap.scenes.firstOrNull { it.open && it.order > after }?.order
            return pool.filter { it.order > after && (open == null || it.order < open) }.sortedBy { it.order }
        }

        /** Late placements of [pool] against [periods], and the formation input after them. */
        fun split(periods: List<PeriodRecord>, pool: List<ChatScene>): Pair<List<Pair<ChatScene, Long>>, List<ChatScene>> {
            val spans = periods.mapNotNull { p ->
                val members = snap.scenesOf[p.id].orEmpty().mapNotNull(::order)
                if (members.isEmpty()) null else Triple(p.id, members.min(), members.max())
            }
            if (spans.isEmpty()) return emptyList<Pair<ChatScene, Long>>() to inputRun(pool, Int.MIN_VALUE)
            val lastEnd = spans.maxOf { it.third }
            val late = pool.filter { it.order <= lastEnd }.map { s ->
                val o = s.order
                val target = spans.firstOrNull { o in it.second..it.third }?.first // the period containing it
                    ?: spans.lastOrNull { it.third < o }?.first // else the one before it
                    ?: spans.first().first // earlier than every period: the first one (its start moves)
                s to target
            }
            return late to inputRun(pool, lastEnd)
        }

        val all = split(snap.periods, candidates)
        // Trailing fallback periods are re-cut only together with new scenes (never a paid call without new input), and
        // only as many whole ones, newest first, as fit the input cap beside ALL the new scenes — none while the new
        // scenes alone fill it (with a failing model, re-opening beside a backlog would advance one scene per three
        // failed calls). An older one that does not fit keeps its fallback name.
        val tail = ArrayDeque<PeriodRecord>()
        var room = inputCap - all.second.size
        for (p in snap.periods.takeLastWhile { it.needsRename }.asReversed()) {
            val size = snap.scenesOf[p.id].orEmpty().size
            if (size > room) break
            room -= size
            tail.addFirst(p)
        }
        fun withoutReopening(): Plan {
            val current = snap.periods.lastOrNull()?.takeIf { it.state == PeriodState.PROVISIONAL }
            return Plan(all.first, all.second, all.second.take(inputCap), current, current?.let { snap.scenesOf[it.id] }.orEmpty(), emptyList(), snap.periods)
        }
        if (all.second.isEmpty() || tail.isEmpty()) return withoutReopening()
        val kept = snap.periods.dropLast(tail.size)
        val reopenedScenes = tail.flatMap { p -> snap.scenesOf[p.id].orEmpty() }.mapNotNull(snap.byId::get)
        val (late, run) = split(kept, (candidates + reopenedScenes).sortedBy { it.order })
        val capped = run.take(inputCap)
        // Every re-opened scene must land somewhere: late scenes interleaved with them could push one past the cap (or an
        // OPEN scene stop the run before it), and its period would be deleted with nothing to take the scene in.
        val placed = late.mapTo(HashSet()) { it.first.id }.apply { capped.forEach { add(it.id) } }
        if (reopenedScenes.any { it.id !in placed }) return withoutReopening()
        val current = kept.lastOrNull()?.takeIf { it.state == PeriodState.PROVISIONAL }
        return Plan(late, run, capped, current, current?.let { snap.scenesOf[it.id] }.orEmpty(), tail.toList(), kept).also { plan ->
            tail.forEach { p -> snap.scenesOf[p.id].orEmpty().forEach { plan.reopenedMembers[it] = p.id } }
        }
    }

    // --- model input --------------------------------------------------------------------------------------

    /** The rendered input plus which scenes it showed in full (their content, not only their dates). */
    private class Prompt(val text: String, val shownInFull: Set<Long>)

    private fun renderInput(r: Round): Prompt {
        val snap = r.snap
        val plan = r.plan
        val views = r.views
        val chatTrust = r.trust
        val shown = HashSet<Long>()
        // A thread an untrusted scene opened never reaches the call (nor its events).
        val trustedThreads = snap.threads.filter { t -> t.openedSceneId != null && chatTrust.trusted(t.openedSceneId) }
        val opened = trustedThreads.groupBy { it.openedSceneId!! }
        val closed = trustedThreads.filter { it.state == ThreadState.CLOSED && it.closedSceneId != null }.groupBy { it.closedSceneId!! }

        fun threadName(t: ThreadRecord): String =
            PeriodFields.oneLine((t.name?.takeIf { it.isNotBlank() } ?: t.description).take(RecallFormatter.THREAD_NAME_CHARS))

        val text = buildString {
            // A name its naming scenes no longer vouch for is not shown; uniqueness is still checked locally.
            val used = plan.kept.filter { it.id != plan.current?.id && shownName(it, snap, chatTrust) }
                .flatMap { p -> listOf(p.name) + snap.aliases[p.id].orEmpty() }
            PeriodFields.appendInputHeader(this, r.language, selfAliases, userName, counterpartFallback = "as named in the scenes", used)
            plan.current?.let { current ->
                append('\n')
                append("## Current period\n")
                if (shownName(current, snap, chatTrust)) {
                    append("Name: 「").append(PeriodFields.normalize(current.name)).append("」\n")
                    val currentAliases = snap.aliases[current.id].orEmpty().map(PeriodFields::normalize).filter { it.isNotEmpty() }
                    if (currentAliases.isNotEmpty()) append("Earlier names: ").append(currentAliases.joinToString("、") { "「$it」" }).append('\n')
                    current.gist?.let(PeriodFields::oneLine)?.takeIf { it.isNotEmpty() }?.let { append("Summary: ").append(it).append('\n') }
                } else {
                    append("Name: ").append(UNTRUSTED_NAME_MARK).append('\n')
                }
                val currentScenes = plan.currentScenes.mapNotNull(views::get)
                PeriodFields.dateRange(currentScenes.firstOrNull()?.dateFrom, currentScenes.lastOrNull()?.dateTo)?.let { append("Dates: ").append(it).append('\n') }
                val latest = currentScenes.takeLast(CURRENT_SCENES_SHOWN)
                append(if (latest.size < currentScenes.size) "Its latest scenes (fixed):\n" else "Its scenes (fixed):\n")
                for (s in latest) {
                    append("- ").append(PeriodFields.dateRange(s.dateFrom, s.dateTo) ?: "(undated)").append(' ')
                    if (chatTrust.trusted(s.sceneId)) {
                        append("「").append(PeriodFields.oneLine(s.title).ifEmpty { "(untitled)" }).append("」")
                        shown += s.sceneId
                    } else {
                        append(UNTRUSTED_MARK)
                    }
                    append('\n')
                }
            }
            append('\n')
            append("## New scenes\n")
            plan.input.forEachIndexed { i, scene ->
                val s = views.getValue(scene.id)
                append('[').append(i + 1).append("] ").append(PeriodFields.dateRange(s.dateFrom, s.dateTo) ?: "(undated)")
                if (!chatTrust.trusted(s.sceneId)) {
                    append(' ').append(UNTRUSTED_MARK).append('\n')
                    return@forEachIndexed
                }
                shown += s.sceneId
                append('\n')
                append("Title: ").append(PeriodFields.oneLine(s.title).ifEmpty { "(untitled)" }).append('\n')
                val gist = s.gist?.let(PeriodFields::oneLine)?.takeIf { it.isNotEmpty() }
                append("Summary: ").append(gist ?: "(none)")
                if (gist != null && s.gistPartial) append(" (covers only part of the scene)")
                append('\n')
                val events = buildList {
                    opened[s.sceneId].orEmpty().forEach { add("opened 「${threadName(it)}」") }
                    closed[s.sceneId].orEmpty().forEach { add("settled 「${threadName(it)}」") }
                }
                if (events.isNotEmpty()) append("Threads: ").append(events.joinToString("; ")).append('\n')
            }
        }
        return Prompt(text, shown)
    }

    /** Whether [period]'s name and gist may be shown (see [periodNameShown]). */
    private fun shownName(period: PeriodRecord, snap: Snapshot, trust: SceneTrust.ChatTrust): Boolean =
        periodNameShown(period.namingSceneIds, snap.scenesOf[period.id].orEmpty(), trust)

    // --- reply ------------------------------------------------------------------------------------------------

    /** One period of the reply: new scenes [first]..[last] (1-based), [continues] = it continues the current period. */
    internal data class Entry(val continues: Boolean, val first: Int, val last: Int, val name: String, val gist: String?)

    internal sealed interface Parsed {
        data class Ok(val entries: List<Entry>) : Parsed

        data class Unparseable(val reason: String) : Parsed
    }

    /**
     * Read the reply: `{"periods": [{"continues_current": true?, "first": 1, "last": 3, "name": "…", "gist": "…"}]}`
     * (lenient on key spellings and on a missing `first`, which follows the previous period). Names and gists are
     * normalized ([PeriodFields.normalize]). Structure is checked by [validate].
     */
    internal fun parse(raw: String, inputSize: Int, hasCurrent: Boolean): Parsed {
        val root = NativeMemoryExtractor.parseLenientObject(raw) ?: return Parsed.Unparseable("reply was not parseable JSON")
        val list = (root["periods"] as? JsonArray) ?: return Parsed.Unparseable("reply has no \"periods\" array")
        val entries = ArrayList<Entry>()
        for (element in list) {
            val o = element as? JsonObject ?: return Parsed.Unparseable("a period is not an object")
            val continues = hasCurrent && (bool(o["continues_current"] ?: o["continues"] ?: o["continue"]) == true)
            val span = (o["scenes"] as? JsonArray)?.mapNotNull { int(it) }
            val last = int(o["last"] ?: o["to"] ?: o["end"]) ?: span?.maxOrNull()
                ?: return Parsed.Unparseable("a period has no last scene")
            val first = int(o["first"] ?: o["from"] ?: o["start"]) ?: span?.minOrNull() ?: ((entries.lastOrNull()?.last ?: 0) + 1)
            val name = string(o["name"])?.let(PeriodFields::normalize).orEmpty()
            val gist = string(o["gist"] ?: o["summary"])?.let(PeriodFields::normalize)?.takeIf { it.isNotEmpty() }
            entries += Entry(continues, first, last, name, gist)
        }
        if (entries.isEmpty() && inputSize > 0) return Parsed.Unparseable("reply lists no period")
        return Parsed.Ok(entries)
    }

    /** Null when [entries] are a valid formation of [plan]'s input; else why not (a counted failure). */
    private fun validate(entries: List<Entry>, plan: Plan, snap: Snapshot): String? {
        val n = plan.input.size
        var expected = 1
        for ((i, e) in entries.withIndex()) {
            if (e.continues && i > 0) return "only the first period may continue the current one (a split of the provisional period)"
            if (e.first != expected) return "periods are not contiguous: period ${i + 1} starts at ${e.first}, expected $expected"
            if (e.last < e.first || e.last > n) return "period ${i + 1} ends at ${e.last} (scenes 1..$n, starting at ${e.first})"
            expected = e.last + 1
        }
        if (expected != n + 1) return "periods cover scenes 1..${expected - 1}, not 1..$n"
        // Names: present, within the caps, not ordinal, unique among themselves and in the chat.
        val continued = if (entries.first().continues) plan.current else null
        val taken = HashMap<String, String>() // key -> whose
        for (p in plan.kept) {
            if (p.id == continued?.id) continue
            (listOf(p.name) + snap.aliases[p.id].orEmpty()).forEach { taken[periodNameKey(it)] = p.name }
        }
        if (continued != null) {
            val renamed = periodNameKey(entries.first().name) != periodNameKey(continued.name)
            // Its old name stays with it (as an alias when renamed): no other period may take it.
            if (renamed) taken[periodNameKey(continued.name)] = continued.name
        }
        val ownKeys = continued?.let { c -> (listOf(c.name) + snap.aliases[c.id].orEmpty()).map(::periodNameKey).toSet() }.orEmpty()
        val seen = HashSet<String>()
        for ((i, e) in entries.withIndex()) {
            PeriodFields.nameProblem(e.name)?.let { return "period ${i + 1}: $it" }
            if (e.gist != null && e.gist.codePointCount(0, e.gist.length) > PeriodFields.MAX_GIST_CODE_POINTS) {
                return "period ${i + 1}: gist longer than ${PeriodFields.MAX_GIST_CODE_POINTS} code points"
            }
            val key = periodNameKey(e.name)
            if (!seen.add(key)) return "period ${i + 1}: the name 「${e.name}」 is used twice"
            val owner = taken[key]
            val isOwn = e.continues && key in ownKeys
            if (owner != null && !isOwn) return "period ${i + 1}: the name 「${e.name}」 is already used by 「$owner」"
            if (!e.continues && key in ownKeys) return "period ${i + 1}: the name 「${e.name}」 belongs to the current period"
        }
        return null
    }

    private fun formationWrite(r: Round, entries: List<Entry>, ids: List<Long?>, prompt: Prompt): PeriodWrite {
        val plan = r.plan
        val periods = ArrayList<PeriodRecord>()
        val members = ArrayList<PeriodMemberRecord>(r.lateMembers)
        val aliases = ArrayList<PeriodAliasRecord>()
        for ((i, e) in entries.withIndex()) {
            val scenes = plan.input.subList(e.first - 1, e.last).map { it.id }
            // Naming scenes: the trusted scenes this call saw in full.
            val naming = scenes.filterTo(LinkedHashSet()) { it in prompt.shownInFull && r.trust.trusted(it) }
            val state = if (i == entries.lastIndex) PeriodState.PROVISIONAL else PeriodState.CLOSED
            if (e.continues) {
                val current = plan.current!!
                val shownOfCurrent = plan.currentScenes.filter { it in prompt.shownInFull && r.trust.trusted(it) }
                val renamed = periodNameKey(e.name) != periodNameKey(current.name)
                periods += current.copy(
                    name = e.name,
                    gist = e.gist ?: current.gist,
                    state = state,
                    needsRename = false,
                    // A new name comes from this call alone; a kept one still from the call that gave it.
                    namingSceneIds = if (renamed) (shownOfCurrent + naming).toSet() else current.namingSceneIds + shownOfCurrent + naming,
                )
                if (renamed) aliases += PeriodAliasRecord(r.chatKey, current.name, current.id)
                scenes.forEach { members += PeriodMemberRecord(it, current.id) }
            } else {
                val id = ids[i]!!
                periods += PeriodRecord(id = id, chatKey = r.chatKey, name = e.name, gist = e.gist, state = state, namingSceneIds = naming)
                scenes.forEach { members += PeriodMemberRecord(it, id) }
            }
        }
        // The newest period is the only provisional one: any other one closes.
        if (!entries.first().continues) {
            plan.kept.filter { it.state == PeriodState.PROVISIONAL }.forEach { periods += it.copy(state = PeriodState.CLOSED) }
        }
        return PeriodWrite(
            chatKey = r.chatKey,
            deletePeriodIds = plan.reopened.mapTo(LinkedHashSet()) { it.id },
            periods = periods,
            members = members,
            periodAliases = aliases,
            exclusions = r.exclusions,
            failures = listOf(PeriodFailureRecord(r.chatKey, PeriodFailureKind.FORMATION, plan.input.first().id, 0)),
        )
    }

    // --- failure and fallback ---------------------------------------------------------------------------------

    /**
     * A failed formation call ([failuresBefore] = the persisted count before it): below [MAX_FAILURES] the counter moves
     * (with whatever this round placed deterministically); at it, the fallback groups are written instead — or, when no
     * free name can be found, the counter is held at the cap ([hold]).
     */
    private fun failed(r: Round, failuresBefore: Int, reason: String): Outcome {
        val firstInput = r.plan.input.first().id
        val count = failuresBefore + 1
        logger.warn(
            "Period formation failed ({} of {}) chat={} input={} scene(s) from scene={}: {}",
            count, MAX_FAILURES, r.chatKey, r.plan.input.size, firstInput, reason,
        )
        if (count < MAX_FAILURES) return commitCounter(r, count, modelCalls = 1)
        return fallback(r, modelCalls = 1) ?: hold(r, modelCalls = 1)
    }

    /**
     * The fallback groups ([fallbackWrite]) for an input whose counter reached [MAX_FAILURES]: over the model's input
     * first, else over the whole pending run — scenes past the cap (another day) can free a name. Null when no fallback
     * landed: neither finds free names, or the index refused the write.
     */
    private fun fallback(r: Round, modelCalls: Int): Outcome? {
        val write = listOf(r.plan.input, r.plan.run).distinct().firstNotNullOfOrNull { fallbackWrite(r, it) } ?: return null
        val outcome = try {
            commit(r, write, r.view(modelCalls), Outcome.FALLBACK)
        } catch (e: IllegalArgumentException) {
            logger.warn("Period formation fallback refused by the period index chat={}: {}", r.chatKey, e.message)
            return null
        }
        if (outcome == Outcome.FALLBACK) {
            forgetHolds(r.chatKey)
            logger.warn(
                "Period formation fell back to date groups after {} failures chat={}: {}",
                MAX_FAILURES, r.chatKey, write.periods.filter { p -> p.needsRename }.joinToString(", ") { p -> "「${p.name}」" },
            )
        }
        return outcome
    }

    private fun holdKey(r: Round): String = r.chatKey + "|" + r.plan.input.first().id

    /** The input's scene ids as a hold compares them: as a set (sorted), so a re-ordering is no change. */
    private fun holdIds(r: Round): List<Long> = r.plan.input.map { it.id }.sorted()

    /** A formation or fallback landed for [chatKey]: whatever was held for it is placed now. */
    private fun forgetHolds(chatKey: String) {
        held.keys.removeAll { it.startsWith("$chatKey|") }
        heldWarnedAt.keys.removeAll { it.startsWith("$chatKey|") }
    }

    /**
     * No fallback landed (no free name, or a write the index refused): the counter is held at the cap (with this round's
     * deterministic placements), so the model is not asked about this input again until its scenes change or
     * [HELD_RETRY_INTERVAL_MILLIS] passes (then once, see [round]). The counter row is rewritten — its time moved — only
     * by a round that asked the model. Warned when first held and again every [STALL_WARN_INTERVAL_MILLIS].
     */
    private fun hold(r: Round, modelCalls: Int): Outcome {
        val key = holdKey(r)
        held[key] = holdIds(r)
        val now = nowMillis()
        val warnedAt = heldWarnedAt[key]
        if (warnedAt == null || now - warnedAt >= STALL_WARN_INTERVAL_MILLIS) {
            heldWarnedAt[key] = now
            logger.warn(
                "Period formation fallback did not land chat={} input={} scene(s) from scene={} ({} pending): nothing formed; " +
                    "held at the failure cap — one more model call once the input's scenes change, or every 6 h",
                r.chatKey, r.plan.input.size, r.plan.input.first().id, r.plan.run.size,
            )
        } else {
            logger.debug("Period formation input of chat={} from scene={} still held", r.chatKey, r.plan.input.first().id)
        }
        return commitCounter(r, MAX_FAILURES, modelCalls, writeCounter = modelCalls > 0)
    }

    /**
     * A failure's write: the counter at [count] stamped with this round's time (when [writeCounter]), with this round's
     * exclusions and the late placements that leave the re-opened fallback periods alone. Should the index still refuse
     * the placements, the counter is committed alone — a failure is never lost to them.
     */
    private fun commitCounter(r: Round, count: Int, modelCalls: Int, writeCounter: Boolean = true): Outcome {
        val counter = if (!writeCounter) emptyList() else listOf(
            PeriodFailureRecord(r.chatKey, PeriodFailureKind.FORMATION, r.plan.input.first().id, count, Instant.ofEpochMilli(nowMillis())),
        )
        val write = PeriodWrite(r.chatKey, members = r.lateMembersOutsideReopened, exclusions = r.exclusions, failures = counter)
        if (write.members.isEmpty() && write.exclusions.isEmpty() && write.failures.isEmpty()) return Outcome.FAILED
        return try {
            commit(r, write, r.view(modelCalls), Outcome.FAILED)
        } catch (e: IllegalArgumentException) {
            logger.warn("Period formation placements refused chat={} (the failure is counted alone): {}", r.chatKey, e.message)
            if (counter.isEmpty()) Outcome.FAILED else commit(r, PeriodWrite(r.chatKey, failures = counter), r.view(modelCalls), Outcome.FAILED)
        }
    }

    /**
     * The deterministic fallback over [scenes] (the model's input, or the whole pending run): grouped by date gaps
     * ([fallbackGroups]), each group named after its main TRUSTED scene (the longest), else a neutral date name (then
     * that name continued); flagged needs-rename. A group none of whose names is free joins the named group before it
     * (the first group: the one after it); when that group was named after its dates it is renamed for the joined range
     * — a name never states dates it does not cover — and if no such name is free the joined group moves on to the next
     * one. Null when no set of free names covers every scene.
     */
    private fun fallbackWrite(r: Round, scenes: List<ChatScene>): PeriodWrite? {
        val plan = r.plan
        val taken = HashSet<String>()
        for (p in plan.kept) (listOf(p.name) + r.snap.aliases[p.id].orEmpty()).forEach { taken += periodNameKey(it) }

        /** A named group: its scenes, its name, and the trusted scene it is named after (null = named after its dates). */
        class Named(val scenes: List<PeriodSceneView>, val name: String, val namingScene: Long?)

        fun nameOf(group: List<PeriodSceneView>): Named? {
            val main = group.filter { r.trust.trusted(it.sceneId) }
                .maxWithOrNull(compareBy<PeriodSceneView> { it.lastMessageId - it.firstMessageId }.thenByDescending { it.order })
            val candidates = buildList {
                main?.let { m -> PeriodFields.normalize(m.title).takeIf { PeriodFields.nameProblem(it) == null }?.let { add(it to m.sceneId) } }
                val dates = PeriodLabels.neutralDateName(group.first().dateFrom, group.last().dateTo, r.nameLanguage)
                add(dates to null)
                continuedSuffixes(r.nameLanguage).forEach { add(dates + it to null) }
            }
            return candidates.firstOrNull { (name, _) -> periodNameKey(name) !in taken }?.let { Named(group, it.first, it.second) }
        }

        val named = ArrayList<Named>()
        fun add(n: Named) {
            taken += periodNameKey(n.name)
            named += n
        }

        /** [group] joined onto the last named group (a title stays, a date name is recomputed); false when it cannot. */
        fun joinLast(group: List<PeriodSceneView>): Boolean {
            val last = named.removeAt(named.lastIndex)
            taken -= periodNameKey(last.name)
            val joined = last.scenes + group
            val renamed = if (last.namingScene != null) Named(joined, last.name, last.namingScene) else nameOf(joined)
            add(renamed ?: last)
            return renamed != null
        }

        var carried = emptyList<PeriodSceneView>() // groups without a free name so far, joining the next one
        for (next in fallbackGroups(scenes.map { r.views.getValue(it.id) })) {
            val group = carried + next
            val own = nameOf(group)
            carried = when {
                own != null -> emptyList<PeriodSceneView>().also { add(own) }
                named.isNotEmpty() && joinLast(group) -> emptyList()
                else -> group
            }
        }
        // Left over at the end: join backwards, taking named groups into it until a free name covers it.
        while (carried.isNotEmpty()) {
            if (named.isEmpty()) return null
            if (joinLast(carried)) {
                carried = emptyList()
                break
            }
            val last = named.removeAt(named.lastIndex)
            taken -= periodNameKey(last.name)
            carried = last.scenes + carried
            nameOf(carried)?.let { add(it); carried = emptyList() }
        }
        val ids = graph.withGraph { g -> named.map { g.allocatePeriodId() } }
        val periods = ArrayList<PeriodRecord>()
        val members = ArrayList<PeriodMemberRecord>(r.lateMembers)
        for ((i, group) in named.withIndex()) {
            periods += PeriodRecord(
                id = ids[i],
                chatKey = r.chatKey,
                name = group.name,
                state = if (i == named.lastIndex) PeriodState.PROVISIONAL else PeriodState.CLOSED,
                needsRename = true,
                namingSceneIds = setOfNotNull(group.namingScene),
            )
            group.scenes.forEach { members += PeriodMemberRecord(it.sceneId, ids[i]) }
        }
        plan.kept.filter { it.state == PeriodState.PROVISIONAL }.forEach { periods += it.copy(state = PeriodState.CLOSED) }
        return PeriodWrite(
            chatKey = r.chatKey,
            deletePeriodIds = plan.reopened.mapTo(LinkedHashSet()) { it.id },
            periods = periods,
            members = members,
            exclusions = r.exclusions,
            // Reset: a later round with new scenes asks the model again (and re-cuts these groups).
            failures = listOf(PeriodFailureRecord(r.chatKey, PeriodFailureKind.FORMATION, plan.input.first().id, 0)),
        )
    }

    // --- commit -----------------------------------------------------------------------------------------------

    /**
     * Hand a structural [formed] write to [postFormation], then commit it after the re-check on the graph thread. A
     * conflict drops it uncounted and requeues the round (at most [MAX_REQUEUES] times per input set). Additions the
     * index refuses are dropped (logged) and the formation is committed alone. [success] is the outcome of a commit that
     * went through.
     */
    private fun commit(r: Round, formed: PeriodWrite, view: PeriodRound, success: Outcome): Outcome {
        val chatKey = r.chatKey
        val write = if (formed.structural) {
            runCatching { postFormation().extend(view, formed) }
                .onFailure { logger.warn("Period post-formation step failed chat={} (the formation is kept): {}", chatKey, it.toString(), it) }
                .getOrNull()
                ?.takeIf { it.chatKey == chatKey }
                ?: formed
        } else {
            formed
        }
        // The scenes this round places, and where each is now (null = no period; a re-opened fallback period).
        val placed = LinkedHashMap<Long, Long?>()
        formed.members.forEach { placed[it.sceneId] = r.plan.reopenedMembers[it.sceneId] }
        // The input and everything the write places (a fallback over the whole run reaches past the input).
        val watched = (r.plan.input + r.plan.late.map { it.first }).map { it.order } +
            formed.members.mapNotNull { r.views[it.sceneId]?.order } +
            r.excluded.mapNotNull { id -> r.views[id]?.order }
        val rangeIds = if (watched.isEmpty()) null else r.snap.scenes.subList(watched.min(), watched.max() + 1).map { it.id }
        var conflict: String? = null

        fun attempt(candidate: PeriodWrite): Long? = publisher.commit { g ->
            conflict = when {
                g.provisionalPeriod(chatKey)?.id != r.snap.provisionalId -> "the provisional period changed"
                g.periodGeneration(chatKey) != r.snap.generation -> "the period generation moved"
                placed.any { (scene, expected) -> g.periodOfScene(scene)?.id != expected } -> "a placed scene joined a period meanwhile"
                r.excluded.any { g.periodOfScene(it) != null || g.isExcludedFromPeriods(it) } -> "an excluded scene changed meanwhile"
                busy(g, chatKey) -> "the chat is no longer quiet"
                rangeIds != null && !sameSpan(g, chatKey, rangeIds) -> "the scenes between the first and the last input scene changed"
                else -> null
            }
            if (conflict == null) candidate else null
        }

        var committed = write
        val generation = try {
            attempt(write)
        } catch (e: IllegalArgumentException) {
            if (write === formed) throw e
            logger.warn("Period post-formation additions refused chat={} (committing the formation alone): {}", chatKey, e.message)
            committed = formed
            attempt(formed)
        }
        if (generation == null) {
            val key = chatKey + "|" + (r.plan.input.map { it.id }.ifEmpty { placed.keys.toList() }).joinToString(",")
            val count = requeues.merge(key, 1) { a, b -> a + b }!!
            if (count <= MAX_REQUEUES) {
                logger.info("Period formation result dropped chat={} ({}); requeued ({} of {})", chatKey, conflict, count, MAX_REQUEUES)
                runCatching { requeue(chatKey) }
                    .onFailure { logger.warn("Period formation requeue failed chat={}: {}", chatKey, it.toString()) }
            } else {
                logger.info("Period formation result dropped chat={} ({}); {} requeues used, waiting for the next trigger", chatKey, conflict, MAX_REQUEUES)
            }
            return Outcome.CONFLICT
        }
        requeues.keys.removeAll { it.startsWith("$chatKey|") }
        // The catalogue is published when the trigger's rounds end whenever a structural round left it unpublished: one
        // followed by another, or one whose snapshots were lost (its post-formation step failed or was refused).
        if (formed.structural) publishPending = publishPending || view.catalogueDeferred || committed === formed
        if (success == Outcome.PLACED) {
            logger.info(
                "Periods updated without a model call chat={} late={} excluded={} generation={}",
                chatKey, r.plan.late.map { (s, p) -> "${s.id}->$p" }, r.excluded, generation,
            )
        }
        return success
    }

    /** The chat's scenes from the first to the last of [rangeIds] (in [SCENE_ORDER]) are exactly [rangeIds]. */
    private fun sameSpan(g: LatticeGraph, chatKey: String, rangeIds: List<Long>): Boolean {
        val now = g.scenes(chatKey).sortedWith(SCENE_ORDER).map { it.sceneId }
        val from = now.indexOf(rangeIds.first())
        val to = now.indexOf(rangeIds.last())
        return from >= 0 && to >= from && now.subList(from, to + 1) == rangeIds
    }

    private fun logFormed(chatKey: String, write: PeriodWrite, plan: Plan) {
        val byPeriod = write.members.groupBy({ it.periodId }, { it.sceneId })
        logger.info(
            "Periods formed chat={} from {} scene(s){}: {}",
            chatKey, plan.input.size, if (plan.reopened.isEmpty()) "" else " (re-cutting ${plan.reopened.size} fallback period(s))",
            write.periods.joinToString("; ") { p ->
                "「${p.name}」${if (p.state == PeriodState.PROVISIONAL) " (provisional)" else ""} scenes=${byPeriod[p.id].orEmpty()}"
            },
        )
    }

    private fun recordDeadLetter(chatKey: String, firstSceneId: Long, reason: String, prompt: String, raw: String) {
        runCatching {
            deadLetter.record(
                DeadLetterEntry(
                    reason = "period formation chat=$chatKey: $reason",
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
        /** Consecutive failed formations of one input before the deterministic fallback. */
        const val MAX_FAILURES = 3

        /** Conflict requeues per input set before waiting for the next trigger. */
        const val MAX_REQUEUES = 3

        /** Scenes of the current period listed as context (its latest ones). */
        const val CURRENT_SCENES_SHOWN = 10

        /**
         * New scenes one formation call is offered at most; the rest wait for the next round of the same trigger, which
         * sees the provisional period this one left and may continue it. 24 scenes of 150–400 tokens each keep one call
         * short while still showing several periods' worth of turns at once.
         */
        const val FORMATION_MAX_SCENES = 24

        /** Rounds one trigger runs for one chat at most; the rest waits. */
        const val MAX_ROUNDS_PER_TRIGGER = 8

        /** No further round of a trigger starts once it has held the narration thread this long (10 minutes). */
        const val TRIGGER_BUDGET_MILLIS = 600_000L

        /** An input held at the failure cap (no free fallback name) is warned about again after this long (1 hour). */
        const val STALL_WARN_INTERVAL_MILLIS = 3_600_000L

        /**
         * While an input stays held at the failure cap with the same scenes, the model gets one more call at most this
         * often (6 hours), timed from its last call on the input (the failure row's time, so a restart does not buy a
         * call). The input and its dates may never change (every pending scene on one day, past the cap, with every
         * fallback name of that day taken); the model's own variation is then the only way out of the name collision.
         */
        const val HELD_RETRY_INTERVAL_MILLIS = 6 * 3_600_000L

        /** Fallback: a quiet stretch of at least this many days between two scenes starts a new group. */
        const val FALLBACK_GAP_DAYS = 2L

        /** Fallback: a group is closed at this many scenes (only where the day changes). */
        const val FALLBACK_MAX_SCENES = 8

        const val DEAD_LETTER_PHASE = "period-formation"
        const val EXCLUSION_REASON = "no_user_message"

        /** How an untrusted scene appears in the model input: its dates and this marker only. */
        const val UNTRUSTED_MARK = "(untrusted: content withheld)"

        /** The current period's name when a scene it was named from is untrusted now. */
        const val UNTRUSTED_NAME_MARK = "(withheld: named from a scene now untrusted — give it a new name if you continue it)"

        /** A scene has ended for the period layer: narrated or abandoned, or not written by the pipeline at all. */
        internal fun ended(state: SceneWorkState?): Boolean =
            state == null || state == SceneWorkState.MATERIALIZED || state == SceneWorkState.ABANDONED

        /** A scene of [chatKey] is being extracted or narrated, or a conversation's failed segmentation waits for a retry. */
        internal fun busy(g: LatticeGraph, chatKey: String): Boolean =
            g.scenes(chatKey).any { s ->
                val state = g.sceneWork(s.sceneId)?.state
                state == SceneWorkState.EXTRACTING || state == SceneWorkState.NARRATING
            } || g.sceneCursors().any { it.chatKey == chatKey && (it.segmentAttempts > 0 || it.fallbackThrough != null) }

        /** Appended to a neutral date name that is already taken (never a number: names are never numbered). */
        internal fun continuedSuffixes(language: Language): List<String> =
            if (RecallMarkers.isChinese(language)) listOf("（续）", "（再续）") else listOf(" (cont.)", " (cont. again)")

        // English scaffolding on purpose (as the extractor and the narrator): the names follow the language asked for in
        // the input, the instructions stay byte-stable.
        val SYSTEM_PROMPT = """
            You divide the scenes of one chat into PERIODS: the stretches of a shared past that people name when
            they look back on it ("the summer we planned the move", "the day we drove out to the lake"). You are the assistant
            of this chat; the scenes are your own memories of it, oldest first.
            Where a period ends:
            - Only where the conversation turns: a clear change in what the two of you are doing together, what you
              talk about, or how you relate to each other (e.g. from planning a move to talking about who you are
              to each other). Scenes on one theme, one activity or one stage of the relationship belong together,
              even across days; a new topic inside one continuing stretch is not a turn.
            - Think in chapters of the relationship, not in topics: moving from one subject to the next within the
              same kind of conversation (one debate or question after another, one book or idea after another) stays
              in the same period. A turn changes what the time together IS: its activity, its setting (everyday
              talk, or a shared imagined world) or the relationship itself.
            - A detour the conversation soon comes back from (a scene or two elsewhere, then back to the earlier
              theme) is part of the surrounding period, not a turn.
            - Never cut by the calendar or by counting scenes: a long pause is not a turn by itself, and a short
              pause does not rule a turn out.
            - A shared imagined journey or world (an imagined trip, a day lived together in imagination) is one
              stretch: never split it in the middle.
            - Prefer a few substantial periods over many small ones; a single scene is a period of its own only
              when it is a turn by itself.
            The current period:
            - "Current period", when shown, is the newest period so far and may still be growing; its scenes are
              fixed. When the first new scenes carry it on, your first period continues it
              ("continues_current": true) and covers only those new scenes; you may keep its name or give one that
              fits the whole stretch better. When the new scenes turn away from it, start a new period instead.
            Untrusted scenes:
            - A scene shown as "$UNTRUSTED_MARK" is shown by its dates only. Place it by its dates and its
              neighbours; never guess what happened in it and never name a period after it.
            Names and gists:
            - name: what people would call that stretch — its theme or a landmark of it, specific and noun-first.
              At most 16 characters in Chinese or Japanese; in other languages at most 6 words.
            - Never number periods or name them by their position ("Period 3", "Part II", "the first stretch",
              "1. …", in any language). Numbers that belong to the content (a date, "dinner for three") are fine.
            - Every name differs from the others and from every name under "Names already used".
            - gist: one sentence (two at most) on what that stretch was, written as your own memory — first person
              for you, the human counterpart by name (see "Who is who"). No dates.
              At most 100 characters in Chinese or Japanese; in other languages at most 30 words.
            - Write names and gists in the language given under "Language". Do not use 「」, 『』, the dot · or
              dashes (—, –, ～) in them: they are shown inside such marks.
            Output JSON only:
            {"periods": [{"continues_current": true, "last": 2, "name": "…", "gist": "…"}, {"first": 3, "last": 7, "name": "…", "gist": "…"}]}
            The periods are in order and cover every new scene exactly once, by the numbers under "New scenes": the
            first starts at scene 1, each next one starts right after the previous one's last scene, and the final
            one ends at the last new scene. Only the first may continue the current period; leave
            "continues_current" out otherwise.
        """.trimIndent()

        /**
         * The fallback groups: runs of [scenes] (in order), a new group starting after a quiet stretch of at least
         * [FALLBACK_GAP_DAYS] days, or — only where the local day changes — once a group holds [FALLBACK_MAX_SCENES]
         * scenes (so two groups never share a day and their date names stay distinct).
         */
        internal fun fallbackGroups(scenes: List<PeriodSceneView>): List<List<PeriodSceneView>> {
            val groups = ArrayList<MutableList<PeriodSceneView>>()
            for (s in scenes) {
                val group = groups.lastOrNull()
                val prev = group?.lastOrNull()
                val gap = if (prev == null) null else daysBetween(prev.dateTo ?: prev.dateFrom, s.dateFrom ?: s.dateTo)
                val newDay = gap != null && gap >= 1
                if (group == null || (gap != null && gap >= FALLBACK_GAP_DAYS) || (group.size >= FALLBACK_MAX_SCENES && newDay)) {
                    groups += mutableListOf(s)
                } else {
                    group += s
                }
            }
            return groups
        }

        private fun daysBetween(a: String?, b: String?): Long? {
            val from = a?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val to = b?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return ChronoUnit.DAYS.between(from, to)
        }

        private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun bool(e: JsonElement?): Boolean? {
            val p = e as? JsonPrimitive ?: return null
            return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase(Locale.ROOT)) {
                "true", "yes" -> true
                "false", "no" -> false
                else -> null
            }
        }

        /** A scene number: `3`, `"3"`, `"[3]"`. */
        private fun int(e: JsonElement?): Int? {
            val p = e as? JsonPrimitive ?: return null
            return p.contentOrNull?.trim()?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() }
        }
    }
}
