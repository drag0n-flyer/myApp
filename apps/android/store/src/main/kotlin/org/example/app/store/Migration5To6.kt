package org.example.app.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 5 to 6: adds `poll_votes`, the latest vote of each account on each poll. Nothing existing changes. */
object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `poll_votes` (`poll_id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, " +
                "`option_ids` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`poll_id`, `account_id`), " +
                "FOREIGN KEY(`chat_id`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `poll_votes_chat` ON `poll_votes` (`chat_id`)")
    }
}
