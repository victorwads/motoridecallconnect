package dev.wads.motoridecallconnect.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.FullTranscriptCard
import dev.wads.motoridecallconnect.ui.components.StatusCard
import dev.wads.motoridecallconnect.ui.components.TranscriptFeedItem
import dev.wads.motoridecallconnect.ui.components.UserProfileView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripDetailScreen(
    tripId: String,
    viewModel: TripDetailViewModel? = null,
    onNavigateBack: () -> Unit,
    onPlayAudio: ((String) -> Unit)? = null,
    onStopAudio: (() -> Unit)? = null,
    onRetryTranscription: ((String) -> Unit)? = null,
    currentlyPlayingId: String? = null
) {
    LaunchedEffect(tripId) {
        viewModel?.loadTrip(tripId)
    }

    val uiState by viewModel?.uiState?.collectAsState() ?: mutableStateOf(TripDetailUiState())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onNavigateBack)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.history_title), color = MaterialTheme.colorScheme.primary)
        }

        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else {
            val trip = uiState.trip
            if (trip != null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusCard(title = stringResource(R.string.summary_title), icon = Icons.Default.Schedule) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                DetailItem(stringResource(R.string.date_label), formatDate(trip.startTime))
                                DetailItem(stringResource(R.string.duration_label), formatDuration(trip.duration))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = stringResource(R.string.with_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    UserProfileView(
                                        userId = trip.participants.firstOrNull(),
                                        showId = false,
                                        avatarSize = 24
                                    )
                                }
                                DetailItem(stringResource(R.string.mode_label), stringResource(R.string.mode_automatic))
                            }
                        }
                    }

                    FullTranscriptCard(
                        transcriptItems = uiState.transcripts.map { entry ->
                            TranscriptFeedItem(
                                id = entry.id,
                                authorId = entry.authorId,
                                authorName = entry.authorName,
                                text = entry.text,
                                timestampMs = entry.timestamp,
                                status = entry.status,
                                errorMessage = entry.errorMessage,
                                audioFileName = entry.audioFileName
                            )
                        },
                        emptyText = stringResource(R.string.no_transcript_records),
                        maxTranscriptItems = 500,
                        onPlayAudio = onPlayAudio,
                        onStopAudio = onStopAudio,
                        onRetry = onRetryTranscription,
                        currentlyPlayingId = currentlyPlayingId
                    )

                    BigButton(
                        text = stringResource(R.string.delete_trip),
                        onClick = { viewModel?.deleteTrip(onDeleted = onNavigateBack) },
                        variant = ButtonVariant.Destructive,
                        icon = Icons.Default.Delete,
                        fullWidth = true,
                        disabled = uiState.isDeleting
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.trip_not_found))
                }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
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
private fun TripDetailScreenPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
            TripDetailScreen(tripId = "1", onNavigateBack = {})
        }
    }
}
