package dev.wads.motoridecallconnect.ui.activetrip

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.repository.UserRepository
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonSize
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.ChronometerView
import dev.wads.motoridecallconnect.ui.components.StatusCard

@Composable
fun ActiveTripScreen(
    uiState: ActiveTripUiState,
    onStartTripClick: () -> Unit,
    onEndTripClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Mic, // Placeholder for Radio
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.app_short_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (uiState.connectionStatus == ConnectionStatus.CONNECTED && uiState.isTripActive) {
                Text(
                    text = stringResource(R.string.live_indicator),
                    color = Color(0xFF22C55E), // SuccessGreen
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        if (uiState.isModelDownloading) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.downloading_model),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { uiState.modelDownloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${uiState.modelDownloadProgress}%", 
                        style = MaterialTheme.typography.bodySmall, 
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }

        // --- Trip Controls ---
        StatusCard(title = stringResource(R.string.trip_header), icon = Icons.Default.PlayArrow) {
            if (uiState.isTripActive) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    uiState.tripStartTime?.let {
                        ChronometerView(startTimeMillis = it)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    BigButton(
                        text = stringResource(R.string.end_trip_action),
                        onClick = onEndTripClick,
                        variant = ButtonVariant.Destructive,
                        icon = Icons.Default.Stop,
                        size = ButtonSize.Xl,
                        fullWidth = true
                    )
                }
            } else {
                BigButton(
                    text = stringResource(R.string.start_trip_action),
                    onClick = onStartTripClick,
                    variant = ButtonVariant.Primary,
                    icon = Icons.Default.PlayArrow,
                    size = ButtonSize.Xl,
                    fullWidth = true
                )
            }
        }

        TransmissionStatusCard(uiState = uiState)
        BluetoothAudioRouteCard(uiState = uiState)

        if (!uiState.tripPath.isNullOrBlank()) {
            StatusCard(title = "Trip Sync Path", icon = Icons.Default.Call) {
                Text(
                    text = uiState.tripPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- Transcription List ---
        if (uiState.isTripActive || uiState.transcriptEntries.isNotEmpty() || uiState.transcriptQueue.isNotEmpty()) {
            StatusCard(title = stringResource(R.string.full_transcript_title), icon = Icons.Default.Call) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val queueStatusText = when {
                        uiState.transcriptQueueProcessingCount > 0 ->
                            stringResource(
                                R.string.transcription_status_processing,
                                uiState.transcriptQueueProcessingCount,
                                uiState.transcriptQueuePendingCount
                            )
                        uiState.transcriptQueuePendingCount > 0 ->
                            stringResource(R.string.transcription_status_pending, uiState.transcriptQueuePendingCount)
                        uiState.transcriptQueueFailedCount > 0 ->
                            stringResource(R.string.transcription_status_failed, uiState.transcriptQueueFailedCount)
                        else -> stringResource(R.string.transcription_status_idle)
                    }

                    Text(
                        text = queueStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (uiState.transcriptQueue.isNotEmpty()) {
                        uiState.transcriptQueue.takeLast(10).forEach { queueItem ->
                            TranscriptionQueueLine(item = queueItem)
                        }
                    }

                    if (uiState.transcriptEntries.isEmpty() && uiState.transcriptQueue.isEmpty()) {
                        Text(
                            text = stringResource(R.string.transcription_waiting_speech),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    uiState.transcriptEntries.takeLast(10).forEach { entry ->
                        TranscriptEntryLine(item = entry)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun TranscriptEntryLine(item: TranscriptEntryUi) {
    val localUser = FirebaseAuth.getInstance().currentUser
    val localUid = localUser?.uid
    val photoOverride = if (!localUid.isNullOrBlank() && localUid == item.authorId) {
        localUser.photoUrl?.toString()
    } else {
        null
    }
    val timeLabel = remember(item.timestampMs) {
        DateFormat.format("HH:mm:ss", item.timestampMs).toString()
    }
    val lineColor = if (item.isPartial) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        ParticipantAvatar(
            userId = item.authorId,
            name = item.authorName,
            photoUrlOverride = photoOverride,
            size = 28.dp
        )
        Text(
            text = item.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = lineColor
        )
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TranscriptionQueueLine(item: TranscriptQueueItemUi) {
    val timeLabel = remember(item.timestampMs) {
        DateFormat.format("HH:mm:ss", item.timestampMs).toString()
    }
    val statusLabel = when (item.status) {
        TranscriptQueueItemStatus.PENDING -> stringResource(R.string.transcription_item_pending)
        TranscriptQueueItemStatus.PROCESSING -> stringResource(R.string.transcription_item_processing)
        TranscriptQueueItemStatus.FAILED -> stringResource(R.string.transcription_item_failed)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (item.status) {
                TranscriptQueueItemStatus.PROCESSING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
                TranscriptQueueItemStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
                TranscriptQueueItemStatus.PENDING -> Unit
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = when (item.status) {
                    TranscriptQueueItemStatus.FAILED -> MaterialTheme.colorScheme.error
                    TranscriptQueueItemStatus.PROCESSING -> MaterialTheme.colorScheme.primary
                    TranscriptQueueItemStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }

    if (item.status == TranscriptQueueItemStatus.FAILED && !item.failureReason.isNullOrBlank()) {
        Text(
            text = item.failureReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1
        )
    }
}

@Composable
private fun BluetoothAudioRouteCard(uiState: ActiveTripUiState) {
    val statusColor = if (uiState.isBluetoothAudioActive) {
        Color(0xFF22C55E)
    } else {
        MaterialTheme.colorScheme.error
    }
    val statusText = if (uiState.isBluetoothAudioActive) {
        stringResource(R.string.bluetooth_audio_active)
    } else {
        stringResource(R.string.bluetooth_audio_required)
    }

    StatusCard(title = stringResource(R.string.audio_route_title), icon = Icons.Default.Headphones) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.audio_route_current_format, uiState.audioRouteLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TransmissionStatusCard(uiState: ActiveTripUiState) {
    val localUser = FirebaseAuth.getInstance().currentUser
    val peerName = uiState.connectedPeer?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.trip_peer_default_name)

    val isConnected = uiState.connectionStatus == ConnectionStatus.CONNECTED
    val localStatusText = if (uiState.isLocalTransmitting) {
        stringResource(R.string.transmission_local_active)
    } else {
        stringResource(R.string.transmission_local_idle)
    }
    val remoteStatusText = when {
        !isConnected -> stringResource(R.string.transmission_waiting_connection)
        uiState.isRemoteTransmitting -> stringResource(R.string.transmission_remote_active)
        else -> stringResource(R.string.transmission_remote_idle)
    }

    StatusCard(title = stringResource(R.string.transmission_status_title), icon = Icons.Default.Mic) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ParticipantTransmissionTile(
                modifier = Modifier.weight(1f),
                userId = localUser?.uid,
                photoUrlOverride = localUser?.photoUrl?.toString(),
                name = stringResource(R.string.transmission_you_label),
                statusText = localStatusText,
                isActive = uiState.isLocalTransmitting
            )
            ParticipantTransmissionTile(
                modifier = Modifier.weight(1f),
                userId = uiState.connectedPeer?.id,
                name = peerName,
                statusText = remoteStatusText,
                isActive = isConnected && uiState.isRemoteTransmitting
            )
        }
    }
}

@Composable
private fun ParticipantTransmissionTile(
    modifier: Modifier = Modifier,
    userId: String?,
    photoUrlOverride: String? = null,
    name: String,
    statusText: String,
    isActive: Boolean
) {
    val borderColor = if (isActive) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline
    val fillColor = if (isActive) Color(0xFF22C55E).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val circleSize: Dp = 54.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ParticipantAvatar(
            userId = userId,
            name = name,
            photoUrlOverride = photoUrlOverride,
            size = circleSize,
            borderColor = borderColor,
            fillColor = fillColor,
            borderWidth = 3.dp
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ParticipantAvatar(
    userId: String?,
    name: String,
    photoUrlOverride: String? = null,
    size: Dp,
    borderColor: Color = Color.Transparent,
    fillColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    borderWidth: Dp = 0.dp
) {
    val placeholderPainter = rememberVectorPainter(Icons.Default.Person)
    var resolvedPhotoUrl by remember(userId, photoUrlOverride) { mutableStateOf(photoUrlOverride) }

    LaunchedEffect(userId, photoUrlOverride) {
        if (!photoUrlOverride.isNullOrBlank()) {
            resolvedPhotoUrl = photoUrlOverride
            return@LaunchedEffect
        }
        resolvedPhotoUrl = if (userId.isNullOrBlank()) {
            null
        } else {
            runCatching { UserRepository.getInstance().getUserProfile(userId)?.photoUrl }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .border(width = borderWidth, color = borderColor, shape = CircleShape)
            .background(fillColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!resolvedPhotoUrl.isNullOrBlank()) {
            AsyncImage(
                model = resolvedPhotoUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(size - if (borderWidth > 0.dp) (borderWidth * 2f + 2.dp) else 4.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                placeholder = placeholderPainter,
                error = placeholderPainter
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ActiveTripScreenPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
            ActiveTripScreen(
                uiState = ActiveTripUiState(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    discoveredServices = emptyList(),
                    transcriptEntries = listOf(
                        TranscriptEntryUi(
                            id = "preview_1",
                            authorId = "me",
                            authorName = "Você",
                            text = "Olá, tudo bem?",
                            timestampMs = System.currentTimeMillis(),
                            isPartial = false
                        )
                    )
                ),
                onStartTripClick = {},
                onEndTripClick = {}
            )
        }
    }
}
