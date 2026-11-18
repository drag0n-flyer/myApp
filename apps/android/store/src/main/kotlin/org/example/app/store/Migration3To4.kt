package org.example.app.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 3 to 4: the outbox row keeps the MLS ciphertext made for its envelope, the conversation it was made for and
 * the epoch it was made in, so a retry in the same conversation and epoch posts the same bytes and does not advance
 * the sender's generation again. Only adds three nullable columns; every existing row keeps its values and starts
 * with no stored ciphertext (the next attempt encrypts and stores one).
 */
object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `ciphertext` BLOB")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `cipher_epoch` INTEGER")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `cipher_conversation` TEXT")
    }
}
