package org.example.app.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 to 3: the outbox row keeps the MLS ciphertext made for its envelope and the epoch it was made in, so a
 * retry in the same epoch posts the same bytes and does not advance the sender's generation again. Only adds two
 * nullable columns; every existing row keeps its values and starts with no stored ciphertext (the next attempt
 * encrypts and stores one).
 *
 * Named for what it does, not for its numbers, so it can be renumbered when another branch also claims version 3:
 * change the two numbers here, `AppDatabase.version`, `MIGRATIONS` and `CURRENT_VERSION` in the migration test.
 */
object MigrationOutboxCiphertext : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `ciphertext` BLOB")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `cipher_epoch` INTEGER")
    }
}
