package org.example.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.text.BidiFormatter
import org.example.app.R

/** "@name", with the name isolated so right-to-left text cannot reorder the at sign. */
@Composable
fun usernameText(username: String): String =
    stringResource(R.string.username_format, BidiFormatter.getInstance().unicodeWrap(username))
