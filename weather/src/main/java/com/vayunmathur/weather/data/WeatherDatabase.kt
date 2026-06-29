package com.vayunmathur.weather.data

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import com.vayunmathur.library.util.DatabaseHelper

const val DB_NAME = "weather-db"

/** Backup config shared by [com.vayunmathur.weather.util.AppBackupAgent]. */
fun weatherDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

@Database(
    entities = [SavedLocation::class, WeatherCache::class],
    version = 1,
    exportSchema = true,
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao
}
