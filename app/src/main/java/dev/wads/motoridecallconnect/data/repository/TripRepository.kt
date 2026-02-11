package dev.wads.motoridecallconnect.data.repository

import dev.wads.motoridecallconnect.data.local.TripDao
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import kotlinx.coroutines.flow.Flow

class TripRepository(private val tripDao: TripDao) {

    fun getAllTrips() = tripDao.getAllTrips()

    fun getTripWithTranscripts(tripId: Long) = tripDao.getTripWithTranscripts(tripId)

    suspend fun insertTrip(trip: Trip): Long {
        return tripDao.insertTrip(trip)
    }

    suspend fun insertTranscriptLine(transcriptLine: TranscriptLine) {
        tripDao.insertTranscriptLine(transcriptLine)
    }
}