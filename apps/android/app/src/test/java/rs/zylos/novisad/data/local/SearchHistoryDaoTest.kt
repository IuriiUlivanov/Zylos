package rs.zylos.novisad.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.zylos.novisad.map.MapDefaults

class SearchHistoryDaoTest {
    @Test
    fun eleventhQueryEvictsOldest() = runBlocking {
        val dao = InMemorySearchHistoryDao()
        val store = RoomSearchHistoryStore(dao, now = { seq.also { seq += 1 } })
        repeat(11) { index ->
            store.save("q$index", hitId = "h$index", label = "L$index", kind = null, categorySlug = null)
        }
        val recent = store.recent()
        assertEquals(MapDefaults.SEARCH_HISTORY_LIMIT, recent.size)
        assertEquals(10, dao.count())
        assertTrue(recent.none { it.query == "q0" })
        assertTrue(recent.any { it.query == "q10" })
        assertEquals("q10", recent.first().query)
    }

    @Test
    fun duplicateQueryIsUniqueAndMovesToFront() = runBlocking {
        val dao = InMemorySearchHistoryDao()
        var clock = 1L
        val store = RoomSearchHistoryStore(dao, now = { clock++ })
        store.save("apotek", "h1", "Apoteka", "organization", "pharmacy")
        store.save("bulevar", "h2", "Bulevar", "address", null)
        store.save("apotek", "h3", "Apoteka Benu", "organization", "pharmacy")
        val recent = store.recent()
        assertEquals(2, recent.size)
        assertEquals("apotek", recent[0].query)
        assertEquals("h3", recent[0].hitId)
        assertEquals("organization", recent[0].kind)
        assertEquals("bulevar", recent[1].query)
    }

    private var seq = 1L

    private class InMemorySearchHistoryDao : SearchHistoryDao {
        private val items = mutableListOf<SearchHistoryEntity>()
        private var nextId = 1L

        override suspend fun recent(limit: Int): List<SearchHistoryEntity> {
            return items.sortedByDescending { it.timestamp }.take(limit)
        }

        override suspend fun insert(entity: SearchHistoryEntity): Long {
            val row = entity.copy(id = nextId++)
            items += row
            return row.id
        }

        override suspend fun deleteByQuery(query: String) {
            items.removeAll { it.query == query }
        }

        override suspend fun deleteOldest(count: Int) {
            val oldest = items.sortedBy { it.timestamp }.take(count).toSet()
            items.removeAll(oldest)
        }

        override suspend fun count(): Int = items.size
    }
}
