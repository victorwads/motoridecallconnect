package dev.wads.motoridecallconnect.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.model.TranscriptLine

@Database(entities = [Trip::class, TranscriptLine::class], version = 1, exportSchema = false)
public abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    companion object {
        // Singleton prevents multiple instances of database opening at the same time.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /*
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "motoride_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
        */
    }
}