package dev.wads.motoridecallconnect.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.wads.motoridecallconnect.data.local.UserPreferences
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.ui.activetrip.ActiveTripScreen
import dev.wads.motoridecallconnect.ui.activetrip.ActiveTripViewModel
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import dev.wads.motoridecallconnect.ui.pairing.PairingViewModel
import dev.wads.motoridecallconnect.ui.components.AudioTestModal
import dev.wads.motoridecallconnect.ui.login.LoginScreen
import dev.wads.motoridecallconnect.ui.login.LoginViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import dev.wads.motoridecallconnect.ui.history.TripDetailScreen
import dev.wads.motoridecallconnect.ui.history.TripHistoryScreen
import dev.wads.motoridecallconnect.ui.history.TripHistoryViewModel
import dev.wads.motoridecallconnect.ui.onboarding.OnboardingScreen
import dev.wads.motoridecallconnect.ui.pairing.PairingScreen
import dev.wads.motoridecallconnect.ui.social.SocialScreen
import dev.wads.motoridecallconnect.ui.settings.SettingsScreen

@Composable
fun AppNavigation(
    activeTripViewModel: ActiveTripViewModel,
    tripHistoryViewModel: TripHistoryViewModel,
    loginViewModel: LoginViewModel,
    socialViewModel: dev.wads.motoridecallconnect.ui.social.SocialViewModel,
    pairingViewModel: PairingViewModel,
    onStartTripClick: () -> Unit,
    onEndTripClick: () -> Unit,
    onConnectToDevice: (Device) -> Unit,
    onDisconnectClick: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userPreferences = remember { UserPreferences(context) }
    
    val isLocalMode by userPreferences.localMode.collectAsState(initial = null)
    var isUserLoggedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }

    if (!isUserLoggedIn && isLocalMode == false) {
        LoginScreen(
            viewModel = loginViewModel,
            onLoginSuccess = { isFirebase ->
                if (isFirebase) {
                    isUserLoggedIn = true
                } else {
                    coroutineScope.launch {
                        userPreferences.setLocalMode(true)
                    }
                }
            }
        )
        return
    }

    if (isLocalMode == null) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val onboardingCompleted by userPreferences.onboardingCompleted.collectAsState(initial = null)
    var showAudioTest by remember { mutableStateOf(false) }

    if (onboardingCompleted == false) {
        OnboardingScreen(onComplete = { /* State update will trigger recomposition */ })
        return
    }

    if (onboardingCompleted == null) {
        // Loading...
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    if (showAudioTest) {
        AudioTestModal(onDismissRequest = { showAudioTest = false })
    }

    val items = listOf(
        Screen.ActiveTrip,
        Screen.Pairing,
        Screen.Friends,
        Screen.TripHistory,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(stringResource(screen.label)) },
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
                    onStartDiscoveryClick = {
                        navController.navigate("pairing")
                    },
                    onModeChange = { activeTripViewModel.onModeChange(it) },
                    onStartCommandChange = { 
                        activeTripViewModel.onModeChange(OperatingMode.VOICE_COMMAND) // Implicit update?
                        // TODO: Add specific update method to VM 
                    },
                    onStopCommandChange = { 
                        // TODO: Add specific update method to VM 
                    },
                    onRecordingToggle = { /* TODO VM update */ },
                    onConnectToService = { serviceInfo ->
                        val device = Device(
                            id = serviceInfo.serviceName,
                            name = serviceInfo.serviceName,
                            deviceName = "Android Device",
                            ip = serviceInfo.host?.hostAddress,
                            port = serviceInfo.port
                        )
                        onConnectToDevice(device)
                    },
                    onDisconnectClick = onDisconnectClick
                )
            }
            composable(Screen.TripHistory.route) {
                val uiState by tripHistoryViewModel.uiState.collectAsState()
                TripHistoryScreen(
                    uiState = uiState,
                    onTripClick = { tripId ->
                        navController.navigate(Screen.TripDetails.createRoute(tripId))
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onTestAudio = { showAudioTest = true },
                    onLogout = {
                        FirebaseAuth.getInstance().signOut()
                        coroutineScope.launch {
                            userPreferences.setLocalMode(false)
                            isUserLoggedIn = false
                        }
                    }
                )
            }
            composable(
                route = Screen.TripDetails.route,
                arguments = listOf(navArgument("tripId") { type = NavType.StringType })
            ) { backStackEntry ->
                val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
                TripDetailScreen(
                    tripId = tripId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Pairing.route) {
                PairingScreen(
                    viewModel = pairingViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onConnectToDevice = { device ->
                        onConnectToDevice(device)
                        navController.navigate(Screen.ActiveTrip.route)
                    }
                )
            }
            composable(Screen.Friends.route) {
                SocialScreen(viewModel = socialViewModel)
            }
        }
    }
}
