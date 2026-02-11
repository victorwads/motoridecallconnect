package dev.wads.motoridecallconnect.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.wads.motoridecallconnect.data.repository.TripRepository
import dev.wads.motoridecallconnect.ui.activetrip.ActiveTripViewModel
import dev.wads.motoridecallconnect.ui.history.TripHistoryViewModel

class ViewModelFactory(private val repository: TripRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ActiveTripViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ActiveTripViewModel() as T
        }
        if (modelClass.isAssignableFrom(TripHistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TripHistoryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}