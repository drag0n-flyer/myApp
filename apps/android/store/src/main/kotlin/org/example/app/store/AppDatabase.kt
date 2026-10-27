package org.example.app.store

import android.content.Context
import android.database.SQLException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ContactEntity::class, ChatEntity::class, ConversationEntity::class, MemberEntity::class, SafetyEntity::class,
        MessageEntity::class, ReactionEntity::class, AttachmentEntity::class, OutboxEntity::class, InboxRawEntity::class,
        ProcessedEntity::class, FolderEntity::class, FolderChatEntity::class, BlockedEntity::class, CallEntity::class,
        KvEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contacts(): ContactDao
    abstract fun chats(): ChatDao
    abstract fun conversations(): ConversationDao
    abstract fun safety(): SafetyDao
    abstract fun messages(): MessageDao
    abstract fun outbox(): OutboxDao
    abstract fun misc(): MiscDao

    companion object {
        const val FILE_NAME = "messages.db"
        private const val FTS_TABLE = "messages_fts"

        /** Opens the encrypted database. [key] is copied: SQLCipher wipes the array it is given. */
        fun open(context: Context, key: ByteArray, name: String = FILE_NAME): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(key.copyOf(), null, true)
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .openHelperFactory(factory)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(callback)
                .build()
        }

        /** The FTS5 index over `messages.folded`, kept in step by triggers. Room cannot declare FTS5 itself. */
        internal val callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL(
                        "CREATE VIRTUAL TABLE $FTS_TABLE USING fts5(folded, content='messages', content_rowid='rowid', tokenize='trigram')",
                    )
                } catch (e: SQLException) {
                    // This SQLite has no FTS5 trigram tokenizer (the JVM test SQLite). Search then uses LIKE on the
                    // folded column. The instrumented SqlCipherTest fails if the real build ever lands here.
                    return
                }
                db.execSQL(
                    "CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN " +
                        "INSERT INTO $FTS_TABLE(rowid, folded) VALUES (new.rowid, new.folded); END",
                )
                db.execSQL(
                    "CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN " +
                        "INSERT INTO $FTS_TABLE($FTS_TABLE, rowid, folded) VALUES ('delete', old.rowid, old.folded); END",
                )
                db.execSQL(
                    "CREATE TRIGGER messages_fts_update AFTER UPDATE OF folded ON messages BEGIN " +
                        "INSERT INTO $FTS_TABLE($FTS_TABLE, rowid, folded) VALUES ('delete', old.rowid, old.folded); " +
                        "INSERT INTO $FTS_TABLE(rowid, folded) VALUES (new.rowid, new.folded); END",
                )
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Overwrite deleted content so a deleted message does not linger in free pages.
                db.query("PRAGMA secure_delete=ON").close()
            }
        }
    }
}
