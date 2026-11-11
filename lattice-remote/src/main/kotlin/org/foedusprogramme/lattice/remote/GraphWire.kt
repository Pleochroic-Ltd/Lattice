package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.foedusprogramme.lattice.core.CatalogueSnapshot
import org.foedusprogramme.lattice.core.Edge
import org.foedusprogramme.lattice.core.EdgeRecord
import org.foedusprogramme.lattice.core.EmbeddingSpec
import org.foedusprogramme.lattice.core.Engram
import org.foedusprogramme.lattice.core.EntityRecord
import org.foedusprogramme.lattice.core.EraAliasRecord
import org.foedusprogramme.lattice.core.EraRecord
import org.foedusprogramme.lattice.core.InMemoryLatticeStore.Change
import org.foedusprogramme.lattice.core.InMemoryLatticeStore.Row
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Base64

/** A device's claim on an id slot of a shared graph: the `device` record. */
internal data class DeviceRecord(val slot: Int, val deviceId: String, val name: String?, val claimedAt: Instant)

/** One op read from the server, as the mirror takes it. */
internal sealed interface WireOp {
    data class Mirror(val change: Change) : WireOp

    data class Device(val slot: Int, val record: DeviceRecord?) : WireOp

    data class Unknown(val description: String) : WireOp
}

/**
 * Lattice's rows as Pleochroic graph ops, both ways; the one place the wire encoding lives (`lattice-remote/WIRE.md`
 * documents it). Engrams and edges have ops of their own; every other row is a `putRecord`/`deleteRecord` with a
 * stable `type` and a `key` built from the row's identity, its parts joined by `/` with `%` and `/` in a part escaped.
 * Field names are the record's property names; instants are ISO-8601 strings; an absent (null) field is left out; an
 * embedding is base64 of its little-endian float32 bytes; ids are JSON numbers (at most 53 bits).
 */
internal object GraphWire {
    const val VERDICT = "verdict"
    const val ENTITY = "entity"
    const val ENTITY_LINK = "entityLink"
    const val ENTITY_RESOLVED = "entityResolved"
    const val SCENE = "scene"
    const val SCENE_MEMBER = "sceneMember"
    const val SCENE_WORK = "sceneWork"
    const val SCENE_CURSOR = "sceneCursor"
    const val THREAD = "thread"
    const val THREAD_LINK = "threadLink"
    const val PERIOD = "period"
    const val ERA = "era"
    const val PERIOD_MEMBER = "periodMember"
    const val PERIOD_ALIAS = "periodAlias"
    const val ERA_ALIAS = "eraAlias"
    const val PERIOD_EXCLUSION = "periodExclusion"
    const val PERIOD_FAILURE = "periodFailure"
    const val PERIOD_GENERATION = "periodGeneration"
    const val CATALOGUE = "catalogue"
    const val DEVICE = "device"

    fun encode(change: Change): JsonObject {
        val row = change.row
        return when {
            row is Row.EngramRow && change.removed -> buildJsonObject {
                put("op", "deleteEngram")
                put("id", row.engram.id)
            }
            row is Row.EngramRow -> buildJsonObject {
                put("op", "putEngram")
                put("engram", engram(row.engram))
            }
            row is Row.EdgeRow && change.removed -> buildJsonObject {
                put("op", "deleteEdge")
                put("a", row.edge.from)
                put("b", row.edge.to)
            }
            row is Row.EdgeRow -> edge(row.edge)
            else -> {
                val (type, key, data) = record(row)
                buildJsonObject {
                    put("op", if (change.removed) "deleteRecord" else "putRecord")
                    put("type", type)
                    put("key", key)
                    if (!change.removed) put("data", data)
                }
            }
        }
    }

    fun putDevice(device: DeviceRecord): JsonObject = buildJsonObject {
        put("op", "putRecord")
        put("type", DEVICE)
        put("key", device.slot.toString())
        put(
            "data",
            buildJsonObject {
                put("slot", device.slot)
                put("deviceId", device.deviceId)
                device.name?.let { put("name", it) }
                put("claimedAt", device.claimedAt.toString())
            },
        )
    }

    fun embedding(spec: EmbeddingSpec): JsonObject = buildJsonObject {
        put("model", spec.model)
        put("dimension", spec.dimension)
    }

    fun embeddingOf(element: JsonElement?): EmbeddingSpec? {
        val o = element as? JsonObject ?: return null
        val model = o.string("model") ?: return null
        val dimension = o.int("dimension") ?: return null
        return EmbeddingSpec(model, dimension)
    }

    /** Decodes one op; a malformed op or an unknown record type comes back as [WireOp.Unknown]. */
    fun decode(op: JsonObject): WireOp = try {
        decodeOrThrow(op)
    } catch (e: WireException) {
        WireOp.Unknown(e.message ?: "a malformed op")
    }

    private fun decodeOrThrow(op: JsonObject): WireOp = when (val kind = op.string("op")) {
        "putEngram" -> WireOp.Mirror(Change(Row.EngramRow(engramOf(op.obj("engram"))), false))
        "deleteEngram" -> WireOp.Mirror(Change(Row.EngramRow(placeholderEngram(op.long("id").need("id"))), true))
        "putEdge" -> WireOp.Mirror(Change(Row.EdgeRow(edgeOf(op)), false))
        "deleteEdge" -> WireOp.Mirror(
            Change(Row.EdgeRow(EdgeRecord(op.long("a").need("a"), op.long("b").need("b"), Edge.Type.SEMANTIC, 0.0, Instant.EPOCH)), true),
        )
        "putRecord" -> {
            val type = op.string("type").need("type")
            val data = op.obj("data")
            if (type == DEVICE) {
                WireOp.Device(data.int("slot").need("slot"), deviceOf(data))
            } else {
                WireOp.Mirror(Change(rowOf(type, data), false))
            }
        }
        "deleteRecord" -> {
            val type = op.string("type").need("type")
            val parts = splitKey(op.string("key").need("key"))
            if (type == DEVICE) WireOp.Device(parts.single().toIntOrNull().need("key"), null)
            else WireOp.Mirror(Change(rowOfKey(type, parts), true))
        }
        else -> WireOp.Unknown("an op of kind $kind")
    }

    fun key(vararg parts: Any): String = parts.joinToString("/") { it.toString().replace("%", "%25").replace("/", "%2F") }

    fun splitKey(key: String): List<String> = key.split('/').map { it.replace("%2F", "/").replace("%2f", "/").replace("%25", "%") }

    // -- Engrams and edges ----------------------------------------------------

    private fun engram(e: Engram): JsonObject = buildJsonObject {
        put("id", e.id)
        put("content", e.content)
        put("embedding", encodeVector(e.embedding))
        put("salience", e.salience)
        put("layer", e.layer.name)
        put("createdAt", e.createdAt.toString())
        put("lastActivatedAt", e.lastActivatedAt.toString())
        put("activationCount", e.activationCount)
        put("softReplays", e.softReplays)
        e.speaker?.let { put("speaker", it.name) }
        e.sourceMessageId?.let { put("sourceMessageId", it) }
        put("isEpisode", e.isEpisode)
        e.supersededBy?.let { put("supersededBy", it) }
        put("salienceDecayedAt", e.salienceDecayedAt.toString())
        e.eventDate?.let { put("eventDate", it) }
        put("isEpisodeHub", e.isEpisodeHub)
        e.origin?.let { put("origin", it.name) }
        e.saidFrom?.let { put("saidFrom", it.toString()) }
        e.saidTo?.let { put("saidTo", it.toString()) }
        e.dueDate?.let { put("dueDate", it) }
        put("tainted", e.tainted)
        e.speakerName?.let { put("speakerName", it) }
    }

    private fun engramOf(o: JsonObject): Engram {
        val createdAt = o.instant("createdAt").need("createdAt")
        return Engram(
            id = o.long("id").need("id"),
            content = o.string("content").need("content"),
            embedding = decodeVector(o.string("embedding").need("embedding")),
            salience = o.double("salience").need("salience"),
            layer = enumOf<Engram.Layer>(o.string("layer").need("layer")),
            createdAt = createdAt,
            lastActivatedAt = o.instant("lastActivatedAt") ?: createdAt,
            activationCount = o.int("activationCount") ?: 0,
            softReplays = o.int("softReplays") ?: 0,
            speaker = o.string("speaker")?.let { enumOrNull<Speaker>(it) },
            sourceMessageId = o.long("sourceMessageId"),
            isEpisode = o.bool("isEpisode") ?: false,
            supersededBy = o.long("supersededBy"),
            salienceDecayedAt = o.instant("salienceDecayedAt") ?: createdAt,
            eventDate = o.string("eventDate"),
            isEpisodeHub = o.bool("isEpisodeHub") ?: false,
            origin = o.string("origin")?.let { enumOrNull<Engram.Origin>(it) },
            saidFrom = o.instant("saidFrom"),
            saidTo = o.instant("saidTo"),
            dueDate = o.string("dueDate"),
            tainted = o.bool("tainted") ?: false,
            speakerName = o.string("speakerName"),
        )
    }

    private fun placeholderEngram(id: Long) = Engram(id, "", FloatArray(0), 0.0, Engram.Layer.EPISODIC, Instant.EPOCH, Instant.EPOCH)

    private fun edge(e: EdgeRecord): JsonObject = buildJsonObject {
        put("op", "putEdge")
        put("a", e.from)
        put("b", e.to)
        put("type", e.type.name)
        put("weight", e.weight)
        put("lastReinforcedAt", e.lastReinforcedAt.toString())
        put("weightDecayedAt", e.weightDecayedAt.toString())
    }

    private fun edgeOf(o: JsonObject): EdgeRecord {
        val lastReinforcedAt = o.instant("lastReinforcedAt").need("lastReinforcedAt")
        return EdgeRecord(
            from = o.long("a").need("a"),
            to = o.long("b").need("b"),
            type = enumOf<Edge.Type>(o.string("type").need("type")),
            weight = o.double("weight").need("weight"),
            lastReinforcedAt = lastReinforcedAt,
            weightDecayedAt = o.instant("weightDecayedAt") ?: lastReinforcedAt,
        )
    }

    fun encodeVector(v: FloatArray): String {
        val buffer = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buffer.putFloat(f)
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decodeVector(text: String): FloatArray {
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw WireException("an embedding that isn't base64")
        }
        if (bytes.size % 4 != 0) throw WireException("an embedding of ${bytes.size} bytes, not whole float32s")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    // -- Records --------------------------------------------------------------

    private fun record(row: Row): Triple<String, String, JsonObject> = when (row) {
        is Row.VerdictRow -> row.record.let { r ->
            Triple(VERDICT, key(r.hashLo, r.hashHi, r.judgeKey), obj {
                put("hashLo", r.hashLo); put("hashHi", r.hashHi); put("judgeKey", r.judgeKey); put("verdict", r.verdict)
                put("votes", r.votes); put("createdAt", r.createdAt.toString())
            })
        }
        is Row.EntityRow -> row.record.let { r ->
            Triple(ENTITY, key(r.id), obj {
                put("id", r.id); put("canonicalName", r.canonicalName); put("nameLc", r.nameLc)
                put("mentionCount", r.mentionCount); r.firstEventDate?.let { put("firstEventDate", it) }
            })
        }
        is Row.EntityLinkRow -> Triple(ENTITY_LINK, key(row.engramId, row.entityId), obj {
            put("engramId", row.engramId); put("entityId", row.entityId)
        })
        is Row.ResolvedRow -> Triple(ENTITY_RESOLVED, key(row.engramId), obj { put("engramId", row.engramId) })
        is Row.SceneRow -> row.record.let { r ->
            Triple(SCENE, key(r.sceneId), obj {
                put("sceneId", r.sceneId); put("title", r.title); put("conversationId", r.conversationId)
                put("chatKey", r.chatKey); put("firstMessageId", r.firstMessageId); put("lastMessageId", r.lastMessageId)
                r.dateFrom?.let { put("dateFrom", it) }; r.dateTo?.let { put("dateTo", it) }
                put("inProgress", r.inProgress); r.hubEngramId?.let { put("hubEngramId", it) }
                r.startedAt?.let { put("startedAt", it.toString()) }; put("seq", r.seq)
            })
        }
        is Row.SceneMemberRow -> Triple(SCENE_MEMBER, key(row.sceneId, row.engramId), obj {
            put("sceneId", row.sceneId); put("engramId", row.engramId)
        })
        is Row.SceneWorkRow -> row.record.let { r ->
            Triple(SCENE_WORK, key(r.sceneId), obj {
                put("sceneId", r.sceneId); put("state", r.state.name); r.synopsis?.let { put("synopsis", it) }
                r.extractedThrough?.let { put("extractedThrough", it) }; put("extractAttempts", r.extractAttempts)
                put("chunksDone", r.chunksDone); put("chunksFailed", r.chunksFailed); put("narrateAttempts", r.narrateAttempts)
            })
        }
        is Row.SceneCursorRow -> row.record.let { r ->
            Triple(SCENE_CURSOR, key(r.conversationId), obj {
                put("conversationId", r.conversationId); put("chatKey", r.chatKey)
                r.segmentedThrough?.let { put("segmentedThrough", it) }; put("segmentAttempts", r.segmentAttempts)
                r.fallbackThrough?.let { put("fallbackThrough", it) }; put("extractAttempts", r.extractAttempts)
                r.blockedSince?.let { put("blockedSince", it.toString()) }; put("finished", r.finished)
            })
        }
        is Row.ThreadRow -> row.record.let { r ->
            Triple(THREAD, key(r.id), obj {
                put("id", r.id); put("chatKey", r.chatKey); put("description", r.description); put("state", r.state.name)
                r.openedSceneId?.let { put("openedSceneId", it) }; r.closedSceneId?.let { put("closedSceneId", it) }
                put("staleCount", r.staleCount); r.name?.let { put("name", it) }
            })
        }
        is Row.ThreadLinkRow -> row.record.let { r ->
            Triple(THREAD_LINK, key(r.threadId, r.engramId, r.role.name), obj {
                put("threadId", r.threadId); put("engramId", r.engramId); put("role", r.role.name)
            })
        }
        is Row.PeriodRow -> row.record.let { r ->
            Triple(PERIOD, key(r.id), obj {
                put("id", r.id); put("chatKey", r.chatKey); put("name", r.name); r.gist?.let { put("gist", it) }
                put("state", r.state.name); r.eraId?.let { put("eraId", it) }; put("needsRename", r.needsRename)
                ids("namingSceneIds", r.namingSceneIds); r.dateFrom?.let { put("dateFrom", it) }; r.dateTo?.let { put("dateTo", it) }
            })
        }
        is Row.EraRow -> row.record.let { r ->
            Triple(ERA, key(r.id), obj {
                put("id", r.id); put("chatKey", r.chatKey); put("name", r.name); r.gist?.let { put("gist", it) }
                put("frozen", r.frozen); ids("namingSceneIds", r.namingSceneIds)
                r.dateFrom?.let { put("dateFrom", it) }; r.dateTo?.let { put("dateTo", it) }
            })
        }
        is Row.PeriodMemberRow -> Triple(PERIOD_MEMBER, key(row.record.sceneId), obj {
            put("sceneId", row.record.sceneId); put("periodId", row.record.periodId)
        })
        is Row.PeriodAliasRow -> row.record.let { r ->
            Triple(PERIOD_ALIAS, key(r.chatKey, r.alias), obj { put("chatKey", r.chatKey); put("alias", r.alias); put("periodId", r.periodId) })
        }
        is Row.EraAliasRow -> row.record.let { r ->
            Triple(ERA_ALIAS, key(r.chatKey, r.alias), obj { put("chatKey", r.chatKey); put("alias", r.alias); put("eraId", r.eraId) })
        }
        is Row.ExclusionRow -> row.record.let { r ->
            Triple(PERIOD_EXCLUSION, key(r.sceneId), obj {
                put("sceneId", r.sceneId); put("chatKey", r.chatKey); r.reason?.let { put("reason", it) }
            })
        }
        is Row.FailureRow -> row.record.let { r ->
            Triple(PERIOD_FAILURE, key(r.chatKey, r.kind.name, r.firstSceneId), obj {
                put("chatKey", r.chatKey); put("kind", r.kind.name); put("firstSceneId", r.firstSceneId); put("count", r.count)
                r.updatedAt?.let { put("updatedAt", it.toString()) }
            })
        }
        is Row.GenerationRow -> Triple(PERIOD_GENERATION, key(row.chatKey), obj {
            put("chatKey", row.chatKey); put("generation", row.generation)
        })
        is Row.CatalogueRow -> row.record.let { r ->
            Triple(CATALOGUE, key(r.chatKey, r.language), obj {
                put("chatKey", r.chatKey); put("language", r.language); put("text", r.text); r.coverage?.let { put("coverage", it) }
                put("sourceGeneration", r.sourceGeneration); put("contentHash", r.contentHash); put("publishedAt", r.publishedAt.toString())
            })
        }
        is Row.EngramRow, is Row.EdgeRow -> error("Engrams and edges have ops of their own.")
    }

    private fun rowOf(type: String, d: JsonObject): Row = when (type) {
        VERDICT -> Row.VerdictRow(
            VerdictRecord(
                d.string("hashLo").need("hashLo"), d.string("hashHi").need("hashHi"), d.string("judgeKey").need("judgeKey"),
                d.string("verdict").need("verdict"), d.int("votes") ?: 0, d.instant("createdAt") ?: Instant.EPOCH,
            ),
        )
        ENTITY -> Row.EntityRow(
            EntityRecord(
                d.long("id").need("id"), d.string("canonicalName").need("canonicalName"), d.string("nameLc").need("nameLc"),
                d.int("mentionCount") ?: 1, d.string("firstEventDate"),
            ),
        )
        ENTITY_LINK -> Row.EntityLinkRow(d.long("engramId").need("engramId"), d.long("entityId").need("entityId"))
        ENTITY_RESOLVED -> Row.ResolvedRow(d.long("engramId").need("engramId"))
        SCENE -> Row.SceneRow(
            SceneRecord(
                sceneId = d.long("sceneId").need("sceneId"),
                title = d.string("title").need("title"),
                conversationId = d.string("conversationId").need("conversationId"),
                chatKey = d.string("chatKey").need("chatKey"),
                firstMessageId = d.long("firstMessageId").need("firstMessageId"),
                lastMessageId = d.long("lastMessageId").need("lastMessageId"),
                dateFrom = d.string("dateFrom"),
                dateTo = d.string("dateTo"),
                inProgress = d.bool("inProgress") ?: false,
                hubEngramId = d.long("hubEngramId"),
                startedAt = d.instant("startedAt"),
                seq = d.long("seq") ?: 0,
            ),
        )
        SCENE_MEMBER -> Row.SceneMemberRow(d.long("sceneId").need("sceneId"), d.long("engramId").need("engramId"))
        SCENE_WORK -> Row.SceneWorkRow(
            SceneWorkRecord(
                sceneId = d.long("sceneId").need("sceneId"),
                state = enumOf(d.string("state").need("state")),
                synopsis = d.string("synopsis"),
                extractedThrough = d.long("extractedThrough"),
                extractAttempts = d.int("extractAttempts") ?: 0,
                chunksDone = d.int("chunksDone") ?: 0,
                chunksFailed = d.int("chunksFailed") ?: 0,
                narrateAttempts = d.int("narrateAttempts") ?: 0,
            ),
        )
        SCENE_CURSOR -> Row.SceneCursorRow(
            SceneCursorRecord(
                conversationId = d.string("conversationId").need("conversationId"),
                chatKey = d.string("chatKey").need("chatKey"),
                segmentedThrough = d.long("segmentedThrough"),
                segmentAttempts = d.int("segmentAttempts") ?: 0,
                fallbackThrough = d.long("fallbackThrough"),
                extractAttempts = d.int("extractAttempts") ?: 0,
                blockedSince = d.instant("blockedSince"),
                finished = d.bool("finished") ?: false,
            ),
        )
        THREAD -> Row.ThreadRow(
            ThreadRecord(
                id = d.long("id").need("id"),
                chatKey = d.string("chatKey").need("chatKey"),
                description = d.string("description").need("description"),
                state = d.string("state")?.let { enumOrNull<ThreadState>(it) } ?: ThreadState.OPEN,
                openedSceneId = d.long("openedSceneId"),
                closedSceneId = d.long("closedSceneId"),
                staleCount = d.int("staleCount") ?: 0,
                name = d.string("name"),
            ),
        )
        THREAD_LINK -> Row.ThreadLinkRow(
            ThreadLinkRecord(d.long("threadId").need("threadId"), d.long("engramId").need("engramId"), enumOf(d.string("role").need("role"))),
        )
        PERIOD -> Row.PeriodRow(
            PeriodRecord(
                id = d.long("id").need("id"),
                chatKey = d.string("chatKey").need("chatKey"),
                name = d.string("name").need("name"),
                gist = d.string("gist"),
                state = enumOf<PeriodState>(d.string("state").need("state")),
                eraId = d.long("eraId"),
                needsRename = d.bool("needsRename") ?: false,
                namingSceneIds = d.ids("namingSceneIds"),
                dateFrom = d.string("dateFrom"),
                dateTo = d.string("dateTo"),
            ),
        )
        ERA -> Row.EraRow(
            EraRecord(
                id = d.long("id").need("id"),
                chatKey = d.string("chatKey").need("chatKey"),
                name = d.string("name").need("name"),
                gist = d.string("gist"),
                frozen = d.bool("frozen") ?: false,
                namingSceneIds = d.ids("namingSceneIds"),
                dateFrom = d.string("dateFrom"),
                dateTo = d.string("dateTo"),
            ),
        )
        PERIOD_MEMBER -> Row.PeriodMemberRow(PeriodMemberRecord(d.long("sceneId").need("sceneId"), d.long("periodId").need("periodId")))
        PERIOD_ALIAS -> Row.PeriodAliasRow(
            PeriodAliasRecord(d.string("chatKey").need("chatKey"), d.string("alias").need("alias"), d.long("periodId").need("periodId")),
        )
        ERA_ALIAS -> Row.EraAliasRow(
            EraAliasRecord(d.string("chatKey").need("chatKey"), d.string("alias").need("alias"), d.long("eraId").need("eraId")),
        )
        PERIOD_EXCLUSION -> Row.ExclusionRow(
            PeriodExclusionRecord(d.long("sceneId").need("sceneId"), d.string("chatKey").need("chatKey"), d.string("reason")),
        )
        PERIOD_FAILURE -> Row.FailureRow(
            PeriodFailureRecord(
                d.string("chatKey").need("chatKey"), enumOf(d.string("kind").need("kind")), d.long("firstSceneId").need("firstSceneId"),
                d.int("count").need("count"), d.instant("updatedAt"),
            ),
        )
        PERIOD_GENERATION -> Row.GenerationRow(d.string("chatKey").need("chatKey"), d.long("generation").need("generation"))
        CATALOGUE -> Row.CatalogueRow(
            CatalogueSnapshot(
                chatKey = d.string("chatKey").need("chatKey"),
                language = d.string("language").need("language"),
                text = d.string("text").need("text"),
                coverage = d.string("coverage"),
                sourceGeneration = d.long("sourceGeneration") ?: 0,
                contentHash = d.string("contentHash").orEmpty(),
                publishedAt = d.instant("publishedAt") ?: Instant.EPOCH,
            ),
        )
        else -> throw WireException("a record of unknown type $type")
    }

    /** A row with only the identity [parts] of a `deleteRecord` key filled in: enough for the mirror to find it. */
    private fun rowOfKey(type: String, parts: List<String>): Row {
        fun long(i: Int) = parts.getOrNull(i)?.toLongOrNull() ?: throw WireException("a $type key without a number at part $i")
        fun text(i: Int) = parts.getOrNull(i) ?: throw WireException("a $type key without part $i")
        return when (type) {
            VERDICT -> Row.VerdictRow(VerdictRecord(text(0), text(1), text(2), "", 0, Instant.EPOCH))
            ENTITY -> Row.EntityRow(EntityRecord(long(0), "", ""))
            ENTITY_LINK -> Row.EntityLinkRow(long(0), long(1))
            ENTITY_RESOLVED -> Row.ResolvedRow(long(0))
            SCENE -> Row.SceneRow(SceneRecord(long(0), "", "", "", 0, 0))
            SCENE_MEMBER -> Row.SceneMemberRow(long(0), long(1))
            SCENE_WORK -> Row.SceneWorkRow(SceneWorkRecord(long(0), SceneWorkState.OPEN))
            SCENE_CURSOR -> Row.SceneCursorRow(SceneCursorRecord(text(0), ""))
            THREAD -> Row.ThreadRow(ThreadRecord(long(0), "", ""))
            THREAD_LINK -> Row.ThreadLinkRow(ThreadLinkRecord(long(0), long(1), enumOf(text(2))))
            PERIOD -> Row.PeriodRow(PeriodRecord(long(0), "", ""))
            ERA -> Row.EraRow(EraRecord(long(0), "", ""))
            PERIOD_MEMBER -> Row.PeriodMemberRow(PeriodMemberRecord(long(0), 0))
            PERIOD_ALIAS -> Row.PeriodAliasRow(PeriodAliasRecord(text(0), text(1), 0))
            ERA_ALIAS -> Row.EraAliasRow(EraAliasRecord(text(0), text(1), 0))
            PERIOD_EXCLUSION -> Row.ExclusionRow(PeriodExclusionRecord(long(0), ""))
            PERIOD_FAILURE -> Row.FailureRow(PeriodFailureRecord(text(0), enumOf<PeriodFailureKind>(text(1)), long(2), 0))
            PERIOD_GENERATION -> Row.GenerationRow(text(0), 0)
            CATALOGUE -> Row.CatalogueRow(CatalogueSnapshot(text(0), text(1), ""))
            else -> throw WireException("a record of unknown type $type")
        }
    }

    private fun deviceOf(d: JsonObject): DeviceRecord = DeviceRecord(
        d.int("slot").need("slot"), d.string("deviceId").need("deviceId"), d.string("name"), d.instant("claimedAt") ?: Instant.EPOCH,
    )

    // -- JSON helpers ---------------------------------------------------------

    private class WireException(message: String) : RuntimeException(message)

    private inline fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    private fun JsonObjectBuilder.ids(name: String, ids: Set<Long>) = putJsonArray(name) { ids.sorted().forEach { add(it) } }

    private fun <T : Any> T?.need(field: String): T = this ?: throw WireException("an op without '$field'")

    private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: throw WireException("an op without '$name'")

    private fun JsonObject.primitive(name: String): JsonPrimitive? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.string(name: String): String? = primitive(name)?.takeIf { it.isString }?.content

    private fun JsonObject.long(name: String): Long? = primitive(name)?.longOrNull

    private fun JsonObject.int(name: String): Int? = primitive(name)?.intOrNull

    private fun JsonObject.double(name: String): Double? = primitive(name)?.doubleOrNull

    private fun JsonObject.bool(name: String): Boolean? = primitive(name)?.booleanOrNull

    private fun JsonObject.instant(name: String): Instant? = string(name)?.let {
        try {
            Instant.parse(it)
        } catch (e: java.time.format.DateTimeParseException) {
            throw WireException("an op whose '$name' isn't an ISO-8601 instant")
        }
    }

    private fun JsonObject.ids(name: String): Set<Long> =
        (this[name] as? JsonArray)?.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.longOrNull } ?: emptySet()

    private inline fun <reified E : Enum<E>> enumOf(name: String): E = enumOrNull<E>(name) ?: throw WireException("an unknown ${E::class.simpleName} $name")

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
