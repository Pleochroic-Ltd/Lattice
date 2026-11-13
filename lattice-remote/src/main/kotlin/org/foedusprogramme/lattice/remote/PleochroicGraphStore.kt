package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.foedusprogramme.lattice.core.CatalogueSnapshot
import org.foedusprogramme.lattice.core.EdgeRecord
import org.foedusprogramme.lattice.core.EmbeddingSpec
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.EntityRecord
import org.foedusprogramme.lattice.core.EraAliasRecord
import org.foedusprogramme.lattice.core.EraRecord
import org.foedusprogramme.lattice.core.IdAllocator
import org.foedusprogramme.lattice.core.InMemoryLatticeStore
import org.foedusprogramme.lattice.core.InMemoryLatticeStore.Change
import org.foedusprogramme.lattice.core.InMemoryLatticeStore.Row
import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.PeriodAliasRecord
import org.foedusprogramme.lattice.core.PeriodExclusionRecord
import org.foedusprogramme.lattice.core.PeriodFailureRecord
import org.foedusprogramme.lattice.core.PeriodMemberRecord
import org.foedusprogramme.lattice.core.PeriodRecord
import org.foedusprogramme.lattice.core.SceneCursorRecord
import org.foedusprogramme.lattice.core.SceneRecord
import org.foedusprogramme.lattice.core.SceneWorkRecord
import org.foedusprogramme.lattice.core.ThreadLinkRecord
import org.foedusprogramme.lattice.core.ThreadRecord
import org.foedusprogramme.lattice.core.VerdictRecord
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [LatticeStore] kept on Pleochroic as an op log (the service's Lattice API), with the whole
 * graph mirrored in memory ([InMemoryLatticeStore]): the mirror checks every constraint and works out every cascade,
 * each row it writes or deletes goes out as one op (`WIRE.md`), and [load] is served from it.
 *
 * **Opening** (the first call): `GET /graphs/:name` (404 = an empty graph, created by [declareEmbedding]), the
 * snapshot, then `/ops?since=` to catch up. A device takes a free [IdAllocator] slot in 1..1023 the first time it
 * opens a graph, claiming it with a `device` record, and keeps it in [stateDir]. Opening needs the network the first
 * time; after that an unreachable service opens the copy last saved in [stateDir].
 *
 * **Writing**: a write outside a batch is one `POST /graphs/:name/ops`, a [batch] is one too (split, non-atomically,
 * only past the service's 10 000 ops or 8 MiB per post). A `409` means another device wrote first: its ops are pulled
 * into the mirror, this device's pending ops are put back on top (last writer wins per row), and the post is retried.
 * A write the service can't take now (no network, `429`, `5xx`) goes to a durable outbox in [stateDir] and is sent,
 * in order, before the next write or by [flush]; such failures never reach the caller, and writes back off while the
 * service is down. A post refused as malformed (`400`, `413`) is set aside in `<name>.rejected.jsonl`. A graph that is
 * gone from the service (`404` to a post or a pull) was deleted there: the store moves its outbox and copy aside, stops
 * writing and says why in [failure]; it never creates the graph again under the deleted one's ops. A `401` or `403` (a key
 * the service refuses) waits in the outbox like any failure, but is a setup error that [failure] names until a post
 * lands; at opening, it opens the copy last saved like an unreachable service, and without one it throws
 * [PleochroicKeyRefusedException]. The outbox takes at most [Options.maxOutboxOps] ops and [Options.maxOutboxBytes] bytes: past either, later
 * writes stay on this device until it drains (lost at a restart), the oldest are kept, and [failure] says so.
 *
 * **Other devices' writes** reach the mirror on a conflict, on [pullRemoteChanges] and on a later [load]. Those
 * found during a write or [flush] are told to [onRemoteChange] after the call returns, on the calling thread; the
 * service should schedule a reload of its graph from [load] rather than call back into the store from the callback.
 */
class PleochroicGraphStore(
    private val client: PleochroicClient,
    val graphName: String,
    stateDir: Path,
    private val options: Options = Options(),
    private val onRemoteChange: (RemoteChanges) -> Unit = {},
) : LatticeStore {
    data class Options(
        /** Conflicts in a row before a post waits in the outbox. */
        val maxConflictRetries: Int = 5,
        /** The first wait after a failed post (doubling up to [maxRetryDelay]), unless the service says `Retry-After`. */
        val minRetryDelay: Duration = Duration.ofSeconds(2),
        val maxRetryDelay: Duration = Duration.ofMinutes(5),
        /** Ops per `/ops?since=` page. */
        val pageLimit: Int = 1_000,
        /** The service's per-post limits (`MAX.graphBatchOps`, `MAX.graphBatchBytes`). */
        val maxBatchOps: Int = 10_000,
        val maxBatchBytes: Int = 8 * 1024 * 1024,
        /** Names this device in its `device` record, e.g. the host name. */
        val deviceName: String? = null,
        /** Keep a copy of the graph in the state directory, to open without the network. */
        val cacheSnapshot: Boolean = true,
        /** The most ops and bytes the outbox keeps while the service doesn't take them; later writes stay local. */
        val maxOutboxOps: Int = 200_000,
        val maxOutboxBytes: Long = 128L * 1024 * 1024,
    )

    /** Other devices' writes that reached the mirror: the ids they touched, or the whole graph when [reloaded]. */
    data class RemoteChanges(val ids: Set<Long>, val reloaded: Boolean) {
        val isEmpty: Boolean get() = ids.isEmpty() && !reloaded

        operator fun plus(other: RemoteChanges) = RemoteChanges(ids + other.ids, reloaded || other.reloaded)

        companion object {
            val NONE: RemoteChanges = RemoteChanges(emptySet(), false)
        }
    }

    init {
        require(NAME.matches(graphName)) {
            "'$graphName' isn't a Pleochroic graph name: use 1-63 lower-case letters, digits and dashes, starting with a letter or digit."
        }
    }

    private val logger = LoggerFactory.getLogger(PleochroicGraphStore::class.java)
    private val lock = ReentrantLock()
    private val files = GraphStateFiles(stateDir, graphName)
    private val collected = ArrayList<Change>()
    private var mirror = newMirror()
    private val devices = HashMap<Int, DeviceRecord>()
    private val queue = ArrayDeque<Queued>()
    private lateinit var device: DeviceState
    private var opened = false
    private var closed = false
    private var loading = false
    private var exists = false
    private var claimPending = false
    private var seq = 0L
    private var depth = 0
    private var retryAt: Instant? = null
    private var retryDelay: Duration = options.minRetryDelay
    private var notice = RemoteChanges.NONE
    private var pulling = false
    private var gone = false

    @Volatile
    private var goneReason: String? = null

    @Volatile
    private var authReason: String? = null

    @Volatile
    private var overflowReason: String? = null
    private var overflowed = 0

    /** Why this store doesn't get its writes to Pleochroic, null while it does: a deleted graph, a refused key, a full outbox. */
    val failure: String? get() = goneReason ?: authReason ?: overflowReason

    private class Queued(entry: OutboxEntry, var persisted: Boolean) {
        var entry: OutboxEntry = entry
            set(value) {
                field = value
                bytes = sizeOf(value)
            }

        var bytes: Long = sizeOf(entry)
            private set

        val ops: Int get() = (entry as? OutboxEntry.Ops)?.ops?.size ?: 0

        private companion object {
            fun sizeOf(entry: OutboxEntry): Long =
                (entry as? OutboxEntry.Ops)?.ops?.sumOf { it.toString().length + 1L } ?: 0L
        }
    }

    private sealed interface Outcome {
        data object Sent : Outcome

        data class Rejected(val reason: String) : Outcome

        data class Retry(val after: Duration?, val reason: String, val refusedKey: Boolean = false) : Outcome

        data object Gone : Outcome

        /** The queue changed under the post: send its head again. */
        data object Requeue : Outcome
    }

    private class SnapshotRequired : RuntimeException()

    override val idSlot: Int
        get() = locked {
            open()
            device.slot ?: error("Graph '$graphName' has no id slot for this device.")
        }

    override val embedding: EmbeddingSpec?
        get() = locked {
            open()
            mirror.embedding
        }

    /** Writes waiting for the service (in memory and in the outbox). */
    val pendingWrites: Int get() = lock.withLock { queue.size }

    override fun declareEmbedding(spec: EmbeddingSpec): Unit = locked {
        open()
        when {
            mirror.embedding != null -> mirror.declareEmbedding(spec)
            exists -> {
                logger.warn("Graph '{}' exists on Pleochroic without an embedding; {} is recorded on this device only.", graphName, spec)
                mirror.declareEmbedding(spec)
            }
            else -> {
                mirror.declareEmbedding(spec)
                queue.addLast(Queued(OutboxEntry.Create(spec), persisted = false))
                exists = true
                if (claimPending) {
                    enqueueClaim(device.slot ?: error("A pending claim has a slot."))
                    claimPending = false
                }
                flushQueue(force = false)
            }
        }
    }

    override fun load(): LatticeStore.Loaded = locked {
        loading = true
        try {
            if (!opened) {
                open()
            } else {
                flushQueue(force = false)
                try {
                    sync()
                } catch (e: RuntimeException) {
                    if (!transient(e) && !refusedKey(e)) throw e
                    logger.warn("Graph '{}' loads as last seen: Pleochroic is unreachable ({}).", graphName, e.message)
                }
            }
            notice = RemoteChanges.NONE
            mirror.load()
        } finally {
            loading = false
        }
    }

    /**
     * Sends the outbox now, ignoring the back-off; returns true when nothing is left waiting. Never throws for a
     * network or service failure.
     */
    fun flush(): Boolean = locked {
        open()
        flushQueue(force = true)
    }

    /**
     * Sends the outbox, then pulls other devices' writes into the mirror and returns what they touched (also when the
     * graph had to be reloaded). These changes are not told to [onRemoteChange]. A failure leaves the mirror as it was
     * and returns [RemoteChanges.NONE].
     */
    fun pullRemoteChanges(): RemoteChanges = lock.withLock {
        open()
        pulling = true
        try {
            flushQueue(force = true)
            try {
                sync()
            } catch (e: RuntimeException) {
                if (!transient(e) && !refusedKey(e)) throw e
                logger.warn("Graph '{}' couldn't pull other devices' writes: {}", graphName, e.message)
            }
            val found = notice
            notice = RemoteChanges.NONE
            if (options.cacheSnapshot && !found.isEmpty) writeCache()
            found
        } finally {
            pulling = false
        }
    }

    override fun putEngram(engram: Engram) = write { mirror.putEngram(engram) }
    override fun deleteEngram(id: Long) = write { mirror.deleteEngram(id) }
    override fun putEdge(edge: EdgeRecord) = write { mirror.putEdge(edge) }
    override fun deleteEdge(a: Long, b: Long) = write { mirror.deleteEdge(a, b) }
    override fun putVerdict(verdict: VerdictRecord) = write { mirror.putVerdict(verdict) }
    override fun putEntity(entity: EntityRecord) = write { mirror.putEntity(entity) }
    override fun linkEngramEntity(engramId: Long, entityId: Long) = write { mirror.linkEngramEntity(engramId, entityId) }
    override fun markEntityResolved(engramId: Long) = write { mirror.markEntityResolved(engramId) }
    override fun mergeEntities(fromId: Long, toId: Long) = write { mirror.mergeEntities(fromId, toId) }
    override fun putScene(scene: SceneRecord) = write { mirror.putScene(scene) }
    override fun putSceneMember(sceneId: Long, engramId: Long) = write { mirror.putSceneMember(sceneId, engramId) }
    override fun putSceneWork(work: SceneWorkRecord) = write { mirror.putSceneWork(work) }
    override fun putSceneCursor(cursor: SceneCursorRecord) = write { mirror.putSceneCursor(cursor) }
    override fun redirectSceneMembers(fromEngramId: Long, toEngramId: Long) = write { mirror.redirectSceneMembers(fromEngramId, toEngramId) }
    override fun putThread(thread: ThreadRecord) = write { mirror.putThread(thread) }
    override fun putThreadLink(link: ThreadLinkRecord) = write { mirror.putThreadLink(link) }
    override fun redirectThreadLinks(fromEngramId: Long, toEngramId: Long) = write { mirror.redirectThreadLinks(fromEngramId, toEngramId) }
    override fun deleteThreadLinks(engramId: Long) = write { mirror.deleteThreadLinks(engramId) }
    override fun putPeriod(period: PeriodRecord) = write { mirror.putPeriod(period) }
    override fun deletePeriod(periodId: Long) = write { mirror.deletePeriod(periodId) }
    override fun putPeriodMember(member: PeriodMemberRecord) = write { mirror.putPeriodMember(member) }
    override fun putPeriodAlias(alias: PeriodAliasRecord) = write { mirror.putPeriodAlias(alias) }
    override fun putEra(era: EraRecord) = write { mirror.putEra(era) }
    override fun deleteEra(eraId: Long) = write { mirror.deleteEra(eraId) }
    override fun putEraAlias(alias: EraAliasRecord) = write { mirror.putEraAlias(alias) }
    override fun putPeriodExclusion(exclusion: PeriodExclusionRecord) = write { mirror.putPeriodExclusion(exclusion) }
    override fun putPeriodFailure(failure: PeriodFailureRecord) = write { mirror.putPeriodFailure(failure) }
    override fun putPeriodGeneration(chatKey: String, generation: Long) = write { mirror.putPeriodGeneration(chatKey, generation) }
    override fun putCatalogueSnapshot(snapshot: CatalogueSnapshot) = write { mirror.putCatalogueSnapshot(snapshot) }
    override fun deletePeriodLayer(chatKey: String) = write { mirror.deletePeriodLayer(chatKey) }

    override fun batch(block: (LatticeStore) -> Unit): Unit = locked {
        open()
        if (depth > 0) {
            mirror.batch { block(this) }
            return@locked
        }
        requireExists()
        collected.clear()
        depth++
        try {
            mirror.batch { block(this) }
        } finally {
            depth--
        }
        submit(takeCollected())
    }

    /** Sends what it can, keeps the rest in the outbox and saves the graph's copy; later calls throw. */
    override fun close() = lock.withLock {
        if (closed) return@withLock
        if (opened) {
            runCatching { flushQueue(force = true) }.onFailure { logger.warn("Graph '{}' closed with writes unsent: {}", graphName, it.message) }
            persistQueue()
            if (options.cacheSnapshot) runCatching { writeCache() }.onFailure { logger.warn("Graph '{}' couldn't save its copy: {}", graphName, it.message) }
        }
        closed = true
    }

    /**
     * Saves the outbox and the graph's copy to the state directory without calling the service, then closes: for a
     * host that stops before [close] could run. Gives up when another call holds the store for more than [wait].
     */
    fun closeOffline(wait: Duration = Duration.ofSeconds(2)) {
        if (!lock.tryLock(wait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            logger.warn("Graph '{}' is still busy, so its unsent writes stay where they were last saved.", graphName)
            return
        }
        try {
            if (closed) return
            if (opened) {
                persistQueue()
                if (options.cacheSnapshot) runCatching { writeCache() }.onFailure { logger.warn("Graph '{}' couldn't save its copy: {}", graphName, it.message) }
            }
            closed = true
        } finally {
            lock.unlock()
        }
    }

    // -- Writing --------------------------------------------------------------

    private fun write(block: () -> Unit): Unit = locked {
        open()
        if (depth > 0) {
            block()
            return@locked
        }
        requireExists()
        collected.clear()
        block()
        submit(takeCollected())
    }

    private fun requireExists() = check(exists) {
        "Graph '$graphName' doesn't exist on Pleochroic yet; call declareEmbedding before the first write, which creates it."
    }

    private fun takeCollected(): List<Change> = collected.toList().also { collected.clear() }

    private fun submit(changes: List<Change>) {
        if (changes.isEmpty() || gone) return
        val chunks = chunk(changes.map(GraphWire::encode))
        if (queue.isNotEmpty()) {
            val ops = queue.sumOf { it.ops } + changes.size
            val bytes = queue.sumOf { it.bytes } + chunks.sumOf { chunk -> chunk.sumOf { it.toString().length + 1L } }
            if (ops > options.maxOutboxOps || bytes > options.maxOutboxBytes) {
                overflow(changes.size)
                flushQueue(force = false)
                return
            }
        }
        if (chunks.size > 1) {
            logger.warn(
                "A batch of {} ops on graph '{}' is past the service's per-post limits and goes as {} posts; each lands on its own.",
                changes.size, graphName, chunks.size,
            )
        }
        chunks.forEach { queue.addLast(Queued(OutboxEntry.Ops(it), persisted = false)) }
        flushQueue(force = false)
    }

    private fun chunk(ops: List<JsonObject>): List<List<JsonObject>> {
        val budget = options.maxBatchBytes - ENVELOPE_BYTES
        val chunks = ArrayList<List<JsonObject>>()
        var current = ArrayList<JsonObject>()
        var bytes = 0
        for (op in ops) {
            val size = op.toString().toByteArray().size + 1
            if (current.isNotEmpty() && (current.size >= options.maxBatchOps || bytes + size > budget)) {
                chunks += current
                current = ArrayList()
                bytes = 0
            }
            current += op
            bytes += size
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    /** Sends the queue in order and says whether it emptied; a failure keeps the rest in the outbox, never throws. */
    private fun flushQueue(force: Boolean): Boolean {
        if (gone) return false
        if (queue.isEmpty()) return true
        if (!force && retryAt?.isAfter(Instant.now()) == true) {
            persistQueue()
            return false
        }
        var rewrite = false
        try {
            while (queue.isNotEmpty()) {
                val head = queue.first()
                val outcome = try {
                    send(head)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Outcome.Retry(null, "the call was interrupted")
                } catch (e: RuntimeException) {
                    logger.error("Graph '{}' couldn't send a write: {}", graphName, e.toString())
                    Outcome.Retry(null, e.message ?: e.toString())
                }
                when (outcome) {
                    Outcome.Sent -> Unit
                    is Outcome.Rejected -> {
                        logger.error("Pleochroic refused a write to graph '{}' ({}); it's kept in {}.rejected.jsonl.", graphName, outcome.reason, graphName)
                        files.appendRejected(head.entry, outcome.reason)
                    }
                    is Outcome.Retry -> {
                        if (outcome.refusedKey) {
                            backOff(options.maxRetryDelay)
                            refused(outcome.reason)
                        } else {
                            backOff(outcome.after)
                        }
                        logger.warn("Graph '{}' keeps {} write(s) in its outbox: {}", graphName, queue.size, outcome.reason)
                        persistQueue()
                        return false
                    }
                    Outcome.Requeue -> continue
                    Outcome.Gone -> {
                        if (rewrite) files.rewriteOutbox(queue.filter { it.persisted }.map { it.entry })
                        rewrite = false
                        markGone()
                        return false
                    }
                }
                queue.removeFirst()
                if (head.persisted) rewrite = true
                authReason = null
            }
            retryAt = null
            retryDelay = options.minRetryDelay
            drained()
            return true
        } finally {
            if (rewrite) files.rewriteOutbox(queue.filter { it.persisted }.map { it.entry })
        }
    }

    private fun persistQueue() {
        if (gone) return
        val fresh = queue.filter { !it.persisted }
        if (fresh.isEmpty()) return
        files.appendOutbox(fresh.map { it.entry })
        fresh.forEach { it.persisted = true }
    }

    private fun backOff(after: Duration?) {
        val delay = after ?: retryDelay
        retryAt = Instant.now().plus(delay)
        retryDelay = minOf(retryDelay.multipliedBy(2), options.maxRetryDelay)
    }

    private fun send(head: Queued): Outcome = try {
        when (val entry = head.entry) {
            is OutboxEntry.Create -> create(entry.spec)
            is OutboxEntry.Ops -> post(head)
        }
    } catch (e: PleochroicUnreachableException) {
        Outcome.Retry(null, e.message ?: "Pleochroic is unreachable")
    } catch (e: PleochroicApiException) {
        classify(e)
    }

    private fun classify(e: PleochroicApiException): Outcome = when {
        e.status == 400 || e.status == 413 || e.status == 422 -> Outcome.Rejected(e.message ?: e.code)
        refusedKey(e) -> Outcome.Retry(e.retryAfter, e.message ?: e.code, refusedKey = true)
        else -> Outcome.Retry(e.retryAfter, e.message ?: e.code)
    }

    /** The service refused the API key: a setup error, said once until a post lands. */
    private fun refused(reason: String) {
        if (authReason != null) return
        authReason = "Pleochroic refused the API key for graph '$graphName' ($reason), so its writes wait on this device; " +
            "set a valid key for the account, then the outbox is sent."
        logger.error(authReason)
    }

    /** The outbox is full: [ops] more ops stay on this device only, and the first such write says so once. */
    private fun overflow(ops: Int) {
        overflowed += ops
        if (overflowReason != null) return
        overflowReason = "Graph '$graphName' has as many writes waiting for Pleochroic as its outbox keeps " +
            "(${options.maxOutboxOps} ops or ${options.maxOutboxBytes / (1024 * 1024)} MiB), so later writes stay on this " +
            "device only and are lost at a restart" + (authReason?.let { ": $it" } ?: "; check the connection to the service.")
        logger.error(overflowReason)
    }

    /** The outbox emptied: a full one takes writes again. */
    private fun drained() {
        if (overflowReason == null) return
        logger.warn("Graph '{}' sent its outbox; {} op(s) made while it was full never reached Pleochroic.", graphName, overflowed)
        overflowReason = null
        overflowed = 0
    }

    private fun create(spec: EmbeddingSpec): Outcome {
        try {
            client.call(
                "POST", "/graphs",
                buildJsonObject {
                    put("name", graphName)
                    put("embedding", GraphWire.embedding(spec))
                },
            )
        } catch (e: PleochroicApiException) {
            if (e.status != 409) throw e
            val existing = info()?.let { GraphWire.embeddingOf(it["embedding"]) }
            if (existing != null && existing != spec) {
                return Outcome.Rejected("graph '$graphName' already exists with the embedding $existing, not $spec")
            }
        }
        return Outcome.Sent
    }

    /** Posts the ops of [head], the queue's first entry, until they land or the queue changes under them. */
    private fun post(head: Queued): Outcome {
        var conflicts = 0
        while (true) {
            if (queue.firstOrNull() !== head) return Outcome.Requeue
            val ops = (head.entry as? OutboxEntry.Ops)?.ops ?: return Outcome.Requeue
            try {
                val answer = client.call("POST", "/graphs/$graphName/ops", envelope(ops)) as? JsonObject
                answer?.long("seq")?.let { seq = it }
                return Outcome.Sent
            } catch (e: PleochroicApiException) {
                when {
                    e.status == 409 -> {
                        if (++conflicts > options.maxConflictRetries) return Outcome.Retry(null, "the graph kept changing under $conflicts posts")
                        sync()
                    }
                    e.status == 404 -> return Outcome.Gone
                    else -> return classify(e)
                }
            }
        }
    }

    private fun envelope(ops: List<JsonObject>) = buildJsonObject {
        put("baseSeq", seq)
        put("ops", JsonArray(ops))
    }

    // -- Reading --------------------------------------------------------------

    private fun open() {
        check(!closed) { "Graph '$graphName' is closed; open a new store." }
        if (opened) return
        device = files.readDevice() ?: DeviceState(UUID.randomUUID().toString(), null).also(files::writeDevice)
        queue.clear()
        files.readOutbox().forEach { queue.addLast(Queued(it, persisted = true)) }
        var fromServer = true
        try {
            reload()
        } catch (e: RuntimeException) {
            val refused = e is PleochroicApiException && (e.status == 401 || e.status == 403)
            if (!transient(e) && !refused) throw e
            val cached = files.readSnapshot()
            if (cached == null || device.slot == null) {
                if (refused) {
                    throw PleochroicKeyRefusedException(
                        "Pleochroic refused the API key for graph '$graphName' (${e.message}), and this device has no copy " +
                            "of it yet; set a valid key for the account.",
                        e,
                    )
                }
                throw PleochroicUnreachableException(
                    "Graph '$graphName' couldn't be loaded from Pleochroic and has no copy on this device yet (${e.message}); " +
                        "connect once to open it.",
                    e,
                )
            }
            logger.warn("Graph '{}' opens from this device's copy (seq {}): {}", graphName, cached.seq, e.message)
            loadCached(cached)
            fromServer = false
            if (refused) {
                refused(e.message ?: "HTTP 401")
                backOff(options.maxRetryDelay)
            } else {
                backOff((e as? PleochroicApiException)?.retryAfter)
            }
        }
        ensureSlot()
        opened = true
        if (fromServer && options.cacheSnapshot) writeCache()
        flushQueue(force = false)
    }

    /** Loads the whole graph again: its info, the snapshot and the ops after it, then this device's pending writes. */
    private fun reload() {
        val info = info()
        if (info == null && opened && exists) {
            markGone()
            return
        }
        devices.clear()
        if (info == null) {
            if (!opened) setAsideDeleted()
            val create = queue.firstNotNullOfOrNull { it.entry as? OutboxEntry.Create }
            exists = create != null
            mirror = newMirror().also { it.reset(create?.spec) }
            seq = 0
        } else {
            exists = true
            val fresh = newMirror().also { it.reset(GraphWire.embeddingOf(info["embedding"])) }
            val freshDevices = HashMap<Int, DeviceRecord>()
            seq = client.lines("/graphs/$graphName/snapshot") { lines ->
                val iterator = lines.iterator()
                val head = if (iterator.hasNext()) JSON.parseToJsonElement(iterator.next()) as? JsonObject else null
                val snapshotSeq = head?.long("seq") ?: error("The snapshot of graph '$graphName' didn't start with its seq.")
                val chunk = ArrayList<JsonObject>(SNAPSHOT_CHUNK)
                for (line in iterator) {
                    chunk += JSON.parseToJsonElement(line) as? JsonObject ?: continue
                    if (chunk.size == SNAPSHOT_CHUNK) {
                        applyOps(fresh, freshDevices, chunk)
                        chunk.clear()
                    }
                }
                applyOps(fresh, freshDevices, chunk)
                snapshotSeq
            }
            mirror = fresh
            devices.putAll(freshDevices)
            try {
                pullPages()
            } catch (e: SnapshotRequired) {
                throw PleochroicApiException(410, "snapshot_required", "The ops right after graph '$graphName''s snapshot are already compacted away.")
            }
        }
        replayQueue()
        repairMirror()
    }

    private fun info(): JsonObject? = try {
        client.call("GET", "/graphs/$graphName") as? JsonObject
    } catch (e: PleochroicApiException) {
        if (e.status == 404) null else throw e
    }

    private fun loadCached(cached: CachedSnapshot) {
        val fresh = newMirror().also { it.reset(cached.spec) }
        devices.clear()
        applyOps(fresh, devices, cached.ops)
        mirror = fresh
        seq = cached.seq
        exists = true
        replayQueue()
    }

    /** Pulls other devices' writes into the mirror; when the log no longer reaches back far enough, reloads it all. */
    private fun sync() {
        if (gone) return
        val touched = try {
            pullPages()
        } catch (e: SnapshotRequired) {
            logger.info("Graph '{}' was behind the op log; loading its snapshot again.", graphName)
            reload()
            note(RemoteChanges(emptySet(), reloaded = true))
            return
        }
        if (touched != null) {
            replayQueue()
            repairMirror()
            note(RemoteChanges(touched, reloaded = false))
        }
    }

    /** Applies `/ops?since=` pages to the mirror; the ids they touched, or null when there was nothing new. */
    private fun pullPages(): Set<Long>? {
        if (!exists) return null
        var touched: MutableSet<Long>? = null
        while (true) {
            val page = try {
                client.call("GET", "/graphs/$graphName/ops?since=$seq&limit=${options.pageLimit}") as? JsonObject
            } catch (e: PleochroicApiException) {
                if (e.status == 410 || e.code == "snapshot_required") throw SnapshotRequired()
                if (e.status == 404) {
                    if (opened) markGone()
                    return touched
                }
                throw e
            } ?: return touched
            val ops = (page["ops"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            if (ops.isNotEmpty()) {
                (touched ?: HashSet<Long>().also { touched = it }) += applyOps(mirror, devices, ops)
            }
            val more = (page["more"] as? JsonPrimitive)?.booleanOrNull == true && ops.isNotEmpty()
            val lastOpSeq = ops.lastOrNull()?.long("seq")
            val pageSeq = page.long("seq")
            seq = if (more) lastOpSeq ?: pageSeq ?: seq else maxOf(pageSeq ?: seq, lastOpSeq ?: seq)
            if (!more) return touched
        }
    }

    /**
     * Puts this device's pending writes back on top of the mirror, so they win over what they will overwrite. A pending
     * claim of a slot that another device's claim took first is dropped instead, and this device takes a new slot.
     */
    private fun replayQueue() {
        var lost = false
        var rewrite = false
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            val entry = queued.entry as? OutboxEntry.Ops ?: continue
            val ops = entry.ops.filterNot(::lostClaim)
            if (ops.size != entry.ops.size) {
                lost = true
                rewrite = rewrite || queued.persisted
                if (ops.isEmpty()) {
                    iterator.remove()
                    continue
                }
                queued.entry = OutboxEntry.Ops(ops)
            }
            applyOps(mirror, devices, ops)
        }
        if (lost) takeNewSlot(rewrite)
    }

    /** Whether [op] is this device's claim of a slot that another device holds. */
    private fun lostClaim(op: JsonObject): Boolean {
        val claim = (GraphWire.decode(op) as? WireOp.Device)?.record ?: return false
        val holder = devices[claim.slot] ?: return false
        return claim.deviceId == device.deviceId && holder.deviceId != device.deviceId
    }

    /**
     * Claims a new slot ahead of every queued write but a pending create, so that it lands before any op that carries an
     * id from it, and has the graph reloaded so that its next ids come from it.
     */
    private fun takeNewSlot(rewrite: Boolean) {
        val old = device.slot
        val claim = DeviceRecord(freeSlot(), device.deviceId, options.deviceName, Instant.now())
        device = device.copy(slot = claim.slot)
        files.writeDevice(device)
        devices[claim.slot] = claim
        val at = queue.indexOfFirst { it.entry !is OutboxEntry.Create }.let { if (it < 0) queue.size else it }
        val persisted = rewrite || queue.any { it.persisted }
        queue.add(at, Queued(OutboxEntry.Ops(listOf(GraphWire.putDevice(claim))), persisted))
        if (persisted) files.rewriteOutbox(queue.filter { it.persisted }.map { it.entry })
        logger.warn(
            "Another device claimed slot {} of graph '{}' while this device's claim waited; this device takes slot {}.",
            old, graphName, claim.slot,
        )
        note(RemoteChanges(emptySet(), reloaded = true))
    }

    /** Drops what remote writes left dangling and sends those deletes like any write. */
    private fun repairMirror() {
        collected.clear()
        mirror.repair()
        val repairs = takeCollected()
        if (repairs.isNotEmpty()) {
            logger.info("Graph '{}' dropped {} row(s) that other devices' writes left dangling.", graphName, repairs.size)
            chunk(repairs.map(GraphWire::encode)).forEach { queue.addLast(Queued(OutboxEntry.Ops(it), persisted = false)) }
        }
    }

    private fun applyOps(target: InMemoryLatticeStore, targetDevices: MutableMap<Int, DeviceRecord>, ops: List<JsonObject>): Set<Long> {
        val changes = ArrayList<Change>(ops.size)
        val touched = HashSet<Long>()
        for (op in ops) {
            when (val decoded = GraphWire.decode(op)) {
                is WireOp.Mirror -> {
                    changes += decoded.change
                    touched += idsOf(decoded.change.row)
                }
                is WireOp.Device -> if (decoded.record == null) targetDevices -= decoded.slot else targetDevices[decoded.slot] = decoded.record
                is WireOp.Unknown -> logger.debug("Graph '{}' skipped {}.", graphName, decoded.description)
            }
        }
        target.applyRemote(changes)
        return touched
    }

    private fun writeCache() {
        if (gone) return
        val rows = mirror.rows().asSequence().map { GraphWire.encode(Change(it, removed = false)) }
        files.writeSnapshot(seq, mirror.embedding, rows + devices.values.asSequence().map(GraphWire::putDevice))
    }

    /**
     * The graph was deleted on the service: its outbox (with the writes not saved yet) and copy go aside, and the store
     * writes no more, so that the deleted graph's ops never come back as a fragment of a new one.
     */
    private fun markGone() {
        if (gone) return
        val aside = files.setAside(queue.filter { !it.persisted }.map { it.entry })
        queue.clear()
        gone = true
        goneReason = "Graph '$graphName' was deleted on Pleochroic, so the memory kept there is wiped; this device " +
            "stopped writing to it" + (aside?.let { " and moved its unsent writes to $it" } ?: "") +
            ". Restart to start a new, empty graph under this name, or restore the graph on Pleochroic first."
        logger.warn(goneReason)
    }

    /**
     * At open, a graph missing from the service while the outbox holds writes for it but no create was deleted while
     * this device was away: those writes and the copy go aside, so that a new, empty graph starts instead of the old
     * writes meeting a `404`.
     */
    private fun setAsideDeleted() {
        if (queue.isEmpty() || queue.any { it.entry is OutboxEntry.Create }) return
        val aside = files.setAside(emptyList())
        queue.clear()
        logger.warn(
            "Graph '{}' was deleted on Pleochroic while this device was away, so a new, empty graph starts; its unsent writes went to {}.",
            graphName, aside,
        )
    }

    // -- Device slot ----------------------------------------------------------

    private fun ensureSlot() {
        device.slot?.let { mine ->
            val holder = devices[mine]
            when {
                holder?.deviceId == device.deviceId -> return
                holder == null -> {
                    if (exists) enqueueClaim(mine) else claimPending = true
                    return
                }
                else -> {
                    logger.error(
                        "Slot {} of graph '{}' is claimed by device {}; this device takes a new slot for its next ids.",
                        mine, graphName, holder.deviceId,
                    )
                    device = device.copy(slot = null)
                }
            }
        }
        if (!exists) {
            device = device.copy(slot = freeSlot())
            files.writeDevice(device)
            claimPending = true
            return
        }
        repeat(options.maxConflictRetries + 1) {
            val claim = DeviceRecord(freeSlot(), device.deviceId, options.deviceName, Instant.now())
            try {
                val answer = client.call(
                    "POST", "/graphs/$graphName/ops",
                    buildJsonObject {
                        put("baseSeq", seq)
                        put("ops", JsonArray(listOf(GraphWire.putDevice(claim))))
                    },
                ) as? JsonObject
                answer?.long("seq")?.let { seq = it }
                devices[claim.slot] = claim
                device = device.copy(slot = claim.slot)
                files.writeDevice(device)
                logger.info("This device claimed id slot {} of graph '{}'.", claim.slot, graphName)
                return
            } catch (e: PleochroicApiException) {
                if (e.status != 409) throw e
                sync()
            }
        }
        error("Graph '$graphName' kept changing while this device claimed an id slot; open it again.")
    }

    private fun freeSlot(): Int {
        val free = (1 until IdAllocator.SLOTS).filter { it !in devices }
        check(free.isNotEmpty()) { "Graph '$graphName' has no free id slot: ${IdAllocator.SLOTS - 1} devices have claimed one." }
        return free.random()
    }

    private fun enqueueClaim(slot: Int) {
        val claim = DeviceRecord(slot, device.deviceId, options.deviceName, Instant.now())
        devices[slot] = claim
        queue.addLast(Queued(OutboxEntry.Ops(listOf(GraphWire.putDevice(claim))), persisted = false))
    }

    // -- Plumbing -------------------------------------------------------------

    private fun newMirror() = InMemoryLatticeStore(onChange = { collected += it })

    private fun note(changes: RemoteChanges) {
        if (opened && !loading) notice += changes
    }

    /** Runs [block] under the lock; once the outermost call is done, tells [onRemoteChange] what it found. */
    private fun <T> locked(block: () -> T): T {
        var found = RemoteChanges.NONE
        val result = lock.withLock {
            val value = block()
            if (lock.holdCount == 1 && !pulling) {
                found = notice
                notice = RemoteChanges.NONE
            }
            value
        }
        if (!found.isEmpty) onRemoteChange(found)
        return result
    }

    private fun refusedKey(e: RuntimeException): Boolean =
        (e is PleochroicApiException && (e.status == 401 || e.status == 403)).also { if (it) refused(e.message ?: "HTTP 401") }

    private fun transient(e: RuntimeException): Boolean =
        e is PleochroicUnreachableException || (e is PleochroicApiException && (e.status == 408 || e.status == 429 || e.status >= 500))

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private companion object {
        val NAME = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
        const val ENVELOPE_BYTES = 1_024
        const val SNAPSHOT_CHUNK = 1_000

        /** Every id a row names, for telling the service what changed. */
        fun idsOf(row: Row): List<Long> = when (row) {
            is Row.EngramRow -> listOf(row.engram.id)
            is Row.EdgeRow -> listOf(row.edge.from, row.edge.to)
            is Row.VerdictRow -> emptyList()
            is Row.EntityRow -> listOf(row.record.id)
            is Row.EntityLinkRow -> listOf(row.engramId, row.entityId)
            is Row.ResolvedRow -> listOf(row.engramId)
            is Row.SceneRow -> listOfNotNull(row.record.sceneId, row.record.hubEngramId)
            is Row.SceneMemberRow -> listOf(row.sceneId, row.engramId)
            is Row.SceneWorkRow -> listOf(row.record.sceneId)
            is Row.SceneCursorRow -> emptyList()
            is Row.ThreadRow -> listOf(row.record.id)
            is Row.ThreadLinkRow -> listOf(row.record.threadId, row.record.engramId)
            is Row.PeriodRow -> listOfNotNull(row.record.id, row.record.eraId)
            is Row.EraRow -> listOf(row.record.id)
            is Row.PeriodMemberRow -> listOf(row.record.sceneId, row.record.periodId)
            is Row.PeriodAliasRow -> listOf(row.record.periodId)
            is Row.EraAliasRow -> listOf(row.record.eraId)
            is Row.ExclusionRow -> listOf(row.record.sceneId)
            is Row.FailureRow -> listOf(row.record.firstSceneId)
            is Row.GenerationRow, is Row.CatalogueRow -> emptyList()
        }
    }
}
