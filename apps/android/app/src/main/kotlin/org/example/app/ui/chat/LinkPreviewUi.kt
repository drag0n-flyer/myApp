package org.example.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.example.app.R
import org.example.app.core.chats.LinkPreviewCard
import org.example.app.core.chats.LinkPreviewMaker
import org.example.app.core.chats.LinkPreviewRule

/** What the composer needs to make previews: the maker, and whether Settings > Privacy > Link previews is on. */
class LinkPreviewSupport(val maker: LinkPreviewMaker, val enabled: StateFlow<Boolean>)

/** Null when previews cannot be made here. Receivers never read it: they show what the message carries. */
val LocalLinkPreviews = staticCompositionLocalOf<LinkPreviewSupport?> { null }

/** How long the text rests before a link in it is looked up. */
private const val SETTLE_MS = 800L

/** The preview shown above the composer for the first link of the draft, and what the person did with it. */
@Stable
class ComposerPreview {
    var card by mutableStateOf<LinkPreviewCard?>(null)
        internal set
    /** Links whose preview the person removed in this draft: they are not looked up again. */
    internal var dismissed by mutableStateOf<Set<String>>(emptySet())

    fun remove() {
        card?.let { dismissed = dismissed + it.url }
        card = null
    }

    /** A new draft: the message went out, or the text was cleared. */
    fun reset() {
        card = null
        dismissed = emptySet()
    }
}

/**
 * Looks up the first https link of [text] once it has rested, only when previews are on, nothing was removed for that
 * link, and the draft is not an edit. At most one lookup per link. No lookup happens for any other text.
 */
@Composable
fun rememberComposerPreview(text: String, editing: Boolean): ComposerPreview {
    val state = remember { ComposerPreview() }
    val support = LocalLinkPreviews.current
    val on by (support?.enabled ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val url = remember(text) { LinkPreviewRule.firstLink(text) }
    // An empty box is a new draft: a removed preview may come back for the next one.
    LaunchedEffect(text.isBlank()) { if (text.isBlank()) state.reset() }
    val wanted = if (on && !editing && url != null && url !in state.dismissed) url else null
    LaunchedEffect(wanted, support) {
        if (wanted == null || support == null) { state.card = null; return@LaunchedEffect }
        if (state.card?.url == wanted) return@LaunchedEffect
        state.card = null
        delay(SETTLE_MS)
        state.card = try {
            support.maker.make(wanted)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    return state
}

/** The card: picture, title, description and the site. A tap opens the link; nothing is fetched here. [onRemove] adds the (x) control. */
@Composable
fun LinkPreviewCardView(card: LinkPreviewCard, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val uri = LocalUriHandler.current
    val image = remember(card.image) { card.image?.let(::decodeSmallJpeg) }
    val site = remember(card.url) { runCatching { java.net.URI(card.url).host }.getOrNull().orEmpty().removePrefix("www.") }
    val open = stringResource(R.string.link_preview_open)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f)
                    .clickable(onClickLabel = open, role = Role.Button) { runCatching { uri.openUri(card.url) } }
                    .semantics(mergeDescendants = true) {},
            ) {
                if (image != null) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(120.dp))
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    card.desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    if (site.isNotEmpty()) Text(site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.link_preview_remove))
                }
            }
        }
    }
}

/** A preview picture is at most 8 KiB, but a hostile one could declare a huge size: anything over 1024 pixels a side is not decoded. */
private fun decodeSmallJpeg(bytes: ByteArray) = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() else null
} catch (_: Throwable) {
    null
}
