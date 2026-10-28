package org.example.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Icons that point somewhere, or are drawn with a handedness, must flip in right-to-left layouts. */
class DirectionalIconsTest {
    private val drawables = File("").absoluteFile.resolve("src/main/res/drawable")

    @Test
    fun directionalIconsAreAutoMirrored() {
        val notMirrored = listOf("ic_back", "ic_chevron", "ic_send", "ic_play", "ic_call", "ic_phone", "ic_volume_up", "ic_signal", "ic_search")
            .filter { !File(drawables, "$it.xml").readText().contains("android:autoMirrored=\"true\"") }
        assertEquals(emptyList(), notMirrored)
    }
}
