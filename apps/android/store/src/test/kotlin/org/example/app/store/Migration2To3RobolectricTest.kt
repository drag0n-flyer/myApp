package org.example.app.store

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 2 to 3 upgrade on the JVM against the stock SQLite that Robolectric bundles (no FTS5 there). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration2To3RobolectricTest : Migration2To3Contract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-2-3-test.db"

    override val hasFts = false

    override fun v2Helper(callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build())

    override fun openCurrent(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .addCallback(AppDatabase.callback(allowNoFts = true))
            .allowMainThreadQueries()
            .build()

    override fun deleteFile() { context.deleteDatabase(name) }
}
