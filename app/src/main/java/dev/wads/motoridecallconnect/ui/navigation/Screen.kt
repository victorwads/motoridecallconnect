package dev.wads.motoridecallconnect.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object ActiveTrip : Screen("active_trip", "Viagem", Icons.Default.Home)
    object TripHistory : Screen("trip_history", "Histórico", Icons.Default.History)
    object Settings : Screen("settings", "Configurações", Icons.Default.Settings)
    object TripDetails : Screen("trip_details/{tripId}", "Detalhes", Icons.Default.History) {
        fun createRoute(tripId: Long) = "trip_details/$tripId"
    }
}