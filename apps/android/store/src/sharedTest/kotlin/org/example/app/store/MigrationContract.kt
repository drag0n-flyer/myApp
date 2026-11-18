package org.example.app.store

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upgrading a populated version 1 database (and a populated version 2 one) to the current version keeps every row and
 * gives the new columns their defaults.
 * The version 1 schema is written out as the SQL Room generated for it (the statements below were read from a
 * database built by the version 1 code), not derived from the current entities, so a change to the entities
 * cannot make this test agree with itself. Runs on the JVM against stock SQLite and on a device against SQLCipher.
 */
abstract class MigrationContract {
    /** Opens (creating it if needed) a version 1 database through the same kind of open helper the app uses. */
    protected abstract fun v1Helper(callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper
    /** Opens the file at the current version through the app's own path, with its migrations. */
    protected abstract fun openCurrent(): AppDatabase
    protected abstract fun deleteFile()
    /** True where the SQLite build has FTS5 (the device); the version 1 database then has the search index too. */
    protected abstract val hasFts: Boolean

    private lateinit var before: Map<String, List<String>>

    @Before fun clean() = deleteFile()
    @After fun cleanUp() = deleteFile()

    private fun createVersion1(): Map<String, List<String>> {
        val helper = v1Helper(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onConfigure(db: SupportSQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
            override fun onCreate(db: SupportSQLiteDatabase) = SchemaV1.create(db, hasFts)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("not expected")
        })
        val db = helper.writableDatabase
        assertEquals(1, db.version)
        val snapshot = snapshot(db)
        helper.close()
        return snapshot
    }

    /** Every row of every version 1 table, as text, using only the version 1 columns. */
    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<String>> =
        SchemaV1.COLUMNS.mapValues { (table, columns) ->
            val rows = mutableListOf<String>()
            db.query("SELECT ${columns.joinToString(",") { "hex($it)" }} FROM $table ORDER BY rowid").use { c ->
                while (c.moveToNext()) rows += (0 until columns.size).joinToString("|") { c.getString(it) ?: "NULL" }
            }
            rows
        }

    @Test fun populatedVersion1OpensAtCurrentVersionWithEveryRowIntact() {
        before = createVersion1()
        // The fixture really is populated: a vacuous pass is not possible.
        for (table in listOf("contacts", "chats", "conversations", "members", "safety", "messages", "reactions", "attachments", "outbox", "inbox_raw", "processed", "folders", "folder_chats", "blocked", "calls", "kv")) {
            assertTrue(before.getValue(table).isNotEmpty(), "fixture has no rows in $table")
        }
        assertEquals(3, before.getValue("chats").size)
        assertEquals(3, before.getValue("messages").size)

        val db = openCurrent()
        try {
            val raw = db.openHelper.writableDatabase
            assertEquals(AppDatabase.VERSION, raw.version)
            assertEquals(before, snapshot(raw), "a version 1 row changed or was lost")
            // Foreign keys still hold after the upgrade.
            raw.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst(), "foreign key violation after migration") }
            raw.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }

            // New columns at their defaults for every old chat; muted kept as it was.
            raw.query("SELECT id, pinned_at, archived, muted FROM chats ORDER BY id").use { c ->
                val seen = mutableMapOf<String, Triple<Long?, Int, Int>>()
                while (c.moveToNext()) seen[c.getString(0)] = Triple(if (c.isNull(1)) null else c.getLong(1), c.getInt(2), c.getInt(3))
                assertEquals(
                    mapOf<String, Triple<Long?, Int, Int>>("chat-a" to Triple(null, 0, 0), "chat-b" to Triple(null, 0, 1), "chat-c" to Triple(null, 0, 0)),
                    seen,
                )
            }
            raw.query("SELECT COUNT(*) FROM drafts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }

            // The repository reads the old rows through the new entities.
            val repo = RoomRepository(db, allowLikeFallback = !hasFts)
            val chat = repo.chat("chat-b")!!
            assertEquals(true, chat.muted)
            assertNull(chat.pinnedAt)
            assertFalse(chat.archived)
            assertEquals(2, chat.unread)
            assertEquals(3, repo.messages("chat-a", null, 10).size + repo.messages("chat-b", null, 10).size + repo.messages("chat-c", null, 10).size)
            assertEquals("outbox-1", repo.dueOutboxHeads(Long.MAX_VALUE).single().messageId)
            assertEquals("remote", repo.attachment("att-1")!!.state)
            assertNull(repo.draft("chat-a"))

            // And the new columns and table work on migrated data.
            repo.setPinnedAt("chat-a", 123)
            repo.setArchived("chat-c", true)
            repo.setDraft("chat-b", "half a thought", 5)
            assertEquals(123L, repo.chat("chat-a")!!.pinnedAt)
            assertTrue(repo.chat("chat-c")!!.archived)
            assertEquals("half a thought", repo.draft("chat-b"))
            if (hasFts) assertEquals(1, repo.search("needle", 10).size, "the search index survived the upgrade")
        } finally {
            db.close()
        }
    }

    @Test fun aDatabaseThatIsAlreadyCurrentOpensUnchanged() {
        createVersion1()
        openCurrent().use { }
        val db = openCurrent()
        try {
            assertEquals(AppDatabase.VERSION, db.openHelper.writableDatabase.version)
            assertEquals(3, RoomRepository(db, allowLikeFallback = !hasFts).chats().size)
        } finally {
            db.close()
        }
    }

    private fun createVersion3(): Unit {
        val helper = v1Helper(object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onConfigure(db: SupportSQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
            override fun onCreate(db: SupportSQLiteDatabase) {
                SchemaV1.create(db, hasFts)
                Migration1To2.migrate(db)
                Migration2To3.migrate(db)
                // A queued message that was already tried twice, and a second one in another chat.
                db.execSQL("INSERT INTO outbox VALUES ('outbox-2', 'chat-a', x'0910', 0, 9500)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("not expected")
        })
        val db = helper.writableDatabase
        assertEquals(3, db.version)
        helper.close()
    }

    /** Outbox rows of a version 3 database have no stored ciphertext after the upgrade, and the new columns are usable. */
    @Test fun populatedVersion3GetsOutboxCiphertextColumnsWithEveryOutboxRowIntact() {
        createVersion3()
        val db = openCurrent()
        try {
            val raw = db.openHelper.writableDatabase
            assertEquals(AppDatabase.VERSION, raw.version)
            raw.query("SELECT message_id, chat_id, hex(envelope), attempts, next_attempt_at, ciphertext, cipher_epoch, cipher_conversation FROM outbox ORDER BY rowid").use { c ->
                val rows = mutableListOf<List<String?>>()
                while (c.moveToNext()) {
                    rows += listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
                    assertTrue(c.isNull(5), "ciphertext starts empty")
                    assertTrue(c.isNull(6), "cipher_epoch starts empty")
                    assertTrue(c.isNull(7), "cipher_conversation starts empty")
                }
                assertEquals(
                    listOf(
                        listOf<String?>("outbox-1", "chat-b", "0708", "2", "9000"),
                        listOf<String?>("outbox-2", "chat-a", "0910", "0", "9500"),
                    ),
                    rows,
                )
            }
            raw.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst(), "foreign key violation after migration") }
            raw.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
            val repo = RoomRepository(db, allowLikeFallback = !hasFts)
            assertEquals(3, repo.chats().size)
            // The new columns work on a migrated row, and leave the rest of it alone.
            repo.storeCiphertext("outbox-1", "srv-b", 7, byteArrayOf(1, 2, 3))
            val row = repo.dueOutbox(Long.MAX_VALUE).single { it.messageId == "outbox-1" }
            assertContentEquals(byteArrayOf(1, 2, 3), row.ciphertext)
            assertEquals(7L, row.cipherEpoch)
            assertEquals("srv-b", row.cipherConversation)
            assertContentEquals(byteArrayOf(7, 8), row.envelope)
            assertEquals(2, row.attempts)
            assertNull(repo.dueOutbox(Long.MAX_VALUE).single { it.messageId == "outbox-2" }.ciphertext)
        } finally {
            db.close()
        }
    }

    private fun createVersion4() {
        val helper = v1Helper(object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onConfigure(db: SupportSQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
            override fun onCreate(db: SupportSQLiteDatabase) {
                SchemaV1.create(db, hasFts)
                Migration1To2.migrate(db)
                Migration2To3.migrate(db)
                Migration3To4.migrate(db)
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("not expected")
        })
        assertEquals(4, helper.writableDatabase.version)
        helper.close()
    }

    /** Version 4 to 5: every one-to-one chat becomes `direct` with its row and children intact; the group tables work. */
    @Test fun populatedVersion4GetsGroupColumnsAndTablesWithEveryChatIntact() {
        createVersion4()
        val db = openCurrent()
        try {
            val raw = db.openHelper.writableDatabase
            assertEquals(AppDatabase.VERSION, raw.version)
            raw.query("SELECT id, kind, conversation_id, title, photo, group_ver, members_version, my_state, muted, unread FROM chats ORDER BY id").use { c ->
                val rows = mutableListOf<List<String?>>()
                while (c.moveToNext()) {
                    assertEquals("direct", c.getString(1), "kind of ${c.getString(0)}")
                    for (i in 2..6) assertTrue(c.isNull(i), "column $i of ${c.getString(0)} starts empty")
                    assertEquals("member", c.getString(7))
                    rows += listOf(c.getString(0), c.getString(8), c.getString(9))
                }
                assertEquals(listOf(listOf<String?>("chat-a", "0", "0"), listOf("chat-b", "1", "2"), listOf("chat-c", "0", "1")), rows)
            }
            raw.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
            raw.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst(), "foreign key violation after migration") }
            raw.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }

            val repo = RoomRepository(db, allowLikeFallback = !hasFts)
            val direct = repo.chat("chat-b")!!
            assertEquals("direct", direct.kind); assertNull(direct.conversationId); assertEquals("member", direct.myState)
            assertEquals(3, repo.chats().size)
            // The new columns and tables work on the migrated database.
            repo.upsertGroup("g:new", "new-conv", "Group", 7)
            repo.setGroupMembers("g:new", listOf(org.example.app.messaging.ports.GroupMemberRecord("acc-a", "owner")), 1)
            assertEquals(1, repo.groupMembers("g:new").size)
            assertTrue(repo.addReceipt("m-1", "acc-a", "read", 9))
            assertEquals(4, repo.chats().size)
        } finally {
            db.close()
        }
    }

    private inline fun AppDatabase.use(body: (AppDatabase) -> Unit) = try { body(this) } finally { close() }
}


/** The version 1 schema and a small populated data set. */
object SchemaV1 {
    val COLUMNS: Map<String, List<String>> = mapOf(
        "contacts" to listOf("account_id", "username", "display_name", "is_contact", "added_at"),
        "chats" to listOf("id", "is_request", "unread", "muted", "last_activity"),
        "conversations" to listOf("server_id", "chat_id", "group_id", "mode", "inner_mode", "mode_mismatch", "epoch", "active", "created_at"),
        "members" to listOf("conversation_id", "identity", "signature_key"),
        "safety" to listOf("chat_id", "current", "verified"),
        "messages" to listOf("id", "chat_id", "conversation_id", "sender_account", "sender_device", "sent_at", "received_at", "envelope", "status", "reply_to", "edited_at", "deleted", "undecryptable", "folded"),
        "reactions" to listOf("message_id", "account_id", "emoji"),
        "attachments" to listOf("id", "message_id", "kind", "key", "digest", "size", "cipher_size", "state", "downloaded", "local_path"),
        "outbox" to listOf("message_id", "chat_id", "envelope", "attempts", "next_attempt_at"),
        "inbox_raw" to listOf("cursor", "kind", "conversation_id", "seq", "epoch", "sender_account", "sender_device", "payload", "sent_at"),
        "processed" to listOf("payload_hash", "processed_at"),
        "folders" to listOf("id", "name", "position"),
        "folder_chats" to listOf("folder_id", "chat_id"),
        "blocked" to listOf("account_id", "blocked_at"),
        "calls" to listOf("id", "chat_id", "video", "outgoing", "outcome", "started_at", "duration"),
        "kv" to listOf("key", "value"),
    )

    private val DDL = listOf(
        "CREATE TABLE IF NOT EXISTS `contacts` (`account_id` TEXT NOT NULL, `username` TEXT NOT NULL, `display_name` TEXT NOT NULL, `is_contact` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`))",
        "CREATE TABLE IF NOT EXISTS `chats` (`id` TEXT NOT NULL, `is_request` INTEGER NOT NULL, `unread` INTEGER NOT NULL, `muted` INTEGER NOT NULL, `last_activity` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `conversations` (`server_id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `group_id` BLOB NOT NULL, `mode` TEXT NOT NULL, `inner_mode` TEXT, `mode_mismatch` INTEGER NOT NULL, `epoch` INTEGER NOT NULL, `active` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`server_id`), FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_conversations_chat_id` ON `conversations` (`chat_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversations_group_id` ON `conversations` (`group_id`)",
        "CREATE TABLE IF NOT EXISTS `members` (`conversation_id` TEXT NOT NULL, `identity` TEXT NOT NULL, `signature_key` BLOB NOT NULL, PRIMARY KEY(`conversation_id`, `identity`), FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`server_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `safety` (`chat_id` TEXT NOT NULL, `current` TEXT NOT NULL, `verified` TEXT, PRIMARY KEY(`chat_id`), FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `conversation_id` TEXT, `sender_account` TEXT NOT NULL, `sender_device` TEXT, `sent_at` INTEGER NOT NULL, `received_at` INTEGER NOT NULL, `envelope` BLOB NOT NULL, `status` TEXT NOT NULL, `reply_to` TEXT, `edited_at` INTEGER, `deleted` INTEGER NOT NULL, `undecryptable` INTEGER NOT NULL, `folded` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`), FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `messages_chat_sent` ON `messages` (`chat_id`, `sent_at`)",
        "CREATE TABLE IF NOT EXISTS `reactions` (`message_id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `emoji` TEXT NOT NULL, PRIMARY KEY(`message_id`, `account_id`), FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `attachments` (`id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `key` BLOB NOT NULL, `digest` BLOB NOT NULL, `size` INTEGER NOT NULL, `cipher_size` INTEGER NOT NULL, `state` TEXT NOT NULL, `downloaded` INTEGER NOT NULL, `local_path` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `attachments_message` ON `attachments` (`message_id`)",
        "CREATE TABLE IF NOT EXISTS `outbox` (`message_id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `envelope` BLOB NOT NULL, `attempts` INTEGER NOT NULL, `next_attempt_at` INTEGER NOT NULL, PRIMARY KEY(`message_id`))",
        "CREATE INDEX IF NOT EXISTS `outbox_chat` ON `outbox` (`chat_id`)",
        "CREATE TABLE IF NOT EXISTS `inbox_raw` (`cursor` INTEGER NOT NULL, `kind` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `seq` INTEGER, `epoch` INTEGER NOT NULL, `sender_account` TEXT NOT NULL, `sender_device` TEXT NOT NULL, `payload` BLOB NOT NULL, `sent_at` INTEGER NOT NULL, PRIMARY KEY(`cursor`))",
        "CREATE TABLE IF NOT EXISTS `processed` (`payload_hash` BLOB NOT NULL, `processed_at` INTEGER NOT NULL, PRIMARY KEY(`payload_hash`))",
        "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `folder_chats` (`folder_id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, PRIMARY KEY(`folder_id`, `chat_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `folder_chats_chat` ON `folder_chats` (`chat_id`)",
        "CREATE TABLE IF NOT EXISTS `blocked` (`account_id` TEXT NOT NULL, `blocked_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`))",
        "CREATE TABLE IF NOT EXISTS `calls` (`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `video` INTEGER NOT NULL, `outgoing` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `duration` REAL NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `calls_chat` ON `calls` (`chat_id`)",
        "CREATE TABLE IF NOT EXISTS `kv` (`key` TEXT NOT NULL, `value` BLOB NOT NULL, PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        // The value checks the app's onCreate callback adds.
        "CREATE TRIGGER conversations_check_insert BEFORE INSERT ON conversations WHEN new.mode NOT IN ('e2ee','server_readable') OR (new.inner_mode IS NOT NULL AND new.inner_mode NOT IN ('e2ee','server_readable')) BEGIN SELECT RAISE(ABORT, 'invalid conversation mode'); END",
        "CREATE TRIGGER conversations_check_update BEFORE UPDATE ON conversations WHEN new.mode NOT IN ('e2ee','server_readable') OR (new.inner_mode IS NOT NULL AND new.inner_mode NOT IN ('e2ee','server_readable')) BEGIN SELECT RAISE(ABORT, 'invalid conversation mode'); END",
        "CREATE TRIGGER attachments_check_insert BEFORE INSERT ON attachments WHEN new.state NOT IN ('remote','downloading','local','failed') BEGIN SELECT RAISE(ABORT, 'invalid attachment state'); END",
        "CREATE TRIGGER attachments_check_update BEFORE UPDATE ON attachments WHEN new.state NOT IN ('remote','downloading','local','failed') BEGIN SELECT RAISE(ABORT, 'invalid attachment state'); END",
    )

    private val FTS = listOf(
        "CREATE VIRTUAL TABLE messages_fts USING fts5(folded, content='messages', content_rowid='rowid', tokenize='trigram')",
        "CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(rowid, folded) VALUES (new.rowid, new.folded); END",
        "CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN INSERT INTO messages_fts(messages_fts, rowid, folded) VALUES ('delete', old.rowid, old.folded); END",
        "CREATE TRIGGER messages_fts_update AFTER UPDATE OF folded ON messages BEGIN INSERT INTO messages_fts(messages_fts, rowid, folded) VALUES ('delete', old.rowid, old.folded); INSERT INTO messages_fts(rowid, folded) VALUES (new.rowid, new.folded); END",
    )

    private val DATA = listOf(
        "INSERT INTO contacts VALUES ('acc-a', 'asha', 'Asha', 1, 100), ('acc-b', 'ben', 'Ben', 1, 101), ('acc-c', 'chi', 'Chi', 0, 102)",
        "INSERT INTO chats VALUES ('chat-a', 0, 0, 0, 1000), ('chat-b', 0, 2, 1, 2000), ('chat-c', 1, 1, 0, 3000)",
        "INSERT INTO conversations VALUES ('srv-a', 'chat-a', x'0a0b0c', 'e2ee', 'e2ee', 0, 4, 1, 500), ('srv-b', 'chat-b', x'0d0e0f', 'server_readable', NULL, 0, 1, 1, 501)",
        "INSERT INTO members VALUES ('srv-a', 'acc-a', x'aa'), ('srv-a', 'me', x'bb'), ('srv-b', 'acc-b', x'cc')",
        "INSERT INTO safety VALUES ('chat-a', '12345', NULL), ('chat-b', '67890', '67890')",
        "INSERT INTO messages VALUES ('m-1', 'chat-a', 'srv-a', 'acc-a', 'dev-1', 1000, 1001, x'010203', 'delivered', NULL, NULL, 0, 0, 'hello needle')",
        "INSERT INTO messages VALUES ('m-2', 'chat-b', 'srv-b', 'me', NULL, 2000, 2001, x'040506', 'sending', 'm-1', 2500, 0, 0, 'second')",
        "INSERT INTO messages VALUES ('m-3', 'chat-c', NULL, 'acc-c', NULL, 3000, 3001, x'', 'sent', NULL, NULL, 1, 1, '')",
        "INSERT INTO reactions VALUES ('m-1', 'me', 'thumbs-up')",
        "INSERT INTO attachments VALUES ('att-1', 'm-1', 'photo', x'11', x'22', 1000, 1016, 'remote', 0, NULL)",
        "INSERT INTO outbox VALUES ('outbox-1', 'chat-b', x'0708', 2, 9000)",
        "INSERT INTO inbox_raw VALUES (7, 'message', 'srv-a', 3, 4, 'acc-a', 'dev-1', x'99', 1000)",
        "INSERT INTO processed VALUES (x'abcd', 1234)",
        "INSERT INTO folders VALUES ('f-1', 'Work', 0)",
        "INSERT INTO folder_chats VALUES ('f-1', 'chat-a')",
        "INSERT INTO blocked VALUES ('acc-x', 55)",
        "INSERT INTO calls VALUES ('call-1', 'chat-a', 1, 0, 'missed', 800, 12.5)",
        "INSERT INTO kv VALUES ('setting', x'0102')",
    )

    fun create(db: SupportSQLiteDatabase, withFts: Boolean) {
        DDL.forEach(db::execSQL)
        if (withFts) FTS.forEach(db::execSQL)
        DATA.forEach(db::execSQL)
        db.execSQL("INSERT INTO room_master_table(id, identity_hash) VALUES (42, 'version-1-hash')")
    }
}
