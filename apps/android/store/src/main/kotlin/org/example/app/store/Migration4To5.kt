package org.example.app.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 4 to 5: group chats. Written by hand and only adds: no table is dropped or recreated, so no row (and no
 * cascade) is touched. Every existing chat becomes `direct` (kind default) in the `member` state with no group fields;
 * its ID, its messages and its other rows stay as they were. Adds the member list (`group_members`) and the per-member
 * delivery and read receipts (`receipts`); `messages.sender_account` already names the sender of each message.
 */
object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'direct'")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `conversation_id` TEXT")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `title` TEXT")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `photo` BLOB")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `group_ver` INTEGER")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `members_version` INTEGER")
        db.execSQL("ALTER TABLE `chats` ADD COLUMN `my_state` TEXT NOT NULL DEFAULT 'member'")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chats_conversation_id` ON `chats` (`conversation_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_members` (`chat_id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "PRIMARY KEY(`chat_id`, `account_id`), FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `receipts` (`message_id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`at` INTEGER NOT NULL, PRIMARY KEY(`message_id`, `account_id`, `kind`), " +
                "FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
