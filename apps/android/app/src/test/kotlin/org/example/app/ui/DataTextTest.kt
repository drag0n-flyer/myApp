package org.example.app.ui

import android.text.format.DateUtils
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.test.core.app.ApplicationProvider
import org.example.app.core.calls.DataFormat
import org.example.app.core.calls.DataMode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataTextTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun keepsTheIphoneWording() {
        assertEquals("≈ 1.2 MB used", DataText.used(context, 1_200_000, Locale.US))
        assertEquals("≈ 640 KB used", DataText.used(context, 640_000, Locale.US))
        assertEquals("~3 MB per 10 min", DataText.estimate(context, DataMode.DataSaver, Locale.US))
        assertEquals("240 KB", DataText.size(context, DataFormat.size(240_000), Locale.US))
    }

    @Test fun numbersFollowTheDeviceLocale() {
        assertEquals("≈ 1,2 MB used", DataText.used(context, 1_200_000, Locale.GERMANY))
    }

    @Test fun clockUsesTheSystemFormatter() {
        assertEquals("04:12", DateUtils.formatElapsedTime(252))
        assertEquals("1:02:05", DateUtils.formatElapsedTime(3725))
        assertEquals("04:12", DataText.clock(252.9))
    }
}
