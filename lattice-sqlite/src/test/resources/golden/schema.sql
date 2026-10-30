-- index edge_from
CREATE INDEX edge_from ON edge (from_id);

-- index edge_pair
CREATE UNIQUE INDEX edge_pair ON edge (min(from_id, to_id), max(from_id, to_id));

-- index edge_to
CREATE INDEX edge_to ON edge (to_id);

-- index engram_entity_entity
CREATE INDEX engram_entity_entity ON engram_entity (entity_id);

-- index era_alias_owner
CREATE INDEX era_alias_owner ON era_alias (era_id);

-- index era_name
CREATE UNIQUE INDEX era_name ON era (chat_key, name_key);

-- index period_alias_owner
CREATE INDEX period_alias_owner ON period_alias (period_id);

-- index period_era
CREATE INDEX period_era ON period (era_id);

-- index period_excluded_chat
CREATE INDEX period_excluded_chat ON period_excluded (chat_key);

-- index period_member_period
CREATE INDEX period_member_period ON period_member (period_id);

-- index period_name
CREATE UNIQUE INDEX period_name ON period (chat_key, name_key);

-- index period_provisional
CREATE UNIQUE INDEX period_provisional ON period (chat_key) WHERE state = 'PROVISIONAL';

-- index scene_chat
CREATE INDEX scene_chat ON scene (chat_key);

-- index scene_hub
CREATE INDEX scene_hub ON scene (hub_engram_id);

-- index scene_member_engram
CREATE INDEX scene_member_engram ON scene_member (engram_id);

-- index thread_chat
CREATE INDEX thread_chat ON thread (chat_key, state);

-- index thread_link_engram
CREATE INDEX thread_link_engram ON thread_link (engram_id);

-- table edge
CREATE TABLE edge (
    from_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
    to_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    weight REAL NOT NULL,
    last_reinforced_at TEXT NOT NULL,
    weight_decayed_at TEXT NOT NULL
);

-- table engram
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
);

-- table engram_entity
CREATE TABLE engram_entity (
    engram_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
    entity_id INTEGER NOT NULL REFERENCES entity (id) ON DELETE CASCADE,
    PRIMARY KEY (engram_id, entity_id)
);

-- table entity
CREATE TABLE entity (
    id INTEGER PRIMARY KEY,
    canonical_name TEXT NOT NULL,
    name_lc TEXT NOT NULL UNIQUE,
    mention_count INTEGER NOT NULL DEFAULT 1,
    first_event_date TEXT
);

-- table entity_resolved
CREATE TABLE entity_resolved (engram_id INTEGER PRIMARY KEY REFERENCES engram (id) ON DELETE CASCADE);

-- table era
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
);

-- table era_alias
CREATE TABLE era_alias (
    chat_key TEXT NOT NULL,
    alias_key TEXT NOT NULL CHECK (length(alias_key) > 0),
    alias TEXT NOT NULL,
    era_id INTEGER NOT NULL REFERENCES era (id) ON DELETE CASCADE,
    PRIMARY KEY (chat_key, alias_key)
);

-- table meta
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- table period
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
);

-- table period_alias
CREATE TABLE period_alias (
    chat_key TEXT NOT NULL,
    alias_key TEXT NOT NULL CHECK (length(alias_key) > 0),
    alias TEXT NOT NULL,
    period_id INTEGER NOT NULL REFERENCES period (id) ON DELETE CASCADE,
    PRIMARY KEY (chat_key, alias_key)
);

-- table period_catalogue
CREATE TABLE period_catalogue (
    chat_key TEXT NOT NULL,
    language TEXT NOT NULL,
    text TEXT NOT NULL,
    coverage TEXT,
    source_generation INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    published_at TEXT NOT NULL,
    PRIMARY KEY (chat_key, language)
);

-- table period_excluded
CREATE TABLE period_excluded (
    scene_id INTEGER PRIMARY KEY,
    chat_key TEXT NOT NULL,
    reason TEXT,
    created_at TEXT NOT NULL
);

-- table period_failure
CREATE TABLE period_failure (
    chat_key TEXT NOT NULL,
    kind TEXT NOT NULL,
    first_scene_id INTEGER NOT NULL,
    count INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (chat_key, kind, first_scene_id)
);

-- table period_generation
CREATE TABLE period_generation (chat_key TEXT PRIMARY KEY, generation INTEGER NOT NULL, updated_at TEXT NOT NULL);

-- table period_member
CREATE TABLE period_member (
    scene_id INTEGER PRIMARY KEY REFERENCES scene (scene_id) ON DELETE CASCADE,
    period_id INTEGER NOT NULL REFERENCES period (id) ON DELETE CASCADE
);

-- table scene
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
);

-- table scene_cursor
CREATE TABLE scene_cursor (
    conversation_id TEXT PRIMARY KEY,
    chat_key TEXT NOT NULL,
    segmented_through INTEGER,
    segment_attempts INTEGER NOT NULL DEFAULT 0,
    fallback_through INTEGER,
    extract_attempts INTEGER NOT NULL DEFAULT 0,
    blocked_since TEXT,
    finished INTEGER NOT NULL DEFAULT 0
);

-- table scene_member
CREATE TABLE scene_member (
    scene_id INTEGER NOT NULL REFERENCES scene (scene_id) ON DELETE CASCADE,
    engram_id INTEGER NOT NULL REFERENCES engram (id) ON DELETE CASCADE,
    PRIMARY KEY (scene_id, engram_id)
);

-- table scene_work
CREATE TABLE scene_work (
    scene_id INTEGER PRIMARY KEY REFERENCES scene (scene_id) ON DELETE CASCADE,
    state TEXT NOT NULL,
    synopsis TEXT,
    extracted_through INTEGER,
    extract_attempts INTEGER NOT NULL DEFAULT 0,
    chunks_done INTEGER NOT NULL DEFAULT 0,
    chunks_failed INTEGER NOT NULL DEFAULT 0,
    narrate_attempts INTEGER NOT NULL DEFAULT 0
);

-- table thread
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
);

-- table thread_link
CREATE TABLE thread_link (
    thread_id INTEGER NOT NULL REFERENCES thread (id) ON DELETE CASCADE,
    engram_id INTEGER NOT NULL,
    role TEXT NOT NULL,
    PRIMARY KEY (thread_id, engram_id, role)
);

-- table verdict
CREATE TABLE verdict (
    hash_lo TEXT NOT NULL,
    hash_hi TEXT NOT NULL,
    judge_key TEXT NOT NULL,
    verdict TEXT NOT NULL,
    votes INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (hash_lo, hash_hi, judge_key)
);

-- trigger engram_delete_thread_links
CREATE TRIGGER engram_delete_thread_links AFTER DELETE ON engram BEGIN DELETE FROM thread_link WHERE engram_id = OLD.id; END;

-- trigger era_alias_insert
CREATE TRIGGER era_alias_insert BEFORE INSERT ON era_alias WHEN EXISTS (SELECT 1 FROM era t WHERE t.chat_key = NEW.chat_key AND t.name_key = NEW.alias_key AND t.id <> NEW.era_id) BEGIN SELECT RAISE(ABORT, 'era_alias alias is another era''s name'); END;

-- trigger era_name_insert
CREATE TRIGGER era_name_insert BEFORE INSERT ON era WHEN EXISTS (SELECT 1 FROM era_alias a WHERE a.chat_key = NEW.chat_key AND a.alias_key = NEW.name_key AND a.era_id <> NEW.id) BEGIN SELECT RAISE(ABORT, 'era name is another era''s alias'); END;

-- trigger era_name_update
CREATE TRIGGER era_name_update BEFORE UPDATE OF chat_key, name_key ON era WHEN EXISTS (SELECT 1 FROM era_alias a WHERE a.chat_key = NEW.chat_key AND a.alias_key = NEW.name_key AND a.era_id <> NEW.id) BEGIN SELECT RAISE(ABORT, 'era name is another era''s alias'); END;

-- trigger period_alias_insert
CREATE TRIGGER period_alias_insert BEFORE INSERT ON period_alias WHEN EXISTS (SELECT 1 FROM period t WHERE t.chat_key = NEW.chat_key AND t.name_key = NEW.alias_key AND t.id <> NEW.period_id) BEGIN SELECT RAISE(ABORT, 'period_alias alias is another period''s name'); END;

-- trigger period_excluded_insert
CREATE TRIGGER period_excluded_insert BEFORE INSERT ON period_excluded WHEN EXISTS (SELECT 1 FROM period_member m WHERE m.scene_id = NEW.scene_id) BEGIN SELECT RAISE(ABORT, 'scene already belongs to a period'); END;

-- trigger period_member_insert
CREATE TRIGGER period_member_insert BEFORE INSERT ON period_member WHEN EXISTS (SELECT 1 FROM period_excluded x WHERE x.scene_id = NEW.scene_id) BEGIN SELECT RAISE(ABORT, 'scene is excluded from periods'); END;

-- trigger period_name_insert
CREATE TRIGGER period_name_insert BEFORE INSERT ON period WHEN EXISTS (SELECT 1 FROM period_alias a WHERE a.chat_key = NEW.chat_key AND a.alias_key = NEW.name_key AND a.period_id <> NEW.id) BEGIN SELECT RAISE(ABORT, 'period name is another period''s alias'); END;

-- trigger period_name_update
CREATE TRIGGER period_name_update BEFORE UPDATE OF chat_key, name_key ON period WHEN EXISTS (SELECT 1 FROM period_alias a WHERE a.chat_key = NEW.chat_key AND a.alias_key = NEW.name_key AND a.period_id <> NEW.id) BEGIN SELECT RAISE(ABORT, 'period name is another period''s alias'); END;

