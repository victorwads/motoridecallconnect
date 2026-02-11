package dev.wads.motoridecallconnect.ui.activetrip

import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import dev.wads.motoridecallconnect.data.repository.TripRepository
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

data class ActiveTripUiState(
    val discoveredServices: List<NsdServiceInfo> = emptyList(),
    val isTripActive: Boolean = false,
    val currentTripId: String? = null,
    val tripStartTime: Long? = null,
    val hostUid: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectedPeer: Device? = null,
    val transcript: List<String> = emptyList(),
    val isModelDownloading: Boolean = false,
    val modelDownloadProgress: Int = 0
)

class ActiveTripViewModel(private val repository: TripRepository) : ViewModel() {
    companion object {
        private const val TAG = "ActiveTripViewModel"
    }

    private val _uiState = MutableStateFlow(ActiveTripUiState())
    val uiState: StateFlow<ActiveTripUiState> = _uiState.asStateFlow()

    private var currentTripId: String? = null
    private var activeTrip: dev.wads.motoridecallconnect.data.model.Trip? = null
    private var transcriptJob: Job? = null

    fun startTrip(remoteTripId: String? = null, hostId: String? = null) {
        val tripId = remoteTripId ?: UUID.randomUUID().toString()
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        val targetHostUid = hostId ?: myUid

        currentTripId = tripId
        val now = System.currentTimeMillis()
        val trip = dev.wads.motoridecallconnect.data.model.Trip(
            id = tripId,
            startTime = now
        )
        activeTrip = trip
        _uiState.update { it.copy(
            isTripActive = true,
            tripStartTime = now,
            currentTripId = tripId,
            hostUid = targetHostUid,
            transcript = emptyList()
        ) }

        if (hostId == null || hostId == myUid) {
            viewModelScope.launch {
                repository.insertTrip(trip)
            }
        }

        // Subscribe to shared transcriptions
        transcriptJob?.cancel()
        if (targetHostUid != null) {
            transcriptJob = viewModelScope.launch {
                repository.getTranscripts(targetHostUid, tripId).collect { lines ->
                    _uiState.update { state ->
                        // Merge partial results from local state with final results from Firebase
                        val firebaseList = lines.map { "${it.authorName}: ${it.text}" }
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
        _uiState.update { it.copy(isTripActive = false, tripStartTime = null, currentTripId = null, hostUid = null) }

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
        _uiState.update { it.copy(connectionStatus = status, connectedPeer = peer) }
    }

    fun onTripStatusChanged(isActive: Boolean, tripId: String? = null) {
        val state = _uiState.value
        if (isActive) {
            if (!state.isTripActive) {
                // If we receive a trip start from a peer, THEY are the host
                val hostId = state.connectedPeer?.id
                startTrip(tripId, hostId)
            }
        } else {
            if (state.isTripActive) {
                endTrip()
            }
        }
    }

    fun updateTranscript(newTranscript: String, isFinal: Boolean) {
        val state = _uiState.value
        val targetUid = state.hostUid ?: FirebaseAuth.getInstance().currentUser?.uid

        if (isFinal) {
            val tripId = state.currentTripId ?: return

            // Show immediately in UI; Firestore listener will later reconcile authoritative lines.
            _uiState.update { s ->
                val updated = s.transcript.toMutableList()
                if (updated.isNotEmpty() && updated.last().startsWith("Parcial:")) {
                    updated.removeAt(updated.lastIndex)
                }
                updated.add("Você: $newTranscript")
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
}
