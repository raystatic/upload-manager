package dev.uploadmanager.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UploadTaskEntity::class], version = 1, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {

    abstract fun uploadTaskDao(): UploadTaskDao

    companion object {
        @Volatile
        private var instance: UploadDatabase? = null

        fun get(context: Context): UploadDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UploadDatabase::class.java,
                    "upload_manager.db",
                ).build().also { instance = it }
            }
    }
}
