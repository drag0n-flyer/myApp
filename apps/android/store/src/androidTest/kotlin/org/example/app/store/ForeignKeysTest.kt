package org.example.app.store

import android.content.Context
import android.database.SQLException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.example.app.messaging.ports.ChatRecord
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PRAGMA foreign_keys` and `secure_delete` are per connection, and the WAL pool has one writer and several readers. Every connection,
 * whichever one a statement lands on, must enforce foreign keys and overwrite deleted content.
 */
@RunWith(AndroidJUnit4::class)
class ForeignKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "foreign-keys.db"
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context.deleteDatabase(name)
        db = AppDatabase.open(context, ByteArray(DbKey.SIZE) { (it * 3).toByte() }, name)
        RoomRepository(db).upsertChat(ChatRecord("chat1", isRequest = false, unread = 0, muted = false, lastActivity = 1))
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun pragma(name: String = "foreign_keys"): Int = db.openHelper.writableDatabase.query("PRAGMA $name").use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }

    /** Runs [block] on [threads] threads at once, [rounds] times each, so the pool opens several connections. */
    private fun <T> concurrently(threads: Int = 6, rounds: Int = 50, block: () -> T): List<T> {
        val results = Collections.synchronizedList(mutableListOf<T>())
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val futures = (1..threads).map { pool.submit { start.await(); repeat(rounds) { results.add(block()) } } }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        return results
    }

    @Test fun writerConnectionHasForeignKeysOn() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.beginTransaction() // a transaction always runs on the writer
        try {
            assertEquals(1, pragma())
            assertEquals(1, pragma("secure_delete"))
        } finally {
            sqlite.endTransaction()
        }
    }

    @Test fun everyReaderConnectionHasForeignKeysOn() {
        assertEquals(setOf(1), concurrently { pragma() }.toSet())
    }

    @Test fun everyReaderConnectionHasSecureDeleteOn() {
        assertEquals(setOf(1), concurrently { pragma("secure_delete") }.toSet())
    }

    private fun insertOrphan(): Boolean = try {
        db.openHelper.writableDatabase.execSQL("INSERT INTO safety(chat_id, current, verified) VALUES('no-such-chat', 'x', NULL)")
        db.openHelper.writableDatabase.execSQL("DELETE FROM safety WHERE chat_id = 'no-such-chat'")
        true
    } catch (e: SQLException) {
        if ("FOREIGN KEY" !in e.message.orEmpty()) throw e
        false
    }

    @Test fun orphanWriteFailsOnEveryThread() {
        assertEquals(setOf(false), concurrently(rounds = 20) { insertOrphan() }.toSet())
    }

    @Test fun deletingChatCascadesFromEveryThread() {
        val repo = RoomRepository(db)
        val left = concurrently(threads = 4, rounds = 10) {
            val id = "c-${Thread.currentThread().id}-${System.nanoTime()}"
            repo.upsertChat(ChatRecord(id, isRequest = false, unread = 0, muted = false, lastActivity = 1))
            db.openHelper.writableDatabase.execSQL("INSERT INTO safety(chat_id, current, verified) VALUES('$id', 'x', NULL)")
            db.openHelper.writableDatabase.execSQL("DELETE FROM chats WHERE id = '$id'")
            db.openHelper.writableDatabase.query("SELECT count(*) FROM safety WHERE chat_id = '$id'").use { it.moveToFirst(); it.getInt(0) }
        }
        assertEquals(setOf(0), left.toSet())
    }
}
