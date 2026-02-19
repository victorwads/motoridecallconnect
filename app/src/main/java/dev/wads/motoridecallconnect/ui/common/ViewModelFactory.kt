package dev.wads.motoridecallconnect.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.wads.motoridecallconnect.data.repository.DeviceDiscoveryRepository
import dev.wads.motoridecallconnect.data.repository.SocialRepository
import dev.wads.motoridecallconnect.data.repository.TripRepository
import dev.wads.motoridecallconnect.data.repository.WifiDirectRepository
import dev.wads.motoridecallconnect.ui.activetrip.ActiveTripViewModel
import dev.wads.motoridecallconnect.ui.history.TripDetailViewModel
import dev.wads.motoridecallconnect.ui.history.TripHistoryViewModel
import dev.wads.motoridecallconnect.ui.login.LoginViewModel
import dev.wads.motoridecallconnect.ui.pairing.PairingViewModel
import dev.wads.motoridecallconnect.ui.social.SocialViewModel

class ViewModelFactory(
    private val repository: TripRepository,
    private val socialRepository: SocialRepository,
    private val deviceDiscoveryRepository: DeviceDiscoveryRepository,
    private val wifiDirectRepository: WifiDirectRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ActiveTripViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ActiveTripViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(TripHistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TripHistoryViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(TripDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TripDetailViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel() as T
        }
        if (modelClass.isAssignableFrom(SocialViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SocialViewModel(socialRepository) as T
        }
        if (modelClass.isAssignableFrom(PairingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PairingViewModel(deviceDiscoveryRepository, wifiDirectRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
