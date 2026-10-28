package org.example.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.text.BidiFormatter
import org.example.app.R

/**
 * "@name" as one isolated run, so right-to-left text cannot reorder the at sign. The formatter follows the layout
 * direction of the screen, not only the device locale.
 */
@Composable
fun usernameText(username: String): String {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return BidiFormatter.getInstance(rtl).unicodeWrap(stringResource(R.string.username_format, username))
}
