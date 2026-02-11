package dev.wads.motoridecallconnect.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.repository.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class TripHistoryUiState(
    val trips: List<Trip> = emptyList(),
    val isLoading: Boolean = true
)

class TripHistoryViewModel(private val tripRepository: TripRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(TripHistoryUiState())
    val uiState: StateFlow<TripHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            tripRepository.getAllTrips().collect { trips ->
                _uiState.value = TripHistoryUiState(trips = trips, isLoading = false)
            }
        }
    }
}