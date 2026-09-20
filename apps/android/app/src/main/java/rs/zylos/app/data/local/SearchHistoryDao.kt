package rs.zylos.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Insert
    suspend fun insert(entity: SearchHistoryEntity): Long

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteByQuery(query: String)

    @Query(
        "DELETE FROM search_history WHERE id IN (SELECT id FROM search_history ORDER BY timestamp ASC LIMIT :count)",
    )
    suspend fun deleteOldest(count: Int)

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int
}
