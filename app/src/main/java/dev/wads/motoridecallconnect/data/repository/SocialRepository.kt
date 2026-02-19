package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dev.wads.motoridecallconnect.data.model.FriendRequest
import dev.wads.motoridecallconnect.data.model.UserProfile
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SocialRepository {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    val currentUserId: String?
        get() = auth.currentUser?.uid

    // --- Public Profile Management ---

    suspend fun updateMyPublicProfile() {
        val user = auth.currentUser ?: return
        val profile = UserProfile(
            uid = user.uid,
            displayName = user.displayName ?: "Moto Rider",
            photoUrl = user.photoUrl?.toString(),
            lastOnlineTime = System.currentTimeMillis()
        )
        firestore.collection(FirestorePaths.ACCOUNTS_PUBLIC_INFO)
            .document(user.uid)
            .set(profile, SetOptions.merge())
            .await()
    }

    suspend fun getUserProfile(uid: String): UserProfile? {
        return UserRepository.getInstance().getUserProfile(uid)
    }

    // --- Friend Requests ---

    fun getFriendRequests(): Flow<List<FriendRequest>> = callbackFlow {
        val uid = currentUserId
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val ref = firestore.collection(FirestorePaths.ACCOUNTS)
            .document(uid)
            .collection(FirestorePaths.FRIEND_REQUESTS)

        val listener = ref.addSnapshotListener { value, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val requests = value?.toObjects(FriendRequest::class.java) ?: emptyList()
            trySend(requests)
        }
        awaitClose { listener.remove() }
    }

    suspend fun sendFriendRequest(targetUid: String) {
        val myUid = currentUserId ?: throw IllegalStateException("Not logged in")
        val myUser = auth.currentUser ?: throw IllegalStateException("Not logged in")

        // 1. Verify target exists
        val targetProfile = UserRepository.getInstance().getUserProfile(targetUid)
        if (targetProfile == null) {
            throw IllegalArgumentException("User ID not found")
        }

        // 2. Create Request Object
        val request = FriendRequest(
            fromUid = myUid,
            fromName = myUser.displayName ?: "Unknown",
            timestamp = System.currentTimeMillis()
        )

        // 3. Write to Target's FriendRequests
        firestore.collection(FirestorePaths.ACCOUNTS)
            .document(targetUid)
            .collection(FirestorePaths.FRIEND_REQUESTS)
            .document(myUid)
            .set(request)
            .await()
    }

    suspend fun acceptFriendRequest(request: FriendRequest) {
        val myUid = currentUserId ?: return
        val targetUid = request.fromUid
        val targetDisplayName = UserRepository.getInstance().getUserProfile(targetUid)
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: "Friend"
        val myDisplayName = auth.currentUser?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: "Friend"

        firestore.runTransaction { transaction ->
            // 1. Add to my friends
            val myFriendRef = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(myUid)
                .collection(FirestorePaths.FRIENDS)
                .document(targetUid)
            
            // We can store a minimal profile or just the ID. 
            // Storing ID and fetching profile dynamically is cleaner, 
            // but for simple lists, copying name is common. 
            // Let's copy basic info from the request or fetch public info.
            // For transaction safety within rules, we just set true or minimal info.
            val friendData = mapOf(
                "uid" to targetUid,
                "displayName" to targetDisplayName,
                "timestamp" to System.currentTimeMillis()
            )
            transaction.set(myFriendRef, friendData)

            // 2. Add me to their friends (Reciprocal)
            val theirFriendRef = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(targetUid)
                .collection(FirestorePaths.FRIENDS)
                .document(myUid)
            
            val myData = mapOf(
                "uid" to myUid,
                "displayName" to myDisplayName,
                "timestamp" to System.currentTimeMillis()
            )
            transaction.set(theirFriendRef, myData)

            // 3. Delete Request
            val requestRef = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(myUid)
                .collection(FirestorePaths.FRIEND_REQUESTS)
                .document(targetUid)
            transaction.delete(requestRef)
        }.await()
    }

    suspend fun rejectFriendRequest(request: FriendRequest) {
        val myUid = currentUserId ?: return
        firestore.collection(FirestorePaths.ACCOUNTS)
            .document(myUid)
            .collection(FirestorePaths.FRIEND_REQUESTS)
            .document(request.fromUid)
            .delete()
            .await()
    }

    // --- Friends List ---

    fun getFriends(): Flow<List<UserProfile>> = callbackFlow {
        val uid = currentUserId
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val friendsRef = firestore.collection(FirestorePaths.ACCOUNTS)
            .document(uid)
            .collection(FirestorePaths.FRIENDS)

        val listener = friendsRef.addSnapshotListener { snapshot, error ->
            if (error != null) { 
                close(error)
                return@addSnapshotListener 
            }
            
            launch {
                val friends = snapshot?.documents.orEmpty()
                    .map { doc -> doc.getString("uid") ?: doc.id }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .map { friendUid ->
                        val fallbackName = snapshot?.documents
                            ?.firstOrNull { (it.getString("uid") ?: it.id) == friendUid }
                            ?.getString("displayName")
                            ?.takeIf { it.isNotBlank() }
                            ?: "Friend"
                        UserRepository.getInstance().getUserProfile(friendUid)
                            ?.copy(uid = friendUid)
                            ?: UserProfile(uid = friendUid, displayName = fallbackName)
                    }
                    .sortedBy { it.displayName.lowercase() }
                trySend(friends)
            }
        }
        awaitClose { listener.remove() }
    }

    suspend fun getFriendsOnce(): List<UserProfile> {
        val uid = currentUserId ?: return emptyList()
        val snapshot = firestore.collection(FirestorePaths.ACCOUNTS)
            .document(uid)
            .collection(FirestorePaths.FRIENDS)
            .get()
            .await()

        return snapshot.documents
            .map { doc -> doc.getString("uid") ?: doc.id }
            .filter { it.isNotBlank() }
            .distinct()
            .map { friendUid ->
                UserRepository.getInstance().getUserProfile(friendUid)
                    ?.copy(uid = friendUid)
                    ?: UserProfile(uid = friendUid, displayName = "Friend")
            }
            .sortedBy { it.displayName.lowercase() }
    }
}
