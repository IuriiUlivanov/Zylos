package rs.zylos.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SearchHistoryEntity::class], version = 2, exportSchema = false)
abstract class ZylosDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        fun create(context: Context): ZylosDatabase {
            return Room.databaseBuilder(context.applicationContext, ZylosDatabase::class.java, "zylos.db")
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}
