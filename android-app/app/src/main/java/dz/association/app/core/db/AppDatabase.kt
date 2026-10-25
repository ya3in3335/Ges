package dz.association.app.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Offline cache of public content only (no credentials, no votes). Payloads are stored as the
 * JSON returned by the API so the cache always matches the network contract.
 */
@Entity(tableName = "cached_payload")
data class CachedPayload(
    @PrimaryKey val key: String, // e.g. "home", "news:<id>", "activity:<id>", "news_page:1:all"
    val json: String,
    val savedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cached_payload WHERE `key` = :key")
    suspend fun get(key: String): CachedPayload?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(p: CachedPayload)

    @Query("DELETE FROM cached_payload WHERE savedAt < :before")
    suspend fun purgeOlderThan(before: Long)

    @Query("DELETE FROM cached_payload")
    suspend fun clear()
}

@Database(entities = [CachedPayload::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "association.db").fallbackToDestructiveMigration().build()

    @Provides fun cacheDao(db: AppDatabase): CacheDao = db.cache()
}
