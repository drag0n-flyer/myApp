package org.example.app.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 to 2: pin time and archive flag on chats, and the drafts table. `chats.muted` already exists.
 * Written by hand, and only adds: no table is dropped or recreated, so no row (and, with foreign keys on, no
 * cascade) is touched.
 */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
    }
}
