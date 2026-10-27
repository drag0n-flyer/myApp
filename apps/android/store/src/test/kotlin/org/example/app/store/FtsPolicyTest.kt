package org.example.app.store

import android.database.SQLException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Robolectric's SQLite has no FTS5, which makes it the stand-in for a build that lacks the trigram tokenizer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FtsPolicyTest {
    private fun build(allowNoFts: Boolean) =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.callback(allowNoFts))
            .build()

    @Test fun strictCreationFailsLoudlyWithoutFts() {
        val db = build(allowNoFts = false)
        try {
            assertFailsWith<SQLException> { db.openHelper.writableDatabase }
        } finally {
            runCatching { db.close() }
        }
    }

    @Test fun repositoryRefusesALikeFallbackUnlessAllowed() {
        val db = build(allowNoFts = true)
        try {
            assertFailsWith<IllegalStateException> { RoomRepository(db) }
            RoomRepository(db, allowLikeFallback = true)
        } finally {
            db.close()
        }
    }
}
