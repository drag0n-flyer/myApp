package org.example.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.example.app.R

/** Settings > Open-source licenses, loading the bundled notices. */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val book = remember { runCatching { OssNotices.load(context) }.getOrNull() }
    if (book == null) {
        SettingsPage(stringResource(R.string.licenses_title), onBack, modifier) {
            item { FooterText(stringResource(R.string.licenses_load_failed)) }
        }
    } else {
        LicensesScreen(book, onBack, modifier)
    }
}

/**
 * The component list; opening one shows its license text. Real component names may appear here
 * (the licenses require them), so the plain-words rule does not apply to this page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(book: OssBook, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = book.components.firstOrNull { it.id == openId }
    if (open == null) {
        SettingsPage(stringResource(R.string.licenses_title), onBack, modifier) {
            items(book.components, key = { it.id }) { c ->
                ListItem(
                    modifier = Modifier.clickable { openId = c.id },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text("${c.version} · ${c.license}") },
                )
            }
        }
    } else {
        BackHandler { openId = null }
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(open.name) },
                    navigationIcon = {
                        IconButton(onClick = { openId = null }) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            SelectionContainer {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("${open.version} · ${open.license}", style = MaterialTheme.typography.titleSmall)
                    if (open.authors.isNotEmpty()) Text(open.authors, style = MaterialTheme.typography.bodyMedium)
                    open.repository?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (open.pending) Text(stringResource(R.string.licenses_pending), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        book.text(open),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
