package dev.wads.motoridecallconnect.data.model

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val lastOnlineTime: Long = 0,
    val availableRoom: String? = null
)

data class FriendRequest(
    val fromUid: String = "",
    val fromName: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
