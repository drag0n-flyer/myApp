package org.example.app.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The repository logic on the JVM against the stock SQLite that Robolectric bundles. It proves the queries,
 * transactions and triggers. Encryption and the SQLCipher build are covered by the instrumented tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomRepositoryRobolectricTest : RepositoryContract() {
    override fun openDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.callback)
            .build()
}
