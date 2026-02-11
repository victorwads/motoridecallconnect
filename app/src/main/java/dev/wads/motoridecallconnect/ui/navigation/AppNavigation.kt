package dev.wads.motoridecallconnect.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.wads.motoridecallconnect.ui.activetrip.ActiveTripScreen
import dev.wads.motoridecallconnect.ui.activetrip.ActiveTripViewModel
import dev.wads.motoridecallconnect.ui.history.TripHistoryScreen
import dev.wads.motoridecallconnect.ui.history.TripHistoryViewModel

@Composable
fun AppNavigation(
    activeTripViewModel: ActiveTripViewModel,
    tripHistoryViewModel: TripHistoryViewModel,
    onStartTripClick: () -> Unit,
    onEndTripClick: () -> Unit,
    onStartDiscoveryClick: () -> Unit
) {
    val navController = rememberNavController()
    val items = listOf(
        Screen.ActiveTrip,
        Screen.TripHistory,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.ActiveTrip.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.ActiveTrip.route) {
                val uiState by activeTripViewModel.uiState.collectAsState()
                ActiveTripScreen(
                    uiState = uiState,
                    onStartTripClick = onStartTripClick,
                    onEndTripClick = onEndTripClick,
                    onStartDiscoveryClick = onStartDiscoveryClick,
                    onModeChange = { activeTripViewModel.onModeChange(it) },
                    onStartCommandChange = { activeTripViewModel.onStartCommandChange(it) },
                    onStopCommandChange = { activeTripViewModel.onStopCommandChange(it) },
                    onRecordingToggle = { activeTripViewModel.onRecordingToggle(it) }
                )
            }
            composable(Screen.TripHistory.route) {
                val uiState by tripHistoryViewModel.uiState.collectAsState()
                TripHistoryScreen(uiState = uiState, onTripClick = {
                    navController.navigate(Screen.TripDetails.createRoute(it))
                })
            }
            composable(Screen.TripDetails.route) {
                // TODO: Implement Trip Details Screen
            }
        }
    }
}