package dev.wads.motoridecallconnect.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    var duration: Long? = null,
    val peerDevice: String? = null
)