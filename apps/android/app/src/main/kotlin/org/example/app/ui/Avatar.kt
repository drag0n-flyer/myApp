package org.example.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.profile.LocalProfilePhotos
import org.example.app.profile.ProfilePhotoCache
import org.example.app.ui.theme.AvatarTiles
import org.example.app.ui.theme.isDarkTheme

/** Test tag of a tile that shows a profile photo. */
const val AVATAR_PHOTO_TAG = "avatar-photo"

/**
 * The contact's profile photo in a circle when there is one, else the initial in a circle. With [colorKey] (the
 * person's account ID) the circle is one of 8 colours chosen from it and the letter is white, and the photo for that
 * account is looked up in [LocalProfilePhotos]; without one it is the neutral tonal circle. The photo is decoded off
 * the main thread (and checked again first); until then, and when it does not decode, the letter shows. Decorative:
 * the name next to it is the label.
 */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier, colorKey: String?) {
    val initial = name.trim().codePoints().findFirst().let { if (it.isPresent) String(Character.toChars(it.asInt)) else "" }
    val tile = AvatarTiles.tileFor(colorKey, isDarkTheme())
    val profiles = LocalProfilePhotos.current
    val photoBytes = if (profiles != null && colorKey != null) profiles.collectAsState().value[colorKey]?.photo else null
    val photo: ImageBitmap? by produceState<ImageBitmap?>(null, photoBytes) {
        value = if (photoBytes == null) null else withContext(Dispatchers.Default) { ProfilePhotoCache.get(photoBytes)?.asImageBitmap() }
    }
    val shown = photo
    Box(
        modifier.size(size).clip(CircleShape).background(tile ?: MaterialTheme.colorScheme.secondaryContainer)
            .clearAndSetSemantics { if (shown != null) testTag = AVATAR_PHOTO_TAG },
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(shown, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                initial.uppercase(),
                color = if (tile != null) AvatarTiles.onTile else MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = (size.value / 2.4f).sp,
            )
        }
    }
}
