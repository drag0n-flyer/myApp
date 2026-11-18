package org.example.app.store

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upgrading a populated version 2 database to version 3 (expiry, caption, link preview, chat timer, profiles) keeps
 * every row and gives the new columns their defaults. The version 2 schema is written out as SQL (version 1 plus the
 * hand-written statements of the 1 to 2 step), not derived from the current entities.
 */
abstract class Migration2To3Contract {
    protected abstract fun v2Helper(callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper
    protected abstract fun openCurrent(): AppDatabase
    protected abstract fun deleteFile()
    protected abstract val hasFts: Boolean

    @Before fun clean() = deleteFile()
    @After fun cleanUp() = deleteFile()

    private fun createVersion2(): Map<String, List<String>> {
        val helper = v2Helper(object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onConfigure(db: SupportSQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
            override fun onCreate(db: SupportSQLiteDatabase) = SchemaV2.create(db, hasFts)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("not expected")
        })
        val db = helper.writableDatabase
        assertEquals(2, db.version)
        val snapshot = snapshot(db)
        helper.close()
        return snapshot
    }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<String>> =
        SchemaV2.COLUMNS.mapValues { (table, columns) ->
            val rows = mutableListOf<String>()
            db.query("SELECT ${columns.joinToString(",") { "hex($it)" }} FROM $table ORDER BY rowid").use { c ->
                while (c.moveToNext()) rows += (0 until columns.size).joinToString("|") { c.getString(it) ?: "NULL" }
            }
            rows
        }

    @Test fun populatedVersion2OpensAtVersion3WithEveryRowIntact() {
        val before = createVersion2()
        for (table in listOf("chats", "messages", "attachments", "drafts", "outbox", "kv")) {
            assertTrue(before.getValue(table).isNotEmpty(), "fixture has no rows in $table")
        }
        val db = openCurrent()
        try {
            val raw = db.openHelper.writableDatabase
            assertEquals(AppDatabase.VERSION, raw.version)
            assertTrue(AppDatabase.VERSION >= 3) // later migrations (3 to 4) run on top of this one
            assertEquals(before, snapshot(raw), "a version 2 row changed or was lost")
            raw.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst(), "foreign key violation after migration") }
            raw.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }

            raw.query("SELECT COUNT(*) FROM messages WHERE expires_at IS NOT NULL OR caption IS NOT NULL OR link_preview IS NOT NULL").use {
                it.moveToFirst(); assertEquals(0, it.getInt(0))
            }
            raw.query("SELECT COUNT(*) FROM chats WHERE timer_seconds = 0").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
            raw.query("SELECT COUNT(*) FROM profiles").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            raw.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'messages_expires'").use { assertTrue(it.moveToFirst()) }

            val repo = RoomRepository(db, allowLikeFallback = !hasFts)
            val chat = repo.chat("chat-a")!!
            assertEquals(0, chat.timerSeconds)
            assertEquals(123L, chat.pinnedAt)
            assertEquals("half a thought", repo.draft("chat-b"))
            assertNull(repo.message("m-1")!!.expiresAt)

            // The new columns and table work on migrated data, and a sweep leaves old rows alone.
            repo.setTimer("chat-a", 86_400)
            assertEquals(86_400, repo.timerSeconds("chat-a"))
            repo.insertMessage(repo.message("m-1")!!.copy(id = "m-new", expiresAt = 10, caption = "c", linkPreview = "{}"))
            assertEquals(listOf("m-new"), repo.sweepExpired(10, "me"))
            assertEquals(3, repo.chats().size)
            assertTrue(repo.message("m-1") != null && repo.message("m-2") != null && repo.message("m-3") != null)
            assertTrue(repo.saveProfile("acc-a", "Asha", null, 1))
            if (hasFts) assertEquals(1, repo.search("needle", 10).size, "the search index survived the upgrade")
        } finally {
            db.close()
        }
    }

    @Test fun aDatabaseThatIsAlreadyVersion3OpensUnchanged() {
        createVersion2()
        openCurrent().close()
        val db = openCurrent()
        try {
            assertEquals(AppDatabase.VERSION, db.openHelper.writableDatabase.version)
            assertEquals(3, RoomRepository(db, allowLikeFallback = !hasFts).chats().size)
        } finally {
            db.close()
        }
    }
}

/** The version 2 schema: version 1 plus pin time, archive flag and drafts (the 1 to 2 step as plain SQL), with a few values set. */
object SchemaV2 {
    val COLUMNS: Map<String, List<String>> = SchemaV1.COLUMNS + mapOf(
        "chats" to listOf("id", "is_request", "unread", "muted", "last_activity", "pinned_at", "archived"),
        "drafts" to listOf("chat_id", "text", "updated_at"),
    )

    fun create(db: SupportSQLiteDatabase, withFts: Boolean) {
        SchemaV1.create(db, withFts)
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `pinned_at` INTEGER")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drafts` (`chat_id` TEXT NOT NULL, `text` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chat_id`), FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("UPDATE chats SET pinned_at = 123 WHERE id = 'chat-a'")
        db.execSQL("UPDATE chats SET archived = 1 WHERE id = 'chat-c'")
        db.execSQL("INSERT INTO drafts VALUES ('chat-b', 'half a thought', 5)")
    }
}
