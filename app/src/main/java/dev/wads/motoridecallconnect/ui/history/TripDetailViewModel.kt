package dev.wads.motoridecallconnect.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.data.model.TranscriptEntry
import dev.wads.motoridecallconnect.data.model.TranscriptStatus
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.repository.TripRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripDetailUiState(
    val trip: Trip? = null,
    val transcripts: List<TranscriptEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null
)

class TripDetailViewModel(private val repository: TripRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(TripDetailUiState())
    val uiState: StateFlow<TripDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun loadTrip(tripId: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { state -> state.copy(isLoading = true, errorMessage = null) }

            val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
            val transcriptFlow = if (currentUserId.isNullOrEmpty()) {
                flowOf(emptyList())
            } else {
                repository.getTranscriptEntries(currentUserId, tripId)
            }

            combine(
                repository.getTripWithTranscripts(tripId),
                transcriptFlow
            ) { tripWithTranscripts, remoteTranscripts ->
                val trip = tripWithTranscripts?.trip
                val transcripts = if (currentUserId.isNullOrEmpty()) {
                    tripWithTranscripts?.transcripts?.mapIndexed { index, line ->
                        TranscriptEntry(
                            id = "local_${line.id}_$index",
                            tripId = line.tripId,
                            authorId = line.authorId,
                            authorName = line.authorName.ifBlank { "Unknown" },
                            text = line.text,
                            timestamp = line.timestamp,
                            status = if (line.isPartial) TranscriptStatus.PROCESSING else TranscriptStatus.SUCCESS,
                            errorMessage = null
                        )
                    } ?: emptyList()
                } else {
                    remoteTranscripts
                }

                TripDetailUiState(
                    trip = trip,
                    transcripts = transcripts,
                    isLoading = false,
                    isDeleting = _uiState.value.isDeleting,
                    errorMessage = null
                )
            }.collect { newState ->
                _uiState.value = newState.copy(isDeleting = _uiState.value.isDeleting)
            }
        }
    }

    fun deleteTrip(onDeleted: () -> Unit = {}) {
        val tripId = _uiState.value.trip?.id ?: return
        if (_uiState.value.isDeleting) return

        viewModelScope.launch {
            _uiState.update { state -> state.copy(isDeleting = true, errorMessage = null) }
            try {
                repository.deleteTrip(tripId)
                onDeleted()
            } catch (t: Throwable) {
                _uiState.update { state ->
                    state.copy(errorMessage = t.message ?: "Failed to delete trip")
                }
            } finally {
                _uiState.update { state -> state.copy(isDeleting = false) }
            }
        }
    }
}
