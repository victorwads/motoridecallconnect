package dev.wads.motoridecallconnect.ui.activetrip

import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import dev.wads.motoridecallconnect.data.repository.TripRepository
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
    val operatingMode: OperatingMode = OperatingMode.VOICE_COMMAND,
    val startCommand: String = "iniciar",
    val stopCommand: String = "parar",
    val isRecordingTranscript: Boolean = true,
    val isTripActive: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectedPeer: Device? = null,
    val transcript: List<String> = emptyList()
)

class ActiveTripViewModel(private val repository: TripRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ActiveTripUiState())
    val uiState: StateFlow<ActiveTripUiState> = _uiState.asStateFlow()

    private var currentTripId: String? = null
    private var activeTrip: dev.wads.motoridecallconnect.data.model.Trip? = null

    fun startTrip() {
        val tripId = UUID.randomUUID().toString()
        currentTripId = tripId
        val trip = dev.wads.motoridecallconnect.data.model.Trip(
            id = tripId,
            startTime = System.currentTimeMillis()
        )
        activeTrip = trip
        _uiState.update { it.copy(isTripActive = true, transcript = emptyList()) }
        
        viewModelScope.launch {
            repository.insertTrip(trip)
        }
    }

    fun endTrip() {
        val trip = activeTrip
        activeTrip = null
        currentTripId = null
        _uiState.update { it.copy(isTripActive = false) }

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

    fun onModeChange(mode: OperatingMode) {
        _uiState.update { it.copy(operatingMode = mode) }
    }

    fun onStartCommandChange(command: String) {
        _uiState.update { it.copy(startCommand = command) }
    }

    fun onStopCommandChange(command: String) {
        _uiState.update { it.copy(stopCommand = command) }
    }

    fun onRecordingToggle(isRecording: Boolean) {
        _uiState.update { it.copy(isRecordingTranscript = isRecording) }
    }

    fun onConnectionStatusChanged(status: ConnectionStatus, peer: Device?) {
        _uiState.update { it.copy(connectionStatus = status, connectedPeer = peer) }
    }

    fun updateTranscript(newTranscript: String, isFinal: Boolean) {
        _uiState.update { state ->
            val currentTranscript = state.transcript.toMutableList()
            if (isFinal) {
                if (currentTranscript.isNotEmpty() && currentTranscript.last().startsWith("Parcial:")) {
                    currentTranscript.removeAt(currentTranscript.lastIndex)
                }
                currentTranscript.add(newTranscript)
                
                currentTripId?.let { tripId ->
                    if (state.isRecordingTranscript) {
                        viewModelScope.launch {
                            repository.insertTranscriptLine(
                                TranscriptLine(
                                    tripId = tripId,
                                    text = newTranscript,
                                    isPartial = false
                                )
                            )
                        }
                    }
                }
            } else {
                if (currentTranscript.isEmpty() || !currentTranscript.last().startsWith("Parcial:")) {
                    currentTranscript.add("Parcial: $newTranscript")
                } else {
                    currentTranscript[currentTranscript.lastIndex] = "Parcial: $newTranscript"
                }
            }
            state.copy(transcript = currentTranscript)
        }
    }
}