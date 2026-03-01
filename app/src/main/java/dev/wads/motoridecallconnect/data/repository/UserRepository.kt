package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dev.wads.motoridecallconnect.data.model.UserProfile
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

class UserRepository private constructor() {
    private val firestore = FirebaseFirestore.getInstance()
    private val profileCache = ConcurrentHashMap<String, UserProfile>()

    suspend fun getUserProfile(uid: String): UserProfile? {
        // Return from cache if available
        profileCache[uid]?.let { return it }

        return try {
            val docRef = firestore.collection(FirestorePaths.ACCOUNTS_PUBLIC_INFO)
                .document(uid)
            val cachedSnapshot = runCatching {
                docRef.get(Source.CACHE).await()
            }.getOrNull()
            val snapshot = if (cachedSnapshot != null && cachedSnapshot.exists()) {
                cachedSnapshot
            } else {
                docRef.get().await()
            }
            val profile = snapshot.toObject(UserProfile::class.java)
            if (profile != null) {
                profileCache[uid] = profile
            }
            profile
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache() {
        profileCache.clear()
    }

    companion object {
        @Volatile
        private var instance: UserRepository? = null

        fun getInstance(): UserRepository {
            return instance ?: synchronized(this) {
                instance ?: UserRepository().also { instance = it }
            }
        }
    }
}
