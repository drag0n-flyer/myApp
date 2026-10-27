let schemaV1: [String] = [
    "CREATE TABLE contacts(account_id TEXT PRIMARY KEY, username TEXT NOT NULL, display_name TEXT NOT NULL, is_contact INTEGER NOT NULL, added_at INTEGER NOT NULL) STRICT",
    "CREATE TABLE chats(id TEXT PRIMARY KEY, is_request INTEGER NOT NULL, unread INTEGER NOT NULL DEFAULT 0, muted INTEGER NOT NULL DEFAULT 0, last_activity INTEGER NOT NULL) STRICT",
    """
    CREATE TABLE conversations(server_id TEXT PRIMARY KEY, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, \
    group_id BLOB NOT NULL UNIQUE, mode TEXT NOT NULL CHECK(mode IN ('e2ee','server_readable')), \
    inner_mode TEXT CHECK(inner_mode IN ('e2ee','server_readable')), mode_mismatch INTEGER NOT NULL DEFAULT 0, \
    epoch INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL) STRICT
    """,
    "CREATE TABLE members(conversation_id TEXT NOT NULL REFERENCES conversations(server_id) ON DELETE CASCADE, identity TEXT NOT NULL, signature_key BLOB NOT NULL, PRIMARY KEY(conversation_id, identity)) STRICT",
    "CREATE TABLE safety(chat_id TEXT PRIMARY KEY REFERENCES chats ON DELETE CASCADE, current TEXT NOT NULL, verified TEXT) STRICT",
    """
    CREATE TABLE messages(id TEXT PRIMARY KEY, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, conversation_id TEXT, \
    sender_account TEXT NOT NULL, sender_device TEXT, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, envelope BLOB NOT NULL, \
    status TEXT NOT NULL, reply_to TEXT, edited_at INTEGER, deleted INTEGER NOT NULL DEFAULT 0, \
    undecryptable INTEGER NOT NULL DEFAULT 0, folded TEXT NOT NULL DEFAULT '') STRICT
    """,
    "CREATE INDEX messages_chat_sent ON messages(chat_id, sent_at)",
    "CREATE TABLE reactions(message_id TEXT NOT NULL REFERENCES messages ON DELETE CASCADE, account_id TEXT NOT NULL, emoji TEXT NOT NULL, PRIMARY KEY(message_id, account_id)) STRICT",
    "CREATE VIRTUAL TABLE message_fts USING fts5(folded, content='messages', content_rowid='rowid', tokenize='trigram')",
    """
    CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN \
    INSERT INTO message_fts(rowid, folded) VALUES (new.rowid, new.folded); END
    """,
    """
    CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN \
    INSERT INTO message_fts(message_fts, rowid, folded) VALUES ('delete', old.rowid, old.folded); END
    """,
    """
    CREATE TRIGGER messages_fts_update AFTER UPDATE OF folded ON messages BEGIN \
    INSERT INTO message_fts(message_fts, rowid, folded) VALUES ('delete', old.rowid, old.folded); \
    INSERT INTO message_fts(rowid, folded) VALUES (new.rowid, new.folded); END
    """,
    """
    CREATE TABLE attachments(id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages ON DELETE CASCADE, kind TEXT NOT NULL, \
    key BLOB NOT NULL, digest BLOB NOT NULL, size INTEGER NOT NULL, cipher_size INTEGER NOT NULL, \
    state TEXT NOT NULL CHECK(state IN ('remote','downloading','local','failed')), downloaded INTEGER NOT NULL DEFAULT 0, local_path TEXT) STRICT
    """,
    "CREATE TABLE outbox(message_id TEXT PRIMARY KEY, chat_id TEXT NOT NULL, envelope BLOB NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL) STRICT",
    """
    CREATE TABLE inbox_raw(cursor INTEGER PRIMARY KEY, kind TEXT NOT NULL, conversation_id TEXT NOT NULL, seq INTEGER, epoch INTEGER NOT NULL, \
    sender_account TEXT NOT NULL, sender_device TEXT NOT NULL, payload BLOB NOT NULL, sent_at INTEGER NOT NULL) STRICT
    """,
    "CREATE TABLE processed(payload_hash BLOB PRIMARY KEY, processed_at INTEGER NOT NULL) STRICT",
    "CREATE TABLE folders(id TEXT PRIMARY KEY, name TEXT NOT NULL, position INTEGER NOT NULL) STRICT",
    "CREATE TABLE folder_chats(folder_id TEXT NOT NULL REFERENCES folders ON DELETE CASCADE, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, PRIMARY KEY(folder_id, chat_id)) STRICT",
    "CREATE TABLE blocked(account_id TEXT PRIMARY KEY, blocked_at INTEGER NOT NULL) STRICT",
    "CREATE TABLE calls(id TEXT PRIMARY KEY, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, video INTEGER NOT NULL, outgoing INTEGER NOT NULL, outcome TEXT NOT NULL, started_at INTEGER NOT NULL, duration REAL NOT NULL) STRICT",
    "CREATE TABLE kv(key TEXT PRIMARY KEY, value BLOB NOT NULL) STRICT",
]
