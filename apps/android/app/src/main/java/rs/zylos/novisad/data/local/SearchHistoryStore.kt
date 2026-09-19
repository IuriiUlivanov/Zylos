package rs.zylos.novisad.data.local

import rs.zylos.novisad.map.MapDefaults

interface SearchHistoryStore {
    suspend fun recent(): List<SearchHistoryEntity>
    suspend fun save(
        query: String,
        hitId: String?,
        label: String?,
        kind: String? = null,
        categorySlug: String? = null,
    )
}

class RoomSearchHistoryStore(
    private val dao: SearchHistoryDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) : SearchHistoryStore {
    override suspend fun recent(): List<SearchHistoryEntity> {
        return dao.recent(MapDefaults.SEARCH_HISTORY_LIMIT)
    }

    override suspend fun save(
        query: String,
        hitId: String?,
        label: String?,
        kind: String?,
        categorySlug: String?,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return
        }
        dao.deleteByQuery(trimmed)
        dao.insert(
            SearchHistoryEntity(
                query = trimmed,
                hitId = hitId,
                label = label,
                kind = kind,
                categorySlug = categorySlug,
                timestamp = now(),
            ),
        )
        val extra = dao.count() - MapDefaults.SEARCH_HISTORY_LIMIT
        if (extra > 0) {
            dao.deleteOldest(extra)
        }
    }
}
