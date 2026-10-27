package org.example.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.example.app.R
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.DataModeInfo
import org.example.app.core.calls.ModePreference
import org.example.app.core.settings.DownloadScope
import org.example.app.core.settings.ServerPolicy
import org.example.app.ui.keyString

@Composable
fun DataStorageScreen(settings: SettingsStore, server: ServerPolicy, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val current by settings.settings.collectAsState()
    val modeChoices = listOf(Choice<ModePreference>(ModePreference.Auto, keyString(ModePreference.Auto.titleKey))) +
        DataMode.entries.map { Choice<ModePreference>(ModePreference.Fixed(it), keyString(DataModeInfo.titleKey(it))) }
    val limitChoices = DataMode.entries.map { Choice(it, keyString(DataModeInfo.titleKey(it))) }
    val scopeChoices = DownloadScope.entries.map { Choice(it, keyString(it.titleKey)) }

    SettingsPage(stringResource(R.string.settings_data), onBack, modifier) {
        item { SectionHeader(stringResource(R.string.data_calls)) }
        item {
            ChoiceRow(stringResource(R.string.data_call_mode), modeChoices, current.call.callDataMode) { v ->
                settings.update { it.copy(call = it.call.copy(callDataMode = v)) }
            }
        }
        item {
            ChoiceRow(stringResource(R.string.data_mobile_limit), limitChoices, current.call.mobileDataLimit) { v ->
                settings.update { it.copy(call = it.call.copy(mobileDataLimit = v)) }
            }
        }
        item {
            SwitchRow(stringResource(R.string.data_video_off), current.call.videoOffOnMobile) { v ->
                settings.update { it.copy(call = it.call.copy(videoOffOnMobile = v)) }
            }
        }
        item {
            FooterText(
                stringResource(
                    R.string.data_server_footer,
                    keyString(server.defaultPreference.titleKey), keyString(DataModeInfo.titleKey(server.maximum)),
                ),
            )
        }
        item { SectionHeader(stringResource(R.string.data_auto_download)) }
        item {
            ChoiceRow(stringResource(R.string.data_wifi), scopeChoices, current.autoDownload.onWiFi) { v ->
                settings.update { it.copy(autoDownload = it.autoDownload.copy(onWiFi = v)) }
            }
        }
        item {
            ChoiceRow(stringResource(R.string.data_mobile), scopeChoices, current.autoDownload.onMobile) { v ->
                settings.update { it.copy(autoDownload = it.autoDownload.copy(onMobile = v)) }
            }
        }
        item {
            SwitchRow(stringResource(R.string.data_voice_always), current.autoDownload.voiceAlways) { v ->
                settings.update { it.copy(autoDownload = it.autoDownload.copy(voiceAlways = v)) }
            }
        }
    }
}
