package org.example.app.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** The shared repository behaviour on a real device against the encrypted database. */
@RunWith(AndroidJUnit4::class)
class RoomRepositoryTest : RepositoryContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "repo-contract.db"

    override fun openDatabase(): AppDatabase {
        context.deleteDatabase(name)
        return AppDatabase.open(context, ByteArray(DbKey.SIZE) { it.toByte() }, name)
    }
}
