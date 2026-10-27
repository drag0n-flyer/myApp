package org.example.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import org.example.app.R
import org.example.app.core.chats.MessagePreview

/** Resolves a string resource named by a `:core` key; falls back to the key itself if it is missing. */
@Composable
fun keyString(key: String, vararg args: Any): String {
    val resources = LocalResources.current
    val id = resources.getIdentifier(key, "string", LocalContext.current.packageName)
    return if (id == 0) key else resources.getString(id, *args)
}

/** One-line text of a message preview. `preview_unsupported` needs the app name (the core emits it without arguments). */
@Composable
fun previewText(preview: MessagePreview): String = when (preview) {
    is MessagePreview.Text -> preview.text
    is MessagePreview.Resource ->
        if (preview.key == "preview_unsupported") stringResource(R.string.preview_unsupported, stringResource(R.string.app_name))
        else keyString(preview.key, *preview.args.toTypedArray())
}

/** Time for today, short date otherwise. */
fun shortTime(at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val sameDay = at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    val format = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateInstance(DateFormat.SHORT)
    return format.format(Date.from(at))
}
