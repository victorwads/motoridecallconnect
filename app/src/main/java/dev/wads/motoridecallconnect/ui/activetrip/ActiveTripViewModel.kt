package dev.wads.motoridecallconnect.ui.activetrip

import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.TranscriptStatus
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import dev.wads.motoridecallconnect.data.repository.TripRepository
import dev.wads.motoridecallconnect.stt.queue.TranscriptionChunkStatus
import dev.wads.motoridecallconnect.stt.queue.TranscriptionQueueSnapshot
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class OperatingMode {
    VOICE_COMMAND,
    VOICE_ACTIVITY_DETECTION,
    CONTINUOUS_TRANSMISSION
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class TranscriptQueueItemStatus {
    PENDING,
    PROCESSING,
    FAILED,

    SUCCESS,
}

data class TranscriptQueueItemUi(
    val id: String,
    val timestampMs: Long,
    val status: TranscriptQueueItemStatus,
    val failureReason: String? = null
)

data class TranscriptEntryUi(
    val id: String,
    val authorId: String?,
    val authorName: String,
    val text: String,
    val timestampMs: Long,
    val status: TranscriptStatus = TranscriptStatus.SUCCESS,
    val errorMessage: String? = null
) {
    val isPartial: Boolean
        get() = status == TranscriptStatus.PROCESSING || status == TranscriptStatus.QUEUED
}

data class ActiveTripUiState(
    val discoveredServices: List<NsdServiceInfo> = emptyList(),
    val isTripActive: Boolean = false,
    val currentTripId: String? = null,
    val tripStartTime: Long? = null,
    val hostUid: String? = null,
    val tripPath: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isLocalTransmitting: Boolean = false,
    val isRemoteTransmitting: Boolean = false,
    val connectedPeer: Device? = null,
    val audioRouteLabel: String = "Bluetooth headset unavailable",
    val isBluetoothAudioActive: Boolean = false,
    val isBluetoothRequired: Boolean = true,
    val transcriptEntries: List<TranscriptEntryUi> = emptyList(),
    val transcriptQueue: List<TranscriptQueueItemUi> = emptyList(),
    val transcriptQueuePendingCount: Int = 0,
    val transcriptQueueProcessingCount: Int = 0,
    val transcriptQueueFailedCount: Int = 0,
    val isModelDownloading: Boolean = false,
    val modelDownloadProgress: Int = 0
)

class ActiveTripViewModel(private val repository: TripRepository) : ViewModel() {
    companion object {
        private const val TAG = "ActiveTripViewModel"
        private const val PARTIAL_TRANSCRIPT_ID = "local_partial"
    }

    private val _uiState = MutableStateFlow(ActiveTripUiState())
    val uiState: StateFlow<ActiveTripUiState> = _uiState.asStateFlow()

    private var currentTripId: String? = null
    private var activeTrip: dev.wads.motoridecallconnect.data.model.Trip? = null
    private var transcriptJob: Job? = null

    fun startTrip(remoteTripId: String? = null, hostId: String? = null, remoteTripPath: String? = null) {
        val parsedTripPath = parseTripPath(remoteTripPath)
        val tripId = remoteTripId ?: parsedTripPath.second ?: UUID.randomUUID().toString()
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        val targetHostUid = hostId ?: parsedTripPath.first ?: myUid
        val tripPath = remoteTripPath?.takeIf { it.isNotBlank() } ?: buildTripPath(targetHostUid, tripId)
        val selectedPeer = _uiState.value.connectedPeer
        val connectedPeerUid = selectedPeer?.id?.takeIf { it.isNotBlank() }
        val participants = listOfNotNull(
            connectedPeerUid?.takeUnless { it == myUid },
            targetHostUid?.takeIf { it.isNotBlank() },
            myUid?.takeIf { it.isNotBlank() }
        ).distinct()
        val peerDeviceLabel = selectedPeer?.name?.takeIf { it.isNotBlank() }
            ?: selectedPeer?.deviceName?.takeIf { it.isNotBlank() }

        currentTripId = tripId
        val now = System.currentTimeMillis()
        val trip = dev.wads.motoridecallconnect.data.model.Trip(
            id = tripId,
            startTime = now,
            peerDevice = peerDeviceLabel,
            participants = participants
        )
        activeTrip = trip
        _uiState.update { it.copy(
            isTripActive = true,
            tripStartTime = now,
            currentTripId = tripId,
            hostUid = targetHostUid,
            tripPath = tripPath,
            transcriptEntries = emptyList()
        ) }

        if (targetHostUid == myUid) {
            viewModelScope.launch {
                repository.insertTrip(trip)
            }
        } else if (!targetHostUid.isNullOrBlank() && !myUid.isNullOrBlank()) {
            viewModelScope.launch {
                repository.upsertParticipatedRideReference(
                    hostUid = targetHostUid,
                    tripId = tripId,
                    tripPath = tripPath,
                    joinedAtMs = now
                )
            }
        }

        // Subscribe to shared transcriptions
        transcriptJob?.cancel()
        val subscribeHostUid = parsedTripPath.first ?: targetHostUid
        val subscribeTripId = parsedTripPath.second ?: tripId
        if (!subscribeHostUid.isNullOrBlank() && subscribeTripId.isNotBlank()) {
            transcriptJob = viewModelScope.launch {
                repository.getTranscriptEntries(subscribeHostUid, subscribeTripId).collect { entries ->
                    _uiState.update { state ->
                        // Merge partial results from local state with final results from Firebase
                        val firebaseEntries = entries.map { entry ->
                            TranscriptEntryUi(
                                id = entry.id,
                                authorId = entry.authorId.takeIf { it.isNotBlank() },
                                authorName = entry.authorName.ifBlank { "Unknown" },
                                text = entry.text,
                                timestampMs = entry.timestamp,
                                status = entry.status,
                                errorMessage = entry.errorMessage
                            )
                        }
                        val localPartial = state.transcriptEntries.lastOrNull()
                            ?.takeIf { it.id == PARTIAL_TRANSCRIPT_ID }

                        state.copy(
                            transcriptEntries = if (localPartial != null) {
                                firebaseEntries + localPartial
                            } else {
                                firebaseEntries
                            }
                        )
                    }
                }
            }
        }
    }

    fun endTrip() {
        val trip = activeTrip
        val currentState = _uiState.value
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        val tripId = currentState.currentTripId
        val hostUid = currentState.hostUid
        val tripPath = currentState.tripPath
        activeTrip = null
        currentTripId = null
        transcriptJob?.cancel()
        transcriptJob = null
        _uiState.update {
            it.copy(
                isTripActive = false,
                tripStartTime = null,
                currentTripId = null,
                hostUid = null,
                tripPath = null,
                isLocalTransmitting = false,
                isRemoteTransmitting = false
            )
        }

        if (trip != null) {
            viewModelScope.launch {
                if (!myUid.isNullOrBlank() && !hostUid.isNullOrBlank() && hostUid != myUid && !tripId.isNullOrBlank()) {
                    repository.upsertParticipatedRideReference(
                        hostUid = hostUid,
                        tripId = tripId,
                        tripPath = tripPath
                    )
                    return@launch
                }

                trip.endTime = System.currentTimeMillis()
                trip.duration = trip.endTime!! - trip.startTime
                repository.insertTrip(trip)
            }
        }
    }

    fun addService(serviceInfo: NsdServiceInfo) {
        _uiState.update {
            val currentServices = it.discoveredServices.toMutableList()
            if (currentServices.none { s -> s.serviceName == serviceInfo.serviceName }) {
                currentServices.add(serviceInfo)
            }
            it.copy(discoveredServices = currentServices)
        }
    }

    fun removeService(serviceInfo: NsdServiceInfo) {
        _uiState.update {
            val currentServices = it.discoveredServices.toMutableList()
            currentServices.removeAll { s -> s.serviceName == serviceInfo.serviceName }
            it.copy(discoveredServices = currentServices)
        }
    }

    fun onConnectionStatusChanged(status: ConnectionStatus, peer: Device?) {
        _uiState.update {
            it.copy(
                connectionStatus = status,
                connectedPeer = peer,
                isRemoteTransmitting = if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.ERROR) {
                    false
                } else {
                    it.isRemoteTransmitting
                }
            )
        }
    }

    fun onTransmissionStateChanged(isLocalTransmitting: Boolean, isRemoteTransmitting: Boolean) {
        _uiState.update {
            it.copy(
                isLocalTransmitting = isLocalTransmitting,
                isRemoteTransmitting = isRemoteTransmitting
            )
        }
    }

    fun onAudioRouteChanged(routeLabel: String, isBluetoothActive: Boolean, isBluetoothRequired: Boolean) {
        _uiState.update {
            it.copy(
                audioRouteLabel = routeLabel,
                isBluetoothAudioActive = isBluetoothActive,
                isBluetoothRequired = isBluetoothRequired,
                isLocalTransmitting = if (isBluetoothRequired && !isBluetoothActive) {
                    false
                } else {
                    it.isLocalTransmitting
                }
            )
        }
    }

    fun onTripStatusChanged(
        isActive: Boolean,
        tripId: String? = null,
        hostUid: String? = null,
        tripPath: String? = null
    ) {
        val currentState = _uiState.value
        val parsedTripPath = parseTripPath(tripPath)
        if (isActive) {
            val resolvedTripId = tripId ?: parsedTripPath.second ?: currentState.currentTripId ?: UUID.randomUUID().toString()
            val resolvedHostUid = hostUid ?: parsedTripPath.first ?: currentState.hostUid
            val resolvedPath = tripPath ?: buildTripPath(resolvedHostUid, resolvedTripId)
            if (
                currentState.isTripActive &&
                    currentState.currentTripId == resolvedTripId &&
                    currentState.hostUid == resolvedHostUid &&
                    currentState.tripPath == resolvedPath
            ) {
                return
            }
            startTrip(remoteTripId = resolvedTripId, hostId = resolvedHostUid, remoteTripPath = resolvedPath)
            Log.i(
                TAG,
                "Applied remote trip start. tripId=$resolvedTripId, hostUid=$resolvedHostUid, tripPath=$resolvedPath"
            )
            return
        }

        if (!currentState.isTripActive) {
            return
        }
        val incomingTripId = tripId ?: parsedTripPath.second
        val incomingHostUid = hostUid ?: parsedTripPath.first
        if (!incomingTripId.isNullOrBlank() && incomingTripId != currentState.currentTripId) {
            return
        }
        if (!incomingHostUid.isNullOrBlank() && incomingHostUid != currentState.hostUid) {
            return
        }
        endTrip()
        Log.i(TAG, "Applied remote trip stop.")
    }

    fun onTranscriptionQueueUpdated(snapshot: TranscriptionQueueSnapshot) {
        _uiState.update { state ->
            val visibleItems = if (state.currentTripId.isNullOrBlank()) {
                snapshot.items
            } else {
                snapshot.items.filter { queued -> queued.tripId == state.currentTripId }
            }
            val pendingCount = visibleItems.count { it.status == TranscriptionChunkStatus.PENDING }
            val processingCount = visibleItems.count { it.status == TranscriptionChunkStatus.PROCESSING }
            val failedCount = visibleItems.count { it.status == TranscriptionChunkStatus.FAILED }
            state.copy(
                transcriptQueue = visibleItems.map { queued ->
                    TranscriptQueueItemUi(
                        id = queued.id,
                        timestampMs = queued.createdAtMs,
                        status = mapQueueStatus(queued.status),
                        failureReason = queued.failureReason
                    )
                },
                transcriptQueuePendingCount = pendingCount,
                transcriptQueueProcessingCount = processingCount,
                transcriptQueueFailedCount = failedCount
            )
        }
    }

    fun updateTranscript(
        newTranscript: String,
        isFinal: Boolean,
        targetTripId: String? = null,
        transcriptTimestampMs: Long? = null
    ) {
        val state = _uiState.value
        val resolvedTripId = targetTripId ?: state.currentTripId
        val resolvedTimestamp = transcriptTimestampMs ?: System.currentTimeMillis()
        val shouldRenderOnCurrentTrip = resolvedTripId != null && resolvedTripId == state.currentTripId
        Log.d(
            TAG,
            "updateTranscript(isFinal=$isFinal, tripActive=${state.isTripActive}, " +
                "tripId=${state.currentTripId}, targetTripId=$resolvedTripId, textLen=${newTranscript.length})"
        )

        if (isFinal) {
            if (shouldRenderOnCurrentTrip) {
                // Show immediately in UI; Firestore listener will later reconcile authoritative lines.
                _uiState.update { s ->
                    val updated = s.transcriptEntries.toMutableList()
                    if (updated.isNotEmpty() && updated.last().isPartial) {
                        updated.removeAt(updated.lastIndex)
                    }
                    val authorId = FirebaseAuth.getInstance().currentUser?.uid
                    val authorName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Você"
                    updated.add(
                        TranscriptEntryUi(
                            id = "local_final_${resolvedTimestamp}_${newTranscript.hashCode()}",
                            authorId = authorId,
                            authorName = authorName,
                            text = newTranscript,
                            timestampMs = resolvedTimestamp,
                            status = TranscriptStatus.SUCCESS
                        )
                    )
                    s.copy(transcriptEntries = updated)
                }
            }
        } else {
            if (!shouldRenderOnCurrentTrip) {
                return
            }
            // Partial results stay local-only for smoothness
            _uiState.update { s ->
                val currentTranscript = s.transcriptEntries.toMutableList()
                val authorId = FirebaseAuth.getInstance().currentUser?.uid
                val authorName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Você"
                val partialEntry = TranscriptEntryUi(
                    id = PARTIAL_TRANSCRIPT_ID,
                    authorId = authorId,
                    authorName = authorName,
                    text = newTranscript,
                    timestampMs = resolvedTimestamp,
                    status = TranscriptStatus.PROCESSING
                )
                if (currentTranscript.isEmpty() || !currentTranscript.last().isPartial) {
                    currentTranscript.add(partialEntry)
                } else {
                    currentTranscript[currentTranscript.lastIndex] = partialEntry
                }
                s.copy(transcriptEntries = currentTranscript)
            }
        }
    }

    fun updateModelDownloadStatus(isDownloading: Boolean, progress: Int) {
        _uiState.update { it.copy(isModelDownloading = isDownloading, modelDownloadProgress = progress) }
    }

    private fun mapQueueStatus(status: TranscriptionChunkStatus): TranscriptQueueItemStatus {
        return when (status) {
            TranscriptionChunkStatus.PENDING -> TranscriptQueueItemStatus.PENDING
            TranscriptionChunkStatus.PROCESSING -> TranscriptQueueItemStatus.PROCESSING
            TranscriptionChunkStatus.FAILED -> TranscriptQueueItemStatus.FAILED
            TranscriptionChunkStatus.SUCCESS -> TranscriptQueueItemStatus.SUCCESS
        }
    }

    private fun buildTripPath(hostUid: String?, tripId: String?): String? {
        val normalizedHostUid = hostUid?.trim().orEmpty()
        val normalizedTripId = tripId?.trim().orEmpty()
        if (normalizedHostUid.isBlank() || normalizedTripId.isBlank()) {
            return null
        }
        return "${FirestorePaths.ACCOUNTS}/$normalizedHostUid/${FirestorePaths.RIDES}/$normalizedTripId"
    }

    private fun parseTripPath(path: String?): Pair<String?, String?> {
        if (path.isNullOrBlank()) {
            return null to null
        }
        val segments = path.trim().split("/")
        if (segments.size < 4) {
            return null to null
        }
        if (segments[0] != FirestorePaths.ACCOUNTS || segments[2] != FirestorePaths.RIDES) {
            return null to null
        }
        val hostUid = segments[1].takeIf { it.isNotBlank() }
        val tripId = segments[3].takeIf { it.isNotBlank() }
        return hostUid to tripId
    }
}
