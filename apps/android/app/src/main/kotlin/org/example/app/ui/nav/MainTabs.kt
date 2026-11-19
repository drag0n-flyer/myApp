package org.example.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.VerticalDivider
import org.example.app.core.layout.PaneLayout
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
    /** Wide windows: a navigation rail at the start replaces the bottom bar (the Mac sidebar, spec 6.9). */
    rail: Boolean = false,
    /** Wide windows: the pane beside the tab's list (the open chat); null shows the tabs alone. */
    detail: (@Composable () -> Unit)? = null,
    tabContent: @Composable (MainTab) -> Unit = { TabPlaceholder(it) },
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val current = MainTab.fromRoute(entry?.destination?.route) ?: MainTab.start

    val select = { tab: MainTab ->
        if (tab != current) {
            navController.navigate(tab.route) {
                popUpTo(MainTab.start.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val host: @Composable (Modifier) -> Unit = { hostModifier ->
        NavHost(navController, startDestination = MainTab.start.route, modifier = hostModifier) {
            for (tab in tabs) composable(tab.route) { tabContent(tab) }
        }
    }

    if (rail) {
        Row(modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.weight(1f))
                for (tab in tabs) {
                    NavigationRailItem(
                        selected = tab == current,
                        onClick = { select(tab) },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            VerticalDivider()
            host(Modifier.width(PaneLayout.LIST_PANE_DP.dp).fillMaxHeight())
            if (detail != null) {
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
            }
        }
        return
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                for (tab in tabs) {
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { select(tab) },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding -> host(Modifier.padding(padding)) }
}

/** Stands in until the tab's own screen exists. */
@Composable
fun TabPlaceholder(tab: MainTab) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(tab.label)) }
}
