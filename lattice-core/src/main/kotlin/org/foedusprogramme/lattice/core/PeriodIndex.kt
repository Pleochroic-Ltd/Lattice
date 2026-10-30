package org.foedusprogramme.lattice.core

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * One write to the PERIODS layer of one chat ([chatKey]), committed by [LatticeGraph.applyPeriodWrite] as ONE store
 * batch (a formation result — periods, members, aliases, the provisional update, eras, failure counters, the
 * generation and the catalogue snapshots — lands whole or not at all). Applied in this order:
 *  - [reset]: drop every period-layer row of the chat first (the generation survives and is bumped);
 *  - [deletePeriodIds] / [deleteEraIds]: rows of this chat to drop (a period takes its members and aliases along,
 *    an era its aliases; its periods lose their era);
 *  - [eras] / [periods]: upserts (their date ranges are derived, see [PeriodRecord]);
 *  - [members]: new scene -> period rows (a scene already in that same period is a no-op; in another period, a
 *    refusal — move a scene only by deleting its period);
 *  - [periodAliases] / [eraAliases]: old names to add (one already on the same row is a no-op);
 *  - [exclusions]: scenes that never join a period;
 *  - [failures]: counters to set (count <= 0 clears one);
 *  - [catalogues]: snapshots to publish, stamped with the generation after this write, their hash and the time.
 * The generation is bumped when the write changes any structure (everything but [failures] and [catalogues]).
 */
data class PeriodWrite(
    val chatKey: String,
    val reset: Boolean = false,
    val deletePeriodIds: Set<Long> = emptySet(),
    val deleteEraIds: Set<Long> = emptySet(),
    val eras: List<EraRecord> = emptyList(),
    val periods: List<PeriodRecord> = emptyList(),
    val members: List<PeriodMemberRecord> = emptyList(),
    val periodAliases: List<PeriodAliasRecord> = emptyList(),
    val eraAliases: List<EraAliasRecord> = emptyList(),
    val exclusions: List<PeriodExclusionRecord> = emptyList(),
    val failures: List<PeriodFailureRecord> = emptyList(),
    val catalogues: List<CatalogueSnapshot> = emptyList(),
) {
    /** Changes a period, era, member, alias or exclusion (the chat's generation moves). */
    val structural: Boolean
        get() = reset || deletePeriodIds.isNotEmpty() || deleteEraIds.isNotEmpty() || eras.isNotEmpty() ||
            periods.isNotEmpty() || members.isNotEmpty() || periodAliases.isNotEmpty() || eraAliases.isNotEmpty() ||
            exclusions.isNotEmpty()
}

/**
 * The PERIODS side-store of [LatticeGraph]: the RAM image of the stored periods and eras, plus a scene trust cache
 * that is never persisted. Like the graph it is not thread-safe and is touched only on the graph's owner thread.
 *
 * Indexes (rebuilt per chat on every write): chat -> periods in chronological order (their first scene's first
 * message id — never scene ids or `seq`), scene -> period, period -> scenes (same order), name / alias ->
 * period, chat -> eras (by their first period), name / alias -> era.
 *
 * Writes ([apply]) are validated against the state they would produce — mirroring the database constraints, so
 * the in-memory store behaves the same — then handed to ONE [LatticeStore.batch]; the RAM image changes only after
 * that batch returned (committed). A refused or failed write leaves both untouched.
 */
internal class PeriodIndex(
    private val store: LatticeStore,
    /** The graph's id allocator: period and era ids come from the one sequence every id does. */
    private val ids: IdAllocator,
    /** The scene row of a scene id (the graph's scene index): its chat, first message id and dates. */
    private val scenes: (Long) -> SceneRecord?,
) {
    private data class FailureKey(val kind: PeriodFailureKind, val firstSceneId: Long)

    /** Everything of one chat, immutable; replaced as a whole after a committed write. */
    private class ChatState(
        val periods: Map<Long, PeriodRecord>,
        /** scene id -> period id. */
        val members: Map<Long, Long>,
        val eras: Map<Long, EraRecord>,
        /** alias key -> alias row, in insertion order. */
        val periodAliases: Map<String, PeriodAliasRecord>,
        val eraAliases: Map<String, EraAliasRecord>,
        val exclusions: Map<Long, PeriodExclusionRecord>,
        val failures: Map<FailureKey, PeriodFailureRecord>,
        /** language key -> snapshot. */
        val catalogues: Map<String, CatalogueSnapshot>,
        val generation: Long,
        firstMessageOf: (Long) -> Long,
    ) {
        /** period id -> member scene ids by first message id (then scene id). */
        val scenesByPeriod: Map<Long, List<Long>> = members.entries.groupBy({ it.value }, { it.key })
            .mapValues { (_, ids) -> ids.sortedWith(compareBy({ firstMessageOf(it) }, { it })) }
        val orderedPeriods: List<PeriodRecord> = periods.values.sortedWith(
            compareBy({ p -> scenesByPeriod[p.id]?.firstOrNull()?.let(firstMessageOf) ?: Long.MAX_VALUE }, { it.id }),
        )
        val orderedEras: List<EraRecord> = run {
            val rank = orderedPeriods.withIndex().associate { (i, p) -> p.id to i }
            eras.values.sortedWith(
                compareBy(
                    { e -> orderedPeriods.filter { it.eraId == e.id }.minOfOrNull { rank.getValue(it.id) } ?: Int.MAX_VALUE },
                    { it.id },
                ),
            )
        }
        val periodByNameKey: Map<String, Long> = periods.values.associate { nameKey(it.name) to it.id }
        val eraByNameKey: Map<String, Long> = eras.values.associate { nameKey(it.name) to it.id }

        val isEmpty: Boolean
            get() = periods.isEmpty() && eras.isEmpty() && exclusions.isEmpty() && failures.isEmpty() &&
                catalogues.isEmpty() && generation == 0L
    }

    private val chats = HashMap<String, ChatState>()
    private val chatOfPeriod = HashMap<Long, String>()
    private val chatOfEra = HashMap<Long, String>()
    private val periodIdOfScene = HashMap<Long, Long>()
    /** excluded scene id -> its chat. */
    private val excludedScenes = HashMap<Long, String>()
    /** scene id -> content trusted (filled by the trust check on the narration thread; RAM only). */
    private val trust = HashMap<Long, Boolean>()

    private fun firstMessageOf(sceneId: Long): Long = scenes(sceneId)?.firstMessageId ?: Long.MAX_VALUE

    /** Rehydrate from a store load (no write-through; the stored date ranges are taken as they are). */
    fun load(loaded: LatticeStore.Loaded) {
        val periodChat = loaded.periods.associate { it.id to it.chatKey }
        val eraChat = loaded.eras.associate { it.id to it.chatKey }
        val chatKeys = LinkedHashSet<String>().apply {
            loaded.periods.mapTo(this) { it.chatKey }
            loaded.eras.mapTo(this) { it.chatKey }
            loaded.periodExclusions.mapTo(this) { it.chatKey }
            loaded.periodFailures.mapTo(this) { it.chatKey }
            loaded.catalogueSnapshots.mapTo(this) { it.chatKey }
            addAll(loaded.periodGenerations.keys)
        }
        for (chat in chatKeys) {
            val state = ChatState(
                periods = loaded.periods.filter { it.chatKey == chat }.associateBy { it.id },
                members = loaded.periodMembers.filter { periodChat[it.periodId] == chat }.associate { it.sceneId to it.periodId },
                eras = loaded.eras.filter { it.chatKey == chat }.associateBy { it.id },
                periodAliases = loaded.periodAliases
                    .filter { it.chatKey == chat && periodChat[it.periodId] == chat }
                    .associateByTo(LinkedHashMap()) { nameKey(it.alias) },
                eraAliases = loaded.eraAliases
                    .filter { it.chatKey == chat && eraChat[it.eraId] == chat }
                    .associateByTo(LinkedHashMap()) { nameKey(it.alias) },
                exclusions = loaded.periodExclusions.filter { it.chatKey == chat }.associateBy { it.sceneId },
                failures = loaded.periodFailures.filter { it.chatKey == chat }.associateBy { FailureKey(it.kind, it.firstSceneId) },
                catalogues = loaded.catalogueSnapshots.filter { it.chatKey == chat }.associateBy { languageKey(it.language) },
                generation = loaded.periodGenerations[chat] ?: 0L,
                firstMessageOf = ::firstMessageOf,
            )
            install(chat, state)
        }
    }

    fun allocatePeriodId(): Long = ids.next()

    fun allocateEraId(): Long = ids.next()

    /**
     * Validate [write] against the state it would produce, persist it as ONE [LatticeStore.batch], and only then
     * replace the chat's RAM image. [now] stamps the catalogue snapshots. Returns the chat's generation after
     * the write. Throws [IllegalArgumentException] (nothing written) on a write that breaks an invariant.
     */
    fun apply(write: PeriodWrite, now: Instant): Long = applyStamped(stampFailures(write, now), now)

    /** A failure counter without its own time is stamped with the write's (in RAM and on disk alike). */
    private fun stampFailures(write: PeriodWrite, now: Instant): PeriodWrite =
        if (write.failures.none { it.updatedAt == null }) write
        else write.copy(failures = write.failures.map { if (it.updatedAt == null) it.copy(updatedAt = now) else it })

    private fun applyStamped(write: PeriodWrite, now: Instant): Long {
        val chat = write.chatKey
        require(chat.isNotBlank()) { "a period write needs a chat key" }
        val current = chats[chat]
        val next = resultOf(write, current, now)

        val written = next.catalogues.values.filter { c -> write.catalogues.any { languageKey(it.language) == c.language } }
        val newMembers = next.members.filter { (scene, period) -> write.reset || current?.members?.get(scene) != period }
        val newPeriodAliases = next.periodAliases.filter { (key, a) -> write.reset || current?.periodAliases?.get(key) != a }.values
        val newEraAliases = next.eraAliases.filter { (key, a) -> write.reset || current?.eraAliases?.get(key) != a }.values
        val newExclusions = next.exclusions.filter { (scene, x) -> write.reset || current?.exclusions?.get(scene) != x }.values
        val changedEras = next.eras.values.filter { write.reset || current?.eras?.get(it.id) != it }
        // Closed rows before the provisional one (at most one per chat at every step), updates before inserts.
        val changedPeriods = next.periods.values.filter { write.reset || current?.periods?.get(it.id) != it }
            .sortedWith(compareBy({ it.state == PeriodState.PROVISIONAL }, { current?.periods?.containsKey(it.id) != true }))

        store.batch { s ->
            if (write.reset) s.deletePeriodLayer(chat)
            write.deletePeriodIds.forEach(s::deletePeriod)
            write.deleteEraIds.forEach(s::deleteEra)
            changedEras.forEach(s::putEra)
            changedPeriods.forEach(s::putPeriod)
            newMembers.forEach { (scene, period) -> s.putPeriodMember(PeriodMemberRecord(scene, period)) }
            newPeriodAliases.forEach(s::putPeriodAlias)
            newEraAliases.forEach(s::putEraAlias)
            newExclusions.forEach(s::putPeriodExclusion)
            write.failures.forEach(s::putPeriodFailure)
            if (next.generation != (current?.generation ?: 0L)) s.putPeriodGeneration(chat, next.generation)
            written.forEach(s::putCatalogueSnapshot)
        }
        // Committed: now (and only now) the RAM image follows.
        install(chat, next)
        return next.generation
    }

    /** The chat state [write] would produce from [current], or [IllegalArgumentException] when it breaks an invariant. */
    private fun resultOf(write: PeriodWrite, current: ChatState?, now: Instant): ChatState {
        val chat = write.chatKey
        for (p in write.periods) {
            require(p.id > 0) { "period id ${p.id}: allocate one first" }
            require(p.chatKey == chat) { "period ${p.id} is of chat ${p.chatKey}, the write of $chat" }
            require(chatOfPeriod[p.id]?.let { it == chat } != false) { "period ${p.id} belongs to another chat" }
        }
        for (e in write.eras) {
            require(e.id > 0) { "era id ${e.id}: allocate one first" }
            require(e.chatKey == chat) { "era ${e.id} is of chat ${e.chatKey}, the write of $chat" }
            require(chatOfEra[e.id]?.let { it == chat } != false) { "era ${e.id} belongs to another chat" }
        }
        require(write.periods.map { it.id }.toSet().size == write.periods.size) { "a period is listed twice" }
        require(write.eras.map { it.id }.toSet().size == write.eras.size) { "an era is listed twice" }
        for (id in write.deletePeriodIds) {
            require(current?.periods?.containsKey(id) == true) { "period $id to delete is not a period of $chat" }
            require(write.periods.none { it.id == id }) { "period $id is both deleted and written" }
        }
        for (id in write.deleteEraIds) {
            require(current?.eras?.containsKey(id) == true) { "era $id to delete is not an era of $chat" }
            require(write.eras.none { it.id == id }) { "era $id is both deleted and written" }
        }

        val base = if (write.reset || current == null) null else current

        // Eras, then periods (a period of a deleted era loses it, as ON DELETE SET NULL does).
        val eras = LinkedHashMap<Long, EraRecord>()
        base?.eras?.values?.filter { it.id !in write.deleteEraIds }?.forEach { eras[it.id] = it }
        write.eras.forEach { eras[it.id] = it }
        val periods = LinkedHashMap<Long, PeriodRecord>()
        base?.periods?.values?.filter { it.id !in write.deletePeriodIds }?.forEach { p ->
            periods[p.id] = if (p.eraId != null && p.eraId !in eras) p.copy(eraId = null) else p
        }
        for (p in write.periods) {
            require(p.eraId == null || p.eraId in eras) { "period ${p.id} names unknown era ${p.eraId}" }
            periods[p.id] = p
        }

        // Members.
        val members = LinkedHashMap<Long, Long>()
        base?.members?.forEach { (scene, period) -> if (period in periods) members[scene] = period }
        val added = LinkedHashMap<Long, Long>()
        for (m in write.members) {
            require(m.periodId in periods) { "scene ${m.sceneId}: unknown period ${m.periodId}" }
            val scene = requireNotNull(scenes(m.sceneId)) { "scene ${m.sceneId} is not a known scene" }
            require(scene.chatKey == chat) { "scene ${m.sceneId} is of chat ${scene.chatKey}, the write of $chat" }
            val existing = members[m.sceneId] ?: added[m.sceneId]
            if (existing == m.periodId) continue
            require(existing == null) { "scene ${m.sceneId} already belongs to period $existing" }
            added[m.sceneId] = m.periodId
        }
        members.putAll(added)

        // Exclusions (never a member, never another chat's).
        val exclusions = LinkedHashMap<Long, PeriodExclusionRecord>()
        base?.exclusions?.forEach { (scene, x) -> exclusions[scene] = x }
        for (x in write.exclusions) {
            require(x.chatKey == chat) { "exclusion of scene ${x.sceneId} is of chat ${x.chatKey}, the write of $chat" }
            require(excludedScenes[x.sceneId]?.let { it == chat } != false) { "scene ${x.sceneId} is excluded in another chat" }
            scenes(x.sceneId)?.let { require(it.chatKey == chat) { "scene ${x.sceneId} is of chat ${it.chatKey}" } }
            require(x.sceneId !in members) { "scene ${x.sceneId} belongs to a period and cannot be excluded" }
            exclusions.putIfAbsent(x.sceneId, x)
        }
        for (scene in added.keys) require(scene !in exclusions) { "scene $scene is excluded from periods" }

        // Aliases (a deleted row's go with it; the same alias on the same row again is a no-op).
        val periodAliases = LinkedHashMap<String, PeriodAliasRecord>()
        base?.periodAliases?.forEach { (key, a) -> if (a.periodId in periods) periodAliases[key] = a }
        for (a in write.periodAliases) {
            require(a.chatKey == chat) { "alias ${a.alias} is of chat ${a.chatKey}, the write of $chat" }
            require(a.periodId in periods) { "alias ${a.alias}: unknown period ${a.periodId}" }
            val key = nameKey(a.alias)
            require(key.isNotEmpty()) { "alias '${a.alias}' is empty once normalized" }
            val owner = periodAliases[key]
            require(owner == null || owner.periodId == a.periodId) { "alias '${a.alias}' belongs to period ${owner?.periodId}" }
            periodAliases.putIfAbsent(key, a)
        }
        val eraAliases = LinkedHashMap<String, EraAliasRecord>()
        base?.eraAliases?.forEach { (key, a) -> if (a.eraId in eras) eraAliases[key] = a }
        for (a in write.eraAliases) {
            require(a.chatKey == chat) { "alias ${a.alias} is of chat ${a.chatKey}, the write of $chat" }
            require(a.eraId in eras) { "alias ${a.alias}: unknown era ${a.eraId}" }
            val key = nameKey(a.alias)
            require(key.isNotEmpty()) { "alias '${a.alias}' is empty once normalized" }
            val owner = eraAliases[key]
            require(owner == null || owner.eraId == a.eraId) { "alias '${a.alias}' belongs to era ${owner?.eraId}" }
            eraAliases.putIfAbsent(key, a)
        }

        // Names: unique per chat after normalization, never another row's alias (per layer).
        checkNames("period", periods.values.map { it.id to it.name }, periodAliases.mapValues { it.value.periodId })
        checkNames("era", eras.values.map { it.id to it.name }, eraAliases.mapValues { it.value.eraId })
        require(periods.values.count { it.state == PeriodState.PROVISIONAL } <= 1) { "chat $chat would hold two provisional periods" }

        // A period or era this write touches is never left empty.
        val memberCount = members.values.groupingBy { it }.eachCount()
        for (id in write.periods.map { it.id } + added.values) {
            require((memberCount[id] ?: 0) > 0) { "period $id would have no scene" }
        }
        val eraUse = periods.values.mapNotNull { it.eraId }.toSet()
        for (id in write.eras.map { it.id } + write.periods.mapNotNull { it.eraId }) {
            require(id in eraUse) { "era $id would have no period" }
        }

        val failures = LinkedHashMap<FailureKey, PeriodFailureRecord>()
        base?.failures?.forEach { (k, f) -> failures[k] = f }
        for (f in write.failures) {
            require(f.chatKey == chat) { "failure counter of chat ${f.chatKey} in the write of $chat" }
            val key = FailureKey(f.kind, f.firstSceneId)
            if (f.count <= 0) failures.remove(key) else failures[key] = f
        }

        // Derived date ranges: every period from its scenes, every era from its periods (a late scene widens both).
        val datesOf = HashMap<Long, Pair<String?, String?>>()
        for ((scene, period) in members) {
            val record = scenes(scene) ?: continue
            val (from, to) = datesOf[period] ?: (null to null)
            datesOf[period] = minDate(from, record.dateFrom) to maxDate(to, record.dateTo ?: record.dateFrom)
        }
        for ((id, p) in periods) {
            val (from, to) = datesOf[id] ?: (null to null)
            periods[id] = p.copy(dateFrom = from, dateTo = to)
        }
        for ((id, e) in eras) {
            val own = periods.values.filter { it.eraId == id }
            eras[id] = e.copy(
                dateFrom = own.mapNotNull { it.dateFrom }.minOrNull(),
                dateTo = own.mapNotNull { it.dateTo }.maxOrNull(),
            )
        }

        val generation = (current?.generation ?: 0L) + if (write.structural) 1 else 0
        val catalogues = LinkedHashMap<String, CatalogueSnapshot>()
        base?.catalogues?.forEach { (k, c) -> catalogues[k] = c }
        for (c in write.catalogues) {
            require(c.chatKey == chat) { "catalogue of chat ${c.chatKey} in the write of $chat" }
            val language = languageKey(c.language)
            require(language.isNotEmpty()) { "catalogue without a language" }
            catalogues[language] = c.copy(
                language = language,
                sourceGeneration = generation,
                contentHash = contentHash(c.text),
                publishedAt = now,
            )
        }

        return ChatState(
            periods = periods,
            members = members,
            eras = eras,
            periodAliases = periodAliases,
            eraAliases = eraAliases,
            exclusions = exclusions,
            failures = failures,
            catalogues = catalogues,
            generation = generation,
            firstMessageOf = ::firstMessageOf,
        )
    }

    private fun checkNames(layer: String, names: List<Pair<Long, String>>, aliasOwners: Map<String, Long>) {
        val byKey = HashMap<String, Long>()
        for ((id, name) in names) {
            val key = nameKey(name)
            require(key.isNotEmpty()) { "$layer $id: the name '$name' is empty once normalized" }
            val other = byKey.put(key, id)
            require(other == null) { "$layer name '$name' is taken by $other" }
            val aliasOwner = aliasOwners[key]
            require(aliasOwner == null || aliasOwner == id) { "$layer name '$name' is an alias of $aliasOwner" }
        }
    }

    /** Replace [chat]'s RAM image and its global lookups with [state] (a committed write, or the load). */
    private fun install(chat: String, state: ChatState) {
        chats[chat]?.let { old ->
            old.periods.keys.forEach(chatOfPeriod::remove)
            old.eras.keys.forEach(chatOfEra::remove)
            old.members.keys.forEach(periodIdOfScene::remove)
            old.exclusions.keys.forEach(excludedScenes::remove)
        }
        if (state.isEmpty) chats.remove(chat) else chats[chat] = state
        for (id in state.periods.keys) {
            chatOfPeriod[id] = chat
            ids.observe(id)
        }
        for (id in state.eras.keys) {
            chatOfEra[id] = chat
            ids.observe(id)
        }
        periodIdOfScene.putAll(state.members)
        state.exclusions.keys.forEach { excludedScenes[it] = chat }
    }

    // --- reads -----------------------------------------------------------------------------------------

    private fun stateOfPeriod(periodId: Long): ChatState? = chatOfPeriod[periodId]?.let(chats::get)

    private fun stateOfEra(eraId: Long): ChatState? = chatOfEra[eraId]?.let(chats::get)

    fun period(id: Long): PeriodRecord? = stateOfPeriod(id)?.periods?.get(id)

    fun periods(chatKey: String): List<PeriodRecord> = chats[chatKey]?.orderedPeriods.orEmpty()

    fun periodOfScene(sceneId: Long): PeriodRecord? = periodIdOfScene[sceneId]?.let(::period)

    fun periodScenes(periodId: Long): List<Long> = stateOfPeriod(periodId)?.scenesByPeriod?.get(periodId).orEmpty()

    fun provisionalPeriod(chatKey: String): PeriodRecord? =
        chats[chatKey]?.periods?.values?.firstOrNull { it.state == PeriodState.PROVISIONAL }

    fun periodByName(chatKey: String, name: String): PeriodRecord? {
        val state = chats[chatKey] ?: return null
        val key = nameKey(name).takeIf { it.isNotEmpty() } ?: return null
        val id = state.periodByNameKey[key] ?: state.periodAliases[key]?.periodId ?: return null
        return state.periods[id]
    }

    fun periodAliases(periodId: Long): List<PeriodAliasRecord> =
        stateOfPeriod(periodId)?.periodAliases?.values?.filter { it.periodId == periodId }.orEmpty()

    fun era(id: Long): EraRecord? = stateOfEra(id)?.eras?.get(id)

    fun eras(chatKey: String): List<EraRecord> = chats[chatKey]?.orderedEras.orEmpty()

    fun eraPeriods(eraId: Long): List<PeriodRecord> = stateOfEra(eraId)?.orderedPeriods?.filter { it.eraId == eraId }.orEmpty()

    fun eraByName(chatKey: String, name: String): EraRecord? {
        val state = chats[chatKey] ?: return null
        val key = nameKey(name).takeIf { it.isNotEmpty() } ?: return null
        val id = state.eraByNameKey[key] ?: state.eraAliases[key]?.eraId ?: return null
        return state.eras[id]
    }

    fun eraAliases(eraId: Long): List<EraAliasRecord> =
        stateOfEra(eraId)?.eraAliases?.values?.filter { it.eraId == eraId }.orEmpty()

    fun isExcluded(sceneId: Long): Boolean = sceneId in excludedScenes

    fun exclusions(chatKey: String): List<PeriodExclusionRecord> =
        chats[chatKey]?.exclusions?.values?.sortedBy { it.sceneId }.orEmpty()

    fun failureCount(chatKey: String, kind: PeriodFailureKind, firstSceneId: Long): Int = failure(chatKey, kind, firstSceneId)?.count ?: 0

    fun failure(chatKey: String, kind: PeriodFailureKind, firstSceneId: Long): PeriodFailureRecord? =
        chats[chatKey]?.failures?.get(FailureKey(kind, firstSceneId))

    fun generation(chatKey: String): Long = chats[chatKey]?.generation ?: 0L

    fun catalogue(chatKey: String, language: String): CatalogueSnapshot? = chats[chatKey]?.catalogues?.get(languageKey(language))

    fun catalogues(chatKey: String?): List<CatalogueSnapshot> =
        if (chatKey == null) chats.values.flatMap { it.catalogues.values } else chats[chatKey]?.catalogues?.values?.toList().orEmpty()

    fun chatKeys(): Set<String> = chats.keys.toSet()

    // --- scene trust cache (RAM only) --------------------------------------------------------------------

    fun sceneTrust(sceneId: Long): Boolean? = trust[sceneId]

    fun putSceneTrust(verdicts: Map<Long, Boolean>) {
        trust.putAll(verdicts)
    }

    companion object {
        /**
         * The comparison key of a period / era name or alias: NFKC, lower case, without whitespace, control,
         * format (bidi, zero-width) or separator code points and without render quotes / dots. Uniqueness and
         * lookup compare this key; the name itself is stored as given.
         */
        fun nameKey(name: String): String = periodNameKey(name)

        /** The key a catalogue language is stored and looked up under (`zh-CN`, `zh_CN` -> `zh-cn`). */
        fun languageKey(language: String): String = language.trim().lowercase(Locale.ROOT).replace('_', '-')

        /** SHA-256 (hex) of a catalogue text. */
        fun contentHash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun minDate(a: String?, b: String?): String? = listOfNotNull(a, b).minOrNull()

        private fun maxDate(a: String?, b: String?): String? = listOfNotNull(a, b).maxOrNull()
    }
}
