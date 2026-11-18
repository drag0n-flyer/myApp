package org.example.app.store

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.runner.RunWith

/** The 2 to 3 upgrade on a device, through the encrypted open path the app uses, with FTS5. */
@RunWith(AndroidJUnit4::class)
class Migration2To3Test : Migration2To3Contract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-2-3-contract.db"
    private val key = ByteArray(DbKey.SIZE) { (it * 5).toByte() }

    override val hasFts = true

    override fun v2Helper(callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(key.copyOf(), null, true)
            .create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build())
    }

    override fun openCurrent(): AppDatabase = AppDatabase.open(context, key, name)

    override fun deleteFile() { context.deleteDatabase(name) }
}
