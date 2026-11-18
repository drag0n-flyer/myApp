package org.example.app.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.app.R

/** Shown in place of the composer in a group this account left or was removed from. */
@Composable
internal fun GroupReadOnlyBar(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("group-read-only"), color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            stringResource(R.string.group_no_longer_member),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth(),
        )
    }
}
