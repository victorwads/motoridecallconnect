package dev.wads.motoridecallconnect

import android.app.Application
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

class MotoRideApplication : Application() {

    companion object {
        private const val TAG = "MotoRideApplication"
    }

    override fun onCreate() {
        super.onCreate()
        configureFirestoreOfflineFirst()
    }

    private fun configureFirestoreOfflineFirst() {
        val firestore = FirebaseFirestore.getInstance()
        runCatching {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
            firestore.firestoreSettings = settings
            Log.i(TAG, "Firestore offline persistence enabled with unlimited local cache.")
        }.onFailure { error ->
            Log.e(TAG, "Failed to configure Firestore offline persistence.", error)
        }
    }
}
