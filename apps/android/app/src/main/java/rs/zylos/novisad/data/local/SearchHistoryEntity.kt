package rs.zylos.novisad.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    @ColumnInfo(name = "hit_id") val hitId: String?,
    val label: String?,
    val kind: String? = null,
    @ColumnInfo(name = "category_slug") val categorySlug: String? = null,
    val timestamp: Long,
)
