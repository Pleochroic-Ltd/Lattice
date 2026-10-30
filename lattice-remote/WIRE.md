# Lattice graph ops on the wire

How `PleochroicGraphStore` writes a Lattice graph into standalone-service's graph storage (`docs/API.md`, "Lattice").
The encoding lives in one place, `GraphWire.kt`; this file documents it for the server side and for other clients.

## Conventions

- Field names are the Kotlin record's property names (camelCase). A null field is left out; a reader takes a missing
  field as null (or the record's default).
- Ids are JSON numbers. Every id comes from `IdAllocator` (`slot << 43 | counter`), at most 53 bits.
- Instants are ISO-8601 strings in UTC as `Instant.toString()` writes them (`2026-07-23T08:00:00.123456789Z`); dates
  (`eventDate`, `dueDate`, scene and period dates) are `YYYY-MM` or `YYYY-MM-DD` strings.
- Enums are their constant names (`SEMANTIC`, `PROVISIONAL`, …).
- An embedding is base64 (standard alphabet, padded) of the vector's little-endian float32 bytes, L2-normalized.

## Engrams and edges

```json
{"op": "putEngram", "engram": {"id": 1, "content": "…", "embedding": "AAAAPw…", "salience": 0.5, "layer": "EPISODIC",
  "createdAt": "…", "lastActivatedAt": "…", "activationCount": 0, "softReplays": 0, "speaker": "USER",
  "sourceMessageId": 42, "isEpisode": false, "supersededBy": 7, "salienceDecayedAt": "…", "eventDate": "2026-07-22",
  "isEpisodeHub": false, "origin": "MANUAL", "saidFrom": "…", "saidTo": "…", "dueDate": "2026-08", "tainted": false}}
{"op": "deleteEngram", "id": 1}
{"op": "putEdge", "a": 9, "b": 2, "type": "ABSTRACTS", "weight": 0.25, "lastReinforcedAt": "…", "weightDecayedAt": "…"}
{"op": "deleteEdge", "a": 9, "b": 2}
```

An edge is directed: `a` is `EdgeRecord.from`, `b` is `EdgeRecord.to` (parent → child, hub → member, newer → older).
It is keyed by the **unordered** pair: a `putEdge` replaces the edge stored either way, and `deleteEdge` deletes it
whichever way it points. (`types.ts` names the fields `a`/`b`; the client keeps those names and gives them a
direction.)

## Records

Every other row is `{"op": "putRecord", "type", "key", "data"}` or `{"op": "deleteRecord", "type", "key"}`. The key is
the row's identity: its parts joined by `/`, each part with `%` written `%25` and `/` written `%2F`. `data` holds the
whole row; a reader decodes a put from `data` and a delete from the key.

| type | key | data |
|---|---|---|
| `verdict` | `hashLo/hashHi/judgeKey` | `hashLo, hashHi, judgeKey, verdict, votes, createdAt` |
| `entity` | `id` | `id, canonicalName, nameLc, mentionCount, firstEventDate?` |
| `entityLink` | `engramId/entityId` | `engramId, entityId` |
| `entityResolved` | `engramId` | `engramId` |
| `scene` | `sceneId` | `sceneId, title, conversationId, chatKey, firstMessageId, lastMessageId, dateFrom?, dateTo?, inProgress, hubEngramId?, startedAt?, seq` |
| `sceneMember` | `sceneId/engramId` | `sceneId, engramId` |
| `sceneWork` | `sceneId` | `sceneId, state, synopsis?, extractedThrough?, extractAttempts, chunksDone, chunksFailed, narrateAttempts` (the scene pipeline's state of a scene) |
| `sceneCursor` | `conversationId` | `conversationId, chatKey, segmentedThrough?, segmentAttempts, fallbackThrough?, extractAttempts, blockedSince?, finished` (its progress through a conversation) |
| `thread` | `id` | `id, chatKey, description, state, openedSceneId?, closedSceneId?, staleCount, name?` |
| `threadLink` | `threadId/engramId/role` | `threadId, engramId, role` |
| `period` | `id` | `id, chatKey, name, gist?, state, eraId?, needsRename, namingSceneIds[], dateFrom?, dateTo?` |
| `era` | `id` | `id, chatKey, name, gist?, frozen, namingSceneIds[], dateFrom?, dateTo?` |
| `periodMember` | `sceneId` | `sceneId, periodId` (a scene is in one period) |
| `periodAlias` | `chatKey/alias` | `chatKey, alias, periodId` |
| `eraAlias` | `chatKey/alias` | `chatKey, alias, eraId` |
| `periodExclusion` | `sceneId` | `sceneId, chatKey, reason?` |
| `periodFailure` | `chatKey/kind/firstSceneId` | `chatKey, kind, firstSceneId, count, updatedAt?` |
| `periodGeneration` | `chatKey` | `chatKey, generation` |
| `catalogue` | `chatKey/language` | `chatKey, language, text, coverage?, sourceGeneration, contentHash, publishedAt` |
| `device` | `slot` | `slot, deviceId, name?, claimedAt` |

`namingSceneIds` is an array of ids in ascending order. A reader skips an op or a record type it doesn't know.

`device` isn't a Lattice row: it records which device holds which `IdAllocator` slot (1..1023). A device picks a slot
no `device` record holds and claims it with a `putRecord` against the current `baseSeq`; a `409` makes it read the
claims again and pick anew. A claim that waited in the outbox is never posted over another device's claim of the same
slot: the client drops it and claims a new slot ahead of its other queued ops.

## Cascades

The server needn't know Lattice's schema. Every cascade a delete implies is sent as explicit ops in the same post,
dependents first: deleting an engram sends `deleteEdge` for its edges, `deleteRecord` for its entity links, resolved
marker, scene memberships and thread links, a `putRecord scene` with no `hubEngramId` for a scene it was the hub of,
then `deleteEngram`. `deletePeriod`, `deleteEra`, `mergeEntities`, the redirects and `deletePeriodLayer` work the same
way. The server's own cascade on `deleteEngram` (its edges) is then a no-op.

## What the client assumes of the server

Beyond `docs/API.md`:

- Ops of one `POST /graphs/:name/ops` land in order, in one transaction; the answer's `seq` is the graph's seq after
  them. A `409` body carries the current seq as `error.seq` (the client also reads a top-level `seq`, and doesn't
  need either: it pulls from its own seq).
- Compaction keys a record as `(type, key)`, an engram by `id` and an edge by its unordered pair; a later op on the
  same key replaces the earlier one in the log. A delete stays in the log (other devices must see it) until the
  retention drops it.
- `GET /graphs/:name/ops?since=&limit=` answers the ops with seq greater than `since` in seq order, each with its
  `seq`, including the caller's own; `more` is true while ops remain; a page never splits ops that share one seq.
  When `more` is false the answer's `seq` is the graph's current seq.
- `GET /graphs/:name/snapshot` answers NDJSON: `{"seq": n}`, then one put op per live row in any order, consistent at
  `n`. `410 snapshot_required` comes from `/ops?since=` only.
- `POST /graphs` answers `409 conflict` when the graph exists. `GET /graphs/:name` carries `embedding`.
- `putEngram` with an embedding of another dimension than the graph's is `400`. A `400` or `413` on a post means the
  post itself is bad: the client sets it aside (`<name>.rejected.jsonl`) and goes on. Any other failure (`401`, `402`,
  `403`, `429`, `5xx`, no connection) keeps it in the outbox for later, except `404`. A `401` or `403` is a setup error
  (a refused key) that the client reports until a post lands. The outbox keeps at most 200 000 ops or 128 MiB; past
  that, later writes stay on the device only (lost at a restart) and the client reports it, keeping the oldest. The
  rejected posts are kept in `<name>.rejected.jsonl` up to 8 MiB, then in `<name>.rejected.jsonl.1`, replacing the one
  before. The client reads at most 64 MiB of an answer (64 KiB of a streamed answer's error).
- A `404` to a post or to `/ops?since=` means the graph was deleted on the service. The client never creates it again
  under the deleted graph's ops: it moves its outbox and copy aside (`<name>.outbox.gone-<millis>.jsonl`), stops writing
  and reports it; a new store (after a restart) starts a new, empty graph. A store that opens to a `404` with writes in
  its outbox but no create (the graph was deleted while the device was away) moves them and its copy aside the same way
  first, so that they never meet the `404`.
