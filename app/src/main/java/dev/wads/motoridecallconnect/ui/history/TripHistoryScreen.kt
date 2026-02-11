package dev.wads.motoridecallconnect.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.ui.components.EmptyState
import dev.wads.motoridecallconnect.ui.components.UserProfileView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripHistoryScreen(
    uiState: TripHistoryUiState,
    onTripClick: (String) -> Unit
) {
    // Local filter state
    var periodFilter by remember { mutableStateOf("all") }
    var transcriptFilter by remember { mutableStateOf("all") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Filters
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.period_label), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                FilterButton(stringResource(R.string.filter_all), periodFilter == "all") { periodFilter = "all" }
                FilterButton(stringResource(R.string.filter_today), periodFilter == "today") { periodFilter = "today" }
                FilterButton(stringResource(R.string.filter_7days), periodFilter == "7days") { periodFilter = "7days" }
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterButton(stringResource(R.string.filter_all), transcriptFilter == "all") { transcriptFilter = "all" }
                FilterButton(stringResource(R.string.filter_with_transcript), transcriptFilter == "with") { transcriptFilter = "with" }
                FilterButton(stringResource(R.string.filter_without_transcript), transcriptFilter == "without") { transcriptFilter = "without" }
            }
        }

        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.trips.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Schedule,
                title = stringResource(R.string.no_trips_title),
                description = stringResource(R.string.no_trips_desc)
            )
        } else {
            // Apply filters (mock implementation using simplistic conditions as Date parsing is needed)
            // Real implementation would parse trip.startTime
            val filteredTrips = uiState.trips // TODO: Implement real filtering logic
            
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredTrips) { trip ->
                    TripItem(trip = trip, onClick = { onTripClick(trip.id) })
                }
            }
        }
    }
}

@Composable
fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TripItem(trip: Trip, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        text = formatDate(trip.startTime),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formatDuration(trip.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Com ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UserProfileView(
                        userId = trip.participants.firstOrNull(),
                        showId = false,
                        modifier = Modifier.height(24.dp)
                    )
                    Text(
                        text = " · Modo Auto",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Icons if has transcription
                Icon(
                    imageVector = Icons.Default.Description, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(durationMillis: Long?): String {
    if (durationMillis == null) return "Em andamento"
    val seconds = durationMillis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    if (hours > 0) {
         return String.format("%dh %02dm", hours, minutes % 60)
    }
    return String.format("%02dm %02ds", minutes, seconds % 60)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TripHistoryScreenPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
            TripHistoryScreen(
                uiState = TripHistoryUiState(
                    trips = listOf(
                        dev.wads.motoridecallconnect.data.model.Trip("1", System.currentTimeMillis(), System.currentTimeMillis() + 3600000, 3600000, "Galaxy S23"),
                        dev.wads.motoridecallconnect.data.model.Trip("2", System.currentTimeMillis() - 86400000, System.currentTimeMillis() - 82800000, 3600000, "iPhone 15")
                    )
                ),
                onTripClick = {}
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Empty History")
@Composable
private fun TripHistoryEmptyPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
            TripHistoryScreen(
                uiState = TripHistoryUiState(trips = emptyList()),
                onTripClick = {}
            )
        }
    }
}
