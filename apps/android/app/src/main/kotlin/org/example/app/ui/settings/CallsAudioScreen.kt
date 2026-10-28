package org.example.app.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.example.app.R
import org.example.app.core.settings.NoiseSuppression

/** Background noise choices. Android has no system voice-isolation switch to point to, so there is no such row. */
@Composable
fun CallsAudioScreen(settings: SettingsStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val current by settings.settings.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_calls_audio), onBack, modifier) {
        item { SectionHeader(stringResource(R.string.audio_noise)) }
        items(NoiseSuppression.entries.toList(), key = { it.name }) { mode ->
            val (title, subtitle) = when (mode) {
                NoiseSuppression.Standard -> R.string.audio_standard to R.string.audio_standard_subtitle
                NoiseSuppression.Enhanced -> R.string.audio_enhanced to R.string.audio_enhanced_subtitle
                NoiseSuppression.Off -> R.string.audio_off to R.string.audio_off_subtitle
            }
            RadioRow(stringResource(title), stringResource(subtitle), current.noiseSuppression == mode) {
                settings.update { it.copy(noiseSuppression = mode) }
            }
        }
    }
}
