package org.example.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // the newest SDK this Robolectric release ships
class ManifestRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun backupIsDisabled() {
        val flags = context.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun applicationIdComesFromAppIdFile() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "app-id.properties").exists()) dir = dir.parentFile
        val props = java.util.Properties().apply { java.io.File(checkNotNull(dir), "app-id.properties").inputStream().use { load(it) } }
        assertEquals(props.getProperty("applicationId"), context.packageName)
    }

    @Test
    fun layoutSupportsRtlAndAppNameIsPlaceholder() {
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL != 0)
        assertEquals("[App]", context.getString(R.string.app_name))
    }

    @Test
    fun dataExtractionRulesExcludeEveryDomain() {
        val domains = listOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        val excluded = mutableMapOf<String, MutableSet<String>>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "include" -> throw AssertionError("no include rule is allowed")
                "exclude" -> {
                    assertEquals(".", parser.getAttributeValue(null, "path"))
                    excluded.getOrPut(checkNotNull(section)) { mutableSetOf() }.add(parser.getAttributeValue(null, "domain"))
                }
            }
        }
        assertEquals(setOf("cloud-backup", "device-transfer"), excluded.keys)
        for ((name, set) in excluded) assertEquals(domains.toSet(), set, name)
    }

    @Test
    fun incomingCallsMayUseFullScreenIntent() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.USE_FULL_SCREEN_INTENT"))
    }
}
