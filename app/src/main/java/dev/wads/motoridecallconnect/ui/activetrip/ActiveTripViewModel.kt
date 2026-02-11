package dev.wads.motoridecallconnect.ui.activetrip

import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import dev.wads.motoridecallconnect.data.repository.TripRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val transcript: List<String> = emptyList(),
    val isModelDownloading: Boolean = false,
    val modelDownloadProgress: Int = 0
)

class ActiveTripViewModel(private val repository: TripRepository) : ViewModel() {
    companion object {
        private const val TAG = "ActiveTripViewModel"
        private val transcriptTimeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
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
            transcript = emptyList()
        ) }

        if (targetHostUid == myUid) {
            viewModelScope.launch {
                repository.insertTrip(trip)
            }
        }

        // Subscribe to shared transcriptions
        transcriptJob?.cancel()
        val subscribeHostUid = parsedTripPath.first ?: targetHostUid
        val subscribeTripId = parsedTripPath.second ?: tripId
        if (!subscribeHostUid.isNullOrBlank() && subscribeTripId.isNotBlank()) {
            transcriptJob = viewModelScope.launch {
                repository.getTranscripts(subscribeHostUid, subscribeTripId).collect { lines ->
                    _uiState.update { state ->
                        // Merge partial results from local state with final results from Firebase
                        val firebaseList = lines.map { line ->
                            "${formatTranscriptTime(line.timestamp)} - ${line.authorName}: ${line.text}"
                        }
                        val localPartial = state.transcript.lastOrNull()?.takeIf { it.startsWith("Parcial:") }

                        state.copy(transcript = if (localPartial != null) firebaseList + localPartial else firebaseList)
                    }
                }
            }
        }
    }

    fun endTrip() {
        val trip = activeTrip
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

    fun onAudioRouteChanged(routeLabel: String, isBluetoothActive: Boolean) {
        _uiState.update {
            it.copy(
                audioRouteLabel = routeLabel,
                isBluetoothAudioActive = isBluetoothActive,
                isLocalTransmitting = if (isBluetoothActive) it.isLocalTransmitting else false
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

    fun updateTranscript(newTranscript: String, isFinal: Boolean) {
        val state = _uiState.value
        val targetUid = state.hostUid
            ?: parseTripPath(state.tripPath).first
            ?: FirebaseAuth.getInstance().currentUser?.uid
        Log.d(
            TAG,
            "updateTranscript(isFinal=$isFinal, tripActive=${state.isTripActive}, " +
                "tripId=${state.currentTripId}, textLen=${newTranscript.length})"
        )

        if (isFinal) {
            val tripId = state.currentTripId ?: return

            // Show immediately in UI; Firestore listener will later reconcile authoritative lines.
            _uiState.update { s ->
                val updated = s.transcript.toMutableList()
                if (updated.isNotEmpty() && updated.last().startsWith("Parcial:")) {
                    updated.removeAt(updated.lastIndex)
                }
                val now = System.currentTimeMillis()
                updated.add("${formatTranscriptTime(now)} - Você: $newTranscript")
                s.copy(transcript = updated)
            }

            if (true) { // TODO: Re-wire this to the settings view model
                viewModelScope.launch {
                    try {
                        repository.insertTranscriptLine(
                            TranscriptLine(
                                tripId = tripId,
                                text = newTranscript,
                                isPartial = false
                            ),
                            targetHostUid = targetUid
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to persist transcript line for tripId=$tripId", t)
                    }
                }
            }
        } else {
            // Partial results stay local-only for smoothness
            _uiState.update { s ->
                val currentTranscript = s.transcript.toMutableList()
                if (currentTranscript.isEmpty() || !currentTranscript.last().startsWith("Parcial:")) {
                    currentTranscript.add("Parcial: $newTranscript")
                } else {
                    currentTranscript[currentTranscript.lastIndex] = "Parcial: $newTranscript"
                }
                s.copy(transcript = currentTranscript)
            }
        }
    }

    fun updateModelDownloadStatus(isDownloading: Boolean, progress: Int) {
        _uiState.update { it.copy(isModelDownloading = isDownloading, modelDownloadProgress = progress) }
    }

    private fun formatTranscriptTime(timestamp: Long): String {
        return synchronized(transcriptTimeFormatter) {
            transcriptTimeFormatter.format(Date(timestamp))
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
