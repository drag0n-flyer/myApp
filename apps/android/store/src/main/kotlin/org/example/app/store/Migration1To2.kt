package org.example.app.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 to 2: pin time and archive flag on chats, and the drafts table. `chats.muted` already exists.
 * Written by hand, and only adds: no table is dropped or recreated, so no row (and, with foreign keys on, no
 * cascade) is touched. Existing chats get `pinned_at` NULL (not pinned) and `archived` 0 (shown), and start with
 * no drafts.
 */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `pinned_at` INTEGER")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drafts` (`chat_id` TEXT NOT NULL, `text` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chat_id`), FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
