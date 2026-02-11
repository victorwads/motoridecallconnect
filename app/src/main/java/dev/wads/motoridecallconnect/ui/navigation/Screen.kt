package dev.wads.motoridecallconnect.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import dev.wads.motoridecallconnect.R

sealed class Screen(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    object ActiveTrip : Screen("active_trip", R.string.nav_active_trip, Icons.Default.Home)
    object Pairing : Screen("pairing", R.string.nav_pairing, Icons.Default.Link)
    object Friends : Screen("friends", R.string.nav_friends, Icons.Default.Person)
    object TripHistory : Screen("trip_history", R.string.nav_history, Icons.Default.History)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
    object TripDetails : Screen("trip_details/{tripId}", R.string.nav_details, Icons.Default.History) {
        fun createRoute(tripId: String) = "trip_details/$tripId"
    }
    object FriendProfile : Screen("friend_profile/{friendId}", R.string.nav_profile, Icons.Default.Person) {
        fun createRoute(friendId: String) = "friend_profile/$friendId"
    }
}