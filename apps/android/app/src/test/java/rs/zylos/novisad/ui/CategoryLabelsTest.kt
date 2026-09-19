package rs.zylos.novisad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryLabelsTest {
    @Test
    fun prefersApiName() {
        assertEquals("Аптека", CategoryLabels.label("pharmacy", "Аптека"))
    }

    @Test
    fun mapsKnownSlugsToSrLatn() {
        assertEquals("Apoteka", CategoryLabels.label("pharmacy"))
        assertEquals("Banka", CategoryLabels.label("bank"))
        assertEquals("Kafić", CategoryLabels.label("cafe"))
    }

    @Test
    fun humanizesUnknownSlugs() {
        assertEquals("fast food kiosk", CategoryLabels.label("fast_food_kiosk"))
    }

    @Test
    fun missingSlugAndNameIsNull() {
        assertNull(CategoryLabels.label(null, null))
        assertNull(CategoryLabels.label("", "  "))
    }
}
