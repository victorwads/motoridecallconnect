package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dev.wads.motoridecallconnect.data.local.TripDao
import dev.wads.motoridecallconnect.data.local.TripWithTranscripts
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TripRepository(private val tripDao: TripDao) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    fun getAllTrips(): Flow<List<Trip>> {
        val user = auth.currentUser
        return if (user != null) {
            callbackFlow {
                val listenerInfo = firestore.collection("users").document(user.uid).collection("trips")
                    .orderBy("startTime", Query.Direction.DESCENDING)
                    .addSnapshotListener { value, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }
                        if (value != null) {
                            val trips = value.toObjects(Trip::class.java)
                            trySend(trips)
                        }
                    }
                awaitClose { listenerInfo.remove() }
            }
        } else {
            tripDao.getAllTrips()
        }
    }

    fun getTripWithTranscripts(tripId: String): Flow<TripWithTranscripts?> {
        val user = auth.currentUser
        return if (user != null) {
             callbackFlow {
                 val tripRef = firestore.collection("users").document(user.uid).collection("trips").document(tripId)
                 val listener = tripRef.addSnapshotListener { snapshot, error ->
                     if (error != null) {
                         close(error)
                         return@addSnapshotListener
                     }
                     if (snapshot != null && snapshot.exists()) {
                         val trip = snapshot.toObject(Trip::class.java)
                         if (trip != null) {
                             trySend(TripWithTranscripts(trip, emptyList()))
                         }
                     } else {
                         trySend(null)
                     }
                 }
                 awaitClose { listener.remove() }
             }
        } else {
            tripDao.getTripWithTranscripts(tripId).map { it }
        }
    }

    suspend fun insertTrip(trip: Trip): String {
        val user = auth.currentUser
        return if (user != null) {
            val docRef = firestore.collection("users").document(user.uid).collection("trips").document(trip.id)
            suspendCancellableCoroutine { continuation ->
                docRef.set(trip)
                    .addOnSuccessListener { continuation.resume(trip.id) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } else {
            tripDao.insertTrip(trip)
            trip.id
        }
    }

    suspend fun insertTranscriptLine(transcriptLine: TranscriptLine) {
        val user = auth.currentUser
        if (user != null) {
            val tripRef = firestore.collection("users").document(user.uid).collection("trips").document(transcriptLine.tripId)
            val lineRef = tripRef.collection("transcripts").document()
            suspendCancellableCoroutine<Unit> { continuation ->
                lineRef.set(transcriptLine)
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } else {
            tripDao.insertTranscriptLine(transcriptLine)
        }
    }
}
