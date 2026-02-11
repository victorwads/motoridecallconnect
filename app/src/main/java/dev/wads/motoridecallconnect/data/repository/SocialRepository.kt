package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import dev.wads.motoridecallconnect.data.model.FriendRequest
import dev.wads.motoridecallconnect.data.model.UserProfile
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
            .set(profile)
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
            val friendData = mapOf("uid" to targetUid, "timestamp" to System.currentTimeMillis())
            transaction.set(myFriendRef, friendData)

            // 2. Add me to their friends (Reciprocal)
            val theirFriendRef = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(targetUid)
                .collection(FirestorePaths.FRIENDS)
                .document(myUid)
            
            val myData = mapOf("uid" to myUid, "timestamp" to System.currentTimeMillis())
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
            
            // This is a list of IDs mainly. We might need to fetch profiles.
            // For a robust app, we'd fetch profiles for each friend ID.
            // For now, let's assume we map the docs to profiles or just emit IDs.
            // To simplify, let's just return the list of friends as basic profiles from the stored map if possible
            // or fetch them. 
            // Given the complexity of "live" fetching list of secondary docs in Firestore, 
            // usually you store a snapshot of the name in the Friend doc or query PublicInfo.
            // Let's implement a basic version that just returns what's in the Friends collection.
            
            if (snapshot != null) {
                // If we want real profiles, we'd query AccountsPublicInfo where UID in [...].
                // Firestore 'in' query supports up to 10 items.
                // For a full scaling app we need a different approach (paging).
                // Let's just return objects from the data we saved in acceptFriendRequest
                val friends = snapshot.documents.map { doc ->
                    UserProfile(
                        uid = doc.getString("uid") ?: doc.id,
                        displayName = "Friend" // Placeholder, real app should fetch or store name
                    )
                }
                trySend(friends)
            }
        }
        awaitClose { listener.remove() }
    }
}
