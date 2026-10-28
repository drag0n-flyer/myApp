package org.example.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/** Bottom bar plus one destination per tab. Later tasks pass real screens as [tabContent]. */
@Composable
fun MainTabs(
    modifier: Modifier = Modifier,
    /** The tabs shown; the live build hides Calls until E6. */
    tabs: List<MainTab> = MainTab.entries,
    tabContent: @Composable (MainTab) -> Unit = { TabPlaceholder(it) },
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val current = MainTab.fromRoute(entry?.destination?.route) ?: MainTab.start

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                for (tab in tabs) {
                    val label = stringResource(tab.label)
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = {
                            if (tab != current) {
                                navController.navigate(tab.route) {
                                    popUpTo(MainTab.start.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = MainTab.start.route, modifier = Modifier.padding(padding)) {
            for (tab in tabs) composable(tab.route) { tabContent(tab) }
        }
    }
}

/** Stands in until the tab's own screen exists. */
@Composable
fun TabPlaceholder(tab: MainTab) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(tab.label)) }
}
