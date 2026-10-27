package org.example.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Initial in a tonal circle. Decorative: the name next to it is the label. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val initial = name.trim().codePoints().findFirst().let { if (it.isPresent) String(Character.toChars(it.asInt)) else "" }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(initial.uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = (size.value / 2.4f).sp)
    }
}
