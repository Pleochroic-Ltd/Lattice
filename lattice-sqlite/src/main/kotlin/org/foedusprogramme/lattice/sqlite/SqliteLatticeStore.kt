package org.foedusprogramme.lattice.sqlite

import org.foedusprogramme.lattice.core.CatalogueSnapshot
import org.foedusprogramme.lattice.core.Edge
import org.foedusprogramme.lattice.core.EdgeRecord
import org.foedusprogramme.lattice.core.EmbeddingSpec
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.EntityRecord
import org.foedusprogramme.lattice.core.EraAliasRecord
import org.foedusprogramme.lattice.core.EraRecord
import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.PeriodAliasRecord
import org.foedusprogramme.lattice.core.PeriodExclusionRecord
import org.foedusprogramme.lattice.core.PeriodFailureKind
import org.foedusprogramme.lattice.core.PeriodFailureRecord
import org.foedusprogramme.lattice.core.PeriodMemberRecord
import org.foedusprogramme.lattice.core.PeriodRecord
import org.foedusprogramme.lattice.core.PeriodState
import org.foedusprogramme.lattice.core.SceneCursorRecord
import org.foedusprogramme.lattice.core.SceneRecord
import org.foedusprogramme.lattice.core.SceneWorkRecord
import org.foedusprogramme.lattice.core.SceneWorkState
import org.foedusprogramme.lattice.core.Speaker
import org.foedusprogramme.lattice.core.ThreadLinkRecord
import org.foedusprogramme.lattice.core.ThreadRecord
import org.foedusprogramme.lattice.core.ThreadRole
import org.foedusprogramme.lattice.core.ThreadState
import org.foedusprogramme.lattice.core.VerdictRecord
import org.foedusprogramme.lattice.core.periodNameKey
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [LatticeStore] in one SQLite file. The schema's foreign keys,
 * unique indexes and triggers enforce the constraints and cascades (see [SqliteSchema]); a refused write throws
 * [java.sql.SQLException] and changes nothing. Every call runs as one transaction and [batch] as one more (nested
 * batches as savepoints).
 *
 * One connection stays open until [close], guarded by a lock, in WAL mode with `synchronous=NORMAL` and a 5 s busy
 * timeout. Opening a file migrates it to the current schema; a file from a newer Lattice is refused. Ids come from the
 * caller, so [idSlot] is 0: one device writes a local graph.
 */
class SqliteLatticeStore(path: Path) : LatticeStore {
    private val logger = LoggerFactory.getLogger(SqliteLatticeStore::class.java)
    private val lock = ReentrantLock()
    private val connection: Connection
    private val statements = HashMap<String, PreparedStatement>()
    private var depth = 0

    /** The transaction was rolled back under open savepoints: refused until the outermost one ends. */
    private var aborted = false
    private var closed = false

    @Volatile
    private var spec: EmbeddingSpec? = null

    override val idSlot: Int get() = 0

    override val embedding: EmbeddingSpec? get() = spec

    init {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            connection.createStatement().use { s ->
                s.execute("PRAGMA foreign_keys = ON")
                s.execute("PRAGMA journal_mode = WAL")
                s.execute("PRAGMA synchronous = NORMAL")
                s.execute("PRAGMA busy_timeout = 5000")
            }
            migrate(path)
            spec = readSpec()
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
    }

    private fun migrate(path: Path) {
        val current = connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        check(current <= SqliteSchema.version) {
            "The Lattice graph at $path has schema version $current, newer than this Lattice's ${SqliteSchema.version}; " +
                "open it with the newer Lattice that wrote it."
        }
        for (version in current + 1..SqliteSchema.version) {
            tx {
                connection.createStatement().use { s ->
                    SqliteSchema.migrations[version - 1].forEach(s::execute)
                    s.execute("PRAGMA user_version = $version")
                }
            }
            logger.debug("Migrated the Lattice graph at {} to schema version {}.", path, version)
        }
    }

    private fun readSpec(): EmbeddingSpec? {
        val meta = HashMap<String, String>()
        query("SELECT key, value FROM meta") { meta[it.getString(1)] = it.getString(2) }
        val model = meta[META_MODEL] ?: return null
        val dimension = meta[META_DIMENSION]?.toIntOrNull() ?: return null
        return EmbeddingSpec(model, dimension)
    }

    override fun declareEmbedding(spec: EmbeddingSpec) = write {
        val current = this.spec
        if (current == null) {
            update("INSERT INTO meta (key, value) VALUES (?, ?)", META_MODEL, spec.model)
            update("INSERT INTO meta (key, value) VALUES (?, ?)", META_DIMENSION, spec.dimension.toString())
            this.spec = spec
        } else {
            check(current == spec) {
                "This graph's embeddings come from ${current.model} (${current.dimension} dimensions), not ${spec.model} " +
                    "(${spec.dimension}); use the model the graph was created with, or re-embed it into a new graph."
            }
        }
    }

    override fun load(): LatticeStore.Loaded = tx {
        LatticeStore.Loaded(
            engrams = rows("SELECT * FROM engram ORDER BY rowid", ::engramOf),
            edges = rows("SELECT * FROM edge ORDER BY rowid") { rs ->
                val type = runCatching { Edge.Type.valueOf(rs.getString("type")) }.getOrNull()
                if (type == null) {
                    logger.warn("Skipped an edge of unknown type {}; a newer Lattice wrote it.", rs.getString("type"))
                    null
                } else {
                    EdgeRecord(
                        from = rs.getLong("from_id"),
                        to = rs.getLong("to_id"),
                        type = type,
                        weight = rs.getDouble("weight"),
                        lastReinforcedAt = Instant.parse(rs.getString("last_reinforced_at")),
                        weightDecayedAt = Instant.parse(rs.getString("weight_decayed_at")),
                    )
                }
            },
            verdicts = rows("SELECT * FROM verdict ORDER BY rowid") { rs ->
                VerdictRecord(
                    rs.getString("hash_lo"), rs.getString("hash_hi"), rs.getString("judge_key"), rs.getString("verdict"),
                    rs.getInt("votes"), Instant.parse(rs.getString("created_at")),
                )
            },
            entities = rows("SELECT * FROM entity ORDER BY rowid") { rs ->
                EntityRecord(
                    rs.getLong("id"), rs.getString("canonical_name"), rs.getString("name_lc"), rs.getInt("mention_count"),
                    rs.getString("first_event_date"),
                )
            },
            entityLinks = rows("SELECT engram_id, entity_id FROM engram_entity ORDER BY rowid") { it.getLong(1) to it.getLong(2) },
            resolvedEntityIds = rows("SELECT engram_id FROM entity_resolved ORDER BY rowid") { it.getLong(1) }.toCollection(LinkedHashSet()),
            scenes = rows("SELECT * FROM scene ORDER BY rowid") { rs ->
                SceneRecord(
                    sceneId = rs.getLong("scene_id"),
                    title = rs.getString("title"),
                    conversationId = rs.getString("conversation_id"),
                    chatKey = rs.getString("chat_key"),
                    firstMessageId = rs.getLong("first_message_id"),
                    lastMessageId = rs.getLong("last_message_id"),
                    dateFrom = rs.getString("date_from"),
                    dateTo = rs.getString("date_to"),
                    inProgress = rs.getInt("in_progress") == 1,
                    hubEngramId = rs.longOrNull("hub_engram_id"),
                    startedAt = rs.getString("started_at")?.let(Instant::parse),
                    seq = rs.getLong("seq"),
                )
            },
            sceneMembers = rows("SELECT scene_id, engram_id FROM scene_member ORDER BY rowid") { it.getLong(1) to it.getLong(2) },
            sceneWork = rows("SELECT * FROM scene_work ORDER BY rowid") { rs ->
                val state = runCatching { SceneWorkState.valueOf(rs.getString("state")) }.getOrNull()
                if (state == null) {
                    logger.warn("Skipped the work state of scene {}: unknown state {}.", rs.getLong("scene_id"), rs.getString("state"))
                    null
                } else {
                    SceneWorkRecord(
                        sceneId = rs.getLong("scene_id"),
                        state = state,
                        synopsis = rs.getString("synopsis"),
                        extractedThrough = rs.longOrNull("extracted_through"),
                        extractAttempts = rs.getInt("extract_attempts"),
                        chunksDone = rs.getInt("chunks_done"),
                        chunksFailed = rs.getInt("chunks_failed"),
                        narrateAttempts = rs.getInt("narrate_attempts"),
                    )
                }
            },
            sceneCursors = rows("SELECT * FROM scene_cursor ORDER BY rowid") { rs ->
                SceneCursorRecord(
                    conversationId = rs.getString("conversation_id"),
                    chatKey = rs.getString("chat_key"),
                    segmentedThrough = rs.longOrNull("segmented_through"),
                    segmentAttempts = rs.getInt("segment_attempts"),
                    fallbackThrough = rs.longOrNull("fallback_through"),
                    extractAttempts = rs.getInt("extract_attempts"),
                    blockedSince = rs.getString("blocked_since")?.let(Instant::parse),
                    finished = rs.getInt("finished") == 1,
                )
            },
            threads = rows("SELECT * FROM thread ORDER BY rowid") { rs ->
                val state = runCatching { ThreadState.valueOf(rs.getString("state")) }.getOrNull()
                if (state == null) {
                    logger.warn("Skipped thread {}: unknown state {}; a newer Lattice wrote it.", rs.getLong("id"), rs.getString("state"))
                    null
                } else {
                    ThreadRecord(
                        id = rs.getLong("id"),
                        chatKey = rs.getString("chat_key"),
                        description = rs.getString("description"),
                        state = state,
                        openedSceneId = rs.longOrNull("opened_scene_id"),
                        closedSceneId = rs.longOrNull("closed_scene_id"),
                        staleCount = rs.getInt("stale_count"),
                        name = rs.getString("name"),
                    )
                }
            },
            threadLinks = rows("SELECT thread_id, engram_id, role FROM thread_link ORDER BY rowid") { rs ->
                val role = runCatching { ThreadRole.valueOf(rs.getString(3)) }.getOrNull()
                if (role == null) {
                    logger.warn("Skipped a link of thread {}: unknown role {}; a newer Lattice wrote it.", rs.getLong(1), rs.getString(3))
                    null
                } else {
                    ThreadLinkRecord(rs.getLong(1), rs.getLong(2), role)
                }
            },
            periods = rows("SELECT * FROM period ORDER BY rowid") { rs ->
                PeriodRecord(
                    id = rs.getLong("id"),
                    chatKey = rs.getString("chat_key"),
                    name = rs.getString("name"),
                    gist = rs.getString("gist"),
                    state = PeriodState.valueOf(rs.getString("state")),
                    eraId = rs.longOrNull("era_id"),
                    needsRename = rs.getInt("needs_rename") == 1,
                    namingSceneIds = parseIds(rs.getString("naming_scene_ids")),
                    dateFrom = rs.getString("date_from"),
                    dateTo = rs.getString("date_to"),
                )
            },
            eras = rows("SELECT * FROM era ORDER BY rowid") { rs ->
                EraRecord(
                    id = rs.getLong("id"),
                    chatKey = rs.getString("chat_key"),
                    name = rs.getString("name"),
                    gist = rs.getString("gist"),
                    frozen = rs.getInt("frozen") == 1,
                    namingSceneIds = parseIds(rs.getString("naming_scene_ids")),
                    dateFrom = rs.getString("date_from"),
                    dateTo = rs.getString("date_to"),
                )
            },
            periodMembers = rows("SELECT scene_id, period_id FROM period_member ORDER BY rowid") {
                PeriodMemberRecord(it.getLong(1), it.getLong(2))
            },
            periodAliases = rows("SELECT chat_key, alias, period_id FROM period_alias ORDER BY rowid") {
                PeriodAliasRecord(it.getString(1), it.getString(2), it.getLong(3))
            },
            eraAliases = rows("SELECT chat_key, alias, era_id FROM era_alias ORDER BY rowid") {
                EraAliasRecord(it.getString(1), it.getString(2), it.getLong(3))
            },
            periodExclusions = rows("SELECT scene_id, chat_key, reason FROM period_excluded ORDER BY rowid") {
                PeriodExclusionRecord(it.getLong(1), it.getString(2), it.getString(3))
            },
            periodFailures = rows("SELECT * FROM period_failure ORDER BY rowid") { rs ->
                // A kind this Lattice doesn't know (a newer one wrote it) is skipped, never a load failure.
                runCatching { PeriodFailureKind.valueOf(rs.getString("kind")) }.getOrNull()?.let { kind ->
                    PeriodFailureRecord(
                        rs.getString("chat_key"), kind, rs.getLong("first_scene_id"), rs.getInt("count"),
                        Instant.parse(rs.getString("updated_at")),
                    )
                }
            },
            periodGenerations = rows("SELECT chat_key, generation FROM period_generation ORDER BY rowid") {
                it.getString(1) to it.getLong(2)
            }.toMap(),
            catalogueSnapshots = rows("SELECT * FROM period_catalogue ORDER BY rowid") { rs ->
                CatalogueSnapshot(
                    chatKey = rs.getString("chat_key"),
                    language = rs.getString("language"),
                    text = rs.getString("text"),
                    coverage = rs.getString("coverage"),
                    sourceGeneration = rs.getLong("source_generation"),
                    contentHash = rs.getString("content_hash"),
                    publishedAt = Instant.parse(rs.getString("published_at")),
                )
            },
        )
    }

    /**
     * An engram row. Every other table points at engrams, so a value this Lattice doesn't know (a newer one wrote it)
     * keeps the row: an unknown layer reads as SEMANTIC, an unknown speaker as none (like an unknown origin).
     */
    private fun engramOf(rs: ResultSet): Engram = Engram(
        id = rs.getLong("id"),
        content = rs.getString("content"),
        embedding = rs.getBytes("embedding").toFloatArray(),
        salience = rs.getDouble("salience"),
        layer = rs.getString("layer").let { name ->
            runCatching { Engram.Layer.valueOf(name) }.getOrElse {
                logger.warn("Engram {} has an unknown layer {}; read as SEMANTIC.", rs.getLong("id"), name)
                Engram.Layer.SEMANTIC
            }
        },
        createdAt = Instant.parse(rs.getString("created_at")),
        lastActivatedAt = Instant.parse(rs.getString("last_activated_at")),
        activationCount = rs.getInt("activation_count"),
        softReplays = rs.getInt("soft_replays"),
        speaker = rs.getString("speaker")?.let { name ->
            runCatching { Speaker.valueOf(name) }.getOrElse {
                logger.warn("Engram {} has an unknown speaker {}; read as none.", rs.getLong("id"), name)
                null
            }
        },
        sourceMessageId = rs.longOrNull("source_message_id"),
        isEpisode = rs.getInt("is_episode") == 1,
        supersededBy = rs.longOrNull("superseded_by"),
        salienceDecayedAt = Instant.parse(rs.getString("salience_decayed_at")),
        eventDate = rs.getString("event_date"),
        isEpisodeHub = rs.getInt("is_episode_hub") == 1,
        origin = rs.getString("origin")?.let { runCatching { Engram.Origin.valueOf(it) }.getOrNull() },
        saidFrom = rs.getString("said_from")?.let(Instant::parse),
        saidTo = rs.getString("said_to")?.let(Instant::parse),
        dueDate = rs.getString("due_date"),
        tainted = rs.getInt("tainted") == 1,
    )

    override fun putEngram(engram: Engram) = write {
        spec?.let {
            require(engram.embedding.size == it.dimension) {
                "Engram ${engram.id} has a ${engram.embedding.size}-dimensional embedding, but this graph's are " +
                    "${it.dimension}-dimensional (${it.model}); embed it with the graph's model."
            }
        }
        update(
            """
            INSERT INTO engram (id, content, embedding, salience, layer, created_at, last_activated_at, activation_count,
                soft_replays, speaker, source_message_id, is_episode, superseded_by, salience_decayed_at, event_date,
                is_episode_hub, origin, said_from, said_to, due_date, tainted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                content = excluded.content, embedding = excluded.embedding, salience = excluded.salience,
                layer = excluded.layer, created_at = excluded.created_at, last_activated_at = excluded.last_activated_at,
                activation_count = excluded.activation_count, soft_replays = excluded.soft_replays,
                speaker = excluded.speaker, source_message_id = excluded.source_message_id,
                is_episode = excluded.is_episode, superseded_by = excluded.superseded_by,
                salience_decayed_at = excluded.salience_decayed_at, event_date = excluded.event_date,
                is_episode_hub = excluded.is_episode_hub, origin = excluded.origin, said_from = excluded.said_from,
                said_to = excluded.said_to, due_date = excluded.due_date, tainted = excluded.tainted
            """,
            engram.id, engram.content, engram.embedding.toBlob(), engram.salience, engram.layer.name,
            engram.createdAt.toString(), engram.lastActivatedAt.toString(), engram.activationCount, engram.softReplays,
            engram.speaker?.name, engram.sourceMessageId, engram.isEpisode, engram.supersededBy,
            engram.salienceDecayedAt.toString(), engram.eventDate, engram.isEpisodeHub, engram.origin?.name,
            engram.saidFrom?.toString(), engram.saidTo?.toString(), engram.dueDate, engram.tainted,
        )
    }

    override fun deleteEngram(id: Long) = write { update("DELETE FROM engram WHERE id = ?", id) }

    override fun putEdge(edge: EdgeRecord) = write {
        // REPLACE drops the row on the same unordered pair, whichever way it pointed.
        update(
            "INSERT OR REPLACE INTO edge (from_id, to_id, type, weight, last_reinforced_at, weight_decayed_at) VALUES (?, ?, ?, ?, ?, ?)",
            edge.from, edge.to, edge.type.name, edge.weight, edge.lastReinforcedAt.toString(), edge.weightDecayedAt.toString(),
        )
    }

    override fun deleteEdge(a: Long, b: Long) = write {
        update("DELETE FROM edge WHERE min(from_id, to_id) = ? AND max(from_id, to_id) = ?", minOf(a, b), maxOf(a, b))
    }

    override fun putVerdict(verdict: VerdictRecord) = write {
        update(
            """
            INSERT INTO verdict (hash_lo, hash_hi, judge_key, verdict, votes, created_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (hash_lo, hash_hi, judge_key) DO UPDATE SET
                verdict = excluded.verdict, votes = excluded.votes, created_at = excluded.created_at
            """,
            verdict.hashLo, verdict.hashHi, verdict.judgeKey, verdict.verdict, verdict.votes, verdict.createdAt.toString(),
        )
    }

    override fun putEntity(entity: EntityRecord) = write {
        update(
            """
            INSERT INTO entity (id, canonical_name, name_lc, mention_count, first_event_date) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                canonical_name = excluded.canonical_name, name_lc = excluded.name_lc,
                mention_count = excluded.mention_count, first_event_date = excluded.first_event_date
            """,
            entity.id, entity.canonicalName, entity.nameLc, entity.mentionCount, entity.firstEventDate,
        )
    }

    override fun linkEngramEntity(engramId: Long, entityId: Long) = write {
        update("INSERT OR IGNORE INTO engram_entity (engram_id, entity_id) VALUES (?, ?)", engramId, entityId)
    }

    override fun markEntityResolved(engramId: Long) = write {
        update("INSERT OR IGNORE INTO entity_resolved (engram_id) VALUES (?)", engramId)
    }

    override fun mergeEntities(fromId: Long, toId: Long) = write {
        if (fromId == toId) return@write
        // Move what doesn't collide, then drop the colliding leftovers with the entity itself.
        update("UPDATE OR IGNORE engram_entity SET entity_id = ? WHERE entity_id = ?", toId, fromId)
        update("DELETE FROM engram_entity WHERE entity_id = ?", fromId)
        update("DELETE FROM entity WHERE id = ?", fromId)
    }

    override fun putScene(scene: SceneRecord) = write {
        update(
            """
            INSERT INTO scene (scene_id, title, conversation_id, chat_key, first_message_id, last_message_id, date_from,
                date_to, in_progress, hub_engram_id, started_at, seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (scene_id) DO UPDATE SET
                title = excluded.title, conversation_id = excluded.conversation_id, chat_key = excluded.chat_key,
                first_message_id = excluded.first_message_id, last_message_id = excluded.last_message_id,
                date_from = excluded.date_from, date_to = excluded.date_to, in_progress = excluded.in_progress,
                hub_engram_id = excluded.hub_engram_id, started_at = excluded.started_at, seq = excluded.seq
            """,
            scene.sceneId, scene.title, scene.conversationId, scene.chatKey, scene.firstMessageId, scene.lastMessageId,
            scene.dateFrom, scene.dateTo, scene.inProgress, scene.hubEngramId, scene.startedAt?.toString(), scene.seq,
        )
    }

    override fun putSceneWork(work: SceneWorkRecord) = write {
        update(
            """
            INSERT INTO scene_work (scene_id, state, synopsis, extracted_through, extract_attempts, chunks_done,
                chunks_failed, narrate_attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (scene_id) DO UPDATE SET
                state = excluded.state, synopsis = excluded.synopsis, extracted_through = excluded.extracted_through,
                extract_attempts = excluded.extract_attempts, chunks_done = excluded.chunks_done,
                chunks_failed = excluded.chunks_failed, narrate_attempts = excluded.narrate_attempts
            """,
            work.sceneId, work.state.name, work.synopsis, work.extractedThrough, work.extractAttempts, work.chunksDone,
            work.chunksFailed, work.narrateAttempts,
        )
    }

    override fun putSceneCursor(cursor: SceneCursorRecord) = write {
        update(
            """
            INSERT INTO scene_cursor (conversation_id, chat_key, segmented_through, segment_attempts, fallback_through,
                extract_attempts, blocked_since, finished)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (conversation_id) DO UPDATE SET
                chat_key = excluded.chat_key, segmented_through = excluded.segmented_through,
                segment_attempts = excluded.segment_attempts, fallback_through = excluded.fallback_through,
                extract_attempts = excluded.extract_attempts, blocked_since = excluded.blocked_since,
                finished = excluded.finished
            """,
            cursor.conversationId, cursor.chatKey, cursor.segmentedThrough, cursor.segmentAttempts, cursor.fallbackThrough,
            cursor.extractAttempts, cursor.blockedSince?.toString(), cursor.finished,
        )
    }

    override fun putSceneMember(sceneId: Long, engramId: Long) = write {
        update("INSERT OR IGNORE INTO scene_member (scene_id, engram_id) VALUES (?, ?)", sceneId, engramId)
    }

    override fun redirectSceneMembers(fromEngramId: Long, toEngramId: Long) = write {
        if (fromEngramId == toEngramId) return@write
        update("UPDATE OR IGNORE scene_member SET engram_id = ? WHERE engram_id = ?", toEngramId, fromEngramId)
        update("DELETE FROM scene_member WHERE engram_id = ?", fromEngramId)
    }

    override fun putThread(thread: ThreadRecord) = write {
        val now = Instant.now().toString()
        update(
            """
            INSERT INTO thread (id, chat_key, description, state, opened_scene_id, closed_scene_id, stale_count, name,
                created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                chat_key = excluded.chat_key, description = excluded.description, state = excluded.state,
                opened_scene_id = excluded.opened_scene_id, closed_scene_id = excluded.closed_scene_id,
                stale_count = excluded.stale_count, name = excluded.name, updated_at = excluded.updated_at
            """,
            thread.id, thread.chatKey, thread.description, thread.state.name, thread.openedSceneId, thread.closedSceneId,
            thread.staleCount, thread.name, now, now,
        )
    }

    override fun putThreadLink(link: ThreadLinkRecord) = write {
        update("INSERT OR IGNORE INTO thread_link (thread_id, engram_id, role) VALUES (?, ?, ?)", link.threadId, link.engramId, link.role.name)
    }

    override fun redirectThreadLinks(fromEngramId: Long, toEngramId: Long) = write {
        if (fromEngramId == toEngramId) return@write
        update("UPDATE OR IGNORE thread_link SET engram_id = ? WHERE engram_id = ?", toEngramId, fromEngramId)
        update("DELETE FROM thread_link WHERE engram_id = ?", fromEngramId)
    }

    override fun deleteThreadLinks(engramId: Long) = write { update("DELETE FROM thread_link WHERE engram_id = ?", engramId) }

    override fun putPeriod(period: PeriodRecord) = write {
        val now = Instant.now().toString()
        update(
            """
            INSERT INTO period (id, chat_key, name, name_key, gist, date_from, date_to, state, era_id, needs_rename,
                naming_scene_ids, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                chat_key = excluded.chat_key, name = excluded.name, name_key = excluded.name_key, gist = excluded.gist,
                date_from = excluded.date_from, date_to = excluded.date_to, state = excluded.state,
                era_id = excluded.era_id, needs_rename = excluded.needs_rename,
                naming_scene_ids = excluded.naming_scene_ids, updated_at = excluded.updated_at
            """,
            period.id, period.chatKey, period.name, periodNameKey(period.name), period.gist, period.dateFrom, period.dateTo,
            period.state.name, period.eraId, period.needsRename, formatIds(period.namingSceneIds), now, now,
        )
    }

    override fun deletePeriod(periodId: Long) = write { update("DELETE FROM period WHERE id = ?", periodId) }

    override fun putPeriodMember(member: PeriodMemberRecord) = write {
        update("INSERT INTO period_member (scene_id, period_id) VALUES (?, ?)", member.sceneId, member.periodId)
    }

    override fun putPeriodAlias(alias: PeriodAliasRecord) = write {
        update(
            "INSERT INTO period_alias (chat_key, alias_key, alias, period_id) VALUES (?, ?, ?, ?)",
            alias.chatKey, periodNameKey(alias.alias), alias.alias, alias.periodId,
        )
    }

    override fun putEra(era: EraRecord) = write {
        val now = Instant.now().toString()
        update(
            """
            INSERT INTO era (id, chat_key, name, name_key, gist, date_from, date_to, frozen, naming_scene_ids, created_at,
                updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                chat_key = excluded.chat_key, name = excluded.name, name_key = excluded.name_key, gist = excluded.gist,
                date_from = excluded.date_from, date_to = excluded.date_to, frozen = excluded.frozen,
                naming_scene_ids = excluded.naming_scene_ids, updated_at = excluded.updated_at
            """,
            era.id, era.chatKey, era.name, periodNameKey(era.name), era.gist, era.dateFrom, era.dateTo, era.frozen,
            formatIds(era.namingSceneIds), now, now,
        )
    }

    override fun deleteEra(eraId: Long) = write { update("DELETE FROM era WHERE id = ?", eraId) }

    override fun putEraAlias(alias: EraAliasRecord) = write {
        update(
            "INSERT INTO era_alias (chat_key, alias_key, alias, era_id) VALUES (?, ?, ?, ?)",
            alias.chatKey, periodNameKey(alias.alias), alias.alias, alias.eraId,
        )
    }

    override fun putPeriodExclusion(exclusion: PeriodExclusionRecord) = write {
        update(
            "INSERT OR IGNORE INTO period_excluded (scene_id, chat_key, reason, created_at) VALUES (?, ?, ?, ?)",
            exclusion.sceneId, exclusion.chatKey, exclusion.reason, Instant.now().toString(),
        )
    }

    override fun putPeriodFailure(failure: PeriodFailureRecord) = write {
        if (failure.count <= 0) {
            update(
                "DELETE FROM period_failure WHERE chat_key = ? AND kind = ? AND first_scene_id = ?",
                failure.chatKey, failure.kind.name, failure.firstSceneId,
            )
        } else {
            update(
                """
                INSERT INTO period_failure (chat_key, kind, first_scene_id, count, updated_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (chat_key, kind, first_scene_id) DO UPDATE SET count = excluded.count, updated_at = excluded.updated_at
                """,
                failure.chatKey, failure.kind.name, failure.firstSceneId, failure.count, (failure.updatedAt ?: Instant.now()).toString(),
            )
        }
    }

    override fun putPeriodGeneration(chatKey: String, generation: Long) = write {
        update(
            """
            INSERT INTO period_generation (chat_key, generation, updated_at) VALUES (?, ?, ?)
            ON CONFLICT (chat_key) DO UPDATE SET generation = excluded.generation, updated_at = excluded.updated_at
            """,
            chatKey, generation, Instant.now().toString(),
        )
    }

    override fun putCatalogueSnapshot(snapshot: CatalogueSnapshot) = write {
        update(
            """
            INSERT INTO period_catalogue (chat_key, language, text, coverage, source_generation, content_hash, published_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (chat_key, language) DO UPDATE SET
                text = excluded.text, coverage = excluded.coverage, source_generation = excluded.source_generation,
                content_hash = excluded.content_hash, published_at = excluded.published_at
            """,
            snapshot.chatKey, snapshot.language, snapshot.text, snapshot.coverage, snapshot.sourceGeneration,
            snapshot.contentHash, snapshot.publishedAt.toString(),
        )
    }

    override fun deletePeriodLayer(chatKey: String) = write {
        // Periods first (members and aliases cascade), then eras (aliases cascade); the alias deletes catch an alias
        // whose period is of another chat.
        for (table in listOf(
            "period", "period_alias", "era", "era_alias", "period_excluded", "period_failure", "period_generation",
            "period_catalogue",
        )) {
            update("DELETE FROM $table WHERE chat_key = ?", chatKey)
        }
    }

    override fun batch(block: (LatticeStore) -> Unit) = write { block(this) }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        statements.values.forEach { runCatching { it.close() } }
        statements.clear()
        connection.close()
    }

    private fun write(block: () -> Unit): Unit = tx(block)

    /** Runs [block] in a transaction, or a savepoint inside one: all of it lands or, on a throw, none. */
    private fun <T> tx(block: () -> T): T = lock.withLock {
        check(!closed) { "This Lattice store is closed; open the graph again." }
        check(!aborted) { "This Lattice store's transaction was rolled back by a failed write; the batch can't go on." }
        val savepoint = "s$depth"
        connection.createStatement().use { it.execute("SAVEPOINT $savepoint") }
        depth++
        val result = try {
            block()
        } catch (t: Throwable) {
            depth--
            if (!aborted) {
                runCatching {
                    connection.createStatement().use {
                        it.execute("ROLLBACK TO $savepoint")
                        it.execute("RELEASE $savepoint")
                    }
                }.onFailure {
                    t.addSuppressed(it)
                    abort()
                }
            }
            if (depth == 0) aborted = false
            throw t
        }
        depth--
        try {
            check(!aborted) { "This Lattice store's transaction was rolled back by a failed write; nothing of the batch landed." }
            // The outermost RELEASE commits; when it fails (a deferred constraint, a busy or full disk) SQLite keeps
            // the transaction open, and every later write would nest in it and never commit.
            connection.createStatement().use { it.execute("RELEASE $savepoint") }
        } catch (t: Throwable) {
            if (!aborted) abort()
            if (depth == 0) aborted = false
            throw t
        }
        result
    }

    /** Rolls the whole transaction back (ignoring a failure: there may be none left); the open savepoints are void. */
    private fun abort() {
        runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
        aborted = depth > 0
    }

    private fun statement(sql: String): PreparedStatement =
        statements.getOrPut(sql) { connection.prepareStatement(sql.trimIndent()) }.also { it.clearParameters() }

    private fun update(sql: String, vararg args: Any?): Int {
        val s = statement(sql)
        args.forEachIndexed { i, arg ->
            val at = i + 1
            when (arg) {
                null -> s.setNull(at, Types.NULL)
                is Long -> s.setLong(at, arg)
                is Int -> s.setInt(at, arg)
                is Double -> s.setDouble(at, arg)
                is Boolean -> s.setInt(at, if (arg) 1 else 0)
                is String -> s.setString(at, arg)
                is ByteArray -> s.setBytes(at, arg)
                else -> error("No SQLite binding for ${arg::class}.")
            }
        }
        return s.executeUpdate()
    }

    private fun query(sql: String, each: (ResultSet) -> Unit) {
        statement(sql).executeQuery().use { rs -> while (rs.next()) each(rs) }
    }

    private fun <T : Any> rows(sql: String, map: (ResultSet) -> T?): List<T> {
        val out = ArrayList<T>()
        query(sql) { rs -> map(rs)?.let(out::add) }
        return out
    }

    private companion object {
        const val META_MODEL = "embedding.model"
        const val META_DIMENSION = "embedding.dimension"

        fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeIf { !wasNull() }

        fun FloatArray.toBlob(): ByteArray {
            val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in this) buffer.putFloat(f)
            return buffer.array()
        }

        fun ByteArray.toFloatArray(): FloatArray {
            val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(size / 4) { buffer.float }
        }

        /** A naming-scene set as stored: ascending ids, comma-separated ('' = none). */
        fun formatIds(ids: Set<Long>): String = ids.sorted().joinToString(",")

        fun parseIds(raw: String?): Set<Long> = raw.orEmpty().split(',').mapNotNullTo(LinkedHashSet()) { it.trim().toLongOrNull() }
    }
}
