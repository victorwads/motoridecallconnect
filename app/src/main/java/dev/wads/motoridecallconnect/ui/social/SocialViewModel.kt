package dev.wads.motoridecallconnect.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.FriendRequest
import dev.wads.motoridecallconnect.data.model.UserProfile
import dev.wads.motoridecallconnect.data.repository.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class SocialUiState(
    val friendRequests: List<FriendRequest> = emptyList(),
    val friends: List<UserProfile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val addFriendSuccess: Boolean = false
)

class SocialViewModel(private val repository: SocialRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SocialUiState())
    val uiState: StateFlow<SocialUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            launch {
                repository.getFriendRequests().collect { requests ->
                    _uiState.value = _uiState.value.copy(friendRequests = requests)
                }
            }
            launch {
                repository.getFriends().collect { friends ->
                    _uiState.value = _uiState.value.copy(friends = friends)
                }
            }
        }
    }

    fun sendFriendRequest(targetUid: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, addFriendSuccess = false)
            try {
                repository.sendFriendRequest(targetUid)
                _uiState.value = _uiState.value.copy(isLoading = false, addFriendSuccess = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun acceptRequest(request: FriendRequest) {
        viewModelScope.launch {
            try {
                repository.acceptFriendRequest(request)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun rejectRequest(request: FriendRequest) {
        viewModelScope.launch {
            try {
                repository.rejectFriendRequest(request)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(addFriendSuccess = false)
    }
    
    fun copyMyId() {
        // Helper to expose ID for UI if needed
    }
    
    fun getMyId(): String? = repository.currentUserId
}
