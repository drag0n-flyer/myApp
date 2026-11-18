package org.example.app.store

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.runner.RunWith

/** The 1 to 2 upgrade on a device, through the encrypted open path the app uses (`AppDatabase.open`), with FTS5. */
@RunWith(AndroidJUnit4::class)
class MigrationTest : MigrationContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-contract.db"
    private val key = ByteArray(DbKey.SIZE) { (it * 3).toByte() }

    override val hasFts = true

    override fun v1Helper(callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(key.copyOf(), null, true)
            .create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build())
    }

    override fun openCurrent(): AppDatabase = AppDatabase.open(context, key, name)

    override fun deleteFile() { context.deleteDatabase(name) }
}
