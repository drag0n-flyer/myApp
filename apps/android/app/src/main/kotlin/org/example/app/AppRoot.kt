package org.example.app

import androidx.compose.runtime.Composable
import org.example.app.ui.nav.MainTabs
import org.example.app.ui.theme.AppTheme

@Composable
fun AppRoot(@Suppress("UNUSED_PARAMETER") graph: AppGraph) {
    AppTheme { MainTabs() }
}
