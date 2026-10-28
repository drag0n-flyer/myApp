package org.example.app.store

import android.content.Context
import android.database.SQLException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.example.app.messaging.ports.AttachmentState
import org.example.app.messaging.ports.ConversationMode
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
                .openHelperFactory(ForeignKeysOnEveryConnection(factory))
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(callback(allowNoFts = false))
                .build()
        }

        /**
         * Creates the value checks and the FTS5 index over `messages.folded`, kept in step by triggers. Room can
         * declare neither CHECK nor FTS5. With [allowNoFts] false (every real open) a failure to create the index
         * throws, so a build without the trigram tokenizer fails loudly. Only the JVM tests set it true, because
         * Robolectric's SQLite has no FTS5; search then uses LIKE.
         */
        internal fun callback(allowNoFts: Boolean) = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createValueChecks(db)
                try {
                    db.execSQL(
                        "CREATE VIRTUAL TABLE $FTS_TABLE USING fts5(folded, content='messages', content_rowid='rowid', tokenize='trigram')",
                    )
                } catch (e: SQLException) {
                    val noModule = e.message.orEmpty().let { "no such module" in it || "tokenizer" in it }
                    if (!(allowNoFts && noModule)) throw e
                    return
                }
                // The triggers and `outbox ORDER BY rowid` rely on stable rowids: never VACUUM this database.
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

        /**
         * Turns foreign keys on for every pooled connection. `PRAGMA foreign_keys` is per connection, and a pragma run
         * in onOpen reaches only the one connection it lands on; with WAL the pool's reader connections opened later
         * would have them off. [SupportSQLiteDatabase.setForeignKeyConstraintsEnabled] in onConfigure puts it in the
         * pool's configuration, which every connection applies when it opens. Room exposes no onConfigure, so the
         * helper's callback is wrapped.
         *
         * Foreign keys are therefore on during onCreate and onUpgrade too, and `PRAGMA foreign_keys=OFF` has no effect
         * inside the upgrade transaction. A future migration that drops and recreates a parent table (such as `chats`)
         * would cascade-delete its children: copy rows into the new table before dropping the old one.
         */
        private class ForeignKeysOnEveryConnection(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                val inner = configuration.callback
                val callback = object : SupportSQLiteOpenHelper.Callback(inner.version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                        inner.onConfigure(db)
                    }
                    override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onUpgrade(db, oldVersion, newVersion)
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onDowngrade(db, oldVersion, newVersion)
                    override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)
                    override fun onCorruption(db: SupportSQLiteDatabase) = inner.onCorruption(db)
                }
                return delegate.create(
                    SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                        .name(configuration.name)
                        .callback(callback)
                        .noBackupDirectory(configuration.useNoBackupDirectory)
                        .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                        .build(),
                )
            }
        }

        /** The iPhone's CHECK constraints, as triggers: `mode`, `inner_mode` and attachment `state` take only known values. */
        private fun createValueChecks(db: SupportSQLiteDatabase) {
            val modes = ConversationMode.all.joinToString(",") { "'$it'" }
            val states = AttachmentState.all.joinToString(",") { "'$it'" }
            val badConversation = "new.mode NOT IN ($modes) OR (new.inner_mode IS NOT NULL AND new.inner_mode NOT IN ($modes))"
            for (event in listOf("INSERT", "UPDATE")) {
                db.execSQL(
                    "CREATE TRIGGER conversations_check_${event.lowercase()} BEFORE $event ON conversations WHEN $badConversation " +
                        "BEGIN SELECT RAISE(ABORT, 'invalid conversation mode'); END",
                )
                db.execSQL(
                    "CREATE TRIGGER attachments_check_${event.lowercase()} BEFORE $event ON attachments WHEN new.state NOT IN ($states) " +
                        "BEGIN SELECT RAISE(ABORT, 'invalid attachment state'); END",
                )
            }
        }
    }
}
