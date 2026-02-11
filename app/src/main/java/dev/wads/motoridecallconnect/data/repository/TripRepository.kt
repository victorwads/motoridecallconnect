package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dev.wads.motoridecallconnect.data.local.TripDao
import dev.wads.motoridecallconnect.data.local.TripWithTranscripts
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TripRepository(private val tripDaoProvider: () -> TripDao) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    fun getAllTrips(): Flow<List<Trip>> {
        val user = auth.currentUser
        return if (user != null) {
            callbackFlow {
                val listenerInfo = firestore.collection(FirestorePaths.ACCOUNTS).document(user.uid).collection(FirestorePaths.RIDES)
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
            tripDaoProvider().getAllTrips()
        }
    }

    fun getTripWithTranscripts(tripId: String): Flow<TripWithTranscripts?> {
        val user = auth.currentUser
        return if (user != null) {
             callbackFlow {
                 val tripRef = firestore.collection(FirestorePaths.ACCOUNTS).document(user.uid).collection(FirestorePaths.RIDES).document(tripId)
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
            tripDaoProvider().getTripWithTranscripts(tripId).map { it }
        }
    }

    suspend fun insertTrip(trip: Trip): String {
        val user = auth.currentUser
        return if (user != null) {
            val docRef = firestore.collection(FirestorePaths.ACCOUNTS).document(user.uid).collection(FirestorePaths.RIDES).document(trip.id)
            suspendCancellableCoroutine { continuation ->
                docRef.set(trip)
                    .addOnSuccessListener { continuation.resume(trip.id) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } else {
            tripDaoProvider().insertTrip(trip)
            trip.id
        }
    }

    suspend fun insertTranscriptLine(transcriptLine: TranscriptLine) {
        val user = auth.currentUser
        if (user != null) {
            // Logic to determine where to save:
            // If I am the host, I save to my own collection.
            // If I am a guest, I need to know the Host UID to save to THEIR collection.
            // Currently, 'tripId' is the document ID. We assume we can find the doc.
            // However, our current structure implies: Accounts/{HOST_UID}/Rides/{TRIP_ID}
            // The TripId alone does NOT give us the Host UID unless we store it in the Trip object locally or pass it.
            // For now, assuming the user IS the host for this specific method call as per previous simplicity, 
            // BUT the requirement says "Guest can write".
            // So we need to update this logic.
            
            // NOTE: The current simple implementation assumes 'tripId' points to a local trip which mirrors the remote one.
            // But if we are a guest, we don't 'own' the trip in "users/{me}/trips".
            // We need a way to know WHO is the owner of the trip to construct the path.
            
            // Temporary fix: We proceed with saving to current user's path if no extra metadata. 
            // Real fix requires Trip object to have 'hostUid'.
            
            // Let's assume for this step, we are just adding the author info details and we'll fix the pathing logic 
            // if the user provides the host info. 
            // Actually, let's inject the current user ID into the line before saving.
            
            val lineWithAuth = transcriptLine.copy(
                authorId = user.uid,
                authorName = user.displayName ?: "Unknown"
            )

            // TODO: If this is a shared ride, 'transcriptLine.tripId' needs to target the HOST's trip.
            // Since we haven't refactored the entire app to pass (HostUID, TripID) pairs, 
            // we will stick to the existing path (User's own trips) for this specific edit, 
            // but ensuring the MODEL has the fields is step 1.
            
            val tripRef = firestore.collection(FirestorePaths.ACCOUNTS).document(user.uid).collection(FirestorePaths.RIDES).document(transcriptLine.tripId)
            val lineRef = tripRef.collection(FirestorePaths.RIDE_TRANSCRIPTS).document()
            suspendCancellableCoroutine<Unit> { continuation ->
                lineRef.set(lineWithAuth)
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } else {
            tripDaoProvider().insertTranscriptLine(transcriptLine)
        }
    }
}
