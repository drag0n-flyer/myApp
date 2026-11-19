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
    "CREATE INDEX attachments_message ON attachments(message_id)",
    "CREATE INDEX calls_chat ON calls(chat_id)",
    "CREATE INDEX outbox_chat ON outbox(chat_id)",
    "CREATE INDEX folder_chats_chat ON folder_chats(chat_id)",
    "CREATE TABLE kv(key TEXT PRIMARY KEY, value BLOB NOT NULL) STRICT",
]

/// Chat list actions and saved drafts. Pin, archive and draft are local to the device. (`chats.muted` is in version 1.)
let schemaV2: [String] = [
    "ALTER TABLE chats ADD COLUMN pinned_at INTEGER",
    "ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
    "CREATE TABLE drafts(chat_id TEXT PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE, text TEXT NOT NULL, updated_at INTEGER NOT NULL) STRICT",
]

/// People: disappearing messages (`expires_at`, `chats.timer_seconds`), captions, link previews (JSON) and the
/// profile each contact sent (name, photo, version).
let schemaV3: [String] = [
    "ALTER TABLE messages ADD COLUMN expires_at INTEGER",
    "ALTER TABLE messages ADD COLUMN caption TEXT",
    "ALTER TABLE messages ADD COLUMN link_preview TEXT",
    "ALTER TABLE chats ADD COLUMN timer_seconds INTEGER NOT NULL DEFAULT 0",
    "CREATE INDEX messages_expires ON messages(expires_at) WHERE expires_at IS NOT NULL",
    "CREATE TABLE profiles(account_id TEXT PRIMARY KEY, name TEXT NOT NULL, photo BLOB, ver INTEGER NOT NULL) STRICT",
]

/// A queued message keeps the ciphertext of its one MLS encrypt, and the conversation and epoch it was made in, so a retry posts the
/// same bytes instead of encrypting again. Existing rows have none (they are encrypted on their next attempt).
let schemaV4: [String] = [
    "ALTER TABLE outbox ADD COLUMN ciphertext BLOB",
    "ALTER TABLE outbox ADD COLUMN ciphertext_epoch INTEGER",
    "ALTER TABLE outbox ADD COLUMN ciphertext_conversation TEXT",
]

/// Groups: a chat is `direct` (its ID is the peer's account ID) or `group` (`g:<conversationID>`). A group keeps its
/// name, photo and their version (`group_ver`), the version of the member list last applied (`members_version`; NULL =
/// none yet), this account's own state, its member list with roles, and the delivered and read receipts per member.
/// Existing chats are `direct`. (`messages.sender_account` already holds the sender.)
let schemaV5: [String] = [
    "ALTER TABLE chats ADD COLUMN kind TEXT NOT NULL DEFAULT 'direct' CHECK(kind IN ('direct','group'))",
    "ALTER TABLE chats ADD COLUMN conversation_id TEXT",
    "ALTER TABLE chats ADD COLUMN title TEXT",
    "ALTER TABLE chats ADD COLUMN photo BLOB",
    "ALTER TABLE chats ADD COLUMN group_ver INTEGER",
    "ALTER TABLE chats ADD COLUMN members_version INTEGER",
    "ALTER TABLE chats ADD COLUMN my_state TEXT NOT NULL DEFAULT 'member' CHECK(my_state IN ('member','removed','left'))",
    "CREATE UNIQUE INDEX chats_conversation ON chats(conversation_id) WHERE conversation_id IS NOT NULL",
    """
    CREATE TABLE group_members(chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, account_id TEXT NOT NULL, \
    role TEXT NOT NULL CHECK(role IN ('owner','admin','member')), PRIMARY KEY(chat_id, account_id)) STRICT
    """,
    """
    CREATE TABLE receipts(message_id TEXT NOT NULL REFERENCES messages ON DELETE CASCADE, account_id TEXT NOT NULL, \
    kind TEXT NOT NULL CHECK(kind IN ('delivered','read')), at INTEGER NOT NULL, PRIMARY KEY(message_id, account_id, kind)) STRICT
    """,
]

/// Poll votes: each account's latest answer to a poll message. No foreign key to `messages`, so a vote that arrives before
/// its poll is kept until the poll comes. `at` is the vote's own time: an older vote never replaces a newer one.
/// `option_ids` is a JSON array of strings (empty = answer taken back).
let schemaV6: [String] = [
    """
    CREATE TABLE poll_votes(chat_id TEXT NOT NULL, message_id TEXT NOT NULL, account_id TEXT NOT NULL, at INTEGER NOT NULL, \
    option_ids TEXT NOT NULL, PRIMARY KEY(chat_id, message_id, account_id)) STRICT
    """,
]
