package org.example.app.ui.call

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.ui.Avatar

private val Backdrop = Brush.verticalGradient(listOf(Color(0xFF2A3560), Color(0xFF11142B), Color(0xFF070914)))
private val AnswerGreen = Color(0xFF2FB457)

/** A ringing call shown in the app: who is calling, Decline and Answer. Always dark. */
@Composable
fun IncomingCallScreen(viewModel: CallViewModel, modifier: Modifier = Modifier) {
    val call by viewModel.call.collectAsStateWithLifecycle()
    val current = call ?: return
    IncomingCallContent(
        name = viewModel.contactName(current),
        onDecline = viewModel::decline,
        onAnswer = { viewModel.answer(current.isVideoCall) },
        modifier = modifier,
    )
}

/**
 * The incoming-call screen itself, also shown over the lock screen by [LockScreenCallActivity] (the Android form
 * of the iPhone's CallKit screen).
 */
@Composable
fun IncomingCallContent(name: String, onDecline: () -> Unit, onAnswer: () -> Unit, modifier: Modifier = Modifier) {
    CallTheme {
        CallBackHandler(onMinimise = null)
        Box(modifier.fillMaxSize().background(Backdrop).consumeTouches()) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Avatar(name, size = 110.dp, modifier = Modifier.padding(bottom = 8.dp))
                    Text(name, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.call_incoming), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallControl(stringResource(R.string.call_decline), R.drawable.ic_call_end, tint = EndRed, onClick = onDecline)
                    CallControl(stringResource(R.string.call_answer), R.drawable.ic_call, tint = AnswerGreen, onClick = onAnswer)
                }
            }
        }
    }
}
