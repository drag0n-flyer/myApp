package org.example.app.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 to 3: message expiry, caption and link preview, the chat timer, and the profiles table. Written by hand and
 * only adds: no table is dropped or recreated, so no row (and no cascade) is touched. Old messages get NULL expiry
 * (they never disappear), old chats a timer of 0 (off).
 */
object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `expires_at` INTEGER")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `caption` TEXT")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `link_preview` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `messages_expires` ON `messages` (`expires_at`)")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `timer_seconds` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profiles` (`account_id` TEXT NOT NULL, `name` TEXT NOT NULL, `photo` BLOB, " +
                "`ver` INTEGER NOT NULL, PRIMARY KEY(`account_id`))",
        )
    }
}
