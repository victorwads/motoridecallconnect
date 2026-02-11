package dev.wads.motoridecallconnect.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.repository.TripRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripHistoryUiState(
    val trips: List<Trip> = emptyList(),
    val transcriptAvailability: Map<String, Boolean> = emptyMap(),
    val deletingTripIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class TripHistoryViewModel(private val tripRepository: TripRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(TripHistoryUiState())
    val uiState: StateFlow<TripHistoryUiState> = _uiState.asStateFlow()

    init {
        observeTrips()
    }

    fun deleteTrip(tripId: String) {
        if (_uiState.value.deletingTripIds.contains(tripId)) return

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    deletingTripIds = state.deletingTripIds + tripId,
                    errorMessage = null
                )
            }

            try {
                tripRepository.deleteTrip(tripId)
            } catch (t: Throwable) {
                _uiState.update { state ->
                    state.copy(errorMessage = t.message ?: "Failed to delete trip")
                }
            } finally {
                _uiState.update { state ->
                    state.copy(deletingTripIds = state.deletingTripIds - tripId)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTrips() {
        viewModelScope.launch {
            tripRepository.getAllTrips()
                .flatMapLatest { trips ->
                    tripRepository.observeTranscriptAvailability(trips.map { trip -> trip.id })
                        .map { transcriptAvailability -> trips to transcriptAvailability }
                }
                .catch { throwable ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Failed to load history"
                        )
                    }
                }
                .collect { (trips, transcriptAvailability) ->
                    _uiState.update { state ->
                        state.copy(
                            trips = trips,
                            transcriptAvailability = transcriptAvailability,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
        }
    }
}
