package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import org.example.app.R
import org.example.app.core.account.AccountError

const val CODE_LENGTH = 6

/** Keeps only the first six digits of whatever was typed or pasted. */
fun sixDigits(text: String): String = text.filter { it in '0'..'9' }.take(CODE_LENGTH)

@Composable
fun VerifyEmailScreen(error: AccountError?, busy: Boolean, onVerify: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    OnboardingPage(
        stringResource(R.string.ob_verify_title),
        message = stringResource(R.string.ob_verify_message),
        footer = { PrimaryButton(stringResource(R.string.ob_verify), { onVerify(code) }, enabled = code.length == CODE_LENGTH, busy = busy) },
    ) {
        OutlinedTextField(
            code, { code = sixDigits(it) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_code)) },
            placeholder = { Text(stringResource(R.string.ob_code_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        AccountErrorText(error)
    }
}
