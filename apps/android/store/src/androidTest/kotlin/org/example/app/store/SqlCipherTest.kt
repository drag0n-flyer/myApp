package org.example.app.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.example.app.messaging.ports.ChatRecord
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SqlCipherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "sqlcipher-test.db"
    private val key = ByteArray(DbKey.SIZE) { (it * 7).toByte() }

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun cleanUp() { context.deleteDatabase(name) }

    private fun writeSomething() {
        val db = AppDatabase.open(context, key, name)
        try {
            val repo = RoomRepository(db)
            repo.upsertChat(ChatRecord("chat1", false, 0, false, 1))
            repo.set("marker", "plaintext-marker-do-not-find".toByteArray())
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        } finally {
            db.close()
        }
    }

    @Test fun fileIsNotPlainSqlite() {
        writeSomething()
        val file: File = context.getDatabasePath(name)
        assertTrue(file.exists())
        // Not readNBytes: that is Java 11 and missing on API 29.
        val head = ByteArray(16)
        file.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            assertEquals(head.size, read)
        }
        assertTrue(!head.contentEquals("SQLite format 3\u0000".toByteArray()), "database header must be encrypted")
        assertTrue(!String(file.readBytes(), Charsets.ISO_8859_1).contains("plaintext-marker-do-not-find"))
    }

    @Test fun wrongKeyFailsToOpen() {
        writeSomething()
        val wrong = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val db = AppDatabase.open(context, wrong, name)
        try {
            assertFailsWith<Exception> { RoomRepository(db).chats() }
        } finally {
            runCatching { db.close() }
        }
        // Losing the key must never make SQLCipher delete the user's file.
        assertTrue(context.getDatabasePath(name).length() > 0)
    }

    @Test fun rightKeyReopens() {
        writeSomething()
        val db = AppDatabase.open(context, key, name)
        try {
            assertEquals("chat1", RoomRepository(db).chat("chat1")?.id)
            assertContentEquals("plaintext-marker-do-not-find".toByteArray(), RoomRepository(db).get("marker"))
        } finally {
            db.close()
        }
    }

    @Test fun trigramTokenizerAndWalAreAvailable() {
        val db = AppDatabase.open(context, key, name)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("PRAGMA journal_mode").use { assertTrue(it.moveToFirst()); assertEquals("wal", it.getString(0).lowercase()) }
            sqlite.query("SELECT name FROM sqlite_master WHERE name = 'messages_fts'").use { assertTrue(it.moveToFirst()) }
            assertNotNull(RoomRepository(db))
        } finally {
            db.close()
        }
    }

    @Test fun foreignKeysAreOnAndCascadeUnderSqlCipher() {
        val db = AppDatabase.open(context, key, name)
        try {
            val repo = RoomRepository(db)
            db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            repo.upsertChat(ChatRecord("chat1", false, 0, false, 1))
            repo.insertMessage(
                org.example.app.messaging.ports.MessageRecord("m1", "chat1", null, "alice", null, 1, 1, byteArrayOf(1), "sent", folded = "hello"),
            )
            db.openHelper.writableDatabase.execSQL("DELETE FROM chats WHERE id = 'chat1'")
            assertEquals(null, repo.message("m1"))
        } finally {
            db.close()
        }
    }

    @Test fun keyArrayIsNotWipedByOpening() {
        val copy = key.copyOf()
        AppDatabase.open(context, key, name).close()
        assertContentEquals(copy, key)
    }
}
