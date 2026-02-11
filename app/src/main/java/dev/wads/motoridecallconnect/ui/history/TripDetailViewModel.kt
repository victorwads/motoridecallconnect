package dev.wads.motoridecallconnect.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.repository.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class TripDetailUiState(
    val trip: Trip? = null,
    val transcripts: List<TranscriptLine> = emptyList(),
    val isLoading: Boolean = true
)

class TripDetailViewModel(private val repository: TripRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(TripDetailUiState())
    val uiState: StateFlow<TripDetailUiState> = _uiState.asStateFlow()

    fun loadTrip(tripId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            val currentUser = FirebaseAuth.getInstance().currentUser
            val currentUserId = currentUser?.uid ?: ""

            // Combine trip data and transcript data
            // Note: getTripWithTranscripts returns empty transcripts currently, so we use getTranscripts separately if possible
            // But getTranscripts needs hostUid. We assume current user is host for history.
            
            // We can treat getTripWithTranscripts as just getting the trip for now since we know it returns empty list for transcripts from repo logic
            
            repository.getTripWithTranscripts(tripId).collect { tripWithTranscripts ->
                if (tripWithTranscripts != null) {
                    val trip = tripWithTranscripts.trip
                    // Now fetch real transcripts
                    if (currentUserId.isNotEmpty()) {
                        repository.getTranscripts(currentUserId, tripId).collect { transcripts ->
                            _uiState.value = TripDetailUiState(
                                trip = trip,
                                transcripts = transcripts,
                                isLoading = false
                            )
                        }
                    } else {
                         _uiState.value = TripDetailUiState(
                                trip = trip,
                                transcripts = tripWithTranscripts.transcripts,
                                isLoading = false
                            )
                    }
                } else {
                     _uiState.value = TripDetailUiState(isLoading = false)
                }
            }
        }
    }
}
