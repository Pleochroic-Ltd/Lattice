package org.foedusprogramme.lattice.sqlite

/**
 * The SQLite store's schema as an ordered list of migrations: migration `n` (1-based) takes a file at
 * `PRAGMA user_version` `n - 1` to `n`. Append a migration for every change and never edit a released one; the golden
 * schema test (`src/test/resources/golden/schema.sql`) fails on any drift from what the migrations produce.
 */
internal object SqliteSchema {
    val migrations: List<List<String>> = listOf(v1(), v2())

    val version: Int get() = migrations.size

    /**
     * The first schema. An edge keeps its direction (`from_id`, `to_id`, unique on the unordered pair), an engram has
     * `tainted`, a verdict is keyed by the judge key, a thread link goes with its engram (a trigger), `meta` holds the
     * embedding spec, a scene has its order key (`started_at`, `seq`), and the scene pipeline's state lives in
     * `scene_work` and `scene_cursor`.
     */
    private fun v1(): List<String> = buildList {
        add("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        add(
            """
            CREATE TABLE engram (
                id INTEGER PRIMARY KEY,
                content TEXT NOT NULL,
                embedding BLOB NOT NULL,
                salience REAL NOT NULL,
                layer TEXT NOT NULL,
                created_at TEXT NOT NULL,
                last_activated_at TEXT NOT NULL,
                activation_count INTEGER NOT NULL DEFAULT 0,
                soft_replays INTEGER NOT NULL DEFAULT 0,
                speaker TEXT,
                source_message_id INTEGER,
                is_episode INTEGER NOT NULL DEFAULT 0,
                superseded_by INTEGER,
                salience_decayed_at TEXT NOT NULL,
                event_date TEXT,
                is_episode_hub INTEGER NOT NULL DEFAULT 0,
                origin TEXT,
                said_from TEXT,
                said_to TEXT,
                due_date TEXT,
                tainted INTEGER NOT NULL DEFAULT 0
            )
            """,
        )
        add(
            """
            CREATE TABLE edge (
                from_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
                to_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
                type TEXT NOT NULL,
                weight REAL NOT NULL,
                last_reinforced_at TEXT NOT NULL,
                weight_decayed_at TEXT NOT NULL
            )
            """,
        )
        add("CREATE UNIQUE INDEX edge_pair ON edge (min(from_id, to_id), max(from_id, to_id))")
        add("CREATE INDEX edge_from ON edge (from_id)")
        add("CREATE INDEX edge_to ON edge (to_id)")
        add(
            """
            CREATE TABLE verdict (
                hash_lo TEXT NOT NULL,
                hash_hi TEXT NOT NULL,
                judge_key TEXT NOT NULL,
                verdict TEXT NOT NULL,
                votes INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (hash_lo, hash_hi, judge_key)
            )
            """,
        )
        add(
            """
            CREATE TABLE entity (
                id INTEGER PRIMARY KEY,
                canonical_name TEXT NOT NULL,
                name_lc TEXT NOT NULL UNIQUE,
                mention_count INTEGER NOT NULL DEFAULT 1,
                first_event_date TEXT
            )
            """,
        )
        add(
            """
            CREATE TABLE engram_entity (
                engram_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
                entity_id INTEGER NOT NULL REFERENCES entity (id) ON DELETE CASCADE,
                PRIMARY KEY (engram_id, entity_id)
            )
            """,
        )
        add("CREATE INDEX engram_entity_entity ON engram_entity (entity_id)")
        add("CREATE TABLE entity_resolved (engram_id INTEGER PRIMARY KEY REFERENCES engram (id) ON DELETE CASCADE)")
        add(
            """
            CREATE TABLE scene (
                scene_id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                chat_key TEXT NOT NULL,
                first_message_id INTEGER NOT NULL,
                last_message_id INTEGER NOT NULL,
                date_from TEXT,
                date_to TEXT,
                in_progress INTEGER NOT NULL DEFAULT 0,
                hub_engram_id INTEGER REFERENCES engram (id) ON DELETE SET NULL,
                started_at TEXT,
                seq INTEGER NOT NULL DEFAULT 0
            )
            """,
        )
        add("CREATE INDEX scene_chat ON scene (chat_key)")
        add("CREATE INDEX scene_hub ON scene (hub_engram_id)")
        add(
            """
            CREATE TABLE scene_member (
                scene_id INTEGER NOT NULL REFERENCES scene (scene_id) ON DELETE CASCADE,
                engram_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
                PRIMARY KEY (scene_id, engram_id)
            )
            """,
        )
        add("CREATE INDEX scene_member_engram ON scene_member (engram_id)")
        add(
            """
            CREATE TABLE scene_work (
                scene_id INTEGER PRIMARY KEY REFERENCES scene (scene_id) ON DELETE CASCADE,
                state TEXT NOT NULL,
                synopsis TEXT,
                extracted_through INTEGER,
                extract_attempts INTEGER NOT NULL DEFAULT 0,
                chunks_done INTEGER NOT NULL DEFAULT 0,
                chunks_failed INTEGER NOT NULL DEFAULT 0,
                narrate_attempts INTEGER NOT NULL DEFAULT 0
            )
            """,
        )
        add(
            """
            CREATE TABLE scene_cursor (
                conversation_id TEXT PRIMARY KEY,
                chat_key TEXT NOT NULL,
                segmented_through INTEGER,
                segment_attempts INTEGER NOT NULL DEFAULT 0,
                fallback_through INTEGER,
                extract_attempts INTEGER NOT NULL DEFAULT 0,
                blocked_since TEXT,
                finished INTEGER NOT NULL DEFAULT 0
            )
            """,
        )
        add(
            """
            CREATE TABLE thread (
                id INTEGER PRIMARY KEY,
                chat_key TEXT NOT NULL,
                description TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'OPEN',
                opened_scene_id INTEGER,
                closed_scene_id INTEGER,
                stale_count INTEGER NOT NULL DEFAULT 0,
                name TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
        )
        add("CREATE INDEX thread_chat ON thread (chat_key, state)")
        // No foreign key to the engram: a link may be written before its engram; deleting the engram takes it (trigger).
        add(
            """
            CREATE TABLE thread_link (
                thread_id INTEGER NOT NULL REFERENCES thread (id) ON DELETE CASCADE,
                engram_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                PRIMARY KEY (thread_id, engram_id, role)
            )
            """,
        )
        add("CREATE INDEX thread_link_engram ON thread_link (engram_id)")
        add("CREATE TRIGGER engram_delete_thread_links AFTER DELETE ON engram BEGIN DELETE FROM thread_link WHERE engram_id = OLD.id; END")
        addAll(periods())
    }.map { it.trimIndent() }

    /** The period layer: names unique per chat under `name_key`, one provisional period per chat, aliases. */
    private fun periods(): List<String> = buildList {
        add(
            """
            CREATE TABLE era (
                id INTEGER PRIMARY KEY,
                chat_key TEXT NOT NULL,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL CHECK (length(name_key) > 0),
                gist TEXT,
                date_from TEXT,
                date_to TEXT,
                frozen INTEGER NOT NULL DEFAULT 0,
                naming_scene_ids TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
        )
        add("CREATE UNIQUE INDEX era_name ON era (chat_key, name_key)")
        add(
            """
            CREATE TABLE period (
                id INTEGER PRIMARY KEY,
                chat_key TEXT NOT NULL,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL CHECK (length(name_key) > 0),
                gist TEXT,
                date_from TEXT,
                date_to TEXT,
                state TEXT NOT NULL CHECK (state IN ('PROVISIONAL', 'CLOSED')),
                era_id INTEGER REFERENCES era (id) ON DELETE SET NULL,
                needs_rename INTEGER NOT NULL DEFAULT 0,
                naming_scene_ids TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
        )
        add("CREATE UNIQUE INDEX period_name ON period (chat_key, name_key)")
        add("CREATE UNIQUE INDEX period_provisional ON period (chat_key) WHERE state = 'PROVISIONAL'")
        add("CREATE INDEX period_era ON period (era_id)")
        add(
            """
            CREATE TABLE period_member (
                scene_id INTEGER PRIMARY KEY REFERENCES scene (scene_id) ON DELETE CASCADE,
                period_id INTEGER NOT NULL REFERENCES period (id) ON DELETE CASCADE
            )
            """,
        )
        add("CREATE INDEX period_member_period ON period_member (period_id)")
        for ((aliases, owner, table) in listOf(Triple("period_alias", "period_id", "period"), Triple("era_alias", "era_id", "era"))) {
            add(
                """
                CREATE TABLE $aliases (
                    chat_key TEXT NOT NULL,
                    alias_key TEXT NOT NULL CHECK (length(alias_key) > 0),
                    alias TEXT NOT NULL,
                    $owner INTEGER NOT NULL REFERENCES $table (id) ON DELETE CASCADE,
                    PRIMARY KEY (chat_key, alias_key)
                )
                """,
            )
            add("CREATE INDEX ${aliases}_owner ON $aliases ($owner)")
        }
        add(
            """
            CREATE TABLE period_excluded (
                scene_id INTEGER PRIMARY KEY,
                chat_key TEXT NOT NULL,
                reason TEXT,
                created_at TEXT NOT NULL
            )
            """,
        )
        add("CREATE INDEX period_excluded_chat ON period_excluded (chat_key)")
        add(
            """
            CREATE TABLE period_failure (
                chat_key TEXT NOT NULL,
                kind TEXT NOT NULL,
                first_scene_id INTEGER NOT NULL,
                count INTEGER NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (chat_key, kind, first_scene_id)
            )
            """,
        )
        add("CREATE TABLE period_generation (chat_key TEXT PRIMARY KEY, generation INTEGER NOT NULL, updated_at TEXT NOT NULL)")
        add(
            """
            CREATE TABLE period_catalogue (
                chat_key TEXT NOT NULL,
                language TEXT NOT NULL,
                text TEXT NOT NULL,
                coverage TEXT,
                source_generation INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                published_at TEXT NOT NULL,
                PRIMARY KEY (chat_key, language)
            )
            """,
        )
        // A current name is never ANOTHER row's alias, and an alias never another row's current name (a row's own old
        // name coming back is fine). Periods and eras are separate namespaces.
        for ((table, aliases, owner) in listOf(Triple("period", "period_alias", "period_id"), Triple("era", "era_alias", "era_id"))) {
            val clash = "EXISTS (SELECT 1 FROM $aliases a WHERE a.chat_key = NEW.chat_key AND a.alias_key = NEW.name_key AND a.$owner <> NEW.id)"
            val message = "'$table name is another $table''s alias'"
            add("CREATE TRIGGER ${table}_name_insert BEFORE INSERT ON $table WHEN $clash BEGIN SELECT RAISE(ABORT, $message); END")
            add(
                "CREATE TRIGGER ${table}_name_update BEFORE UPDATE OF chat_key, name_key ON $table WHEN $clash " +
                    "BEGIN SELECT RAISE(ABORT, $message); END",
            )
            add(
                "CREATE TRIGGER ${aliases}_insert BEFORE INSERT ON $aliases " +
                    "WHEN EXISTS (SELECT 1 FROM $table t WHERE t.chat_key = NEW.chat_key AND t.name_key = NEW.alias_key " +
                    "AND t.id <> NEW.$owner) BEGIN SELECT RAISE(ABORT, '$aliases alias is another $table''s name'); END",
            )
        }
        // A scene excluded from periods never becomes a member, and a member is never marked excluded.
        add(
            "CREATE TRIGGER period_member_insert BEFORE INSERT ON period_member " +
                "WHEN EXISTS (SELECT 1 FROM period_excluded x WHERE x.scene_id = NEW.scene_id) " +
                "BEGIN SELECT RAISE(ABORT, 'scene is excluded from periods'); END",
        )
        add(
            "CREATE TRIGGER period_excluded_insert BEFORE INSERT ON period_excluded " +
                "WHEN EXISTS (SELECT 1 FROM period_member m WHERE m.scene_id = NEW.scene_id) " +
                "BEGIN SELECT RAISE(ABORT, 'scene already belongs to a period'); END",
        )
    }.map { it.trimIndent() }

    /** A person's quote keeps their name ([org.foedusprogramme.lattice.core.Engram.speakerName]). */
    private fun v2(): List<String> = listOf("ALTER TABLE engram ADD COLUMN speaker_name TEXT")
}
